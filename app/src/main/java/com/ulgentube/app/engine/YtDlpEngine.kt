package com.ulgentube.app.engine

import android.content.Context
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.PlaylistEntry
import com.ulgentube.app.domain.model.SubtitleOption
import com.ulgentube.app.util.FormatUtils
import com.ulgentube.app.util.NetworkSecurityGuard
import com.ulgentube.app.util.UrlSanitizer
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Motor 1: youtubedl-android (yt-dlp + FFmpeg + aria2c) tabanlı video ve ses ayrıştırma/indirme motoru.
 * 1800+ web sitesinden gerçek akış özelliklerini (çözünürlük, FPS, HDR/10-bit, yüksek bitrate, kodek,
 * altyazı ve oynatma listeleri) birebir format ID'leri ile analiz eder; gerektiğinde FFmpeg ile
 * ölçekleme, ses dönüştürme, kırpma (clip), SponsorBlock ve bölüm/altyazı gömme yapar.
 */
class YtDlpEngine(
    private val isAria2cReady: () -> Boolean = { false },
    private val cookieRepository: CookieRepository? = null
) {

    /**
     * Verilen URL için video/ses metadata, altyazı listesi ve gerçek akışlardan türetilen
     * format seçeneklerini getirir.
     */
    suspend fun fetchMediaInfo(
        url: String,
        platform: PlatformType,
        useCookies: Boolean = true,
        poToken: String? = null,
        visitorData: String? = null
    ): ExtractedMedia = withContext(Dispatchers.IO) {
        val safeUrl = UrlSanitizer.extractFirstValidUrl(url)
            ?: throw IllegalArgumentException("Geçerli ve güvenli bir bağlantı bulunamadı.")

        val cookieFile = if (useCookies) cookieRepository?.getCookieFile() else null

        val request = YoutubeDLRequest(safeUrl).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-check-formats")
            addOption("--socket-timeout", "15")
            if (platform == PlatformType.YOUTUBE) {
                val extractorArgs = buildYoutubeExtractorArgs(
                    hasCookies = cookieFile != null,
                    poToken = poToken,
                    visitorData = visitorData
                )
                addOption("--extractor-args", extractorArgs)
            }
            cookieFile?.let { file ->
                addOption("--cookies", file.absolutePath)
            }
        }

        val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)

        val title = info.title?.takeIf { it.isNotBlank() } ?: "Başlıksız Medya"
        val author = info.uploader?.takeIf { it.isNotBlank() }
        val durationSeconds = info.duration.takeIf { it > 0 }?.toLong()
        val thumbnailUrl = info.thumbnail
            ?.takeIf { it.isNotBlank() && NetworkSecurityGuard.isSafePublicUrl(it) }

        val rawFormats = info.formats.orEmpty()
        val videoFormats = buildRealVideoFormats(rawFormats, platform)
        val audioFormats = buildDefaultAudioFormats(rawFormats, durationSeconds)
        val subtitles = extractSubtitles(info)

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
            originalUrl = safeUrl,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
            platform = platform,
            videoFormats = videoFormats,
            audioFormats = audioFormats,
            imageItems = imageItems,
            subtitles = subtitles
        )
    }

    /**
     * Oynatma listesi (playlist / set) bağlantısını `--flat-playlist --dump-single-json` ile
     * hızlıca çözümler ve en fazla 100 güvenli [PlaylistEntry] döndürür.
     * Geçerli bir playlist değilse veya 2'den az öğe içeriyorsa `null` döner.
     */
    suspend fun fetchPlaylistInfo(
        url: String,
        platform: PlatformType
    ): ExtractedMedia? = withContext(Dispatchers.IO) {
        val safeUrl = UrlSanitizer.extractFirstValidUrl(url) ?: return@withContext null
        val isIgStoryOrProfile = platform == PlatformType.INSTAGRAM &&
            SocialImageExtractor.isInstagramStoryOrProfileUrl(safeUrl)
        if (!isLikelyPlaylistUrl(safeUrl) && !isIgStoryOrProfile) return@withContext null

        val cookieFile = cookieRepository?.getCookieFile()
        if (isIgStoryOrProfile && cookieFile == null) {
            return@withContext null
        }

        val request = YoutubeDLRequest(safeUrl).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--socket-timeout", "25")
            addOption("--playlist-end", MAX_PLAYLIST_ENTRIES.toString())
            cookieFile?.let { file ->
                addOption("--cookies", file.absolutePath)
            }
        }

        val response = YoutubeDL.getInstance().execute(request)
        val rawJson = response.out?.trim().orEmpty()
        if (rawJson.isBlank()) return@withContext null

        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return@withContext null
        val entriesArray = root.optJSONArray("entries") ?: return@withContext null
        val minRequiredEntries = if (isIgStoryOrProfile) 1 else 2
        if (entriesArray.length() < minRequiredEntries) return@withContext null

        val playlistTitle = root.optString("title").takeIf { it.isNotBlank() } ?: "Oynatma Listesi"
        val playlistAuthor = root.optString("uploader").takeIf { it.isNotBlank() }
            ?: root.optString("channel").takeIf { it.isNotBlank() }

        val entries = buildList {
            val limit = minOf(entriesArray.length(), MAX_PLAYLIST_ENTRIES)
            for (i in 0 until limit) {
                val obj = entriesArray.optJSONObject(i) ?: continue
                val entryId = obj.optString("id").takeIf { it.isNotBlank() } ?: "item_${i + 1}"
                val candidateUrl = resolvePlaylistEntryUrl(obj, entryId, platform) ?: continue
                if (!NetworkSecurityGuard.isSafePublicUrl(candidateUrl)) continue

                val entryTitle = obj.optString("title")
                    .takeIf { it.isNotBlank() && it != "[Deleted video]" && it != "[Private video]" }
                    ?: "Video ${i + 1}"
                val entryAuthor = obj.optString("uploader").takeIf { it.isNotBlank() }
                    ?: obj.optString("channel").takeIf { it.isNotBlank() }
                    ?: playlistAuthor
                val entryDuration = obj.optDouble("duration", -1.0)
                    .takeIf { !it.isNaN() && it > 0.0 }
                    ?.toLong()
                val entryThumb = extractSafeJsonThumbnail(obj)

                add(
                    PlaylistEntry(
                        id = entryId,
                        url = candidateUrl,
                        title = entryTitle,
                        author = entryAuthor,
                        durationSeconds = entryDuration,
                        thumbnailUrl = entryThumb
                    )
                )
            }
        }

        if (entries.size < minRequiredEntries) return@withContext null

        val playlistThumb = extractSafeJsonThumbnail(root) ?: entries.firstOrNull()?.thumbnailUrl

        ExtractedMedia(
            originalUrl = safeUrl,
            title = playlistTitle,
            author = playlistAuthor,
            durationSeconds = null,
            thumbnailUrl = playlistThumb,
            platform = platform,
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            imageItems = emptyList(),
            isPlaylist = true,
            playlistEntries = entries
        )
    }

    /**
     * Seçilen görevi geçici klasöre indirir; ses çıkarma, FFmpeg çözünürlük küçültme,
     * çerezler, SponsorBlock, bölümler, zaman aralığı kırpma, altyazı ve resume desteği uygular.
     */
    suspend fun download(
        task: DownloadTask,
        outputDir: File,
        settings: AppSettings,
        poToken: String? = null,
        visitorData: String? = null,
        maxSpeedKbps: Int = 0,
        onProgress: (progress: Float, etaSeconds: Long, speedText: String?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val outputTemplate = File(outputDir, "media.%(ext)s").absolutePath
        val rawSourceUrl = task.directUrl?.takeIf { it.isNotBlank() } ?: task.url
        val sourceUrl = UrlSanitizer.extractFirstValidUrl(rawSourceUrl)
            ?: throw IllegalArgumentException("Geçersiz veya güvensiz indirme bağlantısı.")

        val hasClipRange = task.clipStartSeconds != null || task.clipEndSeconds != null
        val parallelConns = if (settings.parallelDownloadEnabled) {
            settings.parallelConnectionCount.coerceIn(1, 16)
        } else {
            1
        }
        val effectiveSpeedKbps = when {
            maxSpeedKbps > 0 -> maxSpeedKbps
            settings.maxDownloadSpeedKbps > 0 -> settings.maxDownloadSpeedKbps
            else -> 0
        }
        val activeCookieFile = if (settings.cookiesEnabled) cookieRepository?.getCookieFile() else null
        val resolvedPoToken = poToken?.takeIf { it.isNotBlank() }
            ?: if (settings.poTokenEnabled) settings.cachedPoToken else null
        val resolvedVisitorData = visitorData?.takeIf { it.isNotBlank() }
            ?: if (settings.poTokenEnabled) settings.cachedVisitorData else null
        val youtubeExtractorArgs = buildYoutubeExtractorArgs(
            hasCookies = activeCookieFile != null,
            poToken = resolvedPoToken,
            visitorData = resolvedVisitorData
        )

        val request = YoutubeDLRequest(sourceUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--continue")
            addOption("-N", parallelConns.toString())
            addOption("-o", outputTemplate)

            if (effectiveSpeedKbps > 0) {
                addOption("--limit-rate", "${effectiveSpeedKbps}K")
            }

            if (task.platform == PlatformType.YOUTUBE) {
                addOption("--extractor-args", youtubeExtractorArgs)
            }

            activeCookieFile?.let { cookieFile ->
                addOption("--cookies", cookieFile.absolutePath)
            }

            if (settings.sponsorBlockEnabled && task.platform == PlatformType.YOUTUBE) {
                addOption("--sponsorblock-remove", "sponsor,intro,outro,selfpromo")
            }

            if (settings.embedChapters) {
                addOption("--embed-chapters")
            }

            if (hasClipRange) {
                val start = (task.clipStartSeconds ?: 0L).coerceAtLeast(0L)
                val endPart = task.clipEndSeconds?.takeIf { it > start }?.toString() ?: "inf"
                addOption("--download-sections", "*$start-$endPart")
                addOption("--force-keyframes-at-cuts")
            }

            val safeSubLang = task.subtitleLang?.trim()?.takeIf { SAFE_SUBTITLE_LANG_REGEX.matches(it) }
            if (safeSubLang != null) {
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-langs", safeSubLang)
                addOption("--sub-format", "srt/best")
                if (task.embedSubtitles) {
                    addOption("--embed-subs")
                }
            }

            if (task.isAudioExtraction || task.category == MediaCategory.AUDIO) {
                val targetAudioFormat = task.targetExtension.lowercase(Locale.ROOT)
                    .takeIf { it in SUPPORTED_AUDIO_FORMATS } ?: "mp3"
                val audioQuality = resolveAudioQuality(task.formatLabel)

                if (task.directUrl.isNullOrBlank()) {
                    val safeSelector = sanitizeFormatSelector(task.ytDlpFormatSelector, "bestaudio/best")
                    addOption("-f", safeSelector)
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
                    val safeSelector = sanitizeFormatSelector(task.ytDlpFormatSelector, "bestvideo+bestaudio/best")
                    addOption("-f", safeSelector)
                }

                val targetHeight = task.transcodeTargetHeight?.takeIf { it in ALLOWED_TRANSCODE_HEIGHTS }
                if (targetHeight != null) {
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

            // YouTube DASH/chunked akışlarında harici aria2c 403/exit code hatası verdiği için
            // YouTube'da yt-dlp'nin kendi yerleşik -N çoklu fragment indiricisi kullanılır.
            if (settings.useAria2c && isAria2cReady() && !hasClipRange && task.platform != PlatformType.YOUTUBE) {
                addOption("--downloader", "libaria2c.so")
                val aria2SpeedArg = if (effectiveSpeedKbps > 0) " --max-overall-download-limit=${effectiveSpeedKbps}K" else ""
                addOption("--external-downloader-args", "aria2c:-x $parallelConns -s $parallelConns -k 1M -c$aria2SpeedArg")
            }
        }

        val primaryAttempt = runCatching {
            YoutubeDL.getInstance().execute(request, task.id) { progress, etaInSeconds, line ->
                val clampedProgress = if (progress in 0f..100f) progress else 0f
                val speed = extractSpeed(line)
                onProgress(clampedProgress, etaInSeconds, speed)
            }
        }

        if (primaryAttempt.isFailure) {
            // İlk deneme (aria2c, özel format ID veya postprocessor nedeniyle) başarısız olduysa
            // yarım dosyaları temizleyip aria2c'siz, en uyumlu yalın yt-dlp isteğiyle otomatik tekrar dene
            outputDir.listFiles()?.forEach { runCatching { it.delete() } }

            val fallbackRequest = YoutubeDLRequest(sourceUrl).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--newline")
                addOption("-N", parallelConns.toString())
                addOption("-o", outputTemplate)

                if (effectiveSpeedKbps > 0) {
                    addOption("--limit-rate", "${effectiveSpeedKbps}K")
                }

                if (task.platform == PlatformType.YOUTUBE) {
                    addOption("--extractor-args", youtubeExtractorArgs)
                }

                activeCookieFile?.let { cookieFile ->
                    addOption("--cookies", cookieFile.absolutePath)
                }

                if (task.isAudioExtraction || task.category == MediaCategory.AUDIO) {
                    val targetAudioFormat = task.targetExtension.lowercase(Locale.ROOT)
                        .takeIf { it in SUPPORTED_AUDIO_FORMATS } ?: "mp3"
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", targetAudioFormat)
                    addOption("--audio-quality", resolveAudioQuality(task.formatLabel))
                } else {
                    val requestedHeight = task.transcodeTargetHeight?.takeIf { it in ALLOWED_TRANSCODE_HEIGHTS }
                        ?: Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
                            .find(task.formatLabel)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?.takeIf { it in 144..4320 }
                    val fallbackSelector = if (requestedHeight != null) {
                        "bestvideo[height<=$requestedHeight][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=$requestedHeight]+bestaudio/best[height<=$requestedHeight]/best"
                    } else {
                        "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                    }
                    addOption("-f", fallbackSelector)
                    addOption("--merge-output-format", "mp4")
                }
            }

            YoutubeDL.getInstance().execute(fallbackRequest, task.id) { progress, etaInSeconds, line ->
                val clampedProgress = if (progress in 0f..100f) progress else 0f
                val speed = extractSpeed(line)
                onProgress(clampedProgress, etaInSeconds, speed)
            }
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
     * [VideoInfo] nesnesindeki manuel (`subtitles`) ve otomatik (`automaticCaptions`) altyazı
     * haritalarını güvenli şekilde okuyup öncelikli (tr, en, diğerleri — max 12 dil) listeler.
     */
    private fun extractSubtitles(info: VideoInfo): List<SubtitleOption> {
        val manualMap = readSubtitleMapSafely(info, "getSubtitles", "subtitles")
        val autoMap = readSubtitleMapSafely(info, "getAutomaticCaptions", "automaticCaptions")

        if (manualMap.isEmpty() && autoMap.isEmpty()) return emptyList()

        val collected = LinkedHashMap<String, SubtitleOption>()

        manualMap.keys.forEach { rawKey ->
            val langCode = rawKey.trim()
            if (SAFE_SUBTITLE_LANG_REGEX.matches(langCode) && langCode != "live_chat") {
                collected[langCode.lowercase(Locale.ROOT)] = SubtitleOption(
                    langCode = langCode,
                    label = resolveTurkishLanguageLabel(langCode),
                    isAutoGenerated = false
                )
            }
        }

        autoMap.keys.forEach { rawKey ->
            val langCode = rawKey.trim()
            val normalizedKey = langCode.lowercase(Locale.ROOT)
            if (SAFE_SUBTITLE_LANG_REGEX.matches(langCode) &&
                langCode != "live_chat" &&
                !collected.containsKey(normalizedKey)
            ) {
                collected[normalizedKey] = SubtitleOption(
                    langCode = langCode,
                    label = resolveTurkishLanguageLabel(langCode),
                    isAutoGenerated = true
                )
            }
        }

        return collected.values
            .sortedWith(
                compareBy<SubtitleOption> { subtitleLanguagePriority(it.langCode) }
                    .thenBy { if (it.isAutoGenerated) 1 else 0 }
                    .thenBy { it.label.lowercase(Locale("tr", "TR")) }
            )
            .take(MAX_SUBTITLE_OPTIONS)
    }

    private fun readSubtitleMapSafely(
        info: VideoInfo,
        getterName: String,
        fieldName: String
    ): Map<String, Any?> {
        val fromGetter = runCatching {
            val method = info.javaClass.getMethod(getterName)
            @Suppress("UNCHECKED_CAST")
            (method.invoke(info) as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
                ?.toMap()
        }.getOrNull()

        if (!fromGetter.isNullOrEmpty()) return fromGetter

        return runCatching {
            val field = info.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(info) as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
                ?.toMap()
                .orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun subtitleLanguagePriority(langCode: String): Int {
        val base = langCode.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        return when (base) {
            "tr" -> 0
            "en" -> 1
            "de" -> 2
            "fr" -> 3
            "es" -> 4
            "ar" -> 5
            "ru" -> 6
            "ja" -> 7
            "ko" -> 8
            else -> 10
        }
    }

    private fun resolveTurkishLanguageLabel(langCode: String): String {
        val normalized = langCode.lowercase(Locale.ROOT)
        val base = normalized.substringBefore('-').substringBefore('_')
        val known = LANGUAGE_LABELS_TR[base]
        if (known != null) {
            return if (normalized != base && normalized.length <= 10) {
                "$known ($langCode)"
            } else {
                known
            }
        }
        val localeName = runCatching {
            Locale.forLanguageTag(langCode.replace('_', '-'))
                .getDisplayLanguage(Locale("tr", "TR"))
                .replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale("tr", "TR")) else ch.toString()
                }
        }.getOrNull()
        return localeName?.takeIf { it.isNotBlank() && !it.equals(langCode, ignoreCase = true) }
            ?: langCode.uppercase(Locale.ROOT)
    }

    private fun resolvePlaylistEntryUrl(
        entryObj: JSONObject,
        entryId: String,
        platform: PlatformType
    ): String? {
        val rawWebpage = entryObj.optString("webpage_url").takeIf { it.startsWith("http", ignoreCase = true) }
        if (rawWebpage != null) return UrlSanitizer.extractFirstValidUrl(rawWebpage)

        val rawUrl = entryObj.optString("url").takeIf { it.isNotBlank() }
        if (rawUrl != null && rawUrl.startsWith("http", ignoreCase = true)) {
            return UrlSanitizer.extractFirstValidUrl(rawUrl)
        }

        if (platform == PlatformType.YOUTUBE && SAFE_FORMAT_ID_REGEX.matches(entryId)) {
            return "https://www.youtube.com/watch?v=$entryId"
        }

        if (platform == PlatformType.INSTAGRAM && SAFE_FORMAT_ID_REGEX.matches(entryId)) {
            return "https://www.instagram.com/p/$entryId/"
        }

        return null
    }

    private fun buildYoutubeExtractorArgs(
        hasCookies: Boolean,
        poToken: String?,
        visitorData: String?
    ): String {
        val base = if (hasCookies) {
            "youtube:player_client=tv,web_safari,mweb,web;skip=translated_subs"
        } else {
            "youtube:player_client=android,ios,tv;skip=translated_subs"
        }
        val sb = StringBuilder(base)
        val safePo = poToken?.trim()?.takeIf { it.isNotBlank() && SAFE_EXTRACTOR_ARG_TOKEN_REGEX.matches(it) }
        val safeVisitor = visitorData?.trim()?.takeIf { it.isNotBlank() && SAFE_EXTRACTOR_ARG_TOKEN_REGEX.matches(it) }
        if (safePo != null) {
            sb.append(";po_token=mweb.gvs+").append(safePo)
        }
        if (safeVisitor != null) {
            sb.append(";visitor_data=").append(safeVisitor)
        }
        return sb.toString()
    }

    private fun extractSafeJsonThumbnail(obj: JSONObject): String? {
        val direct = obj.optString("thumbnail").takeIf {
            it.isNotBlank() && NetworkSecurityGuard.isSafePublicUrl(it)
        }
        if (direct != null) return direct

        val thumbs = obj.optJSONArray("thumbnails") ?: return null
        for (i in thumbs.length() - 1 downTo 0) {
            val url = thumbs.optJSONObject(i)?.optString("url")
            if (!url.isNullOrBlank() && NetworkSecurityGuard.isSafePublicUrl(url)) {
                return url
            }
        }
        return null
    }

    /**
     * Sabit kalite tablosu olmadan yalnızca [VideoInfo.formats] içindeki gerçek akışları analiz eder:
     * - Dikey/yatay kısa kenar bazlı gerçek çözünürlük (`effectiveHeight`)
     * - Gerçek FPS, kodek (AV1/VP9/HEVC/H.264), HDR / 10-bit ve yüksek bitrate (Premium) tespiti
     * - Birebir `formatId` seçimi
     * - Yalnızca tek çözünürlük dönen platformlarda (Instagram/Twitter vb.) orijinalin altına FFmpeg küçültme seçenekleri.
     */
    internal fun buildRealVideoFormats(
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
            val isEnhancedBitrate = detectEnhancedBitrate(fmt)
            val tbr = fmt.tbr.toDouble().coerceAtLeast(0.0)

            ParsedVideoStream(
                format = fmt,
                effectiveHeight = effectiveHeight,
                fps = fpsValue,
                fpsBucket = fpsBucket,
                codec = codec,
                isHdrOrHighBit = isHdrOrHighBit,
                isEnhancedBitrate = isEnhancedBitrate,
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

        // Aynı (effectiveHeight, fpsBucket) grubu için taban/medyan tbr hesapla (1.45x üzeri -> Yüksek Bit Hızı)
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
                val isHighBitrate = stream.isEnhancedBitrate ||
                    (!stream.isHdrOrHighBit &&
                        medianTbr > 0.0 &&
                        stream.tbr >= medianTbr * HIGH_BITRATE_MULTIPLIER)
                StreamGroupKey(
                    effectiveHeight = stream.effectiveHeight,
                    fpsBucket = stream.fpsBucket,
                    isHdrOrHighBit = stream.isHdrOrHighBit,
                    isHighBitrateVariant = isHighBitrate
                )
            }
            .map { (key, candidates) ->
                val best = candidates.maxWithOrNull(
                    compareBy<ParsedVideoStream> { if (it.isEnhancedBitrate) 1 else 0 }
                        .thenBy { it.tbr }
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

        val options = groupedBest.mapNotNull { (key, stream) ->
            val fmt = stream.format
            val formatId = fmt.formatId?.trim().orEmpty()
            if (!SAFE_FORMAT_ID_REGEX.matches(formatId)) return@mapNotNull null
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
                }
                if (stream.isEnhancedBitrate) {
                    add("Gelişmiş Bit Hızı")
                } else if (key.isHighBitrateVariant) {
                    add("Yüksek Bit Hızı")
                }
                stream.codec?.let { add(it) }
            }

            val label = buildVideoLabel(stream.effectiveHeight, stream.fps)
            val bitrateLabel = stream.tbr.toInt().takeIf { it > 0 }?.let { formatBitrate(it) }
            val sizeLabel = FormatUtils.formatBytes(totalSize)
            val sublabel = listOfNotNull(
                bitrateLabel,
                sizeLabel.takeIf { it.isNotBlank() }
            ).joinToString(" • ").ifBlank { "MP4 Video" }

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

    internal fun detectHdrOrHighBit(fmt: VideoFormat): Boolean {
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

    internal fun detectEnhancedBitrate(fmt: VideoFormat): Boolean {
        val id = fmt.formatId?.trim()
        if (id == "356" || id == "616") return true
        val note = fmt.formatNote?.lowercase(Locale.ROOT).orEmpty()
        val formatDesc = fmt.format?.lowercase(Locale.ROOT).orEmpty()
        return ENHANCED_BITRATE_KEYWORDS.any { kw -> note.contains(kw) || formatDesc.contains(kw) }
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
                    !file.name.endsWith(".temp", ignoreCase = true) &&
                    !file.name.endsWith(".aria2", ignoreCase = true)
            }
            .orEmpty()

        val primaryMedia = candidates.filterNot {
            it.extension.lowercase(Locale.ROOT) in SIDECAR_EXTENSIONS
        }
        return (primaryMedia.ifEmpty { candidates }).maxByOrNull { it.length() }
    }

    private data class ParsedVideoStream(
        val format: VideoFormat,
        val effectiveHeight: Int,
        val fps: Int,
        val fpsBucket: Int,
        val codec: String?,
        val isHdrOrHighBit: Boolean,
        val isEnhancedBitrate: Boolean,
        val tbr: Double
    )

    private fun sanitizeFormatSelector(selector: String?, fallback: String): String {
        val candidate = selector?.trim().orEmpty()
        return if (candidate.isNotBlank() &&
            !candidate.startsWith("-") &&
            SAFE_FORMAT_SELECTOR_REGEX.matches(candidate)
        ) {
            candidate
        } else {
            fallback
        }
    }

    companion object {
        private const val HIGH_BITRATE_MULTIPLIER = 1.45
        private const val MAX_PLAYLIST_ENTRIES = 100
        private const val MAX_SUBTITLE_OPTIONS = 12

        private val SAFE_FORMAT_ID_REGEX = Regex("""^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$""")
        private val SAFE_FORMAT_SELECTOR_REGEX = Regex("""^[a-zA-Z0-9._+\-/\[\]=]{1,200}$""")
        private val SAFE_SUBTITLE_LANG_REGEX = Regex("""^[a-zA-Z0-9,_-]{2,32}$""")
        private val SAFE_EXTRACTOR_ARG_TOKEN_REGEX = Regex("""^[A-Za-z0-9%_\-+/=]{4,512}$""")
        private val ALLOWED_TRANSCODE_HEIGHTS = setOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
        private val SPEED_REGEX = Regex("""\b\d+(?:\.\d+)?\s*[KMG]i?B/s\b""", RegexOption.IGNORE_CASE)
        private val SUPPORTED_AUDIO_FORMATS = setOf("mp3", "m4a", "opus", "flac", "wav")
        private val SIDECAR_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png", "srt", "vtt", "ass", "lrc", "json", "description")
        private val HDR_KEYWORDS = listOf("hdr", "10bit", "10-bit")
        private val ENHANCED_BITRATE_KEYWORDS = listOf("premium", "enhanced", "high bitrate")
        private val DOWNSCALE_STEPS = listOf(720, 480, 360)

        private val LANGUAGE_LABELS_TR = mapOf(
            "tr" to "Türkçe",
            "en" to "İngilizce",
            "de" to "Almanca",
            "fr" to "Fransızca",
            "es" to "İspanyolca",
            "ar" to "Arapça",
            "ru" to "Rusça",
            "ja" to "Japonca",
            "ko" to "Korece"
        )

        /**
         * Verilen URL'nin oynatma listesi (YouTube playlist, SoundCloud sets vb.)
         * olma olasılığını kontrol eder.
         */
        fun isLikelyPlaylistUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains("list=") ||
                lower.contains("/playlist") ||
                lower.contains("/browse/mpreb_") ||
                lower.contains("/sets/")
        }

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
