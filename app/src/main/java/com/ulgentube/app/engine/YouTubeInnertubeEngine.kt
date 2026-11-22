package com.ulgentube.app.engine

import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.util.FormatUtils
import com.ulgentube.app.util.NetworkSecurityGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Snaptube / NewPipe tarzı doğrudan YouTube Innertube (`IOS_PRODUCER`, `IOS` & `ANDROID`) motoru:
 * - Python `yt-dlp` sürecini başlatmadan ~0.4 saniyede tüm gerçek 4K/2K/1080p60/HDR/ses akışlarını çözer.
 * - Doğrudan imzalı `HTTP 206` CDN akış URL'leri ile indirip yerel FFmpeg üzerinden birleştirir / dönüştürür;
 *   böylece `yt-dlp` 403 Forbidden ve `aria2c` hatalarını tamamen ortadan kaldırır.
 */
class YouTubeInnertubeEngine(
    private val okHttpClient: OkHttpClient,
    private val localFfmpegProcessor: LocalFfmpegProcessor,
    private val parallelRangeDownloader: ParallelRangeDownloader? = null,
    private val cookieRepository: CookieRepository? = null,
    private val poTokenProvider: YouTubePoTokenProvider? = null
) {

    /**
     * YouTube ve YouTube Music oynatma listelerini (`list=PL...`, `list=OLAK5uy_...`) ve
     * albüm paylaşım bağlantılarını (`music.youtube.com/browse/MPREb_...`) `yt-dlp` başlatmadan
     * ~0.4 saniyede doğrudan `WEB_REMIX` Innertube browse API üzerinden çözümler.
     */
    suspend fun fetchPlaylistOrAlbumInfo(url: String): ExtractedMedia? = withContext(Dispatchers.IO) {
        val mprebId = Regex("""/browse/(MPREb_[A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
        val listId = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
        val browseId = when {
            !mprebId.isNullOrBlank() -> mprebId
            !listId.isNullOrBlank() -> if (listId.startsWith("VL")) listId else "VL$listId"
            else -> return@withContext null
        }

        val payload = """
            {
              "browseId": "$browseId",
              "context": {
                "client": {
                  "clientName": "WEB_REMIX",
                  "clientVersion": "1.20250501.01.00",
                  "hl": "tr",
                  "gl": "TR"
                }
              }
            }
        """.trimIndent()

        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()

        val root = runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                JSONObject(body)
            }
        }.getOrNull() ?: return@withContext null

        val entries = mutableListOf<com.ulgentube.app.domain.model.PlaylistEntry>()
        val seenIds = linkedSetOf<String>()
        var albumTitle: String? = null
        var albumThumb: String? = null

        fun walkJson(node: Any?) {
            when (node) {
                is JSONObject -> {
                    // Albüm / liste başlığı
                    if (albumTitle == null) {
                        val header = node.optJSONObject("musicResponsiveHeaderRenderer")
                            ?: node.optJSONObject("musicDetailHeaderRenderer")
                        val titleRuns = header?.optJSONObject("title")?.optJSONArray("runs")
                        val t = titleRuns?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
                        if (t != null) albumTitle = t
                        val thumbs = header?.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        if (albumThumb == null && thumbs != null && thumbs.length() > 0) {
                            val u = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
                            if (!u.isNullOrBlank() && NetworkSecurityGuard.isSafePublicUrl(u)) {
                                albumThumb = u
                            }
                        }
                    }

                    val item = node.optJSONObject("musicResponsiveListItemRenderer")
                    if (item != null) {
                        val vid = item.optJSONObject("playlistItemData")?.optString("videoId")
                            ?.takeIf { it.length == 11 }
                            ?: item.optJSONObject("overlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId")
                                ?.takeIf { it.length == 11 }
                        if (vid != null && seenIds.add(vid)) {
                            val flexCols = item.optJSONArray("flexColumns")
                            val trackTitle = flexCols?.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text")
                                ?.takeIf { it.isNotBlank() }
                                ?: "Parça ${entries.size + 1}"
                            val trackAuthor = flexCols?.optJSONObject(1)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text")
                                ?.takeIf { it.isNotBlank() }
                            val thumbs = item.optJSONObject("thumbnail")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")
                            val thumbUrl = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))
                                ?.optString("url")
                                ?.takeIf { it.startsWith("http") && NetworkSecurityGuard.isSafePublicUrl(it) }
                                ?: albumThumb
                                ?: "https://i.ytimg.com/vi/$vid/hqdefault.jpg"

                            entries += com.ulgentube.app.domain.model.PlaylistEntry(
                                id = vid,
                                url = "https://music.youtube.com/watch?v=$vid",
                                title = trackTitle,
                                author = trackAuthor,
                                thumbnailUrl = thumbUrl
                            )
                        }
                    }

                    val keys = node.keys()
                    while (keys.hasNext()) {
                        walkJson(node.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walkJson(node.opt(i))
                    }
                }
            }
        }

        walkJson(root)
        if (entries.isEmpty()) return@withContext null

        val isYtMusic = url.contains("music.youtube.com", ignoreCase = true) || !mprebId.isNullOrBlank()
        ExtractedMedia(
            originalUrl = url,
            title = albumTitle ?: if (isYtMusic) "YouTube Music Albümü" else "YouTube Oynatma Listesi",
            author = entries.firstOrNull()?.author,
            durationSeconds = null,
            thumbnailUrl = albumThumb ?: entries.firstOrNull()?.thumbnailUrl,
            platform = PlatformType.YOUTUBE,
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            imageItems = emptyList(),
            isPlaylist = true,
            playlistEntries = entries
        )
    }

    suspend fun fetchVideoInfo(
        url: String,
        poToken: String? = null,
        visitorData: String? = null
    ): ExtractedMedia = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)
            ?: throw IllegalArgumentException("Geçerli bir YouTube video kimliği bulunamadı.")

        // 1) Birincil istemci: IOS 20.10.4 — tek istekte 4K/2K/1080p60/HDR + AV1/VP9/H.264 +
        // çok dilli dublaj (audioTrack) + altyazı (captionTracks) döndürür.
        val iosJson = queryInnertubeClient(
            videoId = videoId,
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientIdHeader = "5",
            userAgent = IOS_YOUTUBE_USER_AGENT,
            extraClientFields = """"deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22D82"""",
            poToken = poToken,
            visitorData = visitorData
        )
        // 2) IOS_PRODUCER: PO Token gerektirmeden 403'süz garantili M4A (itag 140) ses akışı sağlar
        val iosProducerJson = queryInnertubeClient(
            videoId = videoId,
            clientName = "IOS_PRODUCER",
            clientVersion = "0.147.945",
            clientIdHeader = "92",
            userAgent = IOS_PRODUCER_USER_AGENT,
            extraClientFields = """"deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22D82"""",
            poToken = poToken,
            visitorData = visitorData
        )
        // 3) Yedek istemci: ANDROID 20.10.38 (IOS başarısız olursa)
        val androidJson = if (iosJson == null) {
            queryInnertubeClient(
                videoId = videoId,
                clientName = "ANDROID",
                clientVersion = "20.10.38",
                clientIdHeader = "3",
                userAgent = ANDROID_YOUTUBE_USER_AGENT,
                extraClientFields = """"androidSdkVersion":34,"osName":"Android","osVersion":"14"""",
                poToken = poToken,
                visitorData = visitorData
            )
        } else {
            null
        }

        val playerJson = iosJson ?: androidJson ?: iosProducerJson
            ?: throw IOException("YouTube Innertube yanıtı alınamadı.")

        val videoDetails = playerJson.optJSONObject("videoDetails")
            ?: iosProducerJson?.optJSONObject("videoDetails")
            ?: throw IOException("YouTube video detayları bulunamadı.")

        val title = videoDetails.optString("title").takeIf { it.isNotBlank() } ?: "YouTube Videosu"
        val author = videoDetails.optString("author").takeIf { it.isNotBlank() }
        val durationSeconds = videoDetails.optString("lengthSeconds").toLongOrNull()?.takeIf { it > 0L }
        val thumbnailUrl = extractBestThumbnail(videoDetails, videoId)

        data class RawYtStream(
            val itag: Int,
            val url: String,
            val mimeType: String,
            val codecs: String,
            val width: Int,
            val height: Int,
            val fps: Int,
            val bitrate: Long,
            val contentLength: Long?,
            val isVideo: Boolean,
            val isAudio: Boolean,
            val isHdr: Boolean,
            val audioTrackId: String? = null,
            val audioTrackName: String? = null,
            val audioIsDefault: Boolean = false
        )

        val allStreams = mutableListOf<RawYtStream>()
        val seenStreamKeys = linkedSetOf<String>()

        fun collectFrom(arr: JSONArray?, isMuxed: Boolean) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rawStreamUrl = obj.optString("url").trim()
                if (rawStreamUrl.isBlank() || !NetworkSecurityGuard.isSafePublicUrl(rawStreamUrl)) continue
                val streamUrl = if (!poToken.isNullOrBlank() && !rawStreamUrl.contains("pot=")) {
                    val sep = if (rawStreamUrl.contains('?')) '&' else '?'
                    "$rawStreamUrl${sep}pot=${java.net.URLEncoder.encode(poToken, "UTF-8")}"
                } else {
                    rawStreamUrl
                }

                val mimeRaw = obj.optString("mimeType")
                val mimeBase = mimeRaw.substringBefore(';').trim().lowercase(Locale.ROOT)
                val codecs = Regex("""codecs="([^"]+)"""").find(mimeRaw)?.groupValues?.getOrNull(1).orEmpty()
                val itag = obj.optInt("itag", 0)
                val width = obj.optInt("width", 0)
                val height = obj.optInt("height", 0)
                val fps = obj.optInt("fps", 0)
                val bitrate = obj.optLong("averageBitrate", obj.optLong("bitrate", 0L))
                val contentLength = obj.optString("contentLength").toLongOrNull()?.takeIf { it > 0L }
                    ?: if (durationSeconds != null && bitrate > 0L) (durationSeconds * bitrate) / 8L else null

                val qualityLabel = obj.optString("qualityLabel").lowercase(Locale.ROOT)
                val colorTransfer = obj.optJSONObject("colorInfo")?.optString("transferCharacteristics").orEmpty()
                val isHdr = qualityLabel.contains("hdr") ||
                    colorTransfer.contains("SMPTEST2084", ignoreCase = true) ||
                    colorTransfer.contains("ARIB_STD_B67", ignoreCase = true) ||
                    codecs.contains("vp09.02", ignoreCase = true) ||
                    codecs.contains(".10.", ignoreCase = true)

                val audioTrackObj = obj.optJSONObject("audioTrack")
                val trackId = audioTrackObj?.optString("id")?.takeIf { it.isNotBlank() }
                val trackName = audioTrackObj?.optString("displayName")?.takeIf { it.isNotBlank() }
                val trackIsDefault = audioTrackObj?.optBoolean("audioIsDefault", false) == true

                val hasVideo = mimeBase.startsWith("video/")
                val hasAudio = isMuxed || mimeBase.startsWith("audio/")

                val dedupKey = "${itag}_${trackId.orEmpty()}_${height}_${fps}_${bitrate / 100_000L}"
                if (!seenStreamKeys.add(dedupKey)) continue

                allStreams += RawYtStream(
                    itag = itag,
                    url = streamUrl,
                    mimeType = mimeBase,
                    codecs = codecs,
                    width = width,
                    height = height,
                    fps = fps,
                    bitrate = bitrate,
                    contentLength = contentLength,
                    isVideo = hasVideo,
                    isAudio = hasAudio,
                    isHdr = isHdr,
                    audioTrackId = trackId,
                    audioTrackName = trackName,
                    audioIsDefault = trackIsDefault
                )
            }
        }

        // Önce IOS_PRODUCER (403'süz garantili itag 140 M4A ses), ardından IOS/ANDROID (4K/2K/dublaj/altyazı)
        // Not: IOS_PRODUCER'dan gelen garantili M4A ses URL'sini ayrıca saklıyoruz.
        val producerStreams = mutableListOf<RawYtStream>()
        iosProducerJson?.optJSONObject("streamingData")?.let { sd ->
            val before = allStreams.size
            collectFrom(sd.optJSONArray("adaptiveFormats"), isMuxed = false)
            if (allStreams.size > before) {
                producerStreams += allStreams.subList(before, allStreams.size)
            }
        }
        listOfNotNull(iosJson, androidJson).forEach { json ->
            val sd = json.optJSONObject("streamingData") ?: return@forEach
            collectFrom(sd.optJSONArray("adaptiveFormats"), isMuxed = false)
            collectFrom(sd.optJSONArray("formats"), isMuxed = true)
        }
        if (allStreams.isEmpty()) {
            iosProducerJson?.optJSONObject("streamingData")?.let { sd ->
                collectFrom(sd.optJSONArray("formats"), isMuxed = true)
            }
        }

        if (allStreams.isEmpty()) {
            throw IOException("İndirilebilir doğrudan YouTube akışı bulunamadı.")
        }

        // Çok dilli ses parçacıklarını (dublaj) ayıkla — YouTube itag 140 (M4A) PO Token'sız 206 OK verir;
        // itag 139 / 249 / 250 / 251 sıkça 403 verdiği için itag 140 her zaman 1. önceliklidir.
        val audioOnlyStreams = allStreams.filter { !it.isVideo && it.isAudio }
        val audioTracks = audioOnlyStreams
            .filter { !it.audioTrackName.isNullOrBlank() || !it.audioTrackId.isNullOrBlank() }
            .groupBy { stream ->
                (stream.audioTrackId?.substringBefore('.') ?: stream.audioTrackName ?: "default")
                    .lowercase(Locale.ROOT)
            }
            .mapNotNull { (langCode, tracks) ->
                val bestM4a = tracks
                    .filter { it.mimeType.contains("mp4") || it.codecs.contains("mp4a") }
                    .maxWithOrNull(
                        compareBy<RawYtStream> { if (it.itag == 140) 2 else 1 }
                            .thenBy { it.bitrate }
                    )
                val best = bestM4a ?: tracks.maxByOrNull { it.bitrate } ?: return@mapNotNull null
                val rawLabel = best.audioTrackName?.trim()?.takeIf { it.isNotBlank() }
                    ?: langCode.uppercase(Locale.ROOT)
                com.ulgentube.app.domain.model.AudioTrackOption(
                    id = best.audioTrackId ?: langCode,
                    langCode = langCode,
                    label = rawLabel,
                    directUrl = best.url,
                    webmDirectUrl = null,
                    isDefault = tracks.any { it.audioIsDefault },
                    itag = best.itag
                )
            }
            .sortedWith(
                compareByDescending<com.ulgentube.app.domain.model.AudioTrackOption> { it.isDefault }
                    .thenByDescending { it.langCode.startsWith("tr") }
                    .thenByDescending { it.langCode.startsWith("en") }
                    .thenBy { it.label }
            )

        // Varsayılan ses: Çok dilli videoda kullanıcının varsayılan dilindeki itag 140,
        // tek dilli videolarda 403'süz garantili IOS_PRODUCER itag 140!
        val producerBestM4a = producerStreams
            .filter { !it.isVideo && it.isAudio && (it.mimeType.contains("mp4") || it.codecs.contains("mp4a")) }
            .maxWithOrNull(compareBy<RawYtStream> { if (it.itag == 140) 2 else 1 }.thenBy { it.bitrate })

        val defaultAudioPool = audioOnlyStreams.filter { it.audioIsDefault }.ifEmpty { audioOnlyStreams }
        val iosDefaultM4a = defaultAudioPool
            .filter { it.mimeType.contains("mp4") || it.codecs.contains("mp4a") }
            .maxWithOrNull(compareBy<RawYtStream> { if (it.itag == 140) 2 else 1 }.thenBy { it.bitrate })

        val bestM4aAudio = if (audioTracks.size > 1 && iosDefaultM4a?.itag == 140) {
            iosDefaultM4a
        } else {
            producerBestM4a ?: iosDefaultM4a ?: defaultAudioPool.maxByOrNull { it.bitrate }
        }
        val bestOverallAudio = bestM4aAudio ?: defaultAudioPool.maxByOrNull { it.bitrate }

        data class GroupKey(
            val height: Int,
            val fpsBucket: Int,
            val isHdr: Boolean,
            val isHighBitrateTier: Boolean
        )

        val videoStreams = allStreams.filter { it.isVideo && it.height > 0 }
        val groupedVideos = videoStreams
            .groupBy { s ->
                val effHeight = if (s.width > 0 && s.height > 0) minOf(s.width, s.height) else s.height
                val fpsBucket = when {
                    s.fps >= 100 -> 120
                    s.fps >= 48 -> 60
                    else -> 30
                }
                val isHighBitTier = s.itag == 616 || s.itag == 356 ||
                    (effHeight == 1080 && s.bitrate >= 3_500_000L) ||
                    (effHeight >= 1440 && s.bitrate >= 10_000_000L)
                GroupKey(effHeight, fpsBucket, s.isHdr, isHighBitTier)
            }
            .map { (key, list) ->
                val chosen = list.maxWithOrNull(
                    compareBy<RawYtStream> {
                        // Android MediaMuxer (MUXER_OUTPUT_MPEG_4) + 403'süz itag 140 (M4A) uyumu için
                        // her çözünürlükte MP4 (1080p ve altında H.264, 2K/4K'da AV1 MP4) önceliklidir!
                        when {
                            key.height <= 1080 && it.mimeType.contains("mp4") &&
                                it.codecs.startsWith("avc1", ignoreCase = true) -> 3
                            it.mimeType.contains("mp4") -> 2
                            else -> 1
                        }
                    }
                        .thenBy { it.bitrate }
                ) ?: list.first()
                key to chosen
            }
            .sortedWith(
                compareByDescending<Pair<GroupKey, RawYtStream>> { it.first.height }
                    .thenByDescending { it.second.fps }
                    .thenByDescending { if (it.first.isHighBitrateTier) 1 else 0 }
                    .thenByDescending { if (it.first.isHdr) 1 else 0 }
                    .thenByDescending { it.second.bitrate }
            )

        val videoFormats = groupedVideos.map { (key, stream) ->
            val isWebmVideo = stream.mimeType.contains("webm") ||
                stream.codecs.startsWith("vp9", ignoreCase = true) ||
                stream.codecs.startsWith("vp09", ignoreCase = true) ||
                stream.codecs.startsWith("vp8", ignoreCase = true)
            val codecName = when {
                stream.codecs.startsWith("av01", ignoreCase = true) -> "AV1"
                stream.codecs.startsWith("vp9", ignoreCase = true) ||
                    stream.codecs.startsWith("vp09", ignoreCase = true) -> "VP9"
                stream.codecs.startsWith("hev1", ignoreCase = true) ||
                    stream.codecs.startsWith("hvc1", ignoreCase = true) -> "HEVC"
                stream.codecs.startsWith("avc1", ignoreCase = true) -> "H.264"
                else -> if (isWebmVideo) "VP9" else "MP4"
            }

            // 403'süz garantili M4A (itag 140) ses akışıyla eşle
            val pairedAudio = bestM4aAudio
            val pairedAudioSize = pairedAudio?.contentLength ?: 0L
            val pairedAudioUrl = pairedAudio?.url
            val outputExtension = "mp4"

            val totalBytes = when {
                stream.contentLength == null -> null
                stream.isAudio -> stream.contentLength
                pairedAudioSize > 0L -> stream.contentLength + pairedAudioSize
                else -> stream.contentLength
            }

            val combinedDirectUrl = when {
                stream.isAudio -> stream.url
                !pairedAudioUrl.isNullOrBlank() -> "${stream.url}$DUAL_STREAM_SEPARATOR$pairedAudioUrl"
                else -> stream.url
            }

            val kbps = (stream.bitrate / 1000L).toInt()
            val isHighBitrate = key.isHighBitrateTier || (key.height >= 1080 && kbps >= 3500) || kbps >= 10000

            val codecBadge = when (codecName) {
                "AV1" -> "AV1 • Daha küçük"
                "H.264" -> "H.264 • Uyumlu"
                "VP9" -> "VP9 • Yüksek Kalite"
                else -> codecName
            }
            val badgeTags = buildList {
                if (stream.fps >= 48) add("${stream.fps} FPS")
                if (key.isHdr) add("HDR / 10-bit")
                if (isHighBitrate) add("Yüksek Bit Hızı")
                add(codecBadge)
            }

            val bitrateText = when {
                kbps >= 1000 -> String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
                kbps > 0 -> "$kbps kbps"
                else -> null
            }
            val sizeText = FormatUtils.formatBytes(totalBytes).takeIf { it.isNotBlank() }
            val sublabel = listOfNotNull(bitrateText, sizeText).joinToString(" • ").ifBlank { "MP4 Video" }

            val resTitle = when {
                key.height >= 4320 -> "8K 4320p"
                key.height >= 2160 -> "4K 2160p"
                key.height >= 1440 -> "2K 1440p"
                else -> "${key.height}p"
            }
            val label = if (stream.fps >= 48) "$resTitle • ${stream.fps} FPS" else resTitle

            MediaFormatOption(
                id = "yt_innertube_${stream.itag}_${key.height}p_${stream.fps}fps",
                label = label,
                sublabel = sublabel,
                category = MediaCategory.VIDEO,
                extension = outputExtension,
                resolutionHeight = key.height,
                bitrateKbps = kbps,
                fps = if (stream.fps > 0) stream.fps else 30,
                videoCodec = codecName,
                isHdrOrHighBit = key.isHdr || isHighBitrate,
                badgeTags = badgeTags,
                fileSizeBytes = totalBytes,
                ytDlpFormatSelector = if (stream.isAudio) {
                    "${stream.itag}/bestaudio[ext=m4a]/bestaudio"
                } else if (key.height > 0) {
                    "${stream.itag}+bestaudio[ext=m4a]/${stream.itag}+bestaudio/bestvideo[height<=${key.height}][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=${key.height}]+bestaudio/best[height<=${key.height}]/best"
                } else {
                    "${stream.itag}+bestaudio[ext=m4a]/${stream.itag}+bestaudio/bestvideo+bestaudio/best"
                },
                directUrl = combinedDirectUrl
            )
        }

        val audioFormats = YtDlpEngine.buildDefaultAudioFormats(
            formats = emptyList(),
            durationSeconds = durationSeconds,
            directSourceUrl = bestOverallAudio?.url
                ?: videoFormats.firstOrNull()?.directUrl?.substringBefore(DUAL_STREAM_SEPARATOR)
        )

        val images = if (thumbnailUrl.isNotBlank()) {
            listOf(
                ImageItem(
                    id = "yt_thumb_hd",
                    url = thumbnailUrl,
                    label = "Kapak Görseli (Max HD)",
                    extension = "jpg"
                )
            )
        } else {
            emptyList()
        }

        val subtitles = parseCaptionTracks(iosJson, androidJson, iosProducerJson)

        ExtractedMedia(
            originalUrl = url,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
            platform = PlatformType.YOUTUBE,
            videoFormats = videoFormats,
            audioFormats = audioFormats,
            imageItems = images,
            subtitles = subtitles,
            audioTracks = audioTracks
        )
    }

    private fun parseCaptionTracks(vararg jsons: JSONObject?): List<com.ulgentube.app.domain.model.SubtitleOption> {
        val result = mutableListOf<com.ulgentube.app.domain.model.SubtitleOption>()
        val seen = linkedSetOf<String>()
        for (json in jsons) {
            val tracks = json
                ?.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks")
                ?: continue
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val langCode = track.optString("languageCode").trim()
                if (langCode.isBlank()) continue
                val isAuto = track.optString("kind").equals("asr", ignoreCase = true)
                val dedupKey = "${langCode.lowercase(Locale.ROOT)}_$isAuto"
                if (!seen.add(dedupKey)) continue

                val nameObj = track.optJSONObject("name")
                val label = nameObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
                    ?: nameObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
                    ?: langCode.uppercase(Locale.ROOT)
                val baseUrl = track.optString("baseUrl").trim().takeIf { it.startsWith("http") }

                result += com.ulgentube.app.domain.model.SubtitleOption(
                    langCode = langCode,
                    label = if (isAuto) "$label (Otomatik)" else label,
                    isAutoGenerated = isAuto,
                    downloadUrl = baseUrl
                )
            }
            if (result.isNotEmpty()) break
        }
        return result.sortedWith(
            compareByDescending<com.ulgentube.app.domain.model.SubtitleOption> { it.langCode.equals("tr", ignoreCase = true) }
                .thenByDescending { it.langCode.startsWith("tr", ignoreCase = true) }
                .thenByDescending { it.langCode.equals("en", ignoreCase = true) }
                .thenBy { it.isAutoGenerated }
                .thenBy { it.label }
        )
    }

    /**
     * Seçilen YouTube altyazı dilini WebVTT (.vtt) yan dosyası olarak indirir.
     */
    suspend fun downloadSubtitleVtt(
        videoUrl: String,
        langCode: String,
        targetFile: File
    ): File? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(videoUrl) ?: return@withContext null
        val safeLang = langCode.trim().takeIf { it.matches(Regex("""^[A-Za-z0-9_-]{2,15}$""")) }
            ?: return@withContext null

        val candidateUrls = mutableListOf<String>()
        // 1) Önce Innertube IOS yanıtından imzalı captionTrack baseUrl'ini al (en güvenilir)
        val iosJson = runCatching {
            queryInnertubeClient(
                videoId = videoId,
                clientName = "IOS",
                clientVersion = "20.10.4",
                clientIdHeader = "5",
                userAgent = IOS_YOUTUBE_USER_AGENT,
                extraClientFields = """"deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22D82""""
            )
        }.getOrNull()
        val matchedTrack = parseCaptionTracks(iosJson).firstOrNull {
            it.langCode.equals(safeLang, ignoreCase = true)
        } ?: parseCaptionTracks(iosJson).firstOrNull {
            it.langCode.startsWith(safeLang.substringBefore('-'), ignoreCase = true)
        }
        matchedTrack?.downloadUrl?.let { base ->
            val withFmt = if (base.contains("fmt=")) base else "$base&fmt=vtt"
            candidateUrls += withFmt
        }

        // 2) Yedek: doğrudan timedtext uç noktası
        candidateUrls += "https://www.youtube.com/api/timedtext?v=$videoId&lang=$safeLang&fmt=vtt"
        candidateUrls += "https://www.youtube.com/api/timedtext?v=$videoId&lang=$safeLang&kind=asr&fmt=vtt"

        for (subUrl in candidateUrls) {
            if (!NetworkSecurityGuard.isSafePublicUrl(subUrl)) continue
            val text = runCatching {
                val req = Request.Builder()
                    .url(subUrl)
                    .header("User-Agent", IOS_YOUTUBE_USER_AGENT)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()?.takeIf { it.contains("WEBVTT", ignoreCase = true) && it.length > 30 }
                }
            }.getOrNull()
            if (text != null) {
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(text, Charsets.UTF_8)
                return@withContext targetFile
            }
        }
        null
    }

    /**
     * Innertube üzerinden elde edilmiş doğrudan akışları HTTP Range (206 Partial Content) desteğiyle
     * yüksek hızda indirir ve gerektiğinde yerel FFmpeg ikilisiyle birleştirir / ses formatına dönüştürür.
     */
    suspend fun downloadYouTubeDirect(
        task: DownloadTask,
        tempDir: File,
        parallelConnections: Int = 4,
        maxSpeedBytesPerSec: Long = 0L,
        onProgress: (progress: Float, speedText: String?, status: DownloadStatus) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val rawDirect = task.directUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Doğrudan YouTube akış bağlantısı bulunamadı.")

        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }

        val isAudioTask = task.isAudioExtraction || task.category == MediaCategory.AUDIO
        val targetExt = task.targetExtension.lowercase(Locale.ROOT).ifBlank { if (isAudioTask) "mp3" else "mp4" }
        val hasClip = task.clipStartSeconds != null || task.clipEndSeconds != null

        if (isAudioTask) {
            val audioUrl = rawDirect.substringAfter(DUAL_STREAM_SEPARATOR, rawDirect).trim()
            if (!NetworkSecurityGuard.isSafePublicUrl(audioUrl)) {
                throw SecurityException("Güvensiz ses akış URL'si.")
            }

            val rawAudioFile = File(tempDir, "raw_audio.m4a")
            downloadStreamWithChunks(audioUrl, rawAudioFile, parallelConnections, maxSpeedBytesPerSec) { p, speed ->
                onProgress((p * 0.92f).coerceIn(0f, 92f), speed, DownloadStatus.DOWNLOADING)
            }

            if (targetExt == "m4a" && !hasClip) {
                return@withContext rawAudioFile
            }

            onProgress(95f, null, DownloadStatus.MERGING)
            val bitrateKbps = when {
                task.formatLabel.contains("320") -> 320
                task.formatLabel.contains("128") -> 128
                else -> 192
            }
            val outAudio = File(tempDir, "out_audio.$targetExt")
            return@withContext localFfmpegProcessor.muxOrConvertFiles(
                videoFile = null,
                audioFile = rawAudioFile,
                outputFile = outAudio,
                isAudioOnly = true,
                targetExtension = targetExt,
                audioBitrateKbps = bitrateKbps,
                clipStartSeconds = task.clipStartSeconds,
                clipEndSeconds = task.clipEndSeconds
            )
        }

        if (rawDirect.contains(DUAL_STREAM_SEPARATOR)) {
            val videoUrl = rawDirect.substringBefore(DUAL_STREAM_SEPARATOR).trim()
            val audioUrl = rawDirect.substringAfter(DUAL_STREAM_SEPARATOR).trim()
            if (!NetworkSecurityGuard.isSafePublicUrl(videoUrl) || !NetworkSecurityGuard.isSafePublicUrl(audioUrl)) {
                throw SecurityException("Güvensiz medya akış URL'si.")
            }

            val rawVideoFile = File(tempDir, "raw_video.mp4")
            val rawAudioFile = File(tempDir, "raw_audio.m4a")

            downloadStreamWithChunks(videoUrl, rawVideoFile, parallelConnections, maxSpeedBytesPerSec) { p, speed ->
                onProgress((p * 0.82f).coerceIn(0f, 82f), speed, DownloadStatus.DOWNLOADING)
            }
            try {
                downloadStreamWithChunks(audioUrl, rawAudioFile, parallelConnections, maxSpeedBytesPerSec) { p, speed ->
                    onProgress((82f + p * 0.12f).coerceIn(82f, 94f), speed, DownloadStatus.DOWNLOADING)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (audioErr: Exception) {
                // YouTube bazı çok dilli videolarda alternatif dublaj ses akışlarını SABR/403 ile
                // kısıtladığından, indirmeyi bekletmeden 403'süz garantili IOS_PRODUCER itag 140 ile tamamla.
                com.ulgentube.app.util.DiagnosticLogManager.recordEvent(
                    tag = "AudioFallback",
                    message = "Seçilen ses akışı 403 verdi, IOS_PRODUCER itag 140 yedeğine geçildi",
                    metadata = mapOf(
                        "url" to task.url,
                        "reason" to audioErr.message
                    )
                )
                val fallbackAudioUrl = fetchFreshProducerAudioUrl(task.url)
                    ?: throw audioErr
                runCatching { rawAudioFile.delete() }
                downloadStreamWithChunks(fallbackAudioUrl, rawAudioFile, parallelConnections, maxSpeedBytesPerSec) { p, speed ->
                    onProgress((82f + p * 0.12f).coerceIn(82f, 94f), speed, DownloadStatus.DOWNLOADING)
                }
            }

            onProgress(96f, null, DownloadStatus.MERGING)
            val mergedFile = File(tempDir, "merged.mp4")
            return@withContext localFfmpegProcessor.muxOrConvertFiles(
                videoFile = rawVideoFile,
                audioFile = rawAudioFile,
                outputFile = mergedFile,
                isAudioOnly = false,
                targetExtension = "mp4",
                clipStartSeconds = task.clipStartSeconds,
                clipEndSeconds = task.clipEndSeconds,
                transcodeTargetHeight = task.transcodeTargetHeight
            )
        }

        // Tekli (muxed) video akışı
        val videoUrl = rawDirect.trim()
        if (!NetworkSecurityGuard.isSafePublicUrl(videoUrl)) {
            throw SecurityException("Güvensiz video akış URL'si.")
        }
        val singleFile = File(tempDir, "raw_muxed.mp4")
        downloadStreamWithChunks(videoUrl, singleFile, parallelConnections, maxSpeedBytesPerSec) { p, speed ->
            onProgress((p * 0.94f).coerceIn(0f, 94f), speed, DownloadStatus.DOWNLOADING)
        }

        if (hasClip || (task.transcodeTargetHeight != null && task.transcodeTargetHeight > 0)) {
            onProgress(96f, null, DownloadStatus.MERGING)
            val processedFile = File(tempDir, "processed.mp4")
            return@withContext localFfmpegProcessor.muxOrConvertFiles(
                videoFile = singleFile,
                audioFile = singleFile,
                outputFile = processedFile,
                isAudioOnly = false,
                targetExtension = "mp4",
                clipStartSeconds = task.clipStartSeconds,
                clipEndSeconds = task.clipEndSeconds,
                transcodeTargetHeight = task.transcodeTargetHeight
            )
        }

        singleFile
    }

    /**
     * YouTube CDN throttling'ini aşmak için çok bağlantılı paralel Range (HTTP 206) indirmesi yapar;
     * `parallelRangeDownloader` yoksa veya 403 alınırsa `ANDROID_YOUTUBE_USER_AGENT` ile tek bağlantılı
     * parçalı akış yedeğine düşer.
     */
    private suspend fun downloadStreamWithChunks(
        url: String,
        targetFile: File,
        parallelConnections: Int,
        maxSpeedBytesPerSec: Long = 0L,
        overrideUserAgent: String? = null,
        onProgress: (percent: Float, speedText: String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!NetworkSecurityGuard.isSafePublicUrl(url)) {
            throw SecurityException("Güvensiz akış adresi reddedildi.")
        }
        targetFile.parentFile?.mkdirs()
        val effectiveUa = overrideUserAgent ?: IOS_YOUTUBE_USER_AGENT

        if (parallelRangeDownloader != null) {
            try {
                parallelRangeDownloader.download(
                    url = url,
                    targetFile = targetFile,
                    userAgent = effectiveUa,
                    connections = parallelConnections,
                    maxBytes = MAX_INNERTUBE_STREAM_BYTES,
                    maxSpeedBytesPerSec = maxSpeedBytesPerSec,
                    onProgress = onProgress
                )
                return@withContext
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                val is403 = (e as? ParallelRangeDownloader.HttpStatusException)?.statusCode == 403 ||
                    e.message?.contains("403") == true
                if (!is403) throw e
                runCatching { targetFile.delete() }
            }
        }

        downloadStreamLegacy(url, targetFile, overrideUserAgent ?: ANDROID_YOUTUBE_USER_AGENT, maxSpeedBytesPerSec, onProgress)
    }

    private suspend fun downloadStreamLegacy(
        url: String,
        targetFile: File,
        userAgent: String,
        maxSpeedBytesPerSec: Long = 0L,
        onProgress: (percent: Float, speedText: String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        // İlk 2 MB chunk isteği ile toplam boyutu (`Content-Range`) ve 206 desteğini kontrol et
        val firstEnd = CHUNK_SIZE_BYTES - 1L
        val firstReq = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=0-$firstEnd")
            .header("Accept", "*/*")
            .build()

        var downloadedBytes = 0L
        var lastEmitMs = System.currentTimeMillis()
        var lastSnapshotBytes = 0L
        var throttleWindowStartMs = System.currentTimeMillis()
        var throttleWindowBytes = 0L

        suspend fun throttleIfNeeded(bytesRead: Int) {
            if (maxSpeedBytesPerSec <= 0L || bytesRead <= 0) return
            val now = System.currentTimeMillis()
            val elapsed = now - throttleWindowStartMs
            if (elapsed >= 1000L) {
                throttleWindowStartMs = now
                throttleWindowBytes = 0L
            }
            throttleWindowBytes += bytesRead
            if (throttleWindowBytes > maxSpeedBytesPerSec) {
                val expectedMs = (throttleWindowBytes * 1000L) / maxSpeedBytesPerSec
                val sleepMs = (expectedMs - (now - throttleWindowStartMs).coerceAtLeast(0L)).coerceIn(10L, 2000L)
                delay(sleepMs)
            }
        }

        fun emitProgress(totalBytes: Long, force: Boolean = false) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastEmitMs
            if (force || elapsed >= 250L) {
                val delta = downloadedBytes - lastSnapshotBytes
                val speedBytesPerSec = if (elapsed > 0L) (delta * 1000.0) / elapsed else 0.0
                val speedText = if (speedBytesPerSec > 0) formatSpeed(speedBytesPerSec) else null
                val percent = if (totalBytes > 0L) {
                    ((downloadedBytes.toFloat() / totalBytes.toFloat()) * 100f).coerceIn(0f, 100f)
                } else {
                    0f
                }
                onProgress(percent, speedText)
                lastEmitMs = now
                lastSnapshotBytes = downloadedBytes
            }
        }

        try {
            FileOutputStream(targetFile, false).use { output ->
                var totalBytes = -1L

                okHttpClient.newCall(firstReq).execute().use { resp ->
                    if (!NetworkSecurityGuard.isSafePublicUrl(resp.request.url.toString())) {
                        throw SecurityException("Redirect sonrası güvensiz akış adresi reddedildi.")
                    }
                    if (!resp.isSuccessful) {
                        throw IOException("YouTube akışı açılamadı (HTTP ${resp.code}).")
                    }
                    val body = resp.body ?: throw IOException("Boş akış yanıtı.")

                    val contentRange = resp.header("Content-Range")
                    if (resp.code == 206 && !contentRange.isNullOrBlank()) {
                        totalBytes = contentRange.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                    }
                    if (totalBytes <= 0L) {
                        val cl = body.contentLength()
                        if (cl > 0L && resp.code == 200) totalBytes = cl
                    }
                    if (totalBytes > MAX_INNERTUBE_STREAM_BYTES) {
                        throw IOException("Akış güvenlik boyut sınırını aşıyor.")
                    }

                    val buffer = ByteArray(16 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            downloadedBytes += read
                            if (downloadedBytes > MAX_INNERTUBE_STREAM_BYTES) {
                                throw IOException("Akış güvenlik boyut sınırını aşıyor.")
                            }
                            output.write(buffer, 0, read)
                            throttleIfNeeded(read)
                            emitProgress(totalBytes)
                        }
                    }

                    // Sunucu 200 (tüm dosyayı) döndürdüyse burada tamamlandı
                    if (resp.code == 200 || (totalBytes > 0L && downloadedBytes >= totalBytes)) {
                        output.flush()
                        onProgress(100f, null)
                        return@withContext
                    }
                }

                // Kalan byte'ları 2 MB chunk'lar halinde indir (YouTube hız sınırını aşar)
                while (totalBytes <= 0L || downloadedBytes < totalBytes) {
                    currentCoroutineContext().ensureActive()
                    val start = downloadedBytes
                    val end = if (totalBytes > 0L) {
                        minOf(start + CHUNK_SIZE_BYTES - 1L, totalBytes - 1L)
                    } else {
                        start + CHUNK_SIZE_BYTES - 1L
                    }

                    val chunkReq = Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .header("Range", "bytes=$start-$end")
                        .header("Accept", "*/*")
                        .build()

                    var chunkBytes = 0L
                    okHttpClient.newCall(chunkReq).execute().use { chunkResp ->
                        if (!NetworkSecurityGuard.isSafePublicUrl(chunkResp.request.url.toString())) {
                            throw SecurityException("Redirect sonrası güvensiz akış adresi reddedildi.")
                        }
                        if (chunkResp.code == 416) {
                            // Dosya sonuna ulaşıldı
                            output.flush()
                            onProgress(100f, null)
                            return@withContext
                        }
                        if (!chunkResp.isSuccessful) {
                            throw IOException("Parçalı akış indirilemedi (HTTP ${chunkResp.code}).")
                        }
                        val chunkBody = chunkResp.body ?: throw IOException("Boş akış parçası.")
                        if (totalBytes <= 0L) {
                            val cr = chunkResp.header("Content-Range")
                            totalBytes = cr?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                        }

                        val buffer = ByteArray(16 * 1024)
                        chunkBody.byteStream().use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                downloadedBytes += read
                                chunkBytes += read
                                if (downloadedBytes > MAX_INNERTUBE_STREAM_BYTES) {
                                    throw IOException("Akış güvenlik boyut sınırını aşıyor.")
                                }
                                output.write(buffer, 0, read)
                                throttleIfNeeded(read)
                                emitProgress(totalBytes)
                            }
                        }
                    }

                    if (chunkBytes == 0L) break
                }

                output.flush()
                onProgress(100f, null)
            }
        } catch (e: Exception) {
            runCatching { targetFile.delete() }
            throw e
        }
    }

    private fun fetchFreshProducerAudioUrl(videoUrl: String): String? {
        val videoId = extractVideoId(videoUrl) ?: return null
        val json = queryInnertubeClient(
            videoId = videoId,
            clientName = "IOS_PRODUCER",
            clientVersion = "0.147.945",
            clientIdHeader = "92",
            userAgent = IOS_PRODUCER_USER_AGENT,
            extraClientFields = """"deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22D82""""
        ) ?: return null
        val arr = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
        var best140: String? = null
        var bestAnyM4a: String? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val u = obj.optString("url").trim()
            if (u.isBlank() || !NetworkSecurityGuard.isSafePublicUrl(u)) continue
            val mime = obj.optString("mimeType").lowercase(Locale.ROOT)
            if (!mime.startsWith("audio/mp4")) continue
            if (obj.optInt("itag") == 140) {
                best140 = u
                break
            }
            if (bestAnyM4a == null) bestAnyM4a = u
        }
        return best140 ?: bestAnyM4a
    }

    private fun queryInnertubeClient(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientIdHeader: String,
        userAgent: String,
        extraClientFields: String,
        poToken: String? = null,
        visitorData: String? = null
    ): JSONObject? {
        val safeVisitorData = visitorData?.trim()?.takeIf { it.isNotBlank() && !it.contains('"') && !it.contains('\\') }
        val safePoToken = poToken?.trim()?.takeIf { it.isNotBlank() && !it.contains('"') && !it.contains('\\') }

        val visitorField = if (safeVisitorData != null) {
            ""","visitorData":"$safeVisitorData""""
        } else {
            ""
        }
        val integrityField = if (safePoToken != null) {
            ""","serviceIntegrityDimensions":{"poToken":"$safePoToken"}"""
        } else {
            ""
        }

        val payload = """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  "clientName": "$clientName",
                  "clientVersion": "$clientVersion",
                  "hl": "tr",
                  "gl": "TR",
                  $extraClientFields$visitorField
                }
              },
              "contentCheckOk": true,
              "racyCheckOk": true$integrityField
            }
        """.trimIndent()

        val requestBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientIdHeader)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Origin", "https://www.youtube.com")

        if (safeVisitorData != null) {
            requestBuilder.header("X-Goog-Visitor-Id", safeVisitorData)
        }

        // Kullanıcı YouTube oturumu açtıysa Cookie + SAPISIDHASH ekleyerek
        // çok dilli dublaj ve 1080p Premium akışlarındaki 403 kısıtını aş
        val ytAuth = buildYouTubeAuthHeaders()
        if (ytAuth != null) {
            requestBuilder.header("Cookie", ytAuth.first)
            if (ytAuth.second != null) {
                requestBuilder.header("Authorization", ytAuth.second!!)
                requestBuilder.header("X-Origin", "https://www.youtube.com")
            }
        }

        val request = requestBuilder.build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                val streamingData = json.optJSONObject("streamingData")
                val hasAdaptive = (streamingData?.optJSONArray("adaptiveFormats")?.length() ?: 0) > 0
                val hasMuxed = (streamingData?.optJSONArray("formats")?.length() ?: 0) > 0
                if (status == "OK" && (hasAdaptive || hasMuxed)) json else null
            }
        }.getOrNull()
    }

    private fun buildYouTubeAuthHeaders(): Pair<String, String?>? {
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
                        if (fields.size >= 7 &&
                            (fields[0].contains("youtube.com", ignoreCase = true) ||
                                fields[0].contains("google.com", ignoreCase = true))
                        ) {
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
                ?.getCookie("https://www.youtube.com")
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
        if (merged.isEmpty()) return null

        val cookieHeader = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val sapisid = merged["__Secure-3PAPISID"] ?: merged["SAPISID"] ?: merged["__Secure-1PAPISID"]
        val authHeader = if (!sapisid.isNullOrBlank()) {
            runCatching {
                val ts = System.currentTimeMillis() / 1000L
                val input = "$ts $sapisid https://www.youtube.com"
                val digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(input.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { "%02x".format(it) }
                "SAPISIDHASH ${ts}_$hex"
            }.getOrNull()
        } else {
            null
        }
        return cookieHeader to authHeader
    }

    private fun extractBestThumbnail(videoDetails: JSONObject, videoId: String): String {
        val thumbs = videoDetails.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        if (thumbs != null && thumbs.length() > 0) {
            val lastUrl = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
            if (!lastUrl.isNullOrBlank() && NetworkSecurityGuard.isSafePublicUrl(lastUrl)) {
                return lastUrl
            }
        }
        return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
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
        const val DUAL_STREAM_SEPARATOR = "|||"
        private const val IOS_PRODUCER_USER_AGENT =
            "com.google.ios.youtubeproducer/0.147.945 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        private const val IOS_YOUTUBE_USER_AGENT =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        const val ANDROID_YOUTUBE_USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"

        private const val CHUNK_SIZE_BYTES = 2L * 1024L * 1024L // 2 MB chunk
        private const val MAX_INNERTUBE_STREAM_BYTES = 25L * 1024L * 1024L * 1024L // 25 GB (4K/8K destekli)

        private val YOUTUBE_ID_REGEXES = listOf(
            Regex("""(?:v=|/v/|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})"""),
            Regex("""^([A-Za-z0-9_-]{11})$""")
        )

        fun extractVideoId(url: String): String? {
            for (regex in YOUTUBE_ID_REGEXES) {
                val match = regex.find(url)?.groupValues?.getOrNull(1)
                if (!match.isNullOrBlank()) return match
            }
            return null
        }
    }
}
