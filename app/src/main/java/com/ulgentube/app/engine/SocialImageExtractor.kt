package com.ulgentube.app.engine

import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
    private val okHttpClient: OkHttpClient
) {

    /**
     * URL'yi platformuna göre analiz edip görsel/galeri ve varsa doğrudan video bağlantılarını döndürür.
     */
    suspend fun extract(
        url: String,
        platform: PlatformType
    ): ExtractedMedia = withContext(Dispatchers.IO) {
        if (platform == PlatformType.DIRECT_IMAGE || isImageContentType(url)) {
            return@withContext extractDirectImage(url)
        }

        return@withContext when (platform) {
            PlatformType.TWITTER_X -> extractTwitter(url)
            PlatformType.REDDIT -> extractReddit(url)
            else -> extractFromWebPage(url, platform)
        }
    }

    /**
     * Doğrudan bir görsel veya medya dosyasını OkHttp streaming ile hedef dosyaya indirir.
     */
    suspend fun downloadDirectFile(
        url: String,
        targetFile: File,
        onProgress: (progress: Float, speedText: String?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "*/*")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Dosya indirilemedi (HTTP ${response.code}).")
            }

            val body = response.body ?: throw IOException("Sunucu boş yanıt döndürdü.")
            val totalBytes = body.contentLength()
            val buffer = ByteArray(16 * 1024)
            var downloadedBytes = 0L
            var lastEmitTime = System.currentTimeMillis()
            var lastBytesSnapshot = 0L

            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read

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
     * Reddit gönderilerinden tekil görsel, önizleme ve çoklu galeri görsellerini `.json` endpoint'i ile çıkarır.
     */
    private fun extractReddit(url: String): ExtractedMedia {
        val cleanBase = url.substringBefore('?').trimEnd('/')
        val jsonUrl = if (cleanBase.endsWith(".json", ignoreCase = true)) {
            "$cleanBase?raw_json=1"
        } else {
            "$cleanBase.json?raw_json=1"
        }

        val responseText = executeGet(jsonUrl, userAgent = REDDIT_USER_AGENT)
        val postData = parseRedditPostObject(responseText)
            ?: return extractFromWebPage(url, PlatformType.REDDIT)

        val title = postData.optString("title").takeIf { it.isNotBlank() } ?: "Reddit Gönderisi"
        val author = postData.optString("author").takeIf { it.isNotBlank() }?.let { "u/$it" }
        val images = mutableListOf<ImageItem>()
        val seenUrls = linkedSetOf<String>()

        // 1) Galeri gönderisi (media_metadata)
        val mediaMetadata = postData.optJSONObject("media_metadata")
        val galleryItems = postData.optJSONObject("gallery_data")?.optJSONArray("items")
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
                        id = "reddit_gallery_${index + 1}",
                        url = imgUrl,
                        label = "Galeri ${images.size + 1}${resolutionSuffix(width, height)}",
                        extension = UrlSanitizer.guessImageExtension(imgUrl),
                        width = width,
                        height = height
                    )
                }
            }
        }

        // 2) Tekil doğrudan görsel bağlantısı (url_overridden_by_dest)
        val directDest = postData.optString("url_overridden_by_dest")
            .takeIf { it.isNotBlank() }
            ?.replace("&amp;", "&")
        if (!directDest.isNullOrBlank() && UrlSanitizer.isDirectImagePath(directDest) && seenUrls.add(directDest)) {
            images += ImageItem(
                id = "reddit_direct_1",
                url = directDest,
                label = "Orijinal Görsel",
                extension = UrlSanitizer.guessImageExtension(directDest)
            )
        }

        // 3) Önizleme görselleri (preview.images)
        if (images.isEmpty()) {
            val previewImages = postData.optJSONObject("preview")?.optJSONArray("images")
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

        // Reddit videosu varsa doğrudan fallback URL'sini ekle
        val videos = mutableListOf<MediaFormatOption>()
        var durationSeconds: Long? = null
        val redditVideo = postData.optJSONObject("secure_media")?.optJSONObject("reddit_video")
            ?: postData.optJSONObject("media")?.optJSONObject("reddit_video")
        if (redditVideo != null) {
            val fallbackUrl = redditVideo.optString("fallback_url").takeIf { it.isNotBlank() }
            val height = redditVideo.optInt("height", 0)
            durationSeconds = redditVideo.optLong("duration", 0L).takeIf { it > 0L }
            if (fallbackUrl != null) {
                videos += MediaFormatOption(
                    id = "reddit_video_1",
                    label = if (height > 0) "${height}p Reddit Video" else "Reddit Video",
                    sublabel = "MP4",
                    category = MediaCategory.VIDEO,
                    extension = "mp4",
                    resolutionHeight = height,
                    directUrl = fallbackUrl
                )
            }
        }

        return ExtractedMedia(
            originalUrl = url,
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
     * Pinterest, Instagram, Facebook ve genel web sayfalarından OpenGraph, Twitter Card
     * ve yüksek çözünürlüklü `<img>` etiketlerini Jsoup ile ayıklar.
     */
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

    private fun parseRedditPostObject(jsonText: String): JSONObject? {
        return runCatching {
            val trimmed = jsonText.trim()
            val listing = if (trimmed.startsWith("[")) {
                JSONArray(trimmed).optJSONObject(0)
            } else {
                JSONObject(trimmed)
            }
            val data = listing
                ?.optJSONObject("data")
                ?.optJSONArray("children")
                ?.optJSONObject(0)
                ?.optJSONObject("data")

            // Crosspost varsa orijinal gönderiyi tercih et
            val crosspost = data?.optJSONArray("crosspost_parent_list")?.optJSONObject(0)
            crosspost ?: data
        }.getOrNull()
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

    private fun isImageContentType(url: String): Boolean {
        return runCatching {
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            okHttpClient.newCall(headRequest).execute().use { response ->
                val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
                response.isSuccessful && contentType.startsWith("image/")
            }
        }.getOrDefault(false)
    }

    private fun executeGet(url: String, userAgent: String = DESKTOP_USER_AGENT): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("İstek başarısız oldu (HTTP ${response.code}).")
            }
            return response.body?.string() ?: throw IOException("Boş sunucu yanıtı.")
        }
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
        private const val MAX_WEB_IMAGES = 12
        private const val REDDIT_USER_AGENT = "UlgenTube/1.0 (Android)"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val TWITTER_STATUS_REGEX = Regex("""status(?:es)?/(\d+)""", RegexOption.IGNORE_CASE)
        private val TWITTER_VARIANT_RES_REGEX = Regex("""/(\d{2,4})x(\d{2,4})/""")
        private val PINIMG_SIZE_REGEX = Regex("""/\d+x/""")
    }
}
