package com.ulgentube.app.engine

import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.PlaylistEntry
import com.ulgentube.app.util.NetworkSecurityGuard
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Motor 2: Doğrudan görsel bağlantıları, X/Twitter (FxTwitter/VxTwitter API),
 * Reddit (.json), Pinterest, Instagram ve genel web sayfalarından yüksek çözünürlüklü
 * fotoğraf/galeri ve doğrudan dosya indirme motoru.
 */
class SocialImageExtractor(
    private val okHttpClient: OkHttpClient,
    private val parallelRangeDownloader: ParallelRangeDownloader? = null,
    private val cookieRepository: CookieRepository? = null
) {

    /**
     * URL'yi platformuna göre analiz edip görsel/galeri ve varsa doğrudan video bağlantılarını döndürür.
     */
    suspend fun extract(
        url: String,
        platform: PlatformType
    ): ExtractedMedia = withContext(Dispatchers.IO) {
        val safeUrl = UrlSanitizer.extractFirstValidUrl(url)
            ?: throw IllegalArgumentException("Geçersiz veya güvensiz bağlantı.")

        val rawMedia = if (platform == PlatformType.DIRECT_IMAGE || isImageContentType(safeUrl)) {
            extractDirectImage(safeUrl)
        } else {
            when (platform) {
                PlatformType.TWITTER_X -> extractTwitter(safeUrl)
                PlatformType.REDDIT -> extractReddit(safeUrl)
                PlatformType.INSTAGRAM -> extractInstagram(safeUrl)
                else -> extractFromWebPage(safeUrl, platform)
            }
        }

        val sanitized = sanitizeExtractedMedia(rawMedia)
        if (sanitized.videoFormats.isEmpty() &&
            sanitized.audioFormats.isEmpty() &&
            sanitized.imageItems.isEmpty() &&
            sanitized.playlistEntries.isEmpty()
        ) {
            throw IOException("Bu sayfada güvenli ve indirilebilir medya bulunamadı.")
        }
        return@withContext sanitized
    }

    /**
     * Harici JSON/HTML yanıtlarından ayıklanan tüm ikincil URL'lerin (görsel, kapak, video, ses)
     * yalnızca herkese açık güvenli adresler olduğunu garanti eder (Coil & SSRF koruması).
     */
    private fun sanitizeExtractedMedia(media: ExtractedMedia): ExtractedMedia {
        val safeImages = media.imageItems.filter { NetworkSecurityGuard.isSafePublicUrl(it.url) }
        val safeVideos = media.videoFormats.filter { option ->
            option.directUrl == null || NetworkSecurityGuard.isSafePublicUrl(option.directUrl)
        }
        val safeAudios = media.audioFormats.filter { option ->
            option.directUrl == null || NetworkSecurityGuard.isSafePublicUrl(option.directUrl)
        }
        val safeThumb = media.thumbnailUrl?.takeIf { NetworkSecurityGuard.isSafePublicUrl(it) }
            ?: safeImages.firstOrNull()?.url

        return media.copy(
            thumbnailUrl = safeThumb,
            videoFormats = safeVideos,
            audioFormats = safeAudios,
            imageItems = safeImages
        )
    }

    /**
     * Doğrudan bir görsel veya medya dosyasını hedef dosyaya indirir; çoklu bağlantı
     * istendiğinde [ParallelRangeDownloader] ile HTTP Range paralel indirme kullanır.
     */
    suspend fun downloadDirectFile(
        url: String,
        targetFile: File,
        parallelConnections: Int = 1,
        maxSpeedBytesPerSec: Long = 0L,
        onProgress: (progress: Float, speedText: String?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val safeUrl = UrlSanitizer.extractFirstValidUrl(url)
            ?: throw IllegalArgumentException("Geçersiz veya güvensiz dosya bağlantısı.")
        targetFile.parentFile?.mkdirs()

        val isRedditMedia = isRedditMediaUrl(safeUrl)
        if (parallelConnections > 1 && parallelRangeDownloader != null && !isRedditMedia) {
            return@withContext parallelRangeDownloader.download(
                url = safeUrl,
                targetFile = targetFile,
                userAgent = DESKTOP_USER_AGENT,
                connections = parallelConnections,
                maxBytes = MAX_DIRECT_DOWNLOAD_BYTES,
                maxSpeedBytesPerSec = maxSpeedBytesPerSec,
                onProgress = onProgress
            )
        }

        val requestBuilder = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "*/*")
        if (isRedditMedia) {
            requestBuilder.header("Referer", "https://www.reddit.com/")
        }
        val request = requestBuilder.build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!NetworkSecurityGuard.isSafePublicUrl(response.request.url.toString())) {
                    throw SecurityException("Redirect sonrası güvensiz adres reddedildi.")
                }
                if (!response.isSuccessful) {
                    throw IOException("Dosya indirilemedi (HTTP ${response.code}).")
                }

                val body = response.body ?: throw IOException("Sunucu boş yanıt döndürdü.")
                val totalBytes = body.contentLength()
                if (totalBytes > MAX_DIRECT_DOWNLOAD_BYTES) {
                    throw IOException("Dosya güvenlik boyut sınırını (500 MB) aşıyor.")
                }

                val buffer = ByteArray(16 * 1024)
                var downloadedBytes = 0L
                var lastEmitTime = System.currentTimeMillis()
                var lastBytesSnapshot = 0L
                var throttleWindowStartMs = System.currentTimeMillis()
                var throttleWindowBytes = 0L

                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            downloadedBytes += read
                            if (downloadedBytes > MAX_DIRECT_DOWNLOAD_BYTES) {
                                throw IOException("İndirme güvenlik boyut sınırını (500 MB) aştı.")
                            }
                            output.write(buffer, 0, read)

                            if (maxSpeedBytesPerSec > 0L) {
                                val throttleNow = System.currentTimeMillis()
                                val throttleElapsed = throttleNow - throttleWindowStartMs
                                if (throttleElapsed >= 1000L) {
                                    throttleWindowStartMs = throttleNow
                                    throttleWindowBytes = 0L
                                }
                                throttleWindowBytes += read
                                if (throttleWindowBytes > maxSpeedBytesPerSec) {
                                    val expectedMs = (throttleWindowBytes * 1000L) / maxSpeedBytesPerSec
                                    val sleepMs = (expectedMs - (throttleNow - throttleWindowStartMs).coerceAtLeast(0L))
                                        .coerceIn(10L, 2000L)
                                    delay(sleepMs)
                                }
                            }

                            val now = System.currentTimeMillis()
                            val elapsedMs = now - lastEmitTime
                            if (elapsedMs >= 250L) {
                                val bytesDelta = downloadedBytes - lastBytesSnapshot
                                val speedBytesPerSec = (bytesDelta * 1000.0) / elapsedMs.coerceAtLeast(1L)
                                val speedText = formatSpeed(speedBytesPerSec)
                                val percent = if (totalBytes > 0L) {
                                    ((downloadedBytes.toFloat() / totalBytes.toFloat()) * 100f).coerceIn(0f, 100f)
                                } else {
                                    0f
                                }
                                onProgress(percent, speedText)
                                lastEmitTime = now
                                lastBytesSnapshot = downloadedBytes
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(100f, null)
            }
        } catch (e: Exception) {
            runCatching { targetFile.delete() }
            throw e
        }

        targetFile
    }

    private fun extractDirectImage(url: String): ExtractedMedia {
        val ext = UrlSanitizer.guessImageExtension(url)
        val rawName = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            .takeIf { it.isNotBlank() } ?: "Görsel"

        val imageItem = ImageItem(
            id = "direct_img_1",
            url = url,
            label = "Orijinal Görsel (${ext.uppercase(Locale.ROOT)})",
            extension = ext
        )

        return ExtractedMedia(
            originalUrl = url,
            title = rawName,
            author = null,
            durationSeconds = null,
            thumbnailUrl = url,
            platform = PlatformType.DIRECT_IMAGE,
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            imageItems = listOf(imageItem)
        )
    }

    /**
     * X / Twitter gönderilerini FxTwitter (birincil) ve VxTwitter (yedek) API üzerinden çözümler.
     */
    private fun extractTwitter(url: String): ExtractedMedia {
        val statusId = TWITTER_STATUS_REGEX.find(url)?.groupValues?.getOrNull(1)
            ?: return extractFromWebPage(url, PlatformType.TWITTER_X)

        val fxResult = runCatching { fetchFxTwitter(url, statusId) }.getOrNull()
        if (fxResult != null && (fxResult.imageItems.isNotEmpty() || fxResult.videoFormats.isNotEmpty())) {
            return fxResult
        }

        val vxResult = runCatching { fetchVxTwitter(url, statusId) }.getOrNull()
        if (vxResult != null && (vxResult.imageItems.isNotEmpty() || vxResult.videoFormats.isNotEmpty())) {
            return vxResult
        }

        return extractFromWebPage(url, PlatformType.TWITTER_X)
    }

    private fun fetchFxTwitter(originalUrl: String, statusId: String): ExtractedMedia {
        val jsonText = executeGet("https://api.fxtwitter.com/i/status/$statusId")
        val root = JSONObject(jsonText)
        val tweet = root.optJSONObject("tweet")
            ?: throw IOException("FxTwitter yanıtında tweet verisi bulunamadı.")

        val text = tweet.optString("text").trim()
        val authorObj = tweet.optJSONObject("author")
        val authorName = authorObj?.optString("name")?.takeIf { it.isNotBlank() }
        val screenName = authorObj?.optString("screen_name")?.takeIf { it.isNotBlank() }
        val authorDisplay = when {
            authorName != null && screenName != null -> "$authorName (@$screenName)"
            screenName != null -> "@$screenName"
            else -> authorName
        }

        val title = text.take(80).ifBlank { "X Gönderisi #$statusId" }
        val mediaObj = tweet.optJSONObject("media")

        val images = mutableListOf<ImageItem>()
        val photosArray = mediaObj?.optJSONArray("photos")
        if (photosArray != null) {
            for (i in 0 until photosArray.length()) {
                val photo = photosArray.optJSONObject(i) ?: continue
                val photoUrl = photo.optString("url").takeIf { it.isNotBlank() } ?: continue
                val width = photo.optInt("width", 0).takeIf { it > 0 }
                val height = photo.optInt("height", 0).takeIf { it > 0 }
                images += ImageItem(
                    id = "x_photo_${i + 1}",
                    url = upgradeTwitterImageUrl(photoUrl),
                    label = "Fotoğraf ${i + 1}${resolutionSuffix(width, height)}",
                    extension = UrlSanitizer.guessImageExtension(photoUrl),
                    width = width,
                    height = height
                )
            }
        }

        val videos = mutableListOf<MediaFormatOption>()
        var durationSeconds: Long? = null
        var thumbUrl: String? = images.firstOrNull()?.url

        val videosArray = mediaObj?.optJSONArray("videos")
        if (videosArray != null) {
            for (i in 0 until videosArray.length()) {
                val video = videosArray.optJSONObject(i) ?: continue
                val vWidth = video.optInt("width", 0)
                val vHeight = video.optInt("height", 0)
                val effectiveHeight = if (vWidth > 0 && vHeight > 0) minOf(vWidth, vHeight) else vHeight
                val vDuration = video.optDouble("duration", 0.0).toLong().takeIf { it > 0L }
                if (durationSeconds == null) durationSeconds = vDuration
                if (thumbUrl == null) {
                    thumbUrl = video.optString("thumbnail_url").takeIf { it.isNotBlank() }
                }

                val extractedVariants = extractTwitterVariants(video, i + 1)
                if (extractedVariants.isNotEmpty()) {
                    videos += extractedVariants
                } else {
                    val videoUrl = video.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val label = if (effectiveHeight > 0) "${effectiveHeight}p" else "Video ${i + 1} (HD)"
                    videos += MediaFormatOption(
                        id = "x_video_${i + 1}",
                        label = label,
                        sublabel = "MP4 • X Doğrudan",
                        category = MediaCategory.VIDEO,
                        extension = "mp4",
                        resolutionHeight = effectiveHeight,
                        directUrl = videoUrl
                    )
                }
            }
        }

        val finalVideos = appendDownscaleIfSingleResolution(videos)
        val audioOptions = if (finalVideos.isNotEmpty()) {
            YtDlpEngine.buildDefaultAudioFormats(
                formats = emptyList(),
                durationSeconds = durationSeconds,
                directSourceUrl = finalVideos.first().directUrl
            )
        } else {
            emptyList()
        }

        return ExtractedMedia(
            originalUrl = originalUrl,
            title = title,
            author = authorDisplay,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbUrl,
            platform = PlatformType.TWITTER_X,
            videoFormats = finalVideos,
            audioFormats = audioOptions,
            imageItems = images
        )
    }

    private fun fetchVxTwitter(originalUrl: String, statusId: String): ExtractedMedia {
        val jsonText = executeGet("https://api.vxtwitter.com/i/status/$statusId")
        val root = JSONObject(jsonText)

        val text = root.optString("text").trim()
        val userName = root.optString("user_name").takeIf { it.isNotBlank() }
        val screenName = root.optString("user_screen_name").takeIf { it.isNotBlank() }
        val authorDisplay = when {
            userName != null && screenName != null -> "$userName (@$screenName)"
            screenName != null -> "@$screenName"
            else -> userName
        }

        val images = mutableListOf<ImageItem>()
        val videos = mutableListOf<MediaFormatOption>()
        var thumbUrl: String? = null

        val mediaExtended = root.optJSONArray("media_extended")
        if (mediaExtended != null) {
            for (i in 0 until mediaExtended.length()) {
                val item = mediaExtended.optJSONObject(i) ?: continue
                val type = item.optString("type").lowercase(Locale.ROOT)
                val mediaUrl = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val sizeObj = item.optJSONObject("size")
                val width = sizeObj?.optInt("width", 0)?.takeIf { it > 0 }
                val height = sizeObj?.optInt("height", 0)?.takeIf { it > 0 }

                if (type == "image") {
                    images += ImageItem(
                        id = "vx_photo_${images.size + 1}",
                        url = upgradeTwitterImageUrl(mediaUrl),
                        label = "Fotoğraf ${images.size + 1}${resolutionSuffix(width, height)}",
                        extension = UrlSanitizer.guessImageExtension(mediaUrl),
                        width = width,
                        height = height
                    )
                    if (thumbUrl == null) thumbUrl = mediaUrl
                } else if (type == "video" || type == "gif") {
                    val vThumb = item.optString("thumbnail_url").takeIf { it.isNotBlank() }
                    if (thumbUrl == null) thumbUrl = vThumb
                    val extractedVariants = extractTwitterVariants(item, videos.size + 1)
                    if (extractedVariants.isNotEmpty()) {
                        videos += extractedVariants
                    } else {
                        val effectiveHeight = if (width != null && height != null) minOf(width, height) else (height ?: 0)
                        videos += MediaFormatOption(
                            id = "vx_video_${videos.size + 1}",
                            label = if (effectiveHeight > 0) "${effectiveHeight}p" else "Video ${videos.size + 1}",
                            sublabel = "MP4 • X Doğrudan",
                            category = MediaCategory.VIDEO,
                            extension = "mp4",
                            resolutionHeight = effectiveHeight,
                            directUrl = mediaUrl
                        )
                    }
                }
            }
        }

        val finalVideos = appendDownscaleIfSingleResolution(videos)
        val audioOptions = if (finalVideos.isNotEmpty()) {
            YtDlpEngine.buildDefaultAudioFormats(
                formats = emptyList(),
                durationSeconds = null,
                directSourceUrl = finalVideos.first().directUrl
            )
        } else {
            emptyList()
        }

        return ExtractedMedia(
            originalUrl = originalUrl,
            title = text.take(80).ifBlank { "X Gönderisi #$statusId" },
            author = authorDisplay,
            durationSeconds = null,
            thumbnailUrl = thumbUrl,
            platform = PlatformType.TWITTER_X,
            videoFormats = finalVideos,
            audioFormats = audioOptions,
            imageItems = images
        )
    }

    /**
     * Reddit gönderilerinden (`/r/.../s/...` mobil share linkleri, `redd.it`, crosspost, galeri,
     * doğrudan görsel ve `reddit_video`) çok katmanlı JSON/API + OpenGraph proxy fallback ile medya çıkarır.
     */
    private fun extractReddit(url: String): ExtractedMedia {
        val canonicalUrl = resolveRedditCanonicalUrl(url)
        val postId = extractRedditPostId(canonicalUrl) ?: extractRedditPostId(url)

        val postData = fetchRedditPostData(canonicalUrl, postId)
            ?: return extractRedditFromProxyOrWeb(canonicalUrl, postId)

        val title = postData.optString("title").takeIf { it.isNotBlank() } ?: "Reddit Gönderisi"
        val author = postData.optString("author").takeIf { it.isNotBlank() }?.let { "u/$it" }
        val images = mutableListOf<ImageItem>()
        val videos = mutableListOf<MediaFormatOption>()
        val seenUrls = linkedSetOf<String>()

        var durationSeconds = extractRedditMediaFromPost(
            sourcePost = postData,
            images = images,
            videos = videos,
            seenUrls = seenUrls
        )

        // Ana postta medya yoksa crosspost_parent_list içindeki orijinal gönderiden medya çıkar
        if (images.isEmpty() && videos.isEmpty()) {
            val crosspost = postData.optJSONArray("crosspost_parent_list")?.optJSONObject(0)
            if (crosspost != null) {
                val crossDuration = extractRedditMediaFromPost(
                    sourcePost = crosspost,
                    images = images,
                    videos = videos,
                    seenUrls = seenUrls
                )
                if (durationSeconds == null) {
                    durationSeconds = crossDuration
                }
            }
        }

        if (images.isEmpty() && videos.isEmpty()) {
            val fallbackMedia = runCatching {
                extractRedditFromProxyOrWeb(canonicalUrl, postId)
            }.getOrNull()
            if (fallbackMedia != null) {
                return fallbackMedia
            }
        }

        return ExtractedMedia(
            originalUrl = canonicalUrl,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            thumbnailUrl = images.firstOrNull()?.url,
            platform = PlatformType.REDDIT,
            videoFormats = videos,
            audioFormats = emptyList(),
            imageItems = images
        )
    }

    /**
     * Reddit mobil paylaşım (`/r/.../s/<shareCode>`), `redd.it/<id>` veya `v.redd.it/<id>`
     * kısa bağlantılarını HTTP redirect takibi ile canonical `/r/.../comments/<postId>/...` adresine çözer.
     */
    internal fun resolveRedditCanonicalUrl(url: String): String {
        val normalizedProxy = normalizeRedditProxyHost(url)
        val lower = normalizedProxy.lowercase(Locale.ROOT)
        val needsRedirect = lower.contains("/s/") ||
            lower.contains("://redd.it/") ||
            lower.contains("://www.redd.it/") ||
            lower.contains("://v.redd.it/")

        if (!needsRedirect) {
            return normalizedProxy
        }

        val resolved = resolveRedirectUrl(normalizedProxy, DESKTOP_USER_AGENT)
            ?: resolveRedirectUrl(normalizedProxy, REDDIT_USER_AGENT)
            ?: normalizedProxy

        return normalizeRedditProxyHost(resolved)
    }

    private fun normalizeRedditProxyHost(url: String): String {
        return url
            .replace(Regex("""^(https?://)(?:www\.)?(?:rxddit|vxreddit)\.com""", RegexOption.IGNORE_CASE), "$1www.reddit.com")
    }

    private fun fetchRedditPostData(canonicalUrl: String, postId: String?): JSONObject? {
        val attempts = mutableListOf<Pair<String, String>>()
        if (!postId.isNullOrBlank()) {
            val wwwJson = "https://www.reddit.com/comments/$postId.json?raw_json=1"
            val oldJson = "https://old.reddit.com/comments/$postId.json?raw_json=1"
            val apiInfo = "https://api.reddit.com/api/info/?id=t3_$postId&raw_json=1"
            attempts += wwwJson to DESKTOP_USER_AGENT
            attempts += wwwJson to REDDIT_USER_AGENT
            attempts += oldJson to REDDIT_USER_AGENT
            attempts += oldJson to DESKTOP_USER_AGENT
            attempts += apiInfo to REDDIT_USER_AGENT
        } else {
            val cleanBase = canonicalUrl.substringBefore('?').trimEnd('/')
            val jsonUrl = if (cleanBase.endsWith(".json", ignoreCase = true)) {
                "$cleanBase?raw_json=1"
            } else {
                "$cleanBase.json?raw_json=1"
            }
            attempts += jsonUrl to DESKTOP_USER_AGENT
            attempts += jsonUrl to REDDIT_USER_AGENT
        }

        val redditCookie = readRedditCookieHeader()
        val redditHeaders = mapOf(
            "Accept" to "application/json, text/plain;q=0.9, */*;q=0.8",
            "Referer" to "https://www.reddit.com/",
            "Cookie" to redditCookie
        )

        for ((endpoint, ua) in attempts) {
            val body = runCatching {
                executeGetWithHeaders(
                    url = endpoint,
                    userAgent = ua,
                    extraHeaders = redditHeaders
                )
            }.getOrNull() ?: continue

            val parsed = parseRedditPostObject(body)
            if (parsed != null) {
                return parsed
            }
        }
        return null
    }

    private fun readRedditCookieHeader(): String {
        val merged = linkedMapOf("over18" to "1")
        val cookieFile = cookieRepository?.getCookieFile()
        if (cookieFile != null && cookieFile.exists()) {
            runCatching {
                cookieFile.useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty()) return@forEach
                        val normalized = when {
                            line.startsWith("#HttpOnly_", ignoreCase = true) ->
                                line.removePrefix("#HttpOnly_").removePrefix("#httponly_")
                            line.startsWith("#") -> return@forEach
                            else -> line
                        }
                        val fields = normalized.split('\t')
                        if (fields.size >= 7 && fields[0].contains("reddit.com", ignoreCase = true)) {
                            val name = fields[5].trim()
                            val value = fields[6].trim()
                            if (name.isNotBlank() && value.isNotBlank()) {
                                merged[name] = value
                            }
                        }
                    }
                }
            }
        }
        runCatching {
            android.webkit.CookieManager.getInstance()
                ?.getCookie("https://www.reddit.com")
                ?.split(';')
                ?.forEach { part ->
                    val trimmed = part.trim()
                    val eq = trimmed.indexOf('=')
                    if (eq > 0) {
                        val name = trimmed.substring(0, eq).trim()
                        val value = trimmed.substring(eq + 1).trim()
                        if (name.isNotBlank() && value.isNotBlank()) {
                            merged[name] = value
                        }
                    }
                }
        }
        merged["over18"] = "1"
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun extractRedditMediaFromPost(
        sourcePost: JSONObject,
        images: MutableList<ImageItem>,
        videos: MutableList<MediaFormatOption>,
        seenUrls: MutableSet<String>
    ): Long? {
        // 1) Galeri gönderisi (media_metadata)
        val mediaMetadata = sourcePost.optJSONObject("media_metadata")
        val galleryItems = sourcePost.optJSONObject("gallery_data")?.optJSONArray("items")
        if (mediaMetadata != null) {
            val orderedIds = mutableListOf<String>()
            if (galleryItems != null) {
                for (i in 0 until galleryItems.length()) {
                    galleryItems.optJSONObject(i)?.optString("media_id")?.takeIf { it.isNotBlank() }
                        ?.let { orderedIds += it }
                }
            } else {
                val keys = mediaMetadata.keys()
                while (keys.hasNext()) {
                    orderedIds += keys.next()
                }
            }

            orderedIds.forEachIndexed { index, mediaId ->
                val meta = mediaMetadata.optJSONObject(mediaId) ?: return@forEachIndexed
                val source = meta.optJSONObject("s") ?: return@forEachIndexed
                val imgUrl = (source.optString("u").takeIf { it.isNotBlank() }
                    ?: source.optString("gif").takeIf { it.isNotBlank() })
                    ?.replace("&amp;", "&")
                    ?: return@forEachIndexed

                if (seenUrls.add(imgUrl)) {
                    val width = source.optInt("x", 0).takeIf { it > 0 }
                    val height = source.optInt("y", 0).takeIf { it > 0 }
                    images += ImageItem(
                        id = "reddit_gallery_${images.size + 1}",
                        url = imgUrl,
                        label = "Galeri ${images.size + 1}${resolutionSuffix(width, height)}",
                        extension = UrlSanitizer.guessImageExtension(imgUrl),
                        width = width,
                        height = height
                    )
                }
            }
        }

        // 2) Tekil doğrudan görsel bağlantısı (url_overridden_by_dest / i.redd.it / i.imgur.com)
        val directDest = (sourcePost.optString("url_overridden_by_dest").takeIf { it.isNotBlank() }
            ?: sourcePost.optString("url").takeIf { it.isNotBlank() })
            ?.replace("&amp;", "&")
        if (!directDest.isNullOrBlank()) {
            val lowerDest = directDest.lowercase(Locale.ROOT)
            val isDirectImg = UrlSanitizer.isDirectImagePath(directDest) ||
                ((sourcePost.optString("post_hint") == "image" ||
                    lowerDest.contains("://i.redd.it/") ||
                    lowerDest.contains("://i.imgur.com/")) &&
                    !lowerDest.endsWith(".gifv"))
            if (isDirectImg && seenUrls.add(directDest)) {
                images += ImageItem(
                    id = "reddit_direct_${images.size + 1}",
                    url = directDest,
                    label = "Orijinal Görsel",
                    extension = UrlSanitizer.guessImageExtension(directDest)
                )
            }
        }

        // 3) Önizleme görselleri (preview.images)
        if (images.isEmpty()) {
            val previewImages = sourcePost.optJSONObject("preview")?.optJSONArray("images")
            if (previewImages != null) {
                for (i in 0 until previewImages.length()) {
                    val source = previewImages.optJSONObject(i)?.optJSONObject("source") ?: continue
                    val imgUrl = source.optString("url").replace("&amp;", "&")
                        .takeIf { it.isNotBlank() } ?: continue
                    if (seenUrls.add(imgUrl)) {
                        val width = source.optInt("width", 0).takeIf { it > 0 }
                        val height = source.optInt("height", 0).takeIf { it > 0 }
                        images += ImageItem(
                            id = "reddit_preview_${i + 1}",
                            url = imgUrl,
                            label = "Görsel ${images.size + 1}${resolutionSuffix(width, height)}",
                            extension = UrlSanitizer.guessImageExtension(imgUrl),
                            width = width,
                            height = height
                        )
                    }
                }
            }
        }

        // 4) Reddit videosu (sesli yt-dlp mux / HLS + doğrudan fallback_url)
        var durationSeconds: Long? = null
        val redditVideo = sourcePost.optJSONObject("secure_media")?.optJSONObject("reddit_video")
            ?: sourcePost.optJSONObject("media")?.optJSONObject("reddit_video")
            ?: sourcePost.optJSONObject("preview")?.optJSONObject("reddit_video_preview")
        if (redditVideo != null) {
            val fallbackUrl = redditVideo.optString("fallback_url")
                .replace("&amp;", "&")
                .takeIf { it.isNotBlank() }
            val hlsUrl = redditVideo.optString("hls_url")
                .replace("&amp;", "&")
                .takeIf { it.isNotBlank() }
            val height = redditVideo.optInt("height", 0)
            val isGif = redditVideo.optBoolean("is_gif", false)
            durationSeconds = redditVideo.optLong("duration", 0L).takeIf { it > 0L }

            if (!isGif && (hlsUrl != null || fallbackUrl != null)) {
                videos += MediaFormatOption(
                    id = "reddit_video_mux",
                    label = if (height > 0) "${height}p Reddit Video (Sesli)" else "Reddit Video (Sesli)",
                    sublabel = if (hlsUrl != null) "MP4 • HLS/Sesli" else "MP4 • Sesli",
                    category = MediaCategory.VIDEO,
                    extension = "mp4",
                    resolutionHeight = height,
                    ytDlpFormatSelector = "bestvideo+bestaudio/best",
                    directUrl = null
                )
            }
            if (fallbackUrl != null) {
                videos += MediaFormatOption(
                    id = "reddit_video_direct",
                    label = if (height > 0) "${height}p Hızlı Video" else "Reddit Video",
                    sublabel = if (isGif) "MP4 • GIF" else "MP4 • Doğrudan",
                    category = MediaCategory.VIDEO,
                    extension = "mp4",
                    resolutionHeight = height,
                    directUrl = fallbackUrl
                )
            }
        }

        // 4b) Preview MP4 varyantı veya Imgur .gifv -> .mp4 (özellikle 18+ GIF/video gönderileri)
        if (videos.isEmpty()) {
            val variantMp4 = sourcePost.optJSONObject("preview")
                ?.optJSONArray("images")
                ?.optJSONObject(0)
                ?.optJSONObject("variants")
                ?.optJSONObject("mp4")
                ?.optJSONObject("source")
            val mp4Url = variantMp4?.optString("url")?.replace("&amp;", "&")?.takeIf { it.startsWith("http") }
            if (mp4Url != null && seenUrls.add(mp4Url)) {
                val h = variantMp4.optInt("height", 0)
                videos += MediaFormatOption(
                    id = "reddit_variant_mp4",
                    label = if (h > 0) "${h}p Reddit Video (MP4)" else "Reddit Video (MP4)",
                    sublabel = "MP4 • Doğrudan",
                    category = MediaCategory.VIDEO,
                    extension = "mp4",
                    resolutionHeight = h,
                    directUrl = mp4Url
                )
            } else if (!directDest.isNullOrBlank()) {
                val lowerDest = directDest.lowercase(Locale.ROOT)
                val directMp4 = when {
                    lowerDest.endsWith(".mp4") -> directDest
                    lowerDest.endsWith(".gifv") && lowerDest.contains("imgur.com") ->
                        directDest.dropLast(5) + ".mp4"
                    else -> null
                }
                if (directMp4 != null && seenUrls.add(directMp4)) {
                    videos += MediaFormatOption(
                        id = "reddit_ext_mp4",
                        label = "Reddit Video (MP4)",
                        sublabel = "MP4 • Doğrudan",
                        category = MediaCategory.VIDEO,
                        extension = "mp4",
                        directUrl = directMp4
                    )
                } else if (lowerDest.contains("redgifs.com/")) {
                    // Reddit 18+ GIF/video gönderilerinin çoğu Redgifs v2 üzerinde barınır
                    val redgifsMp4 = runCatching { extractRedgifsMp4Url(directDest) }.getOrNull()
                    if (!redgifsMp4.isNullOrBlank() && seenUrls.add(redgifsMp4)) {
                        videos += MediaFormatOption(
                            id = "reddit_redgifs_mp4",
                            label = "HD Video (Redgifs MP4)",
                            sublabel = "MP4 • Doğrudan",
                            category = MediaCategory.VIDEO,
                            extension = "mp4",
                            directUrl = redgifsMp4
                        )
                    }
                }
            }
        }

        return durationSeconds
    }

    /**
     * Redgifs (`redgifs.com/watch/<id>` / `v3.redgifs.com/watch/<id>`) bağlantılarından
     * resmi geçici token ile doğrudan HD/SD `.mp4` adresini çıkarır.
     */
    private fun extractRedgifsMp4Url(redgifsUrl: String): String? {
        val slug = Regex("""redgifs\.com/(?:watch|ifr)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(redgifsUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
            ?: return null

        val authJson = executeGetWithHeaders(
            url = "https://api.redgifs.com/v2/auth/temporary",
            userAgent = DESKTOP_USER_AGENT,
            extraHeaders = mapOf("Accept" to "application/json")
        )
        val token = JSONObject(authJson).optString("token").takeIf { it.isNotBlank() } ?: return null

        val gifJson = executeGetWithHeaders(
            url = "https://api.redgifs.com/v2/gifs/$slug",
            userAgent = DESKTOP_USER_AGENT,
            extraHeaders = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $token"
            )
        )
        val urls = JSONObject(gifJson).optJSONObject("gif")?.optJSONObject("urls") ?: return null
        return urls.optString("hd").takeIf { it.startsWith("http") }
            ?: urls.optString("sd").takeIf { it.startsWith("http") }
    }

    /**
     * JSON/API erişimi 403/429 ile engellendiğinde `rxddit.com` ve `vxreddit.com` OpenGraph
     * sayfalarından `og:image`, `og:video`, `twitter:image` çıkarır; o da boşsa orijinal Reddit HTML'ini dener.
     */
    private fun extractRedditFromProxyOrWeb(canonicalUrl: String, postId: String?): ExtractedMedia {
        val proxyCandidates = buildList {
            val rxUrl = buildRedditProxyUrl(canonicalUrl, postId, "rxddit.com")
            val vxUrl = buildRedditProxyUrl(canonicalUrl, postId, "vxreddit.com")
            if (rxUrl != null) add(rxUrl)
            if (vxUrl != null) add(vxUrl)
        }

        for (proxyUrl in proxyCandidates) {
            val proxyMedia = runCatching {
                extractRedditProxyWebPage(proxyUrl, canonicalUrl)
            }.getOrNull()
            if (proxyMedia != null && (proxyMedia.videoFormats.isNotEmpty() || proxyMedia.imageItems.isNotEmpty())) {
                return proxyMedia
            }
        }

        val webFallback = extractFromWebPage(canonicalUrl, PlatformType.REDDIT)
        val filteredImages = webFallback.imageItems.filterNot { isRedditPlaceholderImage(it.url) }
        val isAgeGate = isRedditAgeGateTitle(webFallback.title)
        if (webFallback.videoFormats.isEmpty() && (filteredImages.isEmpty() || isAgeGate)) {
            throw IOException("Reddit yetişkinlere yönelik (18+) veya korumalı içerik: yt-dlp/oturum yedeğine yönlendiriliyor.")
        }
        return webFallback.copy(
            imageItems = filteredImages,
            thumbnailUrl = filteredImages.firstOrNull()?.url ?: webFallback.thumbnailUrl?.takeUnless { isRedditPlaceholderImage(it) }
        )
    }

    private fun isRedditPlaceholderImage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("redditstatic.com") ||
            lower.contains("share-card") ||
            lower.contains("share_card") ||
            lower.contains("new-icon") ||
            lower.contains("reddit_logo") ||
            lower.contains("reddit-logo") ||
            lower.contains("/static/noimage") ||
            lower.contains("nsfw_thumb") ||
            lower.contains("over18") ||
            lower.contains("styles.redditmedia.com")
    }

    private fun isRedditAgeGateTitle(title: String?): Boolean {
        val lower = title?.lowercase(Locale.ROOT).orEmpty()
        return lower.contains("yetişkinlere yönelik") ||
            lower.contains("mature content") ||
            lower.contains("over 18") ||
            lower.contains("adult content")
    }

    private fun buildRedditProxyUrl(canonicalUrl: String, postId: String?, proxyHost: String): String? {
        val replaced = canonicalUrl.replace(
            Regex("""^(https?://)(?:www\.|old\.|m\.)?reddit\.com""", RegexOption.IGNORE_CASE),
            "$1$proxyHost"
        )
        return when {
            replaced != canonicalUrl -> replaced
            !postId.isNullOrBlank() -> "https://$proxyHost/comments/$postId"
            else -> null
        }
    }

    private fun extractRedditProxyWebPage(proxyUrl: String, canonicalUrl: String): ExtractedMedia? {
        val html = runCatching {
            executeGet(proxyUrl, userAgent = TELEGRAM_BOT_USER_AGENT)
        }.getOrNull()
            ?: runCatching {
                executeGet(proxyUrl, userAgent = FACEBOOK_BOT_USER_AGENT)
            }.getOrNull()
            ?: return null

        val doc = Jsoup.parse(html, proxyUrl)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=twitter:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }
            ?: "Reddit Gönderisi"
        val author = doc.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() }

        val videos = mutableListOf<MediaFormatOption>()
        val seenVideoUrls = linkedSetOf<String>()
        doc.select("meta[property=og:video], meta[property=og:video:secure_url], meta[property=og:video:url], meta[name=twitter:player:stream]")
            .forEach { meta ->
                val videoUrl = meta.attr("abs:content").ifBlank { meta.attr("content") }
                    .replace("&amp;", "&")
                    .trim()
                if ((videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) && seenVideoUrls.add(videoUrl)) {
                    videos += MediaFormatOption(
                        id = "reddit_proxy_video_${videos.size + 1}",
                        label = "Reddit Video",
                        sublabel = "MP4 • Proxy",
                        category = MediaCategory.VIDEO,
                        extension = "mp4",
                        directUrl = videoUrl
                    )
                }
            }

        val images = mutableListOf<ImageItem>()
        val seenImageUrls = linkedSetOf<String>()
        doc.select("meta[property=og:image], meta[property=og:image:secure_url], meta[name=twitter:image], meta[name=twitter:image:src]")
            .forEach { meta ->
                val imgUrl = meta.attr("abs:content").ifBlank { meta.attr("content") }
                    .replace("&amp;", "&")
                    .trim()
                if ((imgUrl.startsWith("http://") || imgUrl.startsWith("https://")) &&
                    !isRedditPlaceholderImage(imgUrl) &&
                    seenImageUrls.add(imgUrl)
                ) {
                    images += ImageItem(
                        id = "reddit_proxy_img_${images.size + 1}",
                        url = imgUrl,
                        label = if (images.isEmpty()) "Ana Görsel (HD)" else "Görsel ${images.size + 1}",
                        extension = UrlSanitizer.guessImageExtension(imgUrl)
                    )
                }
            }

        if (videos.isEmpty() && (images.isEmpty() || isRedditAgeGateTitle(title))) {
            return null
        }

        return ExtractedMedia(
            originalUrl = canonicalUrl,
            title = title,
            author = author,
            durationSeconds = null,
            thumbnailUrl = images.firstOrNull()?.url,
            platform = PlatformType.REDDIT,
            videoFormats = videos,
            audioFormats = emptyList(),
            imageItems = images
        )
    }

    /**
     * Pinterest, Instagram, Facebook ve genel web sayfalarından OpenGraph, Twitter Card
     * ve yüksek çözünürlüklü `<img>` etiketlerini Jsoup ile ayıklar.
     */
    /**
     * Instagram gönderileri (tekil fotoğraf, carousel/çoklu galeri ve Reels/Video) için
     * herkese açık resmi `/embed/captioned/` sayfasını ayrıştırır; başarısız olursa genel web fallback'ine düşer.
     */
    private fun extractInstagram(url: String): ExtractedMedia {
        // Instagram /share/... kısa paylaşım linklerini gerçek /p/ veya /reel/ URL'sine çöz
        val resolvedUrl = if (url.contains("/share/", ignoreCase = true)) {
            resolveRedirectUrl(url, FACEBOOK_BOT_USER_AGENT)
                ?: resolveRedirectUrl(url, DESKTOP_USER_AGENT)
                ?: url
        } else {
            url
        }

        if (isInstagramStoryOrProfileUrl(resolvedUrl)) {
            runCatching { extractInstagramStoryOrProfile(resolvedUrl) }
                .getOrNull()
                ?.let { return it }
        }

        val shortcode = extractInstagramShortcode(resolvedUrl)
            ?: extractInstagramShortcode(url)
            ?: return extractFromWebPage(resolvedUrl, PlatformType.INSTAGRAM)

        // Katman 1: Canlı doğrulamalı Instagram embed proxy motoru (vxinstagram / eeinstagram)
        runCatching { extractInstagramViaEmbedProxy(resolvedUrl, shortcode) }
            .getOrNull()
            ?.let { return it }

        // Katman 2: Çerezsiz bot User-Agent HTML çıkarımı (facebookexternalhit / WhatsApp)
        runCatching { extractInstagramViaBotHtml(resolvedUrl, shortcode) }
            .getOrNull()
            ?.let { return it }

        // Katman 3: Instagram Misafir GraphQL API + Resmi Embed sayfası yedeği
        runCatching { extractInstagramGraphQl(resolvedUrl, shortcode) }
            .getOrNull()
            ?.let { return it }

        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
        val html = runCatching { executeGet(embedUrl, DESKTOP_USER_AGENT) }.getOrNull()
            ?: runCatching { executeGet("https://www.instagram.com/p/$shortcode/embed/", DESKTOP_USER_AGENT) }.getOrNull()

        if (!html.isNullOrBlank()) {
            val fromEmbed = runCatching { parseInstagramEmbedHtml(resolvedUrl, embedUrl, html) }.getOrNull()
            if (fromEmbed != null) return fromEmbed
        }

        return extractFromWebPage(resolvedUrl, PlatformType.INSTAGRAM)
    }

    /**
     * Instagram Hikaye (`/stories/`), Öne Çıkan (`/highlights/`, `/s/`) ve Profil sayfalarından
     * oturum çerezleri (varsa) ile GraphQL / Web Profile API / HTML JSON üzerinden görsel,
     * video ve oynatma listesi öğelerini çıkarır.
     */
    private fun extractInstagramStoryOrProfile(url: String): ExtractedMedia? {
        val cookieHeader = readInstagramCookieHeader()
        val csrfToken = cookieHeader
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("csrftoken=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val apiHeaders = buildMap<String, String> {
            put("X-IG-App-ID", "936619743392459")
            put("X-Requested-With", "XMLHttpRequest")
            put("Accept", "*/*")
            put("Origin", "https://www.instagram.com")
            put("Referer", "https://www.instagram.com/")
            if (!csrfToken.isNullOrBlank()) {
                put("X-CSRFToken", csrfToken)
            }
            if (!cookieHeader.isNullOrBlank()) {
                put("Cookie", cookieHeader)
            }
        }

        val images = mutableListOf<ImageItem>()
        val videos = mutableListOf<MediaFormatOption>()
        val entries = mutableListOf<PlaylistEntry>()
        val seenImageUrls = linkedSetOf<String>()
        val seenVideoUrls = linkedSetOf<String>()
        val seenEntryUrls = linkedSetOf<String>()

        var resolvedTitle: String? = null
        var resolvedAuthor: String? = null
        var profileThumb: String? = null

        fun addSafeImage(raw: String?, label: String, w: Int? = null, h: Int? = null) {
            val cleaned = raw?.replace("\\u0026", "&")?.replace("\\/", "/")?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
            if (!NetworkSecurityGuard.isSafePublicUrl(cleaned)) return
            if (seenImageUrls.add(cleaned)) {
                images += ImageItem(
                    id = "ig_sp_img_${images.size + 1}",
                    url = cleaned,
                    label = "$label${resolutionSuffix(w, h)}",
                    extension = UrlSanitizer.guessImageExtension(cleaned),
                    width = w,
                    height = h
                )
            }
        }

        fun addSafeVideo(raw: String?, height: Int = 1080, labelPrefix: String = "Instagram Hikaye Video") {
            val cleaned = raw?.replace("\\u0026", "&")?.replace("\\/", "/")?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
            if (!NetworkSecurityGuard.isSafePublicUrl(cleaned)) return
            if (seenVideoUrls.add(cleaned)) {
                val idx = videos.size + 1
                val h = if (height > 0) height else 1080
                videos += MediaFormatOption(
                    id = "ig_sp_video_$idx",
                    label = if (idx == 1) "${h}p $labelPrefix" else "${h}p $labelPrefix ($idx)",
                    sublabel = "MP4 • Instagram",
                    category = MediaCategory.VIDEO,
                    extension = "mp4",
                    resolutionHeight = h,
                    directUrl = cleaned
                )
            }
        }

        val pathSegments = extractUrlPathSegments(url)
        val isStories = pathSegments.firstOrNull()?.equals("stories", ignoreCase = true) == true
        val isHighlights = pathSegments.any { it.equals("highlights", ignoreCase = true) } ||
            pathSegments.firstOrNull()?.equals("s", ignoreCase = true) == true

        val highlightId = if (isHighlights) {
            pathSegments.lastOrNull { it.all(Char::isDigit) && it.length >= 6 }
        } else {
            null
        }
        val storyMediaId = if (isStories && !isHighlights) {
            pathSegments.getOrNull(2)?.takeIf { it.all(Char::isDigit) && it.length >= 10 }
        } else {
            null
        }

        val username = when {
            isStories && pathSegments.size >= 2 && !pathSegments[1].equals("highlights", ignoreCase = true) ->
                pathSegments[1]
            !isStories && !isHighlights && pathSegments.isNotEmpty() ->
                pathSegments[0].takeIf { it.lowercase(Locale.ROOT) !in INSTAGRAM_RESERVED_PATHS }
            else -> null
        }

        // 0) Doğrudan Hikaye Media ID varsa (/stories/user/4035391891913590291) önce /api/v1/media/{id}/info/ dene
        if (!storyMediaId.isNullOrBlank() && !cookieHeader.isNullOrBlank()) {
            val mediaInfoUrls = listOf(
                "https://www.instagram.com/api/v1/media/$storyMediaId/info/",
                "https://i.instagram.com/api/v1/media/$storyMediaId/info/"
            )
            for (infoUrl in mediaInfoUrls) {
                val infoStr = runCatching {
                    executeGetWithHeaders(infoUrl, DESKTOP_USER_AGENT, apiHeaders)
                }.getOrNull() ?: continue
                runCatching {
                    val items = JSONObject(infoStr).optJSONArray("items")
                    val item = items?.optJSONObject(0) ?: return@runCatching
                    val itemUser = item.optJSONObject("user")
                    val uname = itemUser?.optString("username")?.takeIf { it.isNotBlank() } ?: username
                    val fname = itemUser?.optString("full_name")?.takeIf { it.isNotBlank() }
                    if (!uname.isNullOrBlank()) {
                        resolvedAuthor = "@$uname"
                        resolvedTitle = "${fname ?: "@$uname"} Hikayesi"
                    }
                    val videoVersions = item.optJSONArray("video_versions")
                    if (videoVersions != null && videoVersions.length() > 0) {
                        val bestVid = videoVersions.optJSONObject(0)
                        addSafeVideo(
                            bestVid?.optString("url"),
                            bestVid?.optInt("height", 1080) ?: 1080,
                            "Hikaye Video"
                        )
                    }
                    val candidates = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val bestImg = candidates.optJSONObject(0)
                        val imgUrl = bestImg?.optString("url")
                        val w = bestImg?.optInt("width", 0)?.takeIf { it > 0 }
                        val h = bestImg?.optInt("height", 0)?.takeIf { it > 0 }
                        if (profileThumb == null && !imgUrl.isNullOrBlank()) profileThumb = imgUrl
                        addSafeImage(imgUrl, "Instagram Hikaye", w, h)
                    }
                }
                if (videos.isNotEmpty() || images.isNotEmpty()) break
            }
        }

        var userId: String? = null

        // 1) Kullanıcı profil API'si (profil fotoğrafı + son gönderiler + userId)
        if (!username.isNullOrBlank() && (videos.isEmpty() && (!isStories || images.isEmpty()))) {
            resolvedAuthor = "@$username"
            val profileApiUrl = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
            val profileJsonStr = runCatching {
                executeGetWithHeaders(profileApiUrl, DESKTOP_USER_AGENT, apiHeaders)
            }.getOrNull()

            if (!profileJsonStr.isNullOrBlank()) {
                runCatching {
                    val userObj = JSONObject(profileJsonStr)
                        .optJSONObject("data")
                        ?.optJSONObject("user")
                    if (userObj != null) {
                        userId = userObj.optString("id").takeIf { it.isNotBlank() }
                        val fullName = userObj.optString("full_name").takeIf { it.isNotBlank() }
                        resolvedTitle = when {
                            isStories -> "${fullName ?: "@$username"} Hikayeleri"
                            else -> "${fullName ?: "@$username"} (@$username)"
                        }
                        val hdPic = userObj.optString("profile_pic_url_hd").takeIf { it.startsWith("http") }
                            ?: userObj.optString("profile_pic_url").takeIf { it.startsWith("http") }
                        if (hdPic != null) {
                            profileThumb = hdPic
                            addSafeImage(hdPic, "Profil Fotoğrafı (HD)")
                        }

                        // Profil gönderilerini galeri/playlist öğeleri olarak topla
                        val edges = userObj.optJSONObject("edge_owner_to_timeline_media")?.optJSONArray("edges")
                        if (edges != null && !isStories) {
                            val limit = minOf(edges.length(), 24)
                            for (i in 0 until limit) {
                                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                                val code = node.optString("shortcode").takeIf { it.isNotBlank() }
                                val displayUrl = node.optString("display_url").takeIf { it.startsWith("http") }
                                val dims = node.optJSONObject("dimensions")
                                val w = dims?.optInt("width", 0)?.takeIf { it > 0 }
                                val h = dims?.optInt("height", 0)?.takeIf { it > 0 }
                                val caption = node.optJSONObject("edge_media_to_caption")
                                    ?.optJSONArray("edges")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("node")
                                    ?.optString("text")
                                    ?.trim()
                                    ?.take(80)
                                    ?.takeIf { it.isNotBlank() }

                                if (displayUrl != null && images.size < MAX_WEB_IMAGES) {
                                    addSafeImage(displayUrl, "Gönderi ${i + 1}", w, h)
                                }
                                if (node.optBoolean("is_video", false)) {
                                    addSafeVideo(node.optString("video_url"), minOf(w ?: 1080, h ?: 1080), "Instagram Profil Video")
                                }
                                if (code != null) {
                                    val postUrl = if (node.optBoolean("is_video", false)) {
                                        "https://www.instagram.com/reel/$code/"
                                    } else {
                                        "https://www.instagram.com/p/$code/"
                                    }
                                    if (seenEntryUrls.add(postUrl)) {
                                        entries += PlaylistEntry(
                                            id = code,
                                            url = postUrl,
                                            title = caption ?: "@$username Gönderi ${i + 1}",
                                            author = "@$username",
                                            durationSeconds = node.optDouble("video_duration", 0.0).toLong().takeIf { it > 0L },
                                            thumbnailUrl = displayUrl?.takeIf { NetworkSecurityGuard.isSafePublicUrl(it) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2) Hikaye veya Öne Çıkan (Highlight) Reels Media API (oturum çerezleri ile)
        val reelId = when {
            !highlightId.isNullOrBlank() -> "highlight:$highlightId"
            isStories && !userId.isNullOrBlank() -> userId
            else -> null
        }
        if (!reelId.isNullOrBlank() && !cookieHeader.isNullOrBlank()) {
            val encodedReelId = java.net.URLEncoder.encode(reelId, "UTF-8")
            val reelsApiUrl = "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$encodedReelId"
            val reelsJsonStr = runCatching {
                executeGetWithHeaders(reelsApiUrl, DESKTOP_USER_AGENT, apiHeaders)
            }.getOrNull()

            if (!reelsJsonStr.isNullOrBlank()) {
                runCatching {
                    val root = JSONObject(reelsJsonStr)
                    val reelsArr = root.optJSONArray("reels_media")
                    val reelObj = reelsArr?.optJSONObject(0)
                        ?: root.optJSONObject("reels")?.optJSONObject(reelId)
                    val items = reelObj?.optJSONArray("items")
                    if (items != null) {
                        val reelTitle = reelObj.optString("title").takeIf { it.isNotBlank() }
                        if (resolvedTitle.isNullOrBlank() && reelTitle != null) {
                            resolvedTitle = reelTitle
                        }
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val videoVersions = item.optJSONArray("video_versions")
                            if (videoVersions != null && videoVersions.length() > 0) {
                                val bestVid = videoVersions.optJSONObject(0)
                                val vidUrl = bestVid?.optString("url")
                                val vHeight = bestVid?.optInt("height", 1080) ?: 1080
                                addSafeVideo(vidUrl, vHeight, if (isHighlights) "Öne Çıkan Video" else "Hikaye Video")
                            }
                            val candidates = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val bestImg = candidates.optJSONObject(0)
                                val imgUrl = bestImg?.optString("url")
                                val w = bestImg?.optInt("width", 0)?.takeIf { it > 0 }
                                val h = bestImg?.optInt("height", 0)?.takeIf { it > 0 }
                                addSafeImage(imgUrl, if (isHighlights) "Öne Çıkan ${i + 1}" else "Hikaye ${i + 1}", w, h)
                            }
                        }
                    }
                }
            }
        }

        // 3) API sonuç vermediyse sayfa HTML / gömülü JSON yedeği
        if (images.isEmpty() && videos.isEmpty() && entries.isEmpty()) {
            val htmlHeaders = if (!cookieHeader.isNullOrBlank()) mapOf("Cookie" to cookieHeader) else emptyMap()
            val html = runCatching { executeGetWithHeaders(url, DESKTOP_USER_AGENT, htmlHeaders) }.getOrNull()
                ?: runCatching { executeGet(url, FACEBOOK_BOT_USER_AGENT) }.getOrNull()
            if (!html.isNullOrBlank()) {
                val doc = Jsoup.parse(html, url)
                val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                val ogDesc = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
                val (parsedTitle, parsedAuthor) = parseInstagramTitleAndAuthor(ogTitle, ogDesc)
                if (resolvedTitle.isNullOrBlank()) resolvedTitle = parsedTitle
                if (resolvedAuthor.isNullOrBlank()) resolvedAuthor = parsedAuthor

                val unescaped = html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                INSTAGRAM_VIDEO_URL_REGEX.findAll(unescaped).forEach { m ->
                    addSafeVideo(m.groupValues.getOrNull(1))
                }
                doc.select("meta[property=og:image:secure_url], meta[property=og:image]").forEach { meta ->
                    addSafeImage(meta.attr("abs:content").ifBlank { meta.attr("content") }, "Instagram Görsel")
                }
                INSTAGRAM_DISPLAY_URL_REGEX.findAll(unescaped).forEach { m ->
                    if (images.size < MAX_WEB_IMAGES) {
                        addSafeImage(m.groupValues.getOrNull(1), "Görsel ${images.size + 1}")
                    }
                }
            }
        }

        if (images.isEmpty() && videos.isEmpty() && entries.isEmpty()) {
            return null
        }

        val finalVideos = appendDownscaleIfSingleResolution(videos)
        val audios = if (finalVideos.isNotEmpty()) {
            YtDlpEngine.buildDefaultAudioFormats(
                formats = emptyList(),
                durationSeconds = null,
                directSourceUrl = finalVideos.first().directUrl
            )
        } else {
            emptyList()
        }

        val fallbackTitle = when {
            isHighlights -> "Instagram Öne Çıkan"
            isStories -> "Instagram Hikayesi"
            !username.isNullOrBlank() -> "@$username Instagram Profili"
            else -> "Instagram İçeriği"
        }

        return ExtractedMedia(
            originalUrl = url,
            title = resolvedTitle?.takeIf { !isGenericInstagramTitle(it) } ?: fallbackTitle,
            author = resolvedAuthor,
            durationSeconds = null,
            thumbnailUrl = profileThumb ?: images.firstOrNull()?.url ?: entries.firstOrNull()?.thumbnailUrl,
            platform = PlatformType.INSTAGRAM,
            videoFormats = finalVideos,
            audioFormats = audios,
            imageItems = images,
            isPlaylist = entries.size >= 2,
            playlistEntries = entries
        )
    }

    private fun readInstagramCookieHeader(): String? {
        val merged = linkedMapOf<String, String>()

        val cookieFile = cookieRepository?.getCookieFile()
        if (cookieFile != null && cookieFile.exists()) {
            runCatching {
                cookieFile.useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty()) return@forEach
                        val normalized = when {
                            line.startsWith("#HttpOnly_", ignoreCase = true) ->
                                line.removePrefix("#HttpOnly_").removePrefix("#httponly_")
                            line.startsWith("#") -> return@forEach
                            else -> line
                        }
                        val fields = normalized.split('\t')
                        if (fields.size >= 7 && fields[0].contains("instagram.com", ignoreCase = true)) {
                            val name = fields[5].trim()
                            val value = fields[6].trim()
                            if (name.isNotBlank() && value.isNotBlank()) {
                                merged[name] = value
                            }
                        }
                    }
                }
            }
        }

        // Canlı WebView CookieManager çerezlerini de birleştir (güncel sessionid/csrftoken öncelikli)
        runCatching {
            val rawWebViewCookies = android.webkit.CookieManager.getInstance()
                ?.getCookie("https://www.instagram.com")
                ?.takeIf { it.isNotBlank() }
            rawWebViewCookies?.split(';')?.forEach { part ->
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val name = trimmed.substring(0, eq).trim()
                    val value = trimmed.substring(eq + 1).trim()
                    if (name.isNotBlank() && value.isNotBlank()) {
                        merged[name] = value
                    }
                }
            }
        }

        return if (merged.isNotEmpty()) {
            merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else {
            null
        }
    }

    private fun executeGetWithHeaders(
        url: String,
        userAgent: String = DESKTOP_USER_AGENT,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val safeUrl = UrlSanitizer.extractFirstValidUrl(url)
            ?: throw IllegalArgumentException("Geçersiz veya güvensiz bağlantı.")

        val builder = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")

        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        okHttpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("İstek başarısız oldu (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("Boş sunucu yanıtı.")
            val contentLength = body.contentLength()
            if (contentLength > MAX_TEXT_RESPONSE_BYTES) {
                throw IOException("Sunucu yanıtı çok büyük.")
            }
            return readLimitedUtf8(body)
        }
    }

    private fun extractInstagramShortcode(rawUrl: String): String? {
        INSTAGRAM_SHORTCODE_REGEX.find(rawUrl)?.groupValues?.getOrNull(1)?.let { return it }
        val decoded = runCatching { java.net.URLDecoder.decode(rawUrl, "UTF-8") }.getOrNull() ?: return null
        return INSTAGRAM_SHORTCODE_REGEX.find(decoded)?.groupValues?.getOrNull(1)
    }

    private fun extractInstagramGraphQl(originalUrl: String, shortcode: String): ExtractedMedia? {
        val docIds = listOf("10015901848480474", "8845758582119845")
        for (docId in docIds) {
            val result = runCatching {
                executeInstagramGraphQlPost(originalUrl, shortcode, docId)
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun executeInstagramGraphQlPost(
        originalUrl: String,
        shortcode: String,
        docId: String
    ): ExtractedMedia? {
        val variablesJson = """{"shortcode":"$shortcode","child_comment_count":3,"fetch_comment_count":40,"parent_comment_count":24,"has_threaded_comments":true}"""
        val formBody = okhttp3.FormBody.Builder()
            .add("av", "0")
            .add("__d", "www")
            .add("__user", "0")
            .add("__a", "1")
            .add("__req", "1")
            .add("dpr", "1")
            .add("__ccg", "EXCELLENT")
            .add("lsd", "AVqbxe3J_YA")
            .add("fb_api_caller_class", "RelayModern")
            .add("fb_api_req_friendly_name", "PolarisPostActionLoadPostQueryQuery")
            .add("variables", variablesJson)
            .add("server_timestamps", "true")
            .add("doc_id", docId)
            .build()

        val request = Request.Builder()
            .url("https://www.instagram.com/graphql/query")
            .post(formBody)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "*/*")
            .header("X-IG-App-ID", "936619743392459")
            .header("X-FB-LSD", "AVqbxe3J_YA")
            .header("X-ASBD-ID", "129477")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/p/$shortcode/")
            .build()

        val rawJson = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_TEXT_RESPONSE_BYTES) return null
            readLimitedUtf8(body)
        }.removePrefix("for (;;);").trim()

        val data = JSONObject(rawJson).optJSONObject("data") ?: return null
        val media = data.optJSONObject("xdt_shortcode_media")
            ?: data.optJSONObject("shortcode_media")
            ?: return null

        val author = media.optJSONObject("owner")?.optString("username")?.takeIf { it.isNotBlank() }
        val caption = media.optJSONObject("edge_media_to_caption")
            ?.optJSONArray("edges")
            ?.optJSONObject(0)
            ?.optJSONObject("node")
            ?.optString("text")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val title = caption?.take(80) ?: author?.let { "@$it Instagram Gönderisi" } ?: "Instagram Gönderisi"
        val durationSeconds = media.optDouble("video_duration", 0.0).toLong().takeIf { it > 0L }

        val images = mutableListOf<ImageItem>()
        val videos = mutableListOf<MediaFormatOption>()
        val seenImages = linkedSetOf<String>()
        val seenVideos = linkedSetOf<String>()

        fun collectNode(node: JSONObject, idxLabel: String) {
            val dims = node.optJSONObject("dimensions")
            val w = dims?.optInt("width", 0)?.takeIf { it > 0 }
            val h = dims?.optInt("height", 0)?.takeIf { it > 0 }
            val effectiveH = if (w != null && h != null) minOf(w, h) else (h ?: 1080)

            if (node.optBoolean("is_video", false)) {
                val videoUrl = node.optString("video_url").takeIf { it.startsWith("http") }
                if (videoUrl != null && seenVideos.add(videoUrl)) {
                    videos += MediaFormatOption(
                        id = "ig_gql_video_${videos.size + 1}",
                        label = "${effectiveH}p Instagram Video",
                        sublabel = "Instagram Doğrudan",
                        category = MediaCategory.VIDEO,
                        extension = "mp4",
                        resolutionHeight = effectiveH,
                        directUrl = videoUrl
                    )
                }
            }
            val displayUrl = node.optString("display_url").takeIf { it.startsWith("http") }
            if (displayUrl != null && seenImages.add(displayUrl)) {
                images += ImageItem(
                    id = "ig_gql_img_${images.size + 1}",
                    url = displayUrl,
                    label = "$idxLabel${resolutionSuffix(w, h)}",
                    extension = UrlSanitizer.guessImageExtension(displayUrl),
                    width = w,
                    height = h
                )
            }
        }

        val sidecarEdges = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (sidecarEdges != null && sidecarEdges.length() > 0) {
            for (i in 0 until sidecarEdges.length()) {
                val node = sidecarEdges.optJSONObject(i)?.optJSONObject("node") ?: continue
                collectNode(node, "Fotoğraf ${i + 1}")
            }
        } else {
            collectNode(media, "Orijinal Fotoğraf")
        }

        if (videos.isEmpty() && images.isEmpty()) return null

        val finalVideos = appendDownscaleIfSingleResolution(videos)
        val audioOptions = if (finalVideos.isNotEmpty()) {
            YtDlpEngine.buildDefaultAudioFormats(
                formats = emptyList(),
                durationSeconds = durationSeconds,
                directSourceUrl = finalVideos.first().directUrl
            )
        } else {
            emptyList()
        }

        return ExtractedMedia(
            originalUrl = originalUrl,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            thumbnailUrl = images.firstOrNull()?.url,
            platform = PlatformType.INSTAGRAM,
            videoFormats = finalVideos,
            audioFormats = audioOptions,
            imageItems = images
        )
    }

    private data class ResolvedOffloadMedia(
        val url: String,
        val isVideo: Boolean
    )

    private data class InstagramBotMeta(
        val title: String?,
        val author: String?,
        val thumbUrl: String?
    )

    internal fun canonicalMediaKey(rawUrl: String): String {
        val cacheKeyMatch = IG_CACHE_KEY_REGEX.find(rawUrl)?.groupValues?.getOrNull(1)
        if (!cacheKeyMatch.isNullOrBlank()) {
            val decoded = runCatching {
                java.net.URLDecoder.decode(cacheKeyMatch.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(cacheKeyMatch).trim()
            if (decoded.isNotBlank()) {
                return "ig:$decoded"
            }
        }
        val withoutQuery = rawUrl.substringBefore('?')
        val lastSegment = withoutQuery.substringAfterLast('/').lowercase(Locale.US)
        return lastSegment.ifBlank { withoutQuery }
    }

    internal fun isGenericInstagramTitle(title: String?): Boolean {
        val trimmed = title?.trim()?.lowercase(Locale.ROOT) ?: return true
        if (trimmed.isBlank()) return true
        if (trimmed in GENERIC_INSTAGRAM_TITLES) return true
        return trimmed.contains("log in")
    }

    internal fun parseInstagramTitleAndAuthor(
        rawOgTitle: String?,
        rawOgDescription: String?
    ): Pair<String?, String?> {
        val ogTitle = rawOgTitle?.trim()?.takeIf { it.isNotBlank() }
        val ogDesc = rawOgDescription?.trim()?.takeIf { it.isNotBlank() }

        var parsedAuthor: String? = null
        var parsedTitle: String? = null

        if (ogTitle != null && !ogTitle.contains("log in", ignoreCase = true)) {
            val onIgIdx = ogTitle.indexOf(" on Instagram:", ignoreCase = true)
            if (onIgIdx > 0) {
                parsedAuthor = ogTitle.substring(0, onIgIdx).trim()
                    .takeIf { it.isNotBlank() && !isGenericInstagramTitle(it) }
                parsedTitle = ogTitle.substring(onIgIdx + " on Instagram:".length)
                    .trim()
                    .trim('"', '\'', '“', '”', '‘', '’')
                    .takeIf { !isGenericInstagramTitle(it) }
                    ?.take(100)
            } else {
                val bulletIgIdx = ogTitle.indexOf("• Instagram", ignoreCase = true)
                if (bulletIgIdx > 0) {
                    parsedAuthor = ogTitle.substring(0, bulletIgIdx).trim()
                        .takeIf { it.isNotBlank() && !isGenericInstagramTitle(it) }
                } else if (!isGenericInstagramTitle(ogTitle)) {
                    parsedTitle = ogTitle.take(100)
                }
            }
        }

        if (ogDesc != null && !ogDesc.contains("log in", ignoreCase = true)) {
            val descMatch = INSTAGRAM_DESC_REGEX.find(ogDesc)
            if (descMatch != null) {
                if (parsedAuthor.isNullOrBlank()) {
                    parsedAuthor = descMatch.groupValues.getOrNull(1)?.trim()
                        ?.takeIf { it.isNotBlank() && !isGenericInstagramTitle(it) }
                }
                if (isGenericInstagramTitle(parsedTitle)) {
                    parsedTitle = descMatch.groupValues.getOrNull(2)?.trim()
                        ?.trim('"', '\'', '“', '”', '‘', '’')
                        ?.takeIf { !isGenericInstagramTitle(it) }
                        ?.take(100)
                }
            } else if (isGenericInstagramTitle(parsedTitle) &&
                !isGenericInstagramTitle(ogDesc) &&
                !ogDesc.startsWith("See Instagram photos and videos", ignoreCase = true)
            ) {
                parsedTitle = ogDesc.take(100)
            }
        }

        return parsedTitle to parsedAuthor
    }

    private fun fetchInstagramBotMetadata(shortcode: String, originalUrl: String): InstagramBotMeta? {
        val isReel = originalUrl.contains("/reel", ignoreCase = true) ||
            originalUrl.contains("/tv/", ignoreCase = true)
        val primaryUrl = if (isReel) {
            "https://www.instagram.com/reel/$shortcode/"
        } else {
            "https://www.instagram.com/p/$shortcode/"
        }
        val agents = listOf(FACEBOOK_BOT_USER_AGENT, WHATSAPP_USER_AGENT)

        for (agent in agents) {
            val html = runCatching { executeGet(primaryUrl, agent) }.getOrNull() ?: continue
            if (html.isBlank()) continue
            val doc = Jsoup.parse(html, primaryUrl)
            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            val ogDesc = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
            val (parsedTitle, parsedAuthor) = parseInstagramTitleAndAuthor(ogTitle, ogDesc)

            val rawThumb = doc.selectFirst("meta[property=og:image:secure_url], meta[property=og:image]")
                ?.let { el -> el.attr("abs:content").ifBlank { el.attr("content") } }
                ?.trim()
            val validThumb = rawThumb
                ?.let { UrlSanitizer.extractFirstValidUrl(it) }
                ?.takeIf { NetworkSecurityGuard.isSafePublicUrl(it) }

            if (parsedTitle != null || parsedAuthor != null || validThumb != null) {
                return InstagramBotMeta(
                    title = parsedTitle,
                    author = parsedAuthor,
                    thumbUrl = validThumb
                )
            }
        }
        return null
    }

    private fun extractInstagramViaEmbedProxy(originalUrl: String, shortcode: String): ExtractedMedia? {
        val isReel = originalUrl.contains("/reel", ignoreCase = true) ||
            originalUrl.contains("/tv/", ignoreCase = true)
        val proxyCandidates = listOfNotNull(
            if (isReel) "https://www.vxinstagram.com/reel/$shortcode" else null,
            "https://www.vxinstagram.com/p/$shortcode",
            if (isReel) "https://www.eeinstagram.com/reel/$shortcode" else null,
            "https://www.eeinstagram.com/p/$shortcode"
        ).distinct()

        for (proxyUrl in proxyCandidates) {
            val html = runCatching { executeGet(proxyUrl, TELEGRAM_BOT_USER_AGENT) }.getOrNull()
                ?: continue
            val doc = Jsoup.parse(html, proxyUrl)
            val rawOgTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            val rawOgDesc = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
            val (initialTitle, initialAuthor) = parseInstagramTitleAndAuthor(rawOgTitle, rawOgDesc)
            var title = initialTitle
            var author = initialAuthor

            val rawVideoUrl = doc.selectFirst("meta[property=og:video:secure_url], meta[property=og:video]")
                ?.attr("abs:content")?.ifBlank {
                    doc.selectFirst("meta[property=og:video:secure_url], meta[property=og:video]")?.attr("content")
                }?.trim()?.takeIf { it.startsWith("http") }
            val rawImageUrl = doc.selectFirst("meta[property=og:image:secure_url], meta[property=og:image]")
                ?.attr("abs:content")?.ifBlank {
                    doc.selectFirst("meta[property=og:image:secure_url], meta[property=og:image]")?.attr("content")
                }?.trim()?.takeIf { it.startsWith("http") }

            val videos = mutableListOf<MediaFormatOption>()
            val images = mutableListOf<ImageItem>()
            val seenUrls = mutableSetOf<String>()
            val seenMediaKeys = mutableSetOf<String>()
            var resolvedThumbUrl: String? = null

            val ogVidW = doc.selectFirst("meta[property=og:video:width]")?.attr("content")?.toIntOrNull()
            val ogVidH = doc.selectFirst("meta[property=og:video:height]")?.attr("content")?.toIntOrNull()
            val detectedProxyRes = when {
                ogVidW != null && ogVidH != null && ogVidW > 0 && ogVidH > 0 -> minOf(ogVidW, ogVidH)
                ogVidH != null && ogVidH > 0 -> ogVidH
                else -> 0
            }

            fun appendResolved(resolved: ResolvedOffloadMedia) {
                val mediaKey = canonicalMediaKey(resolved.url)
                if (!seenUrls.add(resolved.url) || !seenMediaKeys.add(mediaKey)) return
                if (resolved.isVideo) {
                    val index = videos.size + 1
                    val baseLabel = if (detectedProxyRes > 0) "${detectedProxyRes}p Instagram Video" else "Orijinal Kalite (Instagram)"
                    videos += MediaFormatOption(
                        id = "ig_proxy_video_$index",
                        label = if (index == 1) baseLabel else "$baseLabel ($index)",
                        sublabel = "Instagram Doğrudan",
                        category = MediaCategory.VIDEO,
                        extension = "mp4",
                        resolutionHeight = detectedProxyRes,
                        directUrl = resolved.url
                    )
                } else {
                    val index = images.size + 1
                    if (resolvedThumbUrl == null) resolvedThumbUrl = resolved.url
                    images += ImageItem(
                        id = "ig_proxy_img_$index",
                        url = resolved.url,
                        label = if (index == 1) "Orijinal Görsel (HD)" else "Görsel $index (HD)",
                        extension = UrlSanitizer.guessImageExtension(resolved.url)
                    )
                }
            }

            if (rawVideoUrl != null) {
                resolveOffloadMedia(rawVideoUrl)?.let(::appendResolved)
            }

            if (proxyUrl.contains("vxinstagram.com", ignoreCase = true)) {
                if (isReel) {
                    if (rawVideoUrl == null) {
                        resolveOffloadMedia("https://www.vxinstagram.com/offload/$shortcode/0")
                            ?.let(::appendResolved)
                    }
                } else {
                    for (idx in 0..5) {
                        val offloadUrl = "https://www.vxinstagram.com/offload/$shortcode/$idx"
                        val resolved = resolveOffloadMedia(offloadUrl) ?: break
                        if (idx > 0 && canonicalMediaKey(resolved.url) in seenMediaKeys) break
                        appendResolved(resolved)
                    }
                }
            }

            if (rawImageUrl != null && (videos.isEmpty() || images.isEmpty())) {
                val resolvedThumb = resolveOffloadMedia(rawImageUrl)
                if (resolvedThumb != null) {
                    if (!resolvedThumb.isVideo && resolvedThumbUrl == null) {
                        resolvedThumbUrl = resolvedThumb.url
                    }
                    appendResolved(resolvedThumb)
                } else if (NetworkSecurityGuard.isSafePublicUrl(rawImageUrl) &&
                    !rawImageUrl.contains("/offload/", ignoreCase = true)
                ) {
                    if (resolvedThumbUrl == null) resolvedThumbUrl = rawImageUrl
                    val mediaKey = canonicalMediaKey(rawImageUrl)
                    if (seenUrls.add(rawImageUrl) && seenMediaKeys.add(mediaKey)) {
                        val index = images.size + 1
                        images += ImageItem(
                            id = "ig_proxy_img_$index",
                            url = rawImageUrl,
                            label = if (index == 1) "Orijinal Görsel (HD)" else "Görsel $index (HD)",
                            extension = UrlSanitizer.guessImageExtension(rawImageUrl)
                        )
                    }
                }
            }

            if (isReel && videos.isEmpty()) {
                continue
            }

            if (videos.isNotEmpty() || images.isNotEmpty()) {
                if (isGenericInstagramTitle(title) || resolvedThumbUrl == null) {
                    val botMeta = fetchInstagramBotMetadata(shortcode, originalUrl)
                    if (botMeta != null) {
                        if (isGenericInstagramTitle(title) && !isGenericInstagramTitle(botMeta.title)) {
                            title = botMeta.title
                        }
                        if (author.isNullOrBlank() && !botMeta.author.isNullOrBlank()) {
                            author = botMeta.author
                        }
                        if (resolvedThumbUrl == null && botMeta.thumbUrl != null) {
                            resolvedThumbUrl = botMeta.thumbUrl
                        }
                    }
                }

                val finalTitle = title?.takeIf { !isGenericInstagramTitle(it) }
                    ?: author?.let { "$it Instagram Gönderisi" }
                    ?: "Instagram Gönderisi"

                val finalVideos = if (videos.isNotEmpty()) {
                    appendDownscaleIfSingleResolution(videos)
                } else {
                    emptyList()
                }
                val audios = if (finalVideos.isNotEmpty()) {
                    YtDlpEngine.buildDefaultAudioFormats(
                        formats = emptyList(),
                        durationSeconds = null,
                        directSourceUrl = finalVideos.first().directUrl
                    )
                } else {
                    emptyList()
                }
                return ExtractedMedia(
                    originalUrl = originalUrl,
                    title = finalTitle,
                    author = author,
                    durationSeconds = null,
                    thumbnailUrl = resolvedThumbUrl ?: images.firstOrNull()?.url,
                    platform = PlatformType.INSTAGRAM,
                    videoFormats = finalVideos,
                    audioFormats = audios,
                    imageItems = images
                )
            }
        }
        return null
    }

    private fun extractInstagramViaBotHtml(originalUrl: String, shortcode: String): ExtractedMedia? {
        val isReel = originalUrl.contains("/reel", ignoreCase = true) ||
            originalUrl.contains("/tv/", ignoreCase = true)
        val targetUrls = if (isReel) {
            listOf(
                "https://www.instagram.com/reel/$shortcode/",
                "https://www.instagram.com/p/$shortcode/"
            )
        } else {
            listOf(
                "https://www.instagram.com/p/$shortcode/",
                "https://www.instagram.com/reel/$shortcode/"
            )
        }
        val botAgents = listOf(FACEBOOK_BOT_USER_AGENT, WHATSAPP_USER_AGENT)

        for (targetUrl in targetUrls) {
            for (agent in botAgents) {
                val html = runCatching { executeGet(targetUrl, agent) }.getOrNull() ?: continue
                if (html.isBlank()) continue
                val doc = Jsoup.parse(html, targetUrl)
                val rawOgTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                val rawOgDesc = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
                val (parsedTitle, parsedAuthor) = parseInstagramTitleAndAuthor(rawOgTitle, rawOgDesc)
                val title = parsedTitle?.takeIf { !isGenericInstagramTitle(it) }
                    ?: parsedAuthor?.let { "$it Instagram Gönderisi" }
                    ?: "Instagram Gönderisi"

                val unescaped = html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                val videoCandidates = linkedSetOf<String>()
                val imageCandidates = linkedSetOf<String>()

                doc.select("meta[property=og:video:secure_url], meta[property=og:video]")
                    .mapNotNull { it.attr("abs:content").ifBlank { it.attr("content") }.trim().takeIf { u -> u.startsWith("http") } }
                    .forEach { videoCandidates += it }
                INSTAGRAM_VIDEO_URL_REGEX.findAll(unescaped)
                    .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                    .forEach { videoCandidates += it }

                doc.select("meta[property=og:image:secure_url], meta[property=og:image]")
                    .mapNotNull { it.attr("abs:content").ifBlank { it.attr("content") }.trim().takeIf { u -> u.startsWith("http") } }
                    .forEach { imageCandidates += it }
                INSTAGRAM_DISPLAY_URL_REGEX.findAll(unescaped)
                    .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                    .forEach { imageCandidates += it }

                if (imageCandidates.isEmpty()) {
                    SCONTENT_URL_REGEX.findAll(unescaped)
                        .map { it.value.trimEnd('"', '\'', ',', ')') }
                        .filter { url ->
                            !url.contains("s150x150") &&
                                !url.contains("s100x100") &&
                                !url.contains("p50x50") &&
                                !url.contains("profile_pic", ignoreCase = true)
                        }
                        .forEach { imageCandidates += it }
                }

                val safeVideos = videoCandidates
                    .mapNotNull { UrlSanitizer.extractFirstValidUrl(it) }
                    .filter { NetworkSecurityGuard.isSafePublicUrl(it) }
                    .distinctBy { canonicalMediaKey(it) }
                val safeImages = imageCandidates
                    .mapNotNull { UrlSanitizer.extractFirstValidUrl(it) }
                    .filter { NetworkSecurityGuard.isSafePublicUrl(it) }
                    .distinctBy { canonicalMediaKey(it) }

                if (isReel && safeVideos.isEmpty()) continue
                if (safeVideos.isEmpty() && safeImages.isEmpty()) continue

                val botVidW = doc.selectFirst("meta[property=og:video:width]")?.attr("content")?.toIntOrNull()
                val botVidH = doc.selectFirst("meta[property=og:video:height]")?.attr("content")?.toIntOrNull()
                val detectedBotRes = when {
                    botVidW != null && botVidH != null && botVidW > 0 && botVidH > 0 -> minOf(botVidW, botVidH)
                    botVidH != null && botVidH > 0 -> botVidH
                    else -> 0
                }
                val videoFormats = if (safeVideos.isNotEmpty()) {
                    val baseLabel = if (detectedBotRes > 0) "${detectedBotRes}p Instagram Video" else "Orijinal Kalite (Instagram)"
                    appendDownscaleIfSingleResolution(
                        safeVideos.mapIndexed { idx, videoUrl ->
                            MediaFormatOption(
                                id = "ig_bot_video_${idx + 1}",
                                label = if (idx == 0) baseLabel else "$baseLabel (${idx + 1})",
                                sublabel = "Instagram Doğrudan",
                                category = MediaCategory.VIDEO,
                                extension = "mp4",
                                resolutionHeight = detectedBotRes,
                                directUrl = videoUrl
                            )
                        }
                    )
                } else {
                    emptyList()
                }

                val imageItems = safeImages.take(MAX_WEB_IMAGES).mapIndexed { idx, imgUrl ->
                    ImageItem(
                        id = "ig_bot_img_${idx + 1}",
                        url = imgUrl,
                        label = if (idx == 0) "Orijinal Görsel (HD)" else "Görsel ${idx + 1} (HD)",
                        extension = UrlSanitizer.guessImageExtension(imgUrl)
                    )
                }

                val audios = if (videoFormats.isNotEmpty()) {
                    YtDlpEngine.buildDefaultAudioFormats(
                        formats = emptyList(),
                        durationSeconds = null,
                        directSourceUrl = videoFormats.first().directUrl
                    )
                } else {
                    emptyList()
                }

                return ExtractedMedia(
                    originalUrl = originalUrl,
                    title = title,
                    author = parsedAuthor,
                    durationSeconds = null,
                    thumbnailUrl = imageItems.firstOrNull()?.url,
                    platform = PlatformType.INSTAGRAM,
                    videoFormats = videoFormats,
                    audioFormats = audios,
                    imageItems = imageItems
                )
            }
        }
        return null
    }

    private fun resolveOffloadMedia(rawUrl: String): ResolvedOffloadMedia? {
        val safeUrl = UrlSanitizer.extractFirstValidUrl(rawUrl) ?: return null
        if (!NetworkSecurityGuard.isSafePublicUrl(safeUrl)) return null
        return runCatching {
            val request = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", TELEGRAM_BOT_USER_AGENT)
                .header("Range", "bytes=0-0")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val finalUrl = UrlSanitizer.extractFirstValidUrl(response.request.url.toString())
                    ?: return@runCatching null
                if (!NetworkSecurityGuard.isSafePublicUrl(finalUrl)) return@runCatching null
                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
                val isVideo = when {
                    contentType.startsWith("video/") -> true
                    contentType.startsWith("image/") || isImageContentType(contentType) -> false
                    contentType.contains("html") || contentType.contains("json") || contentType.contains("text/") ->
                        return@runCatching null
                    finalUrl.contains(".mp4", ignoreCase = true) || rawUrl.contains("/videos/", ignoreCase = true) -> true
                    finalUrl.contains(".jpg", ignoreCase = true) ||
                        finalUrl.contains(".jpeg", ignoreCase = true) ||
                        finalUrl.contains(".png", ignoreCase = true) ||
                        finalUrl.contains(".webp", ignoreCase = true) -> false
                    else -> return@runCatching null
                }
                ResolvedOffloadMedia(url = finalUrl, isVideo = isVideo)
            }
        }.getOrNull()
    }

    private fun parseInstagramEmbedHtml(url: String, embedUrl: String, html: String): ExtractedMedia? {

        val doc = Jsoup.parse(html, embedUrl)
        val author = doc.selectFirst(".UsernameText, .HoverCardUserName")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
        val caption = doc.selectFirst(".Caption")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
        val title = caption?.take(80) ?: author?.let { "@$it Instagram Gönderisi" } ?: "Instagram Gönderisi"

        val images = mutableListOf<ImageItem>()
        val videos = mutableListOf<MediaFormatOption>()
        val seenImageUrls = linkedSetOf<String>()
        val seenVideoUrls = linkedSetOf<String>()

        fun addImage(rawUrl: String?, label: String, width: Int? = null, height: Int? = null) {
            val cleaned = rawUrl?.replace("\\u0026", "&")?.replace("\\/", "/")?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
            if (cleaned.contains("s150x150") || cleaned.contains("profile_pic")) return
            if (seenImageUrls.add(cleaned)) {
                images += ImageItem(
                    id = "ig_img_${images.size + 1}",
                    url = cleaned,
                    label = "$label${resolutionSuffix(width, height)}",
                    extension = UrlSanitizer.guessImageExtension(cleaned),
                    width = width,
                    height = height
                )
            }
        }

        fun addVideo(rawUrl: String?, height: Int = 0) {
            val cleaned = rawUrl?.replace("\\u0026", "&")?.replace("\\/", "/")?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
            if (seenVideoUrls.add(cleaned)) {
                val label = if (height > 0) "${height}p Instagram Video" else "Instagram Video (HD)"
                videos += MediaFormatOption(
                    id = "ig_video_${videos.size + 1}",
                    label = label,
                    sublabel = "MP4 • Instagram Doğrudan",
                    category = MediaCategory.VIDEO,
                    extension = "mp4",
                    resolutionHeight = if (height > 0) height else 1080,
                    directUrl = cleaned
                )
            }
        }

        // 1) Embed HTML içindeki gömülü gql_data / shortcode_media JSON'undan carousel + video çıkar
        val mediaJsonMatch = INSTAGRAM_GQL_JSON_REGEX.find(html)
        if (mediaJsonMatch != null) {
            runCatching {
                val rawJson = mediaJsonMatch.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val mediaObj = JSONObject(rawJson)
                val sidecar = mediaObj.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
                if (sidecar != null && sidecar.length() > 0) {
                    for (i in 0 until sidecar.length()) {
                        val node = sidecar.optJSONObject(i)?.optJSONObject("node") ?: continue
                        val dims = node.optJSONObject("dimensions")
                        val w = dims?.optInt("width", 0)?.takeIf { it > 0 }
                        val h = dims?.optInt("height", 0)?.takeIf { it > 0 }
                        if (node.optBoolean("is_video", false)) {
                            addVideo(node.optString("video_url"), minOf(w ?: 0, h ?: 0))
                        }
                        addImage(node.optString("display_url"), "Fotoğraf ${i + 1}", w, h)
                    }
                } else {
                    val dims = mediaObj.optJSONObject("dimensions")
                    val w = dims?.optInt("width", 0)?.takeIf { it > 0 }
                    val h = dims?.optInt("height", 0)?.takeIf { it > 0 }
                    if (mediaObj.optBoolean("is_video", false)) {
                        addVideo(mediaObj.optString("video_url"), minOf(w ?: 0, h ?: 0))
                    }
                    addImage(mediaObj.optString("display_url"), "Orijinal Fotoğraf", w, h)
                }
            }
        }

        // 2) JSON parse edilemezse veya tekil video/fotoğraf HTML içindeyse unescaped HTML üzerinde regex + Jsoup ile yakala
        val unescapedHtml = html
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\\\u0026", "&")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        if (videos.isEmpty()) {
            INSTAGRAM_VIDEO_URL_REGEX.findAll(unescapedHtml).forEach { match ->
                addVideo(match.groupValues[1])
            }
            doc.select("video[src], video source[src]").forEach { el ->
                addVideo(el.attr("abs:src").ifBlank { el.attr("src") })
            }
        }

        if (images.isEmpty()) {
            doc.select("img.EmbeddedMediaImage, img[src*=cdninstagram], img[src*=fbcdn]").forEach { img ->
                val src = img.attr("abs:src").ifBlank { img.attr("src") }
                addImage(src, if (images.isEmpty()) "Ana Görsel (HD)" else "Fotoğraf ${images.size + 1}")
            }
            INSTAGRAM_DISPLAY_URL_REGEX.findAll(unescapedHtml).forEach { match ->
                addImage(match.groupValues[1], if (images.isEmpty()) "Ana Görsel (HD)" else "Fotoğraf ${images.size + 1}")
            }
        }

        if (videos.isEmpty() && (images.isEmpty() || isRedditAgeGateTitle(title))) {
            return null
        }

        val finalVideos = appendDownscaleIfSingleResolution(videos)
        val audioOptions = if (finalVideos.isNotEmpty()) {
            YtDlpEngine.buildDefaultAudioFormats(
                formats = emptyList(),
                durationSeconds = null,
                directSourceUrl = finalVideos.first().directUrl
            )
        } else {
            emptyList()
        }

        return ExtractedMedia(
            originalUrl = url,
            title = title,
            author = author,
            durationSeconds = null,
            thumbnailUrl = images.firstOrNull()?.url,
            platform = PlatformType.INSTAGRAM,
            videoFormats = finalVideos,
            audioFormats = audioOptions,
            imageItems = images
        )
    }

    private fun extractFromWebPage(url: String, platform: PlatformType): ExtractedMedia {
        val html = executeGet(url)
        val doc = Jsoup.parse(html, url)

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=twitter:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }
            ?: "${platform.displayName} İçeriği"

        val author = doc.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.takeIf { it.isNotBlank() }

        val candidateUrls = linkedSetOf<String>()

        // 1) OpenGraph & Twitter Card görselleri
        doc.select("meta[property=og:image], meta[property=og:image:secure_url], meta[name=twitter:image], meta[name=twitter:image:src]")
            .forEach { meta ->
                val content = meta.attr("abs:content").ifBlank { meta.attr("content") }.trim()
                if (content.startsWith("http://") || content.startsWith("https://")) {
                    candidateUrls += normalizeImageUrl(content, platform)
                }
            }

        // 2) Sayfa içindeki yüksek çözünürlüklü <img> etiketleri
        doc.select("img[src]").forEach { img ->
            val src = img.attr("abs:src").trim()
            if (!src.startsWith("http://") && !src.startsWith("https://")) return@forEach
            val lower = src.lowercase(Locale.ROOT)
            if (lower.endsWith(".svg") || lower.contains("sprite") || lower.contains("favicon") || lower.contains("logo")) {
                return@forEach
            }
            val width = img.attr("width").toIntOrNull()
            val height = img.attr("height").toIntOrNull()
            if ((width != null && width < 200) || (height != null && height < 200)) {
                return@forEach
            }
            if (candidateUrls.size < MAX_WEB_IMAGES) {
                candidateUrls += normalizeImageUrl(src, platform)
            }
        }

        val images = candidateUrls.mapIndexed { index, imgUrl ->
            val isPinterestOriginal = platform == PlatformType.PINTEREST && imgUrl.contains("/originals/")
            ImageItem(
                id = "web_img_${index + 1}",
                url = imgUrl,
                label = when {
                    isPinterestOriginal -> "Orijinal Pin Görseli"
                    index == 0 -> "Ana Görsel (HD)"
                    else -> "Görsel ${index + 1}"
                },
                extension = UrlSanitizer.guessImageExtension(imgUrl)
            )
        }

        if (images.isEmpty()) {
            throw IOException("Bu sayfada indirilebilir görsel veya medya bulunamadı.")
        }

        return ExtractedMedia(
            originalUrl = url,
            title = title,
            author = author,
            durationSeconds = null,
            thumbnailUrl = images.firstOrNull()?.url,
            platform = platform,
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            imageItems = images
        )
    }

    private fun normalizeImageUrl(url: String, platform: PlatformType): String {
        if (platform == PlatformType.PINTEREST || url.contains("pinimg.com")) {
            return PINIMG_SIZE_REGEX.replace(url, "/originals/")
        }
        return url
    }

    private fun upgradeTwitterImageUrl(url: String): String {
        if (!url.contains("pbs.twimg.com")) return url
        return if (url.contains("name=")) {
            url.replace(Regex("name=[a-zA-Z0-9_]+"), "name=orig")
        } else if (url.contains("?")) {
            "$url&name=orig"
        } else {
            "$url?name=orig"
        }
    }

    private fun resolveRedirectUrl(url: String, userAgent: String = DESKTOP_USER_AGENT): String? {
        val safeUrl = UrlSanitizer.extractFirstValidUrl(url) ?: return null
        return runCatching {
            val request = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", userAgent)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                UrlSanitizer.extractFirstValidUrl(finalUrl)
            }
        }.getOrNull()
    }

    private fun isImageContentType(url: String): Boolean {
        val safeUrl = UrlSanitizer.extractFirstValidUrl(url) ?: return false
        return runCatching {
            val headRequest = Request.Builder()
                .url(safeUrl)
                .head()
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            okHttpClient.newCall(headRequest).execute().use { response ->
                val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
                response.isSuccessful && contentType.startsWith("image/")
            }
        }.getOrDefault(false)
    }

    private fun executeGet(
        url: String,
        userAgent: String = DESKTOP_USER_AGENT,
        acceptHeader: String = "application/json, text/html;q=0.9, */*;q=0.8",
        referer: String? = null
    ): String {
        val safeUrl = UrlSanitizer.extractFirstValidUrl(url)
            ?: throw IllegalArgumentException("Geçersiz veya güvensiz bağlantı.")

        val requestBuilder = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", userAgent)
            .header("Accept", acceptHeader)
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
        if (!referer.isNullOrBlank()) {
            requestBuilder.header("Referer", referer)
        }
        val request = requestBuilder.build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("İstek başarısız oldu (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("Boş sunucu yanıtı.")
            val contentLength = body.contentLength()
            if (contentLength > MAX_TEXT_RESPONSE_BYTES) {
                throw IOException("Sunucu yanıtı çok büyük.")
            }
            return readLimitedUtf8(body)
        }
    }

    private fun readLimitedUtf8(body: okhttp3.ResponseBody): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var totalRead = 0L
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalRead += read
                if (totalRead > MAX_TEXT_RESPONSE_BYTES) {
                    throw IOException("Sunucu yanıtı güvenlik boyut sınırını (5 MB) aştı.")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun extractTwitterVariants(videoObj: JSONObject, videoIndex: Int): List<MediaFormatOption> {
        val variants = videoObj.optJSONArray("variants") ?: return emptyList()
        data class RawVariant(
            val url: String,
            val bitrateKbps: Int,
            val height: Int
        )

        val mp4Variants = mutableListOf<RawVariant>()
        for (j in 0 until variants.length()) {
            val v = variants.optJSONObject(j) ?: continue
            val contentType = v.optString("content_type").lowercase(Locale.ROOT)
            val vUrl = v.optString("url").takeIf { it.isNotBlank() } ?: continue
            if (!contentType.contains("mp4") && !vUrl.contains(".mp4", ignoreCase = true)) continue

            val bitrateBps = v.optInt("bitrate", 0)
            val bitrateKbps = if (bitrateBps > 10_000) bitrateBps / 1000 else bitrateBps

            val resMatch = TWITTER_VARIANT_RES_REGEX.find(vUrl)
            val w = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val h = resMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            val effectiveHeight = when {
                w > 0 && h > 0 -> minOf(w, h)
                h > 0 -> h
                else -> 0
            }
            mp4Variants += RawVariant(vUrl, bitrateKbps, effectiveHeight)
        }

        if (mp4Variants.isEmpty()) return emptyList()

        return mp4Variants
            .sortedWith(compareByDescending<RawVariant> { it.height }.thenByDescending { it.bitrateKbps })
            .distinctBy { if (it.height > 0) it.height else it.bitrateKbps }
            .mapIndexed { idx, v ->
                val label = if (v.height > 0) "${v.height}p" else "Video $videoIndex • Varyant ${idx + 1}"
                val bitrateText = v.bitrateKbps.takeIf { it > 0 }?.let {
                    if (it >= 1000) String.format(Locale.US, "%.1f Mbps", it / 1000.0) else "$it kbps"
                }
                MediaFormatOption(
                    id = "x_variant_${videoIndex}_${v.height}p_${idx + 1}",
                    label = label,
                    sublabel = listOfNotNull("MP4", bitrateText, "X Doğrudan").joinToString(" • "),
                    category = MediaCategory.VIDEO,
                    extension = "mp4",
                    resolutionHeight = v.height,
                    bitrateKbps = v.bitrateKbps,
                    directUrl = v.url
                )
            }
    }

    private fun appendDownscaleIfSingleResolution(videos: List<MediaFormatOption>): List<MediaFormatOption> {
        if (videos.isEmpty()) return videos
        val distinctHeights = videos.map { it.resolutionHeight }.filter { it > 0 }.distinct()
        if (distinctHeights.size == 1 && distinctHeights.first() > 480) {
            return videos + YtDlpEngine.buildDownscaleOptions(videos.first(), distinctHeights.first())
        }
        // Çözünürlük HTML meta'sından okunamadıysa bile tüm Reels/sosyal videolarda
        // tutarlı şekilde 480p ve 360p FFmpeg küçültme seçeneklerini sun
        if (distinctHeights.isEmpty() && videos.size == 1 && !videos.first().directUrl.isNullOrBlank()) {
            return videos + YtDlpEngine.buildDownscaleOptions(videos.first(), 720)
        }
        return videos
    }

    private fun resolutionSuffix(width: Int?, height: Int?): String {
        return if (width != null && height != null) " (${width}x${height})" else ""
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        return when {
            bytesPerSec >= mb -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / mb)
            bytesPerSec >= kb -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / kb)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    companion object {
        private const val MAX_TEXT_RESPONSE_BYTES = 5L * 1024 * 1024
        private const val MAX_DIRECT_DOWNLOAD_BYTES = 500L * 1024 * 1024
        private const val MAX_WEB_IMAGES = 12
        private const val REDDIT_USER_AGENT = "android:com.ulgentube.app:v1.0 (by /u/ulgentube)"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val FACEBOOK_BOT_USER_AGENT =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
        private const val WHATSAPP_USER_AGENT = "WhatsApp/2.24.16.78 A"
        private const val TELEGRAM_BOT_USER_AGENT = "TelegramBot (like TwitterBot)"

        private val REDDIT_COMMENTS_ID_REGEX = Regex("""/(?:comments|gallery)/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
        private val REDDIT_SHORT_ID_REGEX = Regex("""^https?://(?:www\.)?redd\.it/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
        private val TWITTER_STATUS_REGEX = Regex("""status(?:es)?/(\d+)""", RegexOption.IGNORE_CASE)
        private val TWITTER_VARIANT_RES_REGEX = Regex("""/(\d{2,4})x(\d{2,4})/""")
        private val PINIMG_SIZE_REGEX = Regex("""/\d+x/""")
        private val INSTAGRAM_SHORTCODE_REGEX = Regex("""(?:instagram\.com)?/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        private val INSTAGRAM_GQL_JSON_REGEX = Regex(""""shortcode_media"\s*:\s*(\{.*?"__typename".*?\})\s*[,}]""")
        private val INSTAGRAM_VIDEO_URL_REGEX = Regex(""""video_url"\s*:\s*"(https?:[^"]+)"""")
        private val INSTAGRAM_DISPLAY_URL_REGEX = Regex(""""display_url"\s*:\s*"(https?:[^"]+)"""")
        private val SCONTENT_URL_REGEX = Regex("""https://scontent[^\s"'<>\\]+""")
        private val IG_CACHE_KEY_REGEX = Regex("""[?&]ig_cache_key=([^&]+)""", RegexOption.IGNORE_CASE)
        private val INSTAGRAM_DESC_REGEX = Regex("""^.+?\s-\s(.+?)\s+on\s+[^:]+:\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)
        private val GENERIC_INSTAGRAM_TITLES = setOf(
            "instagram",
            "vxinstagram",
            "eeinstagram",
            "ddinstagram",
            "kkinstagram",
            "instagram gönderisi"
        )
        private val INSTAGRAM_RESERVED_PATHS = setOf(
            "p",
            "reel",
            "reels",
            "tv",
            "explore",
            "accounts",
            "direct",
            "share",
            "about",
            "legal",
            "developer",
            "developers",
            "api",
            "graphql",
            "static",
            "login",
            "challenge",
            "oauth",
            "privacy",
            "terms",
            "press",
            "nametag"
        )

        private fun extractUrlPathSegments(url: String): List<String> {
            val withoutFragment = url.substringBefore('#').substringBefore('?')
            val afterScheme = withoutFragment.substringAfter("://", withoutFragment)
            val path = afterScheme.substringAfter('/', "")
            return path.split('/').filter { it.isNotBlank() }
        }

        /**
         * Verilen URL'nin Instagram Hikaye (`/stories/`), Öne Çıkan (`/highlights/`, `/s/`)
         * veya kök profil sayfası (`instagram.com/<username>/`) olup olmadığını kontrol eder.
         */
        fun isInstagramStoryOrProfileUrl(url: String): Boolean {
            val lower = url.trim().lowercase(Locale.ROOT)
            val afterScheme = lower.substringAfter("://", lower)
            val host = afterScheme.substringBefore('/').substringBefore(':')
            val isInstagramHost = host == "instagram.com" ||
                host.endsWith(".instagram.com") ||
                host == "instagr.am" ||
                host.endsWith(".instagr.am")
            if (!isInstagramHost) return false

            val segments = extractUrlPathSegments(lower)
            if (segments.isEmpty()) return false

            val first = segments[0]
            if (first == "stories" || first == "s" || first == "highlights") {
                return true
            }
            if (segments.any { it == "highlights" }) {
                return true
            }

            // Rezerve yollar (/p/, /reel/, /reels/, /tv/, /explore/, /accounts/, /direct/ vb.) profil değildir
            if (first in INSTAGRAM_RESERVED_PATHS) {
                return false
            }

            // Kök profil URL'si: instagram.com/<username>/
            return segments.size == 1 && first.matches(Regex("""^[a-z0-9._]{1,30}$"""))
        }

        /**
         * Reddit gönderi URL'sinden (`/comments/<id>`, `/gallery/<id>` veya `redd.it/<id>`)
         * saf post kimliğini çıkarır. Mobil `/s/<shareCode>` bağlantılarında `null` döner.
         */
        fun extractRedditPostId(url: String): String? {
            val cleaned = url.trim().substringBefore('?').substringBefore('#')
            if (cleaned.isEmpty()) return null
            val fromComments = REDDIT_COMMENTS_ID_REGEX.find(cleaned)?.groupValues?.getOrNull(1)
            if (!fromComments.isNullOrBlank()) return fromComments
            val fromShort = REDDIT_SHORT_ID_REGEX.find(cleaned)?.groupValues?.getOrNull(1)
            return fromShort?.takeIf { it.isNotBlank() }
        }

        /**
         * Reddit JSON yanıtlarından hem `JSONArray` (comments `.json`) hem de tek `JSONObject`
         * (`api.reddit.com/api/info/?id=t3_...`) listing yapılarındaki gönderi `data` nesnesini ayrıştırır.
         */
        fun parseRedditPostObject(jsonText: String): JSONObject? {
            return runCatching {
                val trimmed = jsonText.trim()
                if (trimmed.isEmpty()) return null
                val listing = when {
                    trimmed.startsWith("[") -> JSONArray(trimmed).optJSONObject(0)
                    trimmed.startsWith("{") -> JSONObject(trimmed)
                    else -> return null
                }
                listing
                    ?.optJSONObject("data")
                    ?.optJSONArray("children")
                    ?.optJSONObject(0)
                    ?.optJSONObject("data")
            }.getOrNull()
        }

        /**
         * Verilen URL'nin Reddit medya CDN'i (`redd.it`, `redditmedia.com`, `reddit.com`) olup olmadığını kontrol eder.
         */
        fun isRedditMediaUrl(url: String): Boolean {
            val lower = url.trim().lowercase(Locale.ROOT)
            val afterScheme = lower.substringAfter("://", lower)
            val host = afterScheme.substringBefore('/').substringBefore(':')
            return host == "redd.it" ||
                host.endsWith(".redd.it") ||
                host == "redditmedia.com" ||
                host.endsWith(".redditmedia.com") ||
                host == "reddit.com" ||
                host.endsWith(".reddit.com")
        }
    }
}
