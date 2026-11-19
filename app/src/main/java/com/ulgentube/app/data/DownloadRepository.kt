package com.ulgentube.app.data

import android.content.Context
import com.ulgentube.app.data.local.DownloadDao
import com.ulgentube.app.data.local.SettingsRepository
import com.ulgentube.app.data.local.toDomain
import com.ulgentube.app.data.local.toEntity
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.PlaylistEntry
import com.ulgentube.app.engine.MediaStoreSaver
import com.ulgentube.app.engine.SocialImageExtractor
import com.ulgentube.app.engine.YtDlpEngine
import com.ulgentube.app.service.DownloadForegroundService
import com.ulgentube.app.util.ErrorMessageSanitizer
import com.ulgentube.app.util.FileNameSanitizer
import com.ulgentube.app.util.NetworkSecurityGuard
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * İndirme görevlerinin yaşam döngüsünü, Room veritabanı senkronizasyonunu
 * ve çalışan iş parçacıklarının iptal/yeniden deneme/resume yönetimini üstlenir.
 */
class DownloadRepository(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val settingsRepository: SettingsRepository,
    private val ytDlpEngine: YtDlpEngine,
    private val socialImageExtractor: SocialImageExtractor,
    private val mediaStoreSaver: MediaStoreSaver
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Tüm indirme görevlerini en yeniden eskiye doğru canlı olarak gözlemler. */
    val downloadsFlow: Flow<List<DownloadTask>> = downloadDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    /**
     * Verilen URL daha önce başarıyla indirilmişse ilgili tamamlanmış görevi döndürür.
     */
    suspend fun findDuplicateCompleted(url: String): DownloadTask? = withContext(Dispatchers.IO) {
        downloadDao.findCompletedByUrl(url)?.toDomain()
    }

    /**
     * Video veya ses format seçeneğini (opsiyonel kırpma ve altyazı ayarlarıyla)
     * indirme kuyruğuna ekler ve ön plan servisini başlatır.
     */
    suspend fun enqueue(
        media: ExtractedMedia,
        option: MediaFormatOption,
        clipStartSeconds: Long? = null,
        clipEndSeconds: Long? = null,
        subtitleLang: String? = null,
        embedSubtitles: Boolean = false
    ): DownloadTask = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getCurrentSettings()
        val taskId = UUID.randomUUID().toString()
        val fileName = FileNameSanitizer.applyTemplate(
            template = settings.fileNameTemplate,
            title = media.title,
            author = media.author,
            extension = option.extension
        )

        val task = DownloadTask(
            id = taskId,
            url = media.originalUrl,
            title = media.title,
            author = media.author,
            thumbnailUrl = media.thumbnailUrl,
            platform = media.platform,
            category = option.category,
            formatLabel = option.label,
            fileName = fileName,
            fileSizeBytes = option.fileSizeBytes ?: 0L,
            status = DownloadStatus.QUEUED,
            ytDlpFormatSelector = option.ytDlpFormatSelector,
            directUrl = option.directUrl,
            isAudioExtraction = option.isAudioExtraction,
            transcodeTargetHeight = option.transcodeTargetHeight,
            targetExtension = option.extension,
            clipStartSeconds = clipStartSeconds,
            clipEndSeconds = clipEndSeconds,
            subtitleLang = subtitleLang?.trim()?.takeIf { it.isNotEmpty() },
            embedSubtitles = embedSubtitles
        )

        downloadDao.upsert(task.toEntity())
        DownloadForegroundService.start(context)
        task
    }

    /**
     * Birden fazla bağlantıyı varsayılan kalite/ses ayarlarıyla toplu olarak kuyruğa ekler
     * ve indirme servisini tek seferde başlatır.
     */
    suspend fun enqueueBatchUrls(
        urls: List<String>,
        audioOnly: Boolean = false
    ): List<DownloadTask> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext emptyList()

        val settings = settingsRepository.getCurrentSettings()
        val created = mutableListOf<DownloadTask>()

        urls.forEachIndexed { index, rawUrl ->
            val safeUrl = UrlSanitizer.extractFirstValidUrl(rawUrl) ?: return@forEachIndexed
            val platform = UrlSanitizer.detectPlatform(safeUrl)
            val taskId = UUID.randomUUID().toString()
            val title = "${platform.displayName} Medya #${index + 1}"

            val category = if (audioOnly) MediaCategory.AUDIO else MediaCategory.VIDEO
            val targetExt = if (audioOnly) {
                settings.defaultAudioFormat.lowercase(Locale.ROOT).ifBlank { "mp3" }
            } else {
                "mp4"
            }
            val formatLabel = if (audioOnly) {
                "${targetExt.uppercase(Locale.ROOT)} • Hızlı Ses"
            } else {
                "${settings.defaultVideoQuality} • Hızlı Video"
            }
            val selector = if (audioOnly) {
                "bestaudio/best"
            } else {
                buildDefaultVideoSelector(settings.defaultVideoQuality)
            }

            val fileName = FileNameSanitizer.applyTemplate(
                template = settings.fileNameTemplate,
                title = title,
                author = null,
                extension = targetExt
            )

            val task = DownloadTask(
                id = taskId,
                url = safeUrl,
                title = title,
                author = null,
                thumbnailUrl = null,
                platform = platform,
                category = category,
                formatLabel = formatLabel,
                fileName = fileName,
                status = DownloadStatus.QUEUED,
                ytDlpFormatSelector = selector,
                isAudioExtraction = audioOnly,
                targetExtension = targetExt
            )

            downloadDao.upsert(task.toEntity())
            created.add(task)
        }

        if (created.isNotEmpty()) {
            DownloadForegroundService.start(context)
        }
        created
    }

    /**
     * Oynatma listesinden seçilen öğeleri toplu olarak kuyruğa ekler ve servisi tek seferde başlatır.
     */
    suspend fun enqueuePlaylistEntries(
        entries: List<PlaylistEntry>,
        platform: PlatformType,
        audioOnly: Boolean
    ): List<DownloadTask> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyList()

        val settings = settingsRepository.getCurrentSettings()
        val targetExt = if (audioOnly) {
            settings.defaultAudioFormat.lowercase(Locale.ROOT).ifBlank { "mp3" }
        } else {
            "mp4"
        }
        val category = if (audioOnly) MediaCategory.AUDIO else MediaCategory.VIDEO
        val formatLabel = if (audioOnly) {
            "${targetExt.uppercase(Locale.ROOT)} • Oynatma Listesi"
        } else {
            "${settings.defaultVideoQuality} • Oynatma Listesi"
        }
        val selector = if (audioOnly) {
            "bestaudio/best"
        } else {
            buildDefaultVideoSelector(settings.defaultVideoQuality)
        }

        val created = mutableListOf<DownloadTask>()
        entries.forEach { entry ->
            val safeUrl = UrlSanitizer.extractFirstValidUrl(entry.url) ?: return@forEach
            val safeThumb = entry.thumbnailUrl?.takeIf { NetworkSecurityGuard.isSafePublicUrl(it) }
            val fileName = FileNameSanitizer.applyTemplate(
                template = settings.fileNameTemplate,
                title = entry.title,
                author = entry.author,
                extension = targetExt
            )

            val task = DownloadTask(
                id = UUID.randomUUID().toString(),
                url = safeUrl,
                title = entry.title,
                author = entry.author,
                thumbnailUrl = safeThumb,
                platform = platform,
                category = category,
                formatLabel = formatLabel,
                fileName = fileName,
                status = DownloadStatus.QUEUED,
                ytDlpFormatSelector = selector,
                isAudioExtraction = audioOnly,
                targetExtension = targetExt
            )

            downloadDao.upsert(task.toEntity())
            created.add(task)
        }

        if (created.isNotEmpty()) {
            DownloadForegroundService.start(context)
        }
        created
    }

    /**
     * Tek bir görseli veya kapak resmini indirme kuyruğuna ekler.
     */
    suspend fun enqueueImage(
        media: ExtractedMedia,
        imageItem: ImageItem,
        indexSuffix: String? = null
    ): DownloadTask = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getCurrentSettings()
        val taskId = UUID.randomUUID().toString()
        val fileName = FileNameSanitizer.applyTemplate(
            template = settings.fileNameTemplate,
            title = media.title,
            author = media.author,
            extension = imageItem.extension,
            suffix = indexSuffix
        )

        val task = DownloadTask(
            id = taskId,
            url = media.originalUrl,
            title = media.title,
            author = media.author,
            thumbnailUrl = imageItem.url,
            platform = media.platform,
            category = MediaCategory.IMAGE,
            formatLabel = imageItem.label,
            fileName = fileName,
            status = DownloadStatus.QUEUED,
            directUrl = imageItem.url,
            targetExtension = imageItem.extension
        )

        downloadDao.upsert(task.toEntity())
        DownloadForegroundService.start(context)
        task
    }

    /**
     * Medya içindeki tüm galeri/fotoğraf öğelerini toplu olarak kuyruğa ekler.
     */
    suspend fun enqueueAllImages(media: ExtractedMedia): List<DownloadTask> = withContext(Dispatchers.IO) {
        if (media.imageItems.isEmpty()) return@withContext emptyList()

        val settings = settingsRepository.getCurrentSettings()
        val created = media.imageItems.mapIndexed { index, item ->
            val taskId = UUID.randomUUID().toString()
            val suffix = if (media.imageItems.size > 1) "${index + 1}" else null
            val fileName = FileNameSanitizer.applyTemplate(
                template = settings.fileNameTemplate,
                title = media.title,
                author = media.author,
                extension = item.extension,
                suffix = suffix
            )

            DownloadTask(
                id = taskId,
                url = media.originalUrl,
                title = media.title,
                author = media.author,
                thumbnailUrl = item.url,
                platform = media.platform,
                category = MediaCategory.IMAGE,
                formatLabel = item.label,
                fileName = fileName,
                status = DownloadStatus.QUEUED,
                directUrl = item.url,
                targetExtension = item.extension
            ).also { task ->
                downloadDao.upsert(task.toEntity())
            }
        }

        DownloadForegroundService.start(context)
        created
    }

    /**
     * Kuyruktaki veya yarıda kalmış bekleyen görevleri döndürür.
     */
    suspend fun getPendingTasks(): List<DownloadTask> = withContext(Dispatchers.IO) {
        downloadDao.getPendingTasks().map { it.toDomain() }
    }

    /**
     * Belirtilen görev şu anda aktif olarak yürütülüyor mu kontrol eder.
     */
    fun isTaskRunning(taskId: String): Boolean = activeJobs[taskId]?.isActive == true

    /**
     * Aktif iş haritasına bir görevi atomik olarak kaydeder.
     * Aynı görev zaten çalışıyorsa `false` döner (çift başlatmayı önler).
     */
    fun tryRegisterActiveJob(taskId: String, job: Job): Boolean {
        return activeJobs.putIfAbsent(taskId, job) == null
    }

    /**
     * Tamamlanan veya sonlanan işi yalnızca beklenen [job] ile eşleşiyorsa aktif haritadan çıkarır.
     */
    fun unregisterActiveJob(taskId: String, job: Job? = null) {
        if (job == null) {
            activeJobs.remove(taskId)
        } else {
            activeJobs.remove(taskId, job)
        }
    }

    /**
     * Devam eden veya kuyruktaki bir görevi iptal eder.
     */
    suspend fun cancel(taskId: String) = withContext(Dispatchers.IO) {
        ytDlpEngine.cancel(taskId)
        activeJobs.remove(taskId)?.cancel()
        downloadDao.updateStatus(
            id = taskId,
            status = DownloadStatus.CANCELLED.name,
            errorMessage = "Kullanıcı tarafından iptal edildi."
        )
    }

    /**
     * Tüm aktif indirme görevlerini iptal eder.
     */
    suspend fun cancelAllActive() = withContext(Dispatchers.IO) {
        val ids = activeJobs.keys.toList()
        ids.forEach { id ->
            ytDlpEngine.cancel(id)
            activeJobs.remove(id)?.cancel()
            downloadDao.updateStatus(
                id = id,
                status = DownloadStatus.CANCELLED.name,
                errorMessage = "Kullanıcı tarafından iptal edildi."
            )
        }
    }

    /**
     * Başarısız veya iptal edilmiş bir görevi sıfırlayarak yeniden kuyruğa alır.
     * Geçici klasördeki `.part` dosyaları korunduğu için yt-dlp `--continue` ile kaldığı yerden devam eder.
     */
    suspend fun retry(taskId: String) = withContext(Dispatchers.IO) {
        val existing = downloadDao.getById(taskId)?.toDomain() ?: return@withContext
        val resetTask = existing.copy(
            status = DownloadStatus.QUEUED,
            progress = 0f,
            speedText = null,
            etaSeconds = null,
            errorMessage = null
        )
        downloadDao.upsert(resetTask.toEntity())
        DownloadForegroundService.start(context)
    }

    /**
     * Görevi iptal edip veritabanından tamamen siler.
     * [deletePhysicalFile] `true` ise kaydedilmiş medya dosyasını da cihazdan güvenli şekilde siler.
     */
    suspend fun delete(
        taskId: String,
        deletePhysicalFile: Boolean = false
    ) = withContext(Dispatchers.IO) {
        ytDlpEngine.cancel(taskId)
        activeJobs.remove(taskId)?.cancel()

        if (deletePhysicalFile) {
            val entity = downloadDao.getById(taskId)
            if (entity != null) {
                mediaStoreSaver.deleteMediaFile(
                    savedUri = entity.savedUri,
                    savedPath = entity.savedPath
                )
            }
        }

        cleanupTaskWorkDir(taskId)
        downloadDao.deleteById(taskId)
    }

    /**
     * Tamamlanmış tüm görevleri geçmişten temizler.
     */
    suspend fun clearCompleted() = withContext(Dispatchers.IO) {
        downloadDao.deleteCompleted()
    }

    /**
     * Tek bir indirme görevini uçtan uca yürütür:
     * 1) Geçici klasöre indirir (doğrudan URL veya yt-dlp; yarıda kalmış `.part` dosyalarını korur)
     * 2) MediaStoreSaver ile Movies/Music/Pictures/UlgenTube (veya platform alt klasörü) içine kaydeder
     * 3) Yalnızca tamamlandığında geçici dosyaları temizler ve Room kaydını günceller.
     */
    suspend fun executeDownload(
        task: DownloadTask,
        onProgressUpdate: (progress: Float, speedText: String?, status: DownloadStatus) -> Unit
    ): Result<DownloadTask> = withContext(Dispatchers.IO) {
        val safeTaskId = task.id.takeIf { SAFE_TASK_ID_REGEX.matches(it) }
            ?: return@withContext Result.failure(IllegalArgumentException("Geçersiz görev kimliği."))
        val workRoot = File(context.cacheDir, "dl_work").apply { mkdirs() }
        val tempDir = File(workRoot, safeTaskId)
        val canonicalWorkRoot = workRoot.canonicalPath + File.separator
        if (!tempDir.canonicalPath.startsWith(canonicalWorkRoot)) {
            return@withContext Result.failure(SecurityException("Geçersiz çalışma dizini."))
        }
        // Resume koruması: mevcut .part dosyalarını silme, yalnızca klasör yoksa oluştur
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }

        var completedSuccessfully = false

        try {
            downloadDao.updateProgress(
                id = task.id,
                progress = 0f,
                speedText = null,
                etaSeconds = null,
                status = DownloadStatus.DOWNLOADING.name
            )
            onProgressUpdate(0f, null, DownloadStatus.DOWNLOADING)

            val settings = settingsRepository.getCurrentSettings()
            var lastDbWriteTime = 0L

            val requiresYtDlpOrFfmpeg = task.isAudioExtraction ||
                task.category == MediaCategory.AUDIO ||
                task.transcodeTargetHeight != null ||
                task.clipStartSeconds != null ||
                task.clipEndSeconds != null ||
                !task.subtitleLang.isNullOrBlank()

            val downloadedFile: File = if (!task.directUrl.isNullOrBlank() && !requiresYtDlpOrFfmpeg) {
                val safeFileName = FileNameSanitizer.buildFileName(
                    rawTitle = task.fileName.substringBeforeLast('.', task.fileName),
                    extension = task.fileName.substringAfterLast('.', task.targetExtension)
                )
                val targetFile = File(tempDir, safeFileName)
                if (!targetFile.canonicalPath.startsWith(tempDir.canonicalPath + File.separator)) {
                    throw SecurityException("Geçersiz hedef dosya yolu.")
                }
                socialImageExtractor.downloadDirectFile(
                    url = task.directUrl,
                    targetFile = targetFile
                ) { progress, speedText ->
                    val now = System.currentTimeMillis()
                    if (now - lastDbWriteTime >= PROGRESS_DB_THROTTLE_MS || progress >= 99f) {
                        lastDbWriteTime = now
                        onProgressUpdate(progress, speedText, DownloadStatus.DOWNLOADING)
                        updateProgressNonBlocking(
                            taskId = task.id,
                            progress = progress,
                            speedText = speedText,
                            etaSeconds = null,
                            status = DownloadStatus.DOWNLOADING
                        )
                    }
                }
            } else {
                ytDlpEngine.download(
                    task = task,
                    outputDir = tempDir,
                    settings = settings
                ) { progress, etaSeconds, speedText ->
                    val status = if (progress >= 99.5f) {
                        DownloadStatus.MERGING
                    } else {
                        DownloadStatus.DOWNLOADING
                    }
                    onProgressUpdate(progress, speedText, status)
                    val now = System.currentTimeMillis()
                    if (now - lastDbWriteTime >= PROGRESS_DB_THROTTLE_MS || progress >= 99.5f) {
                        lastDbWriteTime = now
                        updateProgressNonBlocking(task.id, progress, speedText, etaSeconds, status)
                    }
                }
            }

            // MediaStore'a yazma aşaması
            downloadDao.updateProgress(
                id = task.id,
                progress = 99f,
                speedText = null,
                etaSeconds = 0L,
                status = DownloadStatus.MERGING.name
            )
            onProgressUpdate(99f, null, DownloadStatus.MERGING)

            val platformSubfolder = if (settings.platformSubfolders) {
                task.platform.displayName
            } else {
                null
            }

            val saved = mediaStoreSaver.save(
                sourceFile = downloadedFile,
                desiredFileName = task.fileName,
                category = task.category,
                platformSubfolder = platformSubfolder
            )

            downloadDao.updateStatus(
                id = task.id,
                status = DownloadStatus.COMPLETED.name,
                errorMessage = null,
                savedUri = saved.uri,
                savedPath = saved.displayPath,
                fileSizeBytes = saved.fileSizeBytes
            )
            completedSuccessfully = true
            onProgressUpdate(100f, null, DownloadStatus.COMPLETED)

            val completedTask = task.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100f,
                savedUri = saved.uri,
                savedPath = saved.displayPath,
                fileSizeBytes = saved.fileSizeBytes,
                speedText = null,
                etaSeconds = null,
                errorMessage = null
            )
            Result.success(completedTask)
        } catch (cancel: CancellationException) {
            downloadDao.updateStatus(
                id = task.id,
                status = DownloadStatus.CANCELLED.name,
                errorMessage = "İndirme iptal edildi."
            )
            throw cancel
        } catch (e: Exception) {
            val friendlyMessage = ErrorMessageSanitizer.sanitize(
                throwable = e,
                fallback = "İndirme sırasında beklenmeyen bir hata oluştu."
            )
            downloadDao.updateStatus(
                id = task.id,
                status = DownloadStatus.FAILED.name,
                errorMessage = friendlyMessage
            )
            Result.failure(e)
        } finally {
            if (completedSuccessfully) {
                runCatching { tempDir.deleteRecursively() }
            }
        }
    }

    private fun cleanupTaskWorkDir(taskId: String) {
        val safeTaskId = taskId.takeIf { SAFE_TASK_ID_REGEX.matches(it) } ?: return
        runCatching {
            val workRoot = File(context.cacheDir, "dl_work")
            val tempDir = File(workRoot, safeTaskId)
            val canonicalWorkRoot = workRoot.canonicalPath + File.separator
            if (tempDir.exists() && tempDir.canonicalPath.startsWith(canonicalWorkRoot)) {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun buildDefaultVideoSelector(qualityLabel: String): String {
        val maxHeight = when {
            qualityLabel.contains("2160") || qualityLabel.contains("4K", ignoreCase = true) -> 2160
            qualityLabel.contains("1440") || qualityLabel.contains("2K", ignoreCase = true) -> 1440
            qualityLabel.contains("1080") -> 1080
            qualityLabel.contains("720") -> 720
            qualityLabel.contains("480") -> 480
            qualityLabel.contains("360") -> 360
            else -> null
        }
        return if (maxHeight != null) {
            "bestvideo[height<=$maxHeight]+bestaudio[ext=m4a]/bestvideo[height<=$maxHeight]+bestaudio/best[height<=$maxHeight]/best"
        } else {
            "bestvideo+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
        }
    }

    /**
     * Senkron callback içinden (ör. yt-dlp progress satırı) veritabanını güncellemek için kullanılır.
     */
    private fun updateProgressNonBlocking(
        taskId: String,
        progress: Float,
        speedText: String?,
        etaSeconds: Long?,
        status: DownloadStatus
    ) {
        (context.applicationContext as? com.ulgentube.app.UlgenTubeApp)?.applicationScope?.let { scope ->
            scope.launchUpdate(taskId, progress, speedText, etaSeconds, status)
        }
    }

    private fun CoroutineScope.launchUpdate(
        taskId: String,
        progress: Float,
        speedText: String?,
        etaSeconds: Long?,
        status: DownloadStatus
    ) {
        launch {
            runCatching {
                downloadDao.updateProgress(
                    id = taskId,
                    progress = progress,
                    speedText = speedText,
                    etaSeconds = etaSeconds?.takeIf { it >= 0L },
                    status = status.name
                )
            }
        }
    }

    companion object {
        private const val PROGRESS_DB_THROTTLE_MS = 400L
        private val SAFE_TASK_ID_REGEX = Regex("""^[a-zA-Z0-9_-]{1,64}$""")
    }
}
