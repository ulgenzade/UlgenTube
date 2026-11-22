package com.ulgentube.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class LrcLine(
    val timeMs: Long,
    val text: String
)

/**
 * LRCLIB (`https://lrclib.net`) API üzerinden senkronize (`.lrc`) veya düz şarkı sözü çeker.
 */
class LrcLyricsFetcher(
    private val okHttpClient: OkHttpClient
) {

    suspend fun fetchLyrics(
        title: String,
        artist: String?,
        durationSeconds: Long? = null
    ): String? = withContext(Dispatchers.IO) {
        val (cleanTitle, cleanArtist) = cleanTrackQuery(title, artist)
        if (cleanTitle.isBlank()) return@withContext null

        // 1) Doğrudan kesin eşleşme: /api/get
        val directResult = runCatching {
            val urlBuilder = LRCLIB_GET_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("track_name", cleanTitle)
            if (!cleanArtist.isNullOrBlank()) {
                urlBuilder.addQueryParameter("artist_name", cleanArtist)
            }
            if (durationSeconds != null && durationSeconds > 0L) {
                urlBuilder.addQueryParameter("duration", durationSeconds.toString())
            }
            executeLrclibRequest(urlBuilder.build().toString())
        }.getOrNull()

        if (!directResult.isNullOrBlank()) {
            return@withContext directResult
        }

        // 2) Yedek arama uç noktası: /api/search
        val searchResult = runCatching {
            val queryText = listOfNotNull(cleanArtist, cleanTitle)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val urlBuilder = LRCLIB_SEARCH_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("q", queryText)
            executeLrclibRequest(urlBuilder.build().toString())
        }.getOrNull()

        searchResult?.takeIf { it.isNotBlank() }
    }

    private fun executeLrclibRequest(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LRCLIB_USER_AGENT)
            .header("Accept", "application/json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyStr = response.body?.string() ?: return null
            return parseLrcResponseJson(bodyStr)
        }
    }

    companion object {
        private const val LRCLIB_GET_ENDPOINT = "https://lrclib.net/api/get"
        private const val LRCLIB_SEARCH_ENDPOINT = "https://lrclib.net/api/search"
        private const val LRCLIB_USER_AGENT = "UlgenTube/1.0 (Android; https://github.com/ulgentube)"

        private val BRACKET_NOISE_REGEX = Regex(
            """[\(\[\{]\s*(?:official(?:\s+music)?\s+video|official\s+audio|official\s+lyric(?:s)?\s+video|official\s+visualizer|lyric(?:s)?\s+video|lyrics?|audio|music\s+video|visualizer|hd|hq|4k|8k|remastered(?:\s+\d{4})?|explicit|clean|live|video\s+clip|klip|resmi\s+video|feat\.?[^)\]}]*|ft\.?[^)\]}]*)\s*[\)\]\}]""",
            RegexOption.IGNORE_CASE
        )
        private val TRAILING_NOISE_REGEX = Regex(
            """(?:\s*[-|•]\s*(?:youtube|official\s+video|official\s+audio|lyrics?|hd|4k))+$""",
            RegexOption.IGNORE_CASE
        )
        private val AUTHOR_SUFFIX_REGEX = Regex(
            """\s*(?:-\s*topic|vevo|official)$""",
            RegexOption.IGNORE_CASE
        )
        private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")
        private val LRC_TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]""")

        /**
         * Video/müzik başlığındaki `(Official Video)`, `[Lyrics]`, `[4K]`, `- YouTube` gibi
         * gereksiz etiketleri temizler; sanatçı boşsa `Sanatçı - Parça` kalıbını ayırır.
         */
        fun cleanTrackQuery(rawTitle: String, rawAuthor: String?): Pair<String, String?> {
            var title = rawTitle.trim()
            title = BRACKET_NOISE_REGEX.replace(title, " ")
            title = TRAILING_NOISE_REGEX.replace(title, "")
            title = MULTI_SPACE_REGEX.replace(title, " ").trim().trim('-', '|', '•', ' ')

            var author = rawAuthor?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
                AUTHOR_SUFFIX_REGEX.replace(raw, "").trim().takeIf { it.isNotBlank() }
            }

            // Başlık "Sanatçı - Parça" formatındaysa ve sanatçı yoksa ya da kanal adıysa ayır
            val dashParts = title.split(" - ", " – ", " — ", limit = 2)
            if (dashParts.size == 2) {
                val left = dashParts[0].trim()
                val right = dashParts[1].trim()
                if (left.isNotBlank() && right.isNotBlank()) {
                    if (author.isNullOrBlank() ||
                        left.equals(author, ignoreCase = true) ||
                        author.contains(left, ignoreCase = true)
                    ) {
                        author = left
                        title = right
                    }
                }
            }

            // Parça adında kalan son temizlik
            title = BRACKET_NOISE_REGEX.replace(title, " ")
            title = MULTI_SPACE_REGEX.replace(title, " ").trim()

            return title to author
        }

        /**
         * LRCLIB `/api/get` (JSONObject) veya `/api/search` (JSONArray) JSON yanıtından
         * öncelikle `syncedLyrics`, yoksa `plainLyrics` metnini ayıklar.
         */
        fun parseLrcResponseJson(json: String): String? {
            val trimmed = json.trim()
            if (trimmed.isBlank()) return null

            return runCatching {
                if (trimmed.startsWith("[")) {
                    val arr = JSONArray(trimmed)
                    var plainFallback: String? = null
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val synced = extractNonBlankJsonField(obj, "syncedLyrics")
                        if (synced != null) return@runCatching synced
                        if (plainFallback == null) {
                            plainFallback = extractNonBlankJsonField(obj, "plainLyrics")
                        }
                    }
                    plainFallback
                } else {
                    val obj = JSONObject(trimmed)
                    extractNonBlankJsonField(obj, "syncedLyrics")
                        ?: extractNonBlankJsonField(obj, "plainLyrics")
                }
            }.getOrNull()
        }

        private fun extractNonBlankJsonField(obj: JSONObject, key: String): String? {
            if (obj.isNull(key)) return null
            val value = obj.optString(key).trim()
            return value.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        }

        /**
         * `[mm:ss.xx]` ve `[mm:ss.xxx]` formatındaki LRC satırlarını zaman sırasına göre [LrcLine] listesine çevirir.
         */
        fun parseLrcTimestamps(lrc: String): List<LrcLine> {
            if (lrc.isBlank()) return emptyList()

            val result = mutableListOf<LrcLine>()
            lrc.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank()) return@forEach

                val matches = LRC_TIMESTAMP_REGEX.findAll(line).toList()
                if (matches.isEmpty()) return@forEach

                val text = LRC_TIMESTAMP_REGEX.replace(line, "").trim()
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                    val fracStr = match.groupValues.getOrNull(3).orEmpty()
                    val millis = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        else -> fracStr.take(3).toLongOrNull() ?: 0L
                    }
                    val timeMs = (minutes * 60_000L) + (seconds * 1_000L) + millis
                    result += LrcLine(timeMs = timeMs, text = text)
                }
            }

            return result.sortedBy { it.timeMs }
        }
    }
}
