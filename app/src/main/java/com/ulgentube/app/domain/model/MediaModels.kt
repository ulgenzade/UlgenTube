package com.ulgentube.app.domain.model

/**
 * Desteklenen sosyal medya ve içerik platformları.
 */
enum class PlatformType(
    val displayName: String,
    val badgeColorHex: Long
) {
    YOUTUBE("YouTube", 0xFFFF0000),
    INSTAGRAM("Instagram", 0xFFE1306C),
    TIKTOK("TikTok", 0xFF00F2EA),
    TWITTER_X("X / Twitter", 0xFF1DA1F2),
    FACEBOOK("Facebook", 0xFF1877F2),
    REDDIT("Reddit", 0xFFFF4500),
    PINTEREST("Pinterest", 0xFFE60023),
    SOUNDCLOUD("SoundCloud", 0xFFFF5500),
    VIMEO("Vimeo", 0xFF1AB7EA),
    TWITCH("Twitch", 0xFF9146FF),
    KICK("Kick", 0xFF53FC18),
    DIRECT_IMAGE("Görsel", 0xFF10B981),
    OTHER("Web", 0xFF6366F1)
}

/**
 * İndirilecek medya kategorisi.
 */
enum class MediaCategory {
    VIDEO,
    AUDIO,
    IMAGE
}

/**
 * İndirme görevi durum yaşam döngüsü.
 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    MERGING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Uygulama tema tercihi.
 */
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

/**
 * Kullanıcıya sunulan tek bir video veya ses kalite/format seçeneği.
 */
data class MediaFormatOption(
    val id: String,
    val label: String,
    val sublabel: String,
    val category: MediaCategory,
    val extension: String,
    val resolutionHeight: Int = 0,
    val bitrateKbps: Int = 0,
    val fps: Int = 0,
    val videoCodec: String? = null,
    val isHdrOrHighBit: Boolean = false,
    val badgeTags: List<String> = emptyList(),
    val transcodeTargetHeight: Int? = null,
    val fileSizeBytes: Long? = null,
    val ytDlpFormatSelector: String? = null,
    val directUrl: String? = null,
    val isAudioExtraction: Boolean = false
)

/**
 * Fotoğraf, galeri öğesi veya kapak görseli modeli.
 */
data class ImageItem(
    val id: String,
    val url: String,
    val label: String,
    val extension: String = "jpg",
    val width: Int? = null,
    val height: Int? = null
)

/**
 * Bir URL analiz edildiğinde dönen birleşik medya bilgisi.
 */
data class ExtractedMedia(
    val originalUrl: String,
    val title: String,
    val author: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val platform: PlatformType,
    val videoFormats: List<MediaFormatOption>,
    val audioFormats: List<MediaFormatOption>,
    val imageItems: List<ImageItem>
)

/**
 * Veritabanında saklanan ve arka planda yürütülen indirme görevi.
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val platform: PlatformType,
    val category: MediaCategory,
    val formatLabel: String,
    val fileName: String,
    val savedUri: String? = null,
    val savedPath: String? = null,
    val fileSizeBytes: Long = 0L,
    val progress: Float = 0f,
    val speedText: String? = null,
    val etaSeconds: Long? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val ytDlpFormatSelector: String? = null,
    val directUrl: String? = null,
    val isAudioExtraction: Boolean = false,
    val transcodeTargetHeight: Int? = null,
    val targetExtension: String = "mp4",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * DataStore üzerinden yönetilen kullanıcı ayarları.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultVideoQuality: String = "1080p",
    val defaultAudioFormat: String = "mp3",
    val useAria2c: Boolean = true,
    val autoDetectClipboard: Boolean = true,
    val embedThumbnail: Boolean = true
)
