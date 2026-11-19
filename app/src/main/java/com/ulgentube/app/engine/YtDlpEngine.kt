package com.ulgentube.app.engine

import android.content.Context
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.util.FormatUtils
import com.ulgentube.app.util.UrlSanitizer
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Motor 1: youtubedl-android (yt-dlp + FFmpeg + aria2c) tabanlı video ve ses ayrıştırma/indirme motoru.
 * 1800+ web sitesinden gerçek akış özelliklerini (çözünürlük, FPS, HDR/10-bit, yüksek bitrate, kodek)
 * birebir format ID'leri ile analiz eder, gerektiğinde FFmpeg ile ölçekleme ve ses dönüştürme yapar.
 */
class YtDlpEngine(
    private val isAria2cReady: () -> Boolean = { false }
) {

    /**
     * Verilen URL için video/ses metadata ve gerçek akışlardan türetilen format seçeneklerini getirir.
     */
    suspend fun fetchMediaInfo(
        url: String,
        platform: PlatformType
    ): ExtractedMedia = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--skip-download")
            addOption("--socket-timeout", "20")
        }

        val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)

        val title = info.title?.takeIf { it.isNotBlank() } ?: "Başlıksız Medya"
        val author = info.uploader?.takeIf { it.isNotBlank() }
        val durationSeconds = info.duration.takeIf { it > 0 }?.toLong()
        val thumbnailUrl = info.thumbnail?.takeIf { it.isNotBlank() }

        val rawFormats = info.formats.orEmpty()
        val videoFormats = buildRealVideoFormats(rawFormats, platform)
        val audioFormats = buildDefaultAudioFormats(rawFormats, durationSeconds)

        val imageItems = buildList {
            if (!thumbnailUrl.isNullOrBlank()) {
                add(
                    ImageItem(
                        id = "thumb_hd",
                        url = thumbnailUrl,
                        label = "Kapak Görseli (HD)",
                        extension = UrlSanitizer.guessImageExtension(thumbnailUrl)
                    )
                )
            }
        }

        ExtractedMedia(
            originalUrl = url,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
            platform = platform,
            videoFormats = videoFormats,
            audioFormats = audioFormats,
            imageItems = imageItems
        )
    }

    /**
     * Seçilen görevi geçici klasöre indirir; ses çıkarma veya FFmpeg çözünürlük küçültme varsa uygular.
     */
    suspend fun download(
        task: DownloadTask,
        outputDir: File,
        settings: AppSettings,
        onProgress: (progress: Float, etaSeconds: Long, speedText: String?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val outputTemplate = File(outputDir, "media.%(ext)s").absolutePath
        val sourceUrl = task.directUrl?.takeIf { it.isNotBlank() } ?: task.url

        val request = YoutubeDLRequest(sourceUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("-o", outputTemplate)

            if (task.isAudioExtraction || task.category == MediaCategory.AUDIO) {
                val targetAudioFormat = task.targetExtension.lowercase(Locale.ROOT)
                    .takeIf { it in SUPPORTED_AUDIO_FORMATS } ?: "mp3"
                val audioQuality = resolveAudioQuality(task.formatLabel)

                if (task.directUrl.isNullOrBlank()) {
                    addOption("-f", task.ytDlpFormatSelector ?: "bestaudio/best")
                }
                addOption("-x")
                addOption("--audio-format", targetAudioFormat)
                addOption("--audio-quality", audioQuality)

                if (settings.embedThumbnail) {
                    addOption("--add-metadata")
                    if (targetAudioFormat == "mp3" || targetAudioFormat == "m4a") {
                        addOption("--embed-thumbnail")
                    }
                }
            } else {
                if (task.directUrl.isNullOrBlank() && !task.ytDlpFormatSelector.isNullOrBlank()) {
                    addOption("-f", task.ytDlpFormatSelector)
                }

                val targetHeight = task.transcodeTargetHeight
                if (targetHeight != null && targetHeight > 0) {
                    addOption("--recode-video", "mp4")
                    addOption(
                        "--postprocessor-args",
                        "VideoConvertor:-vf scale=-2:$targetHeight -c:v libx264 -preset veryfast -crf 23 -c:a aac -b:a 128k"
                    )
                } else {
                    addOption("--merge-output-format", "mp4")
                }

                if (settings.embedThumbnail) {
                    addOption("--add-metadata")
                }
            }

            if (settings.useAria2c && isAria2cReady()) {
                addOption("--downloader", "libaria2c.so")
                addOption("--external-downloader-args", "aria2c:-x 8 -k 1M")
            }
        }

        YoutubeDL.getInstance().execute(request, task.id) { progress, etaInSeconds, line ->
            val clampedProgress = if (progress in 0f..100f) progress else 0f
            val speed = extractSpeed(line)
            onProgress(clampedProgress, etaInSeconds, speed)
        }

        findDownloadedFile(outputDir)
            ?: throw IOException("İndirme tamamlandı ancak çıktı dosyası bulunamadı.")
    }

    /**
     * Devam eden bir yt-dlp sürecini işlem kimliğine göre sonlandırır.
     */
    fun cancel(processId: String): Boolean {
        return runCatching {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }.getOrDefault(false)
    }

    /**
     * Uygulama içi yt-dlp çekirdeğini kararlı (STABLE) kanaldan günceller.
     */
    suspend fun updateYoutubeDL(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.STABLE
            )
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "yt-dlp motoru başarıyla güncellendi."
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp motoru zaten en güncel sürümde."
                null -> "Güncelleme durumu alınamadı."
            }
        }
    }

    /**
     * Sabit kalite tablosu olmadan yalnızca [VideoInfo.formats] içindeki gerçek akışları analiz eder:
     * - Dikey/yatay kısa kenar bazlı gerçek çözünürlük (`effectiveHeight`)
     * - Gerçek FPS, kodek (AV1/VP9/HEVC/H.264), HDR / 10-bit ve yüksek bitrate (Premium) tespiti
     * - Birebir `formatId` seçimi
     * - Yalnızca tek çözünürlük dönen platformlarda (Instagram/Twitter vb.) orijinalin altına FFmpeg küçültme seçenekleri.
     */
    private fun buildRealVideoFormats(
        formats: List<VideoFormat>,
        platform: PlatformType
    ): List<MediaFormatOption> {
        if (platform == PlatformType.SOUNDCLOUD) {
            return emptyList()
        }

        val bestAudioSize = formats
            .filter { isAudioOnly(it) }
            .maxOfOrNull { resolveSize(it) ?: 0L }
            ?.takeIf { it > 0L } ?: 0L

        val videoCandidates = formats.filter { fmt ->
            hasVideo(fmt) && !fmt.formatId.isNullOrBlank()
        }

        val parsedStreams = videoCandidates.mapNotNull { fmt ->
            val rawHeight = fmt.height
            val rawWidth = fmt.width
            val effectiveHeight = when {
                rawWidth > 0 && rawHeight > 0 -> minOf(rawWidth, rawHeight)
                rawHeight > 0 -> rawHeight
                else -> 0
            }
            if (effectiveHeight <= 0) return@mapNotNull null

            val fpsValue = fmt.fps.toInt().coerceAtLeast(0)
            val fpsBucket = bucketFps(fpsValue)
            val codec = cleanVideoCodec(fmt.vcodec)
            val isHdrOrHighBit = detectHdrOrHighBit(fmt)
            val tbr = fmt.tbr.toDouble().coerceAtLeast(0.0)

            ParsedVideoStream(
                format = fmt,
                effectiveHeight = effectiveHeight,
                fps = fpsValue,
                fpsBucket = fpsBucket,
                codec = codec,
                isHdrOrHighBit = isHdrOrHighBit,
                tbr = tbr
            )
        }

        if (parsedStreams.isEmpty()) {
            val bestSize = videoCandidates.maxOfOrNull { resolveSize(it) ?: 0L }?.takeIf { it > 0L }
            val sizeText = FormatUtils.formatBytes(bestSize)
            return listOf(
                MediaFormatOption(
                    id = "video_best",
                    label = "Orijinal Kalite",
                    sublabel = listOfNotNull("MP4", sizeText.takeIf { it.isNotBlank() }).joinToString(" • "),
                    category = MediaCategory.VIDEO,
                    extension = "mp4",
                    resolutionHeight = 0,
                    fileSizeBytes = bestSize,
                    ytDlpFormatSelector = "bestvideo+bestaudio/best"
                )
            )
        }

        // Aynı (effectiveHeight, fpsBucket) grubu için taban/medyan tbr hesapla (1.6x üzeri -> Yüksek Bitrate)
        val groupMedianTbr = parsedStreams
            .groupBy { it.effectiveHeight to it.fpsBucket }
            .mapValues { (_, list) ->
                val nonZero = list.map { it.tbr }.filter { it > 0.0 }.sorted()
                if (nonZero.isEmpty()) 0.0 else nonZero[nonZero.size / 2]
            }

        data class StreamGroupKey(
            val effectiveHeight: Int,
            val fpsBucket: Int,
            val isHdrOrHighBit: Boolean,
            val isHighBitrateVariant: Boolean
        )

        val groupedBest = parsedStreams
            .groupBy { stream ->
                val medianTbr = groupMedianTbr[stream.effectiveHeight to stream.fpsBucket] ?: 0.0
                val isHighBitrate = !stream.isHdrOrHighBit &&
                    medianTbr > 0.0 &&
                    stream.tbr >= medianTbr * HIGH_BITRATE_MULTIPLIER
                StreamGroupKey(
                    effectiveHeight = stream.effectiveHeight,
                    fpsBucket = stream.fpsBucket,
                    isHdrOrHighBit = stream.isHdrOrHighBit,
                    isHighBitrateVariant = isHighBitrate
                )
            }
            .map { (key, candidates) ->
                val best = candidates.maxWithOrNull(
                    compareBy<ParsedVideoStream> { it.tbr }
                        .thenBy { codecPriority(it.codec) }
                        .thenBy { if (it.format.ext.equals("mp4", ignoreCase = true)) 1 else 0 }
                        .thenBy { resolveSize(it.format) ?: 0L }
                ) ?: candidates.first()
                key to best
            }
            .sortedWith(
                compareByDescending<Pair<StreamGroupKey, ParsedVideoStream>> { it.first.effectiveHeight }
                    .thenByDescending { it.second.fps }
                    .thenByDescending { if (it.first.isHdrOrHighBit) 1 else 0 }
                    .thenByDescending { if (it.first.isHighBitrateVariant) 1 else 0 }
                    .thenByDescending { it.second.tbr }
            )

        val options = groupedBest.map { (key, stream) ->
            val fmt = stream.format
            val formatId = fmt.formatId.orEmpty()
            val videoSize = resolveSize(fmt)
            val totalSize = when {
                videoSize == null -> null
                isAudioPresent(fmt) -> videoSize
                bestAudioSize > 0L -> videoSize + bestAudioSize
                else -> videoSize
            }

            val selector = if (isAudioPresent(fmt)) {
                formatId
            } else {
                "$formatId+bestaudio[ext=m4a]/$formatId+bestaudio/best"
            }

            val badgeTags = buildList {
                if (stream.fps >= 48) {
                    add("${stream.fps} FPS")
                }
                if (key.isHdrOrHighBit) {
                    add("HDR / 10-bit")
                } else if (key.isHighBitrateVariant) {
                    add("Yüksek Bitrate")
                }
                stream.codec?.let { add(it) }
            }

            val label = buildVideoLabel(stream.effectiveHeight, stream.fps)
            val bitrateLabel = stream.tbr.toInt().takeIf { it > 0 }?.let { formatBitrate(it) }
            val sizeLabel = FormatUtils.formatBytes(totalSize)
            val sublabel = listOfNotNull(
                "MP4",
                bitrateLabel,
                sizeLabel.takeIf { it.isNotBlank() }
            ).joinToString(" • ")

            MediaFormatOption(
                id = "video_${formatId}_${stream.effectiveHeight}p_${stream.fps}fps",
                label = label,
                sublabel = sublabel,
                category = MediaCategory.VIDEO,
                extension = "mp4",
                resolutionHeight = stream.effectiveHeight,
                bitrateKbps = stream.tbr.toInt().coerceAtLeast(0),
                fps = stream.fps,
                videoCodec = stream.codec,
                isHdrOrHighBit = key.isHdrOrHighBit || key.isHighBitrateVariant,
                badgeTags = badgeTags,
                fileSizeBytes = totalSize,
                ytDlpFormatSelector = selector
            )
        }.toMutableList()

        // Yalnızca tek farklı gerçek çözünürlük döndüyse ve > 480p ise altına FFmpeg küçültme seçenekleri ekle
        val distinctHeights = options.map { it.resolutionHeight }.filter { it > 0 }.distinct()
        if (distinctHeights.size == 1) {
            val maxRealHeight = distinctHeights.first()
            if (maxRealHeight > 480) {
                val primary = options.first()
                options += buildDownscaleOptions(primary, maxRealHeight)
            }
        }

        return options
    }

    private fun buildVideoLabel(height: Int, fps: Int): String {
        val base = when {
            height >= 4320 -> "8K ${height}p"
            height >= 2160 -> "4K ${height}p"
            height >= 1440 -> "2K ${height}p"
            else -> "${height}p"
        }
        return if (fps >= 48) "$base • $fps FPS" else base
    }

    private fun bucketFps(fps: Int): Int = when {
        fps >= 100 -> 120
        fps >= 48 -> 60
        else -> 30
    }

    private fun cleanVideoCodec(vcodec: String?): String? {
        if (vcodec.isNullOrBlank() || vcodec.equals("none", ignoreCase = true)) return null
        val lower = vcodec.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("av01") || lower.contains("av1") -> "AV1"
            lower.startsWith("vp09") || lower.startsWith("vp9") -> "VP9"
            lower.startsWith("hev1") || lower.startsWith("hvc1") || lower.contains("hevc") || lower.contains("h265") -> "HEVC"
            lower.startsWith("avc1") || lower.contains("h264") -> "H.264"
            lower.startsWith("vp8") -> "VP8"
            else -> null
        }
    }

    private fun detectHdrOrHighBit(fmt: VideoFormat): Boolean {
        val note = fmt.formatNote?.lowercase(Locale.ROOT).orEmpty()
        val formatDesc = fmt.format?.lowercase(Locale.ROOT).orEmpty()
        val vcodec = fmt.vcodec?.lowercase(Locale.ROOT).orEmpty()

        val textHit = HDR_KEYWORDS.any { kw -> note.contains(kw) || formatDesc.contains(kw) }
        val codecHit = vcodec.contains("vp09.02") ||
            vcodec.contains(".10.") ||
            vcodec.contains("hev1.2") ||
            vcodec.contains("hvc1.2")

        return textHit || codecHit
    }

    private fun codecPriority(codec: String?): Int = when (codec) {
        "H.264" -> 4
        "VP9" -> 3
        "HEVC" -> 2
        "AV1" -> 1
        else -> 0
    }

    private fun formatBitrate(kbps: Int): String {
        return if (kbps >= 1000) {
            String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
        } else {
            "$kbps kbps"
        }
    }

    private fun resolveAudioQuality(formatLabel: String): String {
        return when {
            formatLabel.contains("320") -> "320K"
            formatLabel.contains("192") -> "192K"
            formatLabel.contains("128") -> "128K"
            else -> "0"
        }
    }

    private fun hasVideo(format: VideoFormat): Boolean {
        val vcodec = format.vcodec
        return vcodec.isNullOrBlank() || !vcodec.equals("none", ignoreCase = true)
    }

    private fun isAudioPresent(format: VideoFormat): Boolean {
        val acodec = format.acodec
        return !acodec.isNullOrBlank() && !acodec.equals("none", ignoreCase = true)
    }

    private fun isAudioOnly(format: VideoFormat): Boolean {
        val vcodec = format.vcodec
        return vcodec.equals("none", ignoreCase = true) && isAudioPresent(format)
    }

    private fun resolveSize(format: VideoFormat): Long? {
        return when {
            format.fileSize > 0L -> format.fileSize
            format.fileSizeApproximate > 0L -> format.fileSizeApproximate
            else -> null
        }
    }

    private fun extractSpeed(line: String?): String? {
        if (line.isNullOrBlank()) return null
        return SPEED_REGEX.find(line)?.value?.trim()
    }

    private fun findDownloadedFile(outputDir: File): File? {
        val candidates = outputDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.length() > 0L &&
                    !file.name.endsWith(".part", ignoreCase = true) &&
                    !file.name.endsWith(".ytdl", ignoreCase = true) &&
                    !file.name.endsWith(".tmp", ignoreCase = true) &&
                    !file.name.endsWith(".temp", ignoreCase = true)
            }
            .orEmpty()

        val nonImage = candidates.filterNot {
            it.extension.lowercase(Locale.ROOT) in setOf("webp", "jpg", "jpeg", "png")
        }
        return (nonImage.ifEmpty { candidates }).maxByOrNull { it.length() }
    }

    private data class ParsedVideoStream(
        val format: VideoFormat,
        val effectiveHeight: Int,
        val fps: Int,
        val fpsBucket: Int,
        val codec: String?,
        val isHdrOrHighBit: Boolean,
        val tbr: Double
    )

    companion object {
        private const val HIGH_BITRATE_MULTIPLIER = 1.6

        private val SPEED_REGEX = Regex("""\b\d+(?:\.\d+)?\s*[KMG]i?B/s\b""", RegexOption.IGNORE_CASE)
        private val SUPPORTED_AUDIO_FORMATS = setOf("mp3", "m4a", "opus", "flac", "wav")
        private val HDR_KEYWORDS = listOf("hdr", "10bit", "10-bit", "premium", "enhanced", "high")
        private val DOWNSCALE_STEPS = listOf(720, 480, 360)

        /**
         * Orijinal akıştan kesinlikle daha düşük çözünürlüklü FFmpeg ölçekleme seçeneklerini üretir.
         */
        fun buildDownscaleOptions(
            primaryOption: MediaFormatOption,
            maxRealHeight: Int
        ): List<MediaFormatOption> {
            return DOWNSCALE_STEPS
                .filter { it < maxRealHeight }
                .map { h ->
                    MediaFormatOption(
                        id = "video_downscale_${h}p",
                        label = "${h}p (Küçültülmüş)",
                        sublabel = "MP4 • FFmpeg Ölçekleme",
                        category = MediaCategory.VIDEO,
                        extension = "mp4",
                        resolutionHeight = h,
                        badgeTags = listOf("FFmpeg Küçültme"),
                        transcodeTargetHeight = h,
                        ytDlpFormatSelector = primaryOption.ytDlpFormatSelector,
                        directUrl = primaryOption.directUrl
                    )
                }
        }

        /**
         * Video/ses içeren tüm platformlar için standart, kapsamlı ses dönüştürme seçeneklerini üretir.
         */
        fun buildDefaultAudioFormats(
            formats: List<VideoFormat> = emptyList(),
            durationSeconds: Long? = null,
            directSourceUrl: String? = null
        ): List<MediaFormatOption> {
            val bestAudioSize = formats
                .filter { fmt ->
                    val vcodec = fmt.vcodec
                    val acodec = fmt.acodec
                    vcodec.equals("none", ignoreCase = true) &&
                        !acodec.isNullOrBlank() &&
                        !acodec.equals("none", ignoreCase = true)
                }
                .maxOfOrNull { fmt ->
                    when {
                        fmt.fileSize > 0L -> fmt.fileSize
                        fmt.fileSizeApproximate > 0L -> fmt.fileSizeApproximate
                        else -> 0L
                    }
                }
                ?.takeIf { it > 0L }

            fun estimateBytes(kbps: Int): Long? {
                if (durationSeconds != null && durationSeconds > 0L) {
                    return (durationSeconds * kbps * 1000L) / 8L
                }
                return bestAudioSize
            }

            fun makeSublabel(hint: String, estimatedBytes: Long?): String {
                return listOfNotNull(
                    hint,
                    FormatUtils.formatBytes(estimatedBytes).takeIf { it.isNotBlank() }
                ).joinToString(" • ")
            }

            val selectorBest = if (directSourceUrl == null) "bestaudio/best" else null
            val selectorM4a = if (directSourceUrl == null) "bestaudio[ext=m4a]/bestaudio/best" else null
            val selectorOpus = if (directSourceUrl == null) "bestaudio[ext=webm]/bestaudio/best" else null

            return listOf(
                MediaFormatOption(
                    id = "audio_mp3_320",
                    label = "MP3 • 320 kbps",
                    sublabel = makeSublabel("En Yüksek MP3", estimateBytes(320)),
                    category = MediaCategory.AUDIO,
                    extension = "mp3",
                    bitrateKbps = 320,
                    badgeTags = listOf("320 kbps"),
                    fileSizeBytes = estimateBytes(320),
                    ytDlpFormatSelector = selectorBest,
                    directUrl = directSourceUrl,
                    isAudioExtraction = true
                ),
                MediaFormatOption(
                    id = "audio_mp3_192",
                    label = "MP3 • 192 kbps",
                    sublabel = makeSublabel("Dengeli MP3", estimateBytes(192)),
                    category = MediaCategory.AUDIO,
                    extension = "mp3",
                    bitrateKbps = 192,
                    badgeTags = listOf("192 kbps"),
                    fileSizeBytes = estimateBytes(192),
                    ytDlpFormatSelector = selectorBest,
                    directUrl = directSourceUrl,
                    isAudioExtraction = true
                ),
                MediaFormatOption(
                    id = "audio_mp3_128",
                    label = "MP3 • 128 kbps",
                    sublabel = makeSublabel("Ekonomik MP3", estimateBytes(128)),
                    category = MediaCategory.AUDIO,
                    extension = "mp3",
                    bitrateKbps = 128,
                    badgeTags = listOf("128 kbps"),
                    fileSizeBytes = estimateBytes(128),
                    ytDlpFormatSelector = selectorBest,
                    directUrl = directSourceUrl,
                    isAudioExtraction = true
                ),
                MediaFormatOption(
                    id = "audio_m4a",
                    label = "M4A • AAC",
                    sublabel = makeSublabel("Yüksek Kalite", bestAudioSize ?: estimateBytes(192)),
                    category = MediaCategory.AUDIO,
                    extension = "m4a",
                    bitrateKbps = 192,
                    badgeTags = listOf("AAC"),
                    fileSizeBytes = bestAudioSize ?: estimateBytes(192),
                    ytDlpFormatSelector = selectorM4a,
                    directUrl = directSourceUrl,
                    isAudioExtraction = true
                ),
                MediaFormatOption(
                    id = "audio_opus",
                    label = "OPUS • Orijinal",
                    sublabel = makeSublabel("Ultra Verimli", bestAudioSize ?: estimateBytes(160)),
                    category = MediaCategory.AUDIO,
                    extension = "opus",
                    bitrateKbps = 160,
                    badgeTags = listOf("OPUS"),
                    fileSizeBytes = bestAudioSize ?: estimateBytes(160),
                    ytDlpFormatSelector = selectorOpus,
                    directUrl = directSourceUrl,
                    isAudioExtraction = true
                ),
                MediaFormatOption(
                    id = "audio_flac",
                    label = "FLAC • Kayıpsız",
                    sublabel = makeSublabel("Stüdyo Kalitesi", estimateBytes(900)),
                    category = MediaCategory.AUDIO,
                    extension = "flac",
                    bitrateKbps = 900,
                    badgeTags = listOf("Kayıpsız"),
                    fileSizeBytes = estimateBytes(900),
                    ytDlpFormatSelector = selectorBest,
                    directUrl = directSourceUrl,
                    isAudioExtraction = true
                ),
                MediaFormatOption(
                    id = "audio_wav",
                    label = "WAV • Sıkıştırmasız",
                    sublabel = makeSublabel("Ham Ses", estimateBytes(1411)),
                    category = MediaCategory.AUDIO,
                    extension = "wav",
                    bitrateKbps = 1411,
                    badgeTags = listOf("PCM"),
                    fileSizeBytes = estimateBytes(1411),
                    ytDlpFormatSelector = selectorBest,
                    directUrl = directSourceUrl,
                    isAudioExtraction = true
                )
            )
        }
    }
}
