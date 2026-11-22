package com.ulgentube.app.data

import android.content.Context
import android.net.Uri
import com.ulgentube.app.data.local.DownloadDao
import com.ulgentube.app.data.local.FollowedChannelDao
import com.ulgentube.app.data.local.SettingsRepository
import com.ulgentube.app.data.local.toDomain
import com.ulgentube.app.data.local.toEntity
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.FfmpegStudioAction
import com.ulgentube.app.domain.model.FfmpegStudioOptions
import com.ulgentube.app.domain.model.FollowedChannel
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.PlaylistEntry
import com.ulgentube.app.engine.ChannelFeedChecker
import com.ulgentube.app.engine.LocalFfmpegProcessor
import com.ulgentube.app.engine.LrcLyricsFetcher
import com.ulgentube.app.engine.MediaStoreSaver
import com.ulgentube.app.engine.ParallelRangeDownloader
import com.ulgentube.app.engine.SocialImageExtractor
import com.ulgentube.app.engine.YouTubeInnertubeEngine
import com.ulgentube.app.engine.YouTubePoTokenProvider
import com.ulgentube.app.engine.YtDlpEngine
import com.ulgentube.app.service.DownloadForegroundService
import com.ulgentube.app.util.ErrorMessageSanitizer
import com.ulgentube.app.util.FileNameSanitizer
import com.ulgentube.app.util.MediaUriHelper
import com.ulgentube.app.util.NetworkSecurityGuard
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    private val youTubeInnertubeEngine: YouTubeInnertubeEngine? = null,
    private val mediaStoreSaver: MediaStoreSaver,
    private val localFfmpegProcessor: LocalFfmpegProcessor? = null,
    private val followedChannelDao: FollowedChannelDao? = null,
    private val channelFeedChecker: ChannelFeedChecker? = null,
    private val lrcLyricsFetcher: LrcLyricsFetcher? = null,
    private val youTubePoTokenProvider: YouTubePoTokenProvider? = null,
    private val backupRestoreManager: BackupRestoreManager? = null,
    private val parallelRangeDownloader: ParallelRangeDownloader? = null
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Tüm indirme görevlerini en yeniden eskiye doğru canlı olarak gözlemler. */
    val downloadsFlow: Flow<List<DownloadTask>> = downloadDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    /** Takip edilen YouTube kanallarını canlı olarak gözlemler. */
    val followedChannelsFlow: Flow<List<FollowedChannel>> =
        followedChannelDao?.observeAll()?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

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

    private val pausedTaskIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Devam eden veya kuyruktaki bir görevi duraklatır; geçici dosyalar korunur.
     */
    suspend fun pause(taskId: String) = withContext(Dispatchers.IO) {
        pausedTaskIds.add(taskId)
        ytDlpEngine.cancel(taskId)
        activeJobs.remove(taskId)?.cancel()
        downloadDao.updateStatus(
            id = taskId,
            status = DownloadStatus.PAUSED.name,
            errorMessage = "Duraklatıldı"
        )
    }

    /**
     * Tüm aktif indirme görevlerini duraklatır.
     */
    suspend fun pauseAllActive() = withContext(Dispatchers.IO) {
        val pending = downloadDao.getPendingTasks().map { it.id }
        val ids = (activeJobs.keys + pending).distinct()
        ids.forEach { id -> pause(id) }
    }

    /**
     * Duraklatılmış tüm görevleri yeniden kuyruğa alarak kaldığı yerden devam ettirir.
     */
    suspend fun resumeAllPaused() = withContext(Dispatchers.IO) {
        val paused = downloadDao.getPausedTasks()
        paused.forEach { entity ->
            pausedTaskIds.remove(entity.id)
            downloadDao.updateStatus(
                id = entity.id,
                status = DownloadStatus.QUEUED.name,
                errorMessage = null
            )
        }
        if (paused.isNotEmpty()) {
            DownloadForegroundService.start(context)
        }
    }

    /**
     * Devam eden veya kuyruktaki bir görevi iptal eder.
     */
    suspend fun cancel(taskId: String) = withContext(Dispatchers.IO) {
        pausedTaskIds.remove(taskId)
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
            val parallelConns = if (settings.parallelDownloadEnabled) {
                settings.parallelConnectionCount.coerceIn(1, 16)
            } else {
                1
            }
            val maxSpeedKbps = settings.maxDownloadSpeedKbps.coerceAtLeast(0)
            val maxSpeedBytesPerSec = maxSpeedKbps * 1024L
            val poTokenPair = if (task.platform == PlatformType.YOUTUBE &&
                settings.poTokenEnabled &&
                youTubePoTokenProvider != null
            ) {
                runCatching { youTubePoTokenProvider.getOrFetchPoToken() }.getOrNull()
            } else {
                null
            }
            var lastDbWriteTime = 0L

            val requiresYtDlpOrFfmpeg = task.isAudioExtraction ||
                task.category == MediaCategory.AUDIO ||
                task.transcodeTargetHeight != null ||
                task.clipStartSeconds != null ||
                task.clipEndSeconds != null ||
                !task.subtitleLang.isNullOrBlank()

            // YouTube / YT Music playlist veya albümden kuyruğa eklenen görevlerde directUrl boşsa
            // yt-dlp'ye düşmeden önce hızlı Innertube motoruyla doğrudan akış URL'sini çöz
            val resolvedTask = if (task.platform == PlatformType.YOUTUBE &&
                task.directUrl.isNullOrBlank() &&
                youTubeInnertubeEngine != null
            ) {
                val freshMedia = runCatching {
                    youTubeInnertubeEngine.fetchVideoInfo(
                        url = task.url,
                        poToken = poTokenPair?.poToken,
                        visitorData = poTokenPair?.visitorData
                    )
                }.getOrNull()
                val resolvedDirectUrl = when {
                    freshMedia == null -> null
                    task.isAudioExtraction || task.category == MediaCategory.AUDIO ->
                        freshMedia.audioFormats.firstOrNull { !it.directUrl.isNullOrBlank() }?.directUrl
                    else ->
                        freshMedia.videoFormats.firstOrNull { !it.directUrl.isNullOrBlank() }?.directUrl
                }
                if (!resolvedDirectUrl.isNullOrBlank()) task.copy(directUrl = resolvedDirectUrl) else task
            } else {
                task
            }

            val isYouTubeDirect = resolvedTask.platform == PlatformType.YOUTUBE &&
                !resolvedTask.directUrl.isNullOrBlank() &&
                youTubeInnertubeEngine != null

            val downloadedFile: File = if (isYouTubeDirect) {
                val directAttempt = runCatching {
                    youTubeInnertubeEngine!!.downloadYouTubeDirect(
                        task = resolvedTask,
                        tempDir = tempDir,
                        parallelConnections = parallelConns,
                        maxSpeedBytesPerSec = maxSpeedBytesPerSec
                    ) { progress, speedText, status ->
                        onProgressUpdate(progress, speedText, status)
                        val now = System.currentTimeMillis()
                        if (now - lastDbWriteTime >= PROGRESS_DB_THROTTLE_MS || progress >= 95f) {
                            lastDbWriteTime = now
                            updateProgressNonBlocking(task.id, progress, speedText, null, status)
                        }
                    }
                }
                if (directAttempt.isSuccess) {
                    directAttempt.getOrThrow()
                } else {
                    android.util.Log.w(
                        "UlgenTubeDownload",
                        "YouTube direct/mux başarısız, yt-dlp yedeğine geçiliyor: ${directAttempt.exceptionOrNull()?.message}"
                    )
                    com.ulgentube.app.util.DiagnosticLogManager.recordError(
                        tag = "YouTubeDirect",
                        summary = "YouTube direct/mux başarısız, yt-dlp yedeğine geçildi",
                        throwable = directAttempt.exceptionOrNull(),
                        metadata = mapOf(
                            "url" to task.url,
                            "format" to task.formatLabel,
                            "selector" to task.ytDlpFormatSelector,
                            "subtitle" to task.subtitleLang
                        )
                    )
                    // Doğrudan akış başarısız olduysa temizleyip yt-dlp fallback'ine geç
                    tempDir.listFiles()?.forEach { runCatching { it.delete() } }
                    val fallbackTask = task.copy(directUrl = null)
                    ytDlpEngine.download(
                        task = fallbackTask,
                        outputDir = tempDir,
                        settings = settings,
                        poToken = poTokenPair?.poToken,
                        visitorData = poTokenPair?.visitorData,
                        maxSpeedKbps = maxSpeedKbps
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
            } else if (!task.directUrl.isNullOrBlank() && !requiresYtDlpOrFfmpeg) {
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
                    targetFile = targetFile,
                    parallelConnections = parallelConns,
                    maxSpeedBytesPerSec = maxSpeedBytesPerSec
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
                val ytTask = if (task.directUrl?.contains(YouTubeInnertubeEngine.DUAL_STREAM_SEPARATOR) == true) {
                    task.copy(directUrl = null)
                } else {
                    task
                }
                ytDlpEngine.download(
                    task = ytTask,
                    outputDir = tempDir,
                    settings = settings,
                    poToken = poTokenPair?.poToken,
                    visitorData = poTokenPair?.visitorData,
                    maxSpeedKbps = maxSpeedKbps
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

            val actualExt = downloadedFile.extension.lowercase(java.util.Locale.ROOT).takeIf { it.isNotBlank() }
            val desiredSaveName = if (actualExt != null && !task.fileName.lowercase(java.util.Locale.ROOT).endsWith(".$actualExt")) {
                "${task.fileName.substringBeforeLast('.', task.fileName)}.$actualExt"
            } else {
                task.fileName
            }

            val saved = mediaStoreSaver.save(
                sourceFile = downloadedFile,
                desiredFileName = desiredSaveName,
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

            // YouTube için altyazı seçildiyse yan .vtt dosyasını indir (dahili oynatıcı CC otomatik görür)
            if (task.platform == PlatformType.YOUTUBE &&
                !task.subtitleLang.isNullOrBlank() &&
                youTubeInnertubeEngine != null
            ) {
                runCatching {
                    val baseName = task.fileName.substringBeforeLast('.', task.fileName)
                    val subFileName = "${baseName}.${task.subtitleLang}.vtt"
                    val subDir = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ),
                        "UlgenTube"
                    )
                    val targetSub = File(subDir, subFileName)
                    val downloaded = youTubeInnertubeEngine.downloadSubtitleVtt(
                        videoUrl = task.url,
                        langCode = task.subtitleLang,
                        targetFile = targetSub
                    )
                    if (downloaded == null) {
                        val fallbackSub = File(File(context.filesDir, "subtitles"), subFileName)
                        youTubeInnertubeEngine.downloadSubtitleVtt(
                            videoUrl = task.url,
                            langCode = task.subtitleLang,
                            targetFile = fallbackSub
                        )
                    }
                }
            }

            // Ses / MP3 indirmesi tamamlandığında otomatik LRC şarkı sözü çek
            var fetchedLyrics: String? = task.lyricsText
            if ((task.category == MediaCategory.AUDIO || task.isAudioExtraction) &&
                settings.autoFetchLyrics &&
                lrcLyricsFetcher != null
            ) {
                val lyrics = runCatching {
                    lrcLyricsFetcher.fetchLyrics(task.title, task.author)
                }.getOrNull()
                if (!lyrics.isNullOrBlank()) {
                    downloadDao.updateLyricsText(task.id, lyrics)
                    fetchedLyrics = lyrics
                }
            }

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
                errorMessage = null,
                lyricsText = fetchedLyrics
            )
            Result.success(completedTask)
        } catch (cancel: CancellationException) {
            if (pausedTaskIds.remove(task.id)) {
                downloadDao.updateStatus(
                    id = task.id,
                    status = DownloadStatus.PAUSED.name,
                    errorMessage = "Duraklatıldı"
                )
            } else {
                downloadDao.updateStatus(
                    id = task.id,
                    status = DownloadStatus.CANCELLED.name,
                    errorMessage = "İndirme iptal edildi."
                )
            }
            throw cancel
        } catch (e: Exception) {
            com.ulgentube.app.util.DiagnosticLogManager.recordError(
                tag = "DownloadExecute",
                summary = "İndirme başarısız (${task.platform.displayName})",
                throwable = e,
                metadata = mapOf(
                    "taskId" to task.id,
                    "url" to task.url,
                    "format" to task.formatLabel,
                    "fileName" to task.fileName,
                    "hasDirectUrl" to (!task.directUrl.isNullOrBlank()).toString(),
                    "selector" to task.ytDlpFormatSelector,
                    "subtitle" to task.subtitleLang
                )
            )
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

    /**
     * Kaldığın yerden devam (Resume Playback) konumunu milisaniye olarak kaydeder.
     */
    suspend fun savePlaybackPosition(taskId: String, positionMs: Long) = withContext(Dispatchers.IO) {
        downloadDao.updatePlaybackPosition(taskId, positionMs.coerceAtLeast(0L))
    }

    /**
     * İndirme kaydının favori durumunu tersine çevirir.
     */
    suspend fun toggleFavorite(taskId: String) = withContext(Dispatchers.IO) {
        val current = downloadDao.getById(taskId) ?: return@withContext
        downloadDao.updateFavorite(taskId, !current.isFavorite)
    }

    /**
     * Medya dosyasını Özel Kasa'ya (`filesDir/vault/.nomedia`) taşır veya kasadan galeriye geri çıkarır.
     *
     * @return Yeni kasa durumu (`true` = kasa içinde, `false` = genel galeride).
     */
    suspend fun toggleVault(taskId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = downloadDao.getById(taskId)
                ?: throw IllegalStateException("Medya kaydı bulunamadı.")
            val task = entity.toDomain()

            if (!task.isInVault) {
                val moved = mediaStoreSaver.moveToPrivateVault(task)
                    ?: throw IllegalStateException("Dosya Özel Kasa'ya taşınamadı.")
                downloadDao.updateVaultState(
                    id = taskId,
                    isInVault = true,
                    savedUri = moved.first,
                    savedPath = moved.second
                )
                true
            } else {
                val restored = mediaStoreSaver.restoreFromPrivateVault(task)
                    ?: throw IllegalStateException("Dosya Özel Kasa'dan geri çıkarılamadı.")
                downloadDao.updateVaultState(
                    id = taskId,
                    isInVault = false,
                    savedUri = restored.uri,
                    savedPath = restored.displayPath
                )
                false
            }
        }
    }

    /**
     * Ses/şarkı kaydı için LRCLIB üzerinden senkronize LRC şarkı sözlerini çeker veya yeniler.
     */
    suspend fun fetchOrRefreshLyrics(taskId: String): String? = withContext(Dispatchers.IO) {
        val fetcher = lrcLyricsFetcher ?: return@withContext null
        val entity = downloadDao.getById(taskId) ?: return@withContext null
        val lyrics = runCatching {
            fetcher.fetchLyrics(entity.title, entity.author)
        }.getOrNull()
        if (!lyrics.isNullOrBlank()) {
            downloadDao.updateLyricsText(taskId, lyrics)
        }
        lyrics
    }

    /**
     * İndirilmiş bir [task] üzerinde FFmpeg Studio Pro dönüştürme eylemini yürütür ve
     * üretilen yeni medya dosyasını kütüphaneye tamamlanmış görev olarak kaydeder.
     */
    suspend fun processFfmpegStudioPro(
        task: DownloadTask,
        action: FfmpegStudioAction,
        options: FfmpegStudioOptions,
        onProgress: (Float) -> Unit = {}
    ): Result<DownloadTask> = withContext(Dispatchers.IO) {
        val processor = localFfmpegProcessor
            ?: return@withContext Result.failure(IllegalStateException("FFmpeg Stüdyo motoru hazır değil."))
        val sourceUri = MediaUriHelper.resolveTaskUri(context, task)
            ?: return@withContext Result.failure(IllegalStateException("Kaynak medya dosyası bulunamadı."))

        val processedResult = processor.process(
            inputUri = sourceUri,
            action = action,
            outputBaseName = task.title,
            options = options,
            onProgress = onProgress
        )

        processedResult.map { saved ->
            val newCategory = when (action) {
                FfmpegStudioAction.EXTRACT_MP3,
                FfmpegStudioAction.CONVERT_AUDIO -> MediaCategory.AUDIO
                FfmpegStudioAction.MAKE_GIF -> MediaCategory.IMAGE
                else -> MediaCategory.VIDEO
            }
            val newExtension = saved.displayPath.substringAfterLast('.', task.targetExtension)
                .lowercase(Locale.ROOT)
                .ifBlank { task.targetExtension }
            val newFileName = saved.displayPath.substringAfterLast('/', task.fileName)
            val newTask = DownloadTask(
                id = UUID.randomUUID().toString(),
                url = task.url,
                title = "${task.title} (${action.displayName})",
                author = task.author,
                thumbnailUrl = task.thumbnailUrl,
                platform = task.platform,
                category = newCategory,
                formatLabel = if (action == FfmpegStudioAction.CONVERT_AUDIO && options.saveAsRingtone) {
                    "Zil Sesi • ${newExtension.uppercase(Locale.ROOT)}"
                } else {
                    action.displayName
                },
                fileName = newFileName,
                savedUri = saved.uri,
                savedPath = saved.displayPath,
                fileSizeBytes = saved.fileSizeBytes,
                progress = 100f,
                status = DownloadStatus.COMPLETED,
                isAudioExtraction = newCategory == MediaCategory.AUDIO,
                targetExtension = newExtension
            )
            downloadDao.upsert(newTask.toEntity())
            newTask
        }
    }

    /**
     * YouTube kanalını URL, `@handle` veya `UC...` kimliği üzerinden takibe alır.
     */
    suspend fun followChannel(
        urlOrHandle: String,
        autoDownload: Boolean = false
    ): Result<FollowedChannel> = withContext(Dispatchers.IO) {
        runCatching {
            val checker = channelFeedChecker
                ?: throw IllegalStateException("Kanal takip motoru hazır değil.")
            val dao = followedChannelDao
                ?: throw IllegalStateException("Kanal veritabanı hazır değil.")

            val snapshot = checker.resolveAndFetch(urlOrHandle)
                ?: throw IllegalArgumentException("Kanal bulunamadı veya RSS beslemesi okunamadı.")

            val now = System.currentTimeMillis()
            val channel = FollowedChannel(
                channelId = snapshot.channelId,
                title = snapshot.title,
                url = snapshot.canonicalUrl.ifBlank {
                    "https://www.youtube.com/channel/${snapshot.channelId}"
                },
                platform = PlatformType.YOUTUBE,
                autoDownloadLatest = autoDownload,
                lastKnownVideoId = snapshot.latestVideoId,
                lastKnownVideoTitle = snapshot.latestVideoTitle,
                lastCheckedAt = now,
                createdAt = now
            )
            dao.upsert(channel.toEntity())
            channel
        }
    }

    /**
     * Kanalı takipten çıkarır.
     */
    suspend fun unfollowChannel(channelId: String) = withContext(Dispatchers.IO) {
        followedChannelDao?.deleteById(channelId)
    }

    /**
     * Takip edilen kanal için yeni video otomatik indirme seçeneğini günceller.
     */
    suspend fun toggleChannelAutoDownload(channelId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        followedChannelDao?.updateAutoDownload(channelId, enabled)
    }

    /**
     * Takip edilen tüm kanalların RSS beslemelerini kontrol eder; yeni video bulunan kanalları günceller,
     * `autoDownloadLatest = true` olanların yeni videosunu otomatik kuyruğa ekler ve yeni içerik
     * bulunan kanalların listesini döndürür.
     */
    suspend fun checkFollowedChannels(): Result<List<FollowedChannel>> = withContext(Dispatchers.IO) {
        runCatching {
            val checker = channelFeedChecker
                ?: throw IllegalStateException("Kanal takip motoru hazır değil.")
            val dao = followedChannelDao
                ?: throw IllegalStateException("Kanal veritabanı hazır değil.")

            val channels = dao.getAll().map { it.toDomain() }
            val updatedChannels = mutableListOf<FollowedChannel>()
            val autoDownloadUrls = mutableListOf<String>()
            val now = System.currentTimeMillis()

            for (channel in channels) {
                val snapshot = runCatching { checker.resolveAndFetch(channel.url) }.getOrNull() ?: continue
                val newVideoId = snapshot.latestVideoId
                val hasNewVideo = !newVideoId.isNullOrBlank() && newVideoId != channel.lastKnownVideoId

                dao.updateLatestVideo(
                    channelId = channel.channelId,
                    videoId = newVideoId ?: channel.lastKnownVideoId,
                    videoTitle = snapshot.latestVideoTitle ?: channel.lastKnownVideoTitle,
                    checkedAt = now
                )

                if (hasNewVideo) {
                    val updated = channel.copy(
                        lastKnownVideoId = newVideoId,
                        lastKnownVideoTitle = snapshot.latestVideoTitle,
                        lastCheckedAt = now
                    )
                    updatedChannels.add(updated)
                    if (channel.autoDownloadLatest && !snapshot.latestVideoUrl.isNullOrBlank()) {
                        autoDownloadUrls.add(snapshot.latestVideoUrl)
                    }
                }
            }

            if (autoDownloadUrls.isNotEmpty()) {
                enqueueBatchUrls(autoDownloadUrls, audioOnly = false)
            }
            updatedChannels
        }
    }

    /**
     * Geçici indirme ve dönüştürme önbelleğinin toplam boyutunu bayt cinsinden hesaplar.
     * Özel Kasa (`filesDir/vault`) klasörüne ASLA dokunmaz.
     */
    fun calculateTempCacheBytes(): Long {
        val vaultCanonical = runCatching {
            File(context.filesDir, "vault").canonicalPath
        }.getOrNull()

        fun dirSizeSafe(dir: File): Long {
            if (!dir.exists()) return 0L
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return 0L
            if (vaultCanonical != null && (canonical == vaultCanonical || canonical.startsWith(vaultCanonical + File.separator))) {
                return 0L
            }
            if (dir.isFile) return dir.length()
            var total = 0L
            dir.listFiles()?.forEach { child ->
                total += dirSizeSafe(child)
            }
            return total
        }

        val filesDlWork = File(context.filesDir, "dl_work")
        return dirSizeSafe(context.cacheDir) + dirSizeSafe(filesDlWork)
    }

    /**
     * Geçici önbellek klasörlerini (`cacheDir` ve `filesDir/dl_work`) temizler ve
     * kazanılan alanı bayt cinsinden döndürür. Özel Kasa (`filesDir/vault`) ASLA silinmez.
     */
    suspend fun clearTempCache(): Long = withContext(Dispatchers.IO) {
        val vaultCanonical = runCatching {
            File(context.filesDir, "vault").canonicalPath
        }.getOrNull()
        val activeIds = activeJobs.keys.toSet()

        fun deleteDirContentsSafe(dir: File, skipActiveTaskDirs: Boolean): Long {
            if (!dir.exists() || !dir.isDirectory) return 0L
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return 0L
            if (vaultCanonical != null && (canonical == vaultCanonical || canonical.startsWith(vaultCanonical + File.separator))) {
                return 0L
            }

            var freed = 0L
            dir.listFiles()?.forEach { child ->
                if (skipActiveTaskDirs && child.name in activeIds) {
                    return@forEach
                }
                val childCanonical = runCatching { child.canonicalPath }.getOrNull() ?: return@forEach
                if (vaultCanonical != null && (childCanonical == vaultCanonical || childCanonical.startsWith(vaultCanonical + File.separator))) {
                    return@forEach
                }
                if (child.isDirectory) {
                    freed += deleteDirContentsSafe(child, skipActiveTaskDirs = child.name == "dl_work")
                    if (child.listFiles().isNullOrEmpty()) {
                        runCatching { child.delete() }
                    }
                } else {
                    val size = child.length()
                    if (runCatching { child.delete() }.getOrDefault(false)) {
                        freed += size
                    }
                }
            }
            return freed
        }

        var freedBytes = 0L
        freedBytes += deleteDirContentsSafe(context.cacheDir, skipActiveTaskDirs = false)
        val filesDlWork = File(context.filesDir, "dl_work")
        if (filesDlWork.exists()) {
            freedBytes += deleteDirContentsSafe(filesDlWork, skipActiveTaskDirs = true)
        }
        freedBytes
    }

    /**
     * YouTube PO Token ve VisitorData çiftini zorla yeniler.
     */
    suspend fun refreshYouTubePoToken(): Boolean = withContext(Dispatchers.IO) {
        val provider = youTubePoTokenProvider ?: return@withContext false
        runCatching { provider.getOrFetchPoToken(forceRefresh = true) != null }.getOrDefault(false)
    }

    /**
     * Uygulama ayarlarını, kanalları ve indirme geçmişini JSON olarak dışa aktarır.
     */
    suspend fun exportBackup(uri: Uri): Result<Int> {
        val manager = backupRestoreManager
            ?: return Result.failure(IllegalStateException("Yedekleme yöneticisi hazır değil."))
        return manager.exportBackupJson(uri)
    }

    /**
     * JSON yedek dosyasından ayarları, kanalları ve indirme geçmişini geri yükler.
     */
    suspend fun importBackup(uri: Uri): Result<String> {
        val manager = backupRestoreManager
            ?: return Result.failure(IllegalStateException("Yedekleme yöneticisi hazır değil."))
        return manager.importBackupJson(uri).map { summary ->
            "${summary.restoredChannels} kanal ve ${summary.restoredDownloads} indirme kaydı geri yüklendi."
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
