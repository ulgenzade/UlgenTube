package com.ulgentube.app.data

import android.content.Context
import android.net.Uri
import com.ulgentube.app.data.local.DownloadDao
import com.ulgentube.app.data.local.DownloadEntity
import com.ulgentube.app.data.local.FollowedChannelDao
import com.ulgentube.app.data.local.FollowedChannelEntity
import com.ulgentube.app.data.local.SettingsRepository
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.EqualizerPreset
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.ThemeMode
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * JSON yedekleme geri yükleme özet sonucu.
 */
data class BackupRestoreSummary(
    val restoredChannels: Int,
    val restoredDownloads: Int,
    val restoredSettings: Boolean
)

/**
 * Uygulama ayarlarını, takip edilen kanalları ve indirme geçmişini taşınabilir JSON
 * formatında dışa/içe aktarır.
 *
 * Güvenlik ilkesi: PIN hash, şifre, çerez dosyası, PO Token veya visitorData gibi
 * hassas sırlar ASLA dışa aktarılmaz.
 */
class BackupRestoreManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val followedChannelDao: FollowedChannelDao,
    private val settingsRepository: SettingsRepository
) {

    /**
     * Yedek JSON verisini SAF ile seçilen [targetUri] konumuna yazar ve
     * dışa aktarılan toplam kayıt sayısını (kanallar + indirme geçmişi) döndürür.
     */
    suspend fun exportBackupJson(targetUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonString = buildBackupJsonString()
            val parsed = JSONObject(jsonString)
            val channelCount = parsed.optJSONArray("channels")?.length() ?: 0
            val downloadCount = parsed.optJSONArray("downloads")?.length() ?: 0

            val outputStream = context.contentResolver.openOutputStream(targetUri)
                ?: throw IOException("Yedekleme hedef dosyası açılamadı.")

            outputStream.use { out ->
                out.write(jsonString.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            channelCount + downloadCount
        }
    }

    /**
     * Yalnızca güvenli tercihleri, takip edilen kanalları ve indirme geçmişi kayıtlarını içeren
     * JSON metnini üretir. Hassas token/PIN/çerez verilerini kesinlikle dışarıda bırakır.
     */
    suspend fun buildBackupJsonString(): String = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getCurrentSettings()
        val channels = followedChannelDao.getAll()
        val downloads = downloadDao.observeAll().first()

        val settingsJson = JSONObject().apply {
            put("themeMode", settings.themeMode.name)
            put("dynamicColor", settings.dynamicColor)
            put("defaultVideoQuality", settings.defaultVideoQuality)
            put("defaultAudioFormat", settings.defaultAudioFormat)
            put("useAria2c", settings.useAria2c)
            put("autoDetectClipboard", settings.autoDetectClipboard)
            put("embedThumbnail", settings.embedThumbnail)
            put("sponsorBlockEnabled", settings.sponsorBlockEnabled)
            put("embedChapters", settings.embedChapters)
            put("wifiOnly", settings.wifiOnly)
            put("zeroFrictionShareMode", settings.zeroFrictionShareMode)
            put("platformSubfolders", settings.platformSubfolders)
            put("fileNameTemplate", settings.fileNameTemplate)
            put("parallelDownloadEnabled", settings.parallelDownloadEnabled)
            put("parallelConnectionCount", settings.parallelConnectionCount)
            put("maxConcurrentDownloads", settings.maxConcurrentDownloads)
            put("maxDownloadSpeedKbps", settings.maxDownloadSpeedKbps)
            put("scheduledDownloadEnabled", settings.scheduledDownloadEnabled)
            put("scheduleStartHour", settings.scheduleStartHour)
            put("scheduleEndHour", settings.scheduleEndHour)
            put("poTokenEnabled", settings.poTokenEnabled)
            put("autoFetchLyrics", settings.autoFetchLyrics)
            put("equalizerPreset", settings.equalizerPreset.name)
            // NOT: cachedPoToken, cachedVisitorData, cookiesEnabled, appLockEnabled/PIN hash ASLA dahil edilmez!
        }

        val channelsArray = JSONArray()
        channels.forEach { ch ->
            channelsArray.put(
                JSONObject().apply {
                    put("channelId", ch.channelId)
                    put("title", ch.title)
                    put("url", ch.url)
                    put("platform", ch.platform)
                    put("autoDownloadLatest", ch.autoDownloadLatest)
                    if (ch.lastKnownVideoId != null) put("lastKnownVideoId", ch.lastKnownVideoId)
                    if (ch.lastKnownVideoTitle != null) put("lastKnownVideoTitle", ch.lastKnownVideoTitle)
                    put("lastCheckedAt", ch.lastCheckedAt)
                    put("createdAt", ch.createdAt)
                }
            )
        }

        val downloadsArray = JSONArray()
        downloads.forEach { dl ->
            downloadsArray.put(
                JSONObject().apply {
                    put("id", dl.id)
                    put("url", dl.url)
                    put("title", dl.title)
                    if (dl.author != null) put("author", dl.author)
                    if (dl.thumbnailUrl != null) put("thumbnailUrl", dl.thumbnailUrl)
                    put("platform", dl.platform)
                    put("category", dl.category)
                    put("formatLabel", dl.formatLabel)
                    put("fileName", dl.fileName)
                    put("fileSizeBytes", dl.fileSizeBytes)
                    put("status", dl.status)
                    put("targetExtension", dl.targetExtension)
                    put("createdAt", dl.createdAt)
                    put("isFavorite", dl.isFavorite)
                    put("lastPlaybackPositionMs", dl.lastPlaybackPositionMs)
                    if (dl.lyricsText != null) put("lyricsText", dl.lyricsText)
                }
            )
        }

        JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("settings", settingsJson)
            put("channels", channelsArray)
            put("downloads", downloadsArray)
        }.toString(2)
    }

    /**
     * SAF üzerinden seçilen [sourceUri] JSON yedek dosyasını okur ve veritabanı/ayarlara geri yükler.
     */
    suspend fun importBackupJson(sourceUri: Uri): Result<BackupRestoreSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Yedekleme dosyası açılamadı.")

            val bytes = inputStream.use { input ->
                val raw = input.readBytes()
                if (raw.size > MAX_BACKUP_FILE_BYTES) {
                    throw IllegalArgumentException("Yedekleme dosyası 10 MB boyut sınırını aşıyor.")
                }
                raw
            }
            val jsonString = String(bytes, StandardCharsets.UTF_8)
            restoreFromJsonString(jsonString)
        }
    }

    /**
     * Verilen JSON yedek metnini ayrıştırıp ayarları, kanalları ve indirme geçmişini geri yükler.
     */
    suspend fun restoreFromJsonString(jsonString: String): BackupRestoreSummary = withContext(Dispatchers.IO) {
        val root = JSONObject(jsonString)
        var restoredSettings = false
        var restoredChannels = 0
        var restoredDownloads = 0

        root.optJSONObject("settings")?.let { s ->
            val current = settingsRepository.getCurrentSettings()
            val merged = current.copy(
                themeMode = runCatching {
                    ThemeMode.valueOf(s.optString("themeMode", current.themeMode.name))
                }.getOrDefault(current.themeMode),
                dynamicColor = s.optBoolean("dynamicColor", current.dynamicColor),
                defaultVideoQuality = s.optString("defaultVideoQuality", current.defaultVideoQuality),
                defaultAudioFormat = s.optString("defaultAudioFormat", current.defaultAudioFormat),
                useAria2c = s.optBoolean("useAria2c", current.useAria2c),
                autoDetectClipboard = s.optBoolean("autoDetectClipboard", current.autoDetectClipboard),
                embedThumbnail = s.optBoolean("embedThumbnail", current.embedThumbnail),
                sponsorBlockEnabled = s.optBoolean("sponsorBlockEnabled", current.sponsorBlockEnabled),
                embedChapters = s.optBoolean("embedChapters", current.embedChapters),
                wifiOnly = s.optBoolean("wifiOnly", current.wifiOnly),
                zeroFrictionShareMode = s.optBoolean("zeroFrictionShareMode", current.zeroFrictionShareMode),
                platformSubfolders = s.optBoolean("platformSubfolders", current.platformSubfolders),
                fileNameTemplate = s.optString("fileNameTemplate", current.fileNameTemplate),
                parallelDownloadEnabled = s.optBoolean("parallelDownloadEnabled", current.parallelDownloadEnabled),
                parallelConnectionCount = s.optInt("parallelConnectionCount", current.parallelConnectionCount).coerceIn(1, 16),
                maxConcurrentDownloads = s.optInt("maxConcurrentDownloads", current.maxConcurrentDownloads).coerceIn(1, 4),
                maxDownloadSpeedKbps = s.optInt("maxDownloadSpeedKbps", current.maxDownloadSpeedKbps).coerceAtLeast(0),
                scheduledDownloadEnabled = s.optBoolean("scheduledDownloadEnabled", current.scheduledDownloadEnabled),
                scheduleStartHour = s.optInt("scheduleStartHour", current.scheduleStartHour).coerceIn(0, 23),
                scheduleEndHour = s.optInt("scheduleEndHour", current.scheduleEndHour).coerceIn(0, 23),
                poTokenEnabled = s.optBoolean("poTokenEnabled", current.poTokenEnabled),
                autoFetchLyrics = s.optBoolean("autoFetchLyrics", current.autoFetchLyrics),
                equalizerPreset = runCatching {
                    EqualizerPreset.valueOf(s.optString("equalizerPreset", current.equalizerPreset.name))
                }.getOrDefault(current.equalizerPreset)
            )
            settingsRepository.restoreFromSettings(merged)
            settingsRepository.setThemeMode(merged.themeMode)
            settingsRepository.setDynamicColor(merged.dynamicColor)
            settingsRepository.setDefaultVideoQuality(merged.defaultVideoQuality)
            settingsRepository.setDefaultAudioFormat(merged.defaultAudioFormat)
            settingsRepository.setUseAria2c(merged.useAria2c)
            settingsRepository.setAutoDetectClipboard(merged.autoDetectClipboard)
            settingsRepository.setEmbedThumbnail(merged.embedThumbnail)
            settingsRepository.setSponsorBlockEnabled(merged.sponsorBlockEnabled)
            settingsRepository.setEmbedChapters(merged.embedChapters)
            settingsRepository.setWifiOnly(merged.wifiOnly)
            settingsRepository.setZeroFrictionShareMode(merged.zeroFrictionShareMode)
            settingsRepository.setPlatformSubfolders(merged.platformSubfolders)
            settingsRepository.setFileNameTemplate(merged.fileNameTemplate)
            restoredSettings = true
        }

        root.optJSONArray("channels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val channelId = obj.optString("channelId").trim()
                val title = obj.optString("title").trim()
                val rawUrl = obj.optString("url").trim()
                val safeUrl = UrlSanitizer.extractFirstValidUrl(rawUrl) ?: continue
                if (channelId.isBlank() || title.isBlank()) continue

                val platform = runCatching {
                    PlatformType.valueOf(obj.optString("platform", PlatformType.YOUTUBE.name)).name
                }.getOrDefault(PlatformType.YOUTUBE.name)

                val entity = FollowedChannelEntity(
                    channelId = channelId,
                    title = title,
                    url = safeUrl,
                    platform = platform,
                    autoDownloadLatest = obj.optBoolean("autoDownloadLatest", false),
                    lastKnownVideoId = obj.optString("lastKnownVideoId").takeIf { it.isNotBlank() },
                    lastKnownVideoTitle = obj.optString("lastKnownVideoTitle").takeIf { it.isNotBlank() },
                    lastCheckedAt = obj.optLong("lastCheckedAt", 0L),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                followedChannelDao.upsert(entity)
                restoredChannels++
            }
        }

        root.optJSONArray("downloads")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val safeUrl = UrlSanitizer.extractFirstValidUrl(obj.optString("url")) ?: continue
                val title = obj.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue

                val platform = runCatching {
                    PlatformType.valueOf(obj.optString("platform", PlatformType.OTHER.name)).name
                }.getOrDefault(PlatformType.OTHER.name)
                val category = runCatching {
                    MediaCategory.valueOf(obj.optString("category", MediaCategory.VIDEO.name)).name
                }.getOrDefault(MediaCategory.VIDEO.name)
                val status = runCatching {
                    DownloadStatus.valueOf(obj.optString("status", DownloadStatus.COMPLETED.name)).name
                }.getOrDefault(DownloadStatus.COMPLETED.name)
                val safeThumb = obj.optString("thumbnailUrl")
                    .takeIf { it.isNotBlank() }
                    ?.let { UrlSanitizer.extractFirstValidUrl(it) }

                val existing = downloadDao.getById(id)
                val entity = DownloadEntity(
                    id = id,
                    url = safeUrl,
                    title = title,
                    author = obj.optString("author").takeIf { it.isNotBlank() },
                    thumbnailUrl = safeThumb,
                    platform = platform,
                    category = category,
                    formatLabel = obj.optString("formatLabel").ifBlank { "Yedek" },
                    fileName = obj.optString("fileName").ifBlank { "$title.mp4" },
                    savedUri = existing?.savedUri,
                    savedPath = existing?.savedPath,
                    fileSizeBytes = obj.optLong("fileSizeBytes", existing?.fileSizeBytes ?: 0L),
                    progress = if (status == DownloadStatus.COMPLETED.name) 100f else 0f,
                    speedText = null,
                    etaSeconds = null,
                    status = status,
                    errorMessage = null,
                    ytDlpFormatSelector = existing?.ytDlpFormatSelector,
                    directUrl = existing?.directUrl,
                    isAudioExtraction = category == MediaCategory.AUDIO.name,
                    transcodeTargetHeight = existing?.transcodeTargetHeight,
                    targetExtension = obj.optString("targetExtension").ifBlank { "mp4" },
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    lastPlaybackPositionMs = obj.optLong("lastPlaybackPositionMs", 0L).coerceAtLeast(0L),
                    isFavorite = obj.optBoolean("isFavorite", false),
                    isInVault = existing?.isInVault ?: false,
                    lyricsText = obj.optString("lyricsText").takeIf { it.isNotBlank() }
                )
                downloadDao.upsert(entity)
                restoredDownloads++
            }
        }

        BackupRestoreSummary(
            restoredChannels = restoredChannels,
            restoredDownloads = restoredDownloads,
            restoredSettings = restoredSettings
        )
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_BACKUP_FILE_BYTES = 10 * 1024 * 1024 // 10 MB
    }
}
