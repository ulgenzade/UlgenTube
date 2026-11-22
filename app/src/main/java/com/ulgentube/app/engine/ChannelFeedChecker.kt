package com.ulgentube.app.engine

import com.ulgentube.app.util.NetworkSecurityGuard
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException

/**
 * YouTube Atom RSS beslemesinden çözümlenen kanal ve en son video özeti.
 */
data class ChannelFeedSnapshot(
    val channelId: String,
    val title: String,
    val canonicalUrl: String,
    val latestVideoId: String?,
    val latestVideoTitle: String?,
    val latestVideoUrl: String?
)

/**
 * YouTube kanal linklerini (`/channel/UC...`, `@handle`, `/c/...`, `/user/...`)
 * `UC...` kanal kimliğine çözüp ücretsiz YouTube Atom RSS beslemesi
 * (`https://www.youtube.com/feeds/videos.xml?channel_id=...`) üzerinden son yüklenen videoyu takip eder.
 */
class ChannelFeedChecker(
    private val okHttpClient: OkHttpClient
) {

    /**
     * Kanal URL'sini, `@handle` adını veya doğrudan `UC...` kimliğini çözümler ve
     * YouTube Atom RSS beslemesini çekerek [ChannelFeedSnapshot] döndürür.
     */
    suspend fun resolveAndFetch(urlOrHandle: String): ChannelFeedSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = urlOrHandle.trim()
            if (trimmed.isBlank()) return@runCatching null

            val resolvedChannelId = resolveChannelId(trimmed) ?: return@runCatching null
            val feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$resolvedChannelId"
            if (!NetworkSecurityGuard.isSafePublicUrl(feedUrl)) return@runCatching null

            val xml = fetchText(feedUrl) ?: return@runCatching null
            val parsed = parseYouTubeAtomFeed(xml) ?: return@runCatching null

            if (parsed.channelId.isBlank()) {
                parsed.copy(
                    channelId = resolvedChannelId,
                    canonicalUrl = parsed.canonicalUrl.ifBlank {
                        "https://www.youtube.com/channel/$resolvedChannelId"
                    }
                )
            } else {
                parsed
            }
        }.getOrNull()
    }

    private fun resolveChannelId(input: String): String? {
        // Doğrudan UC... kanal kimliği verilmişse
        if (RAW_CHANNEL_ID_REGEX.matches(input)) {
            return input
        }

        // URL içinde /channel/UC... doğrudan geçiyorsa
        CHANNEL_PATH_REGEX.find(input)?.groupValues?.getOrNull(1)?.let { return it }

        // @handle veya tam/kısmi YouTube kanal adresi ise HTML'den channelId çıkar
        val targetUrl = normalizeChannelUrl(input) ?: return null
        if (!NetworkSecurityGuard.isSafePublicUrl(targetUrl)) return null

        val html = fetchText(targetUrl) ?: return null
        return extractChannelIdFromHtml(html)
    }

    private fun normalizeChannelUrl(input: String): String? {
        val cleaned = input.trim()
        return when {
            cleaned.startsWith("@") -> {
                val handle = cleaned.takeWhile { !it.isWhitespace() && it != '/' && it != '?' }
                if (handle.length >= 2) "https://www.youtube.com/$handle" else null
            }
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) -> {
                UrlSanitizer.extractFirstValidUrl(cleaned)
            }
            cleaned.contains("youtube.com", ignoreCase = true) -> {
                UrlSanitizer.extractFirstValidUrl("https://$cleaned")
            }
            else -> {
                val safeHandle = cleaned.trimStart('@').takeWhile { !it.isWhitespace() && it != '/' }
                if (safeHandle.isNotBlank()) "https://www.youtube.com/@$safeHandle" else null
            }
        }
    }

    private fun fetchText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Kanal yanıtı alınamadı: HTTP ${response.code}")
            }
            if (!NetworkSecurityGuard.isSafePublicUrl(response.request.url.toString())) {
                throw SecurityException("Güvensiz yönlendirme engellendi.")
            }
            return response.body?.string()
        }
    }

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val RAW_CHANNEL_ID_REGEX = Regex("""^UC[a-zA-Z0-9_-]{20,30}$""")
        private val CHANNEL_PATH_REGEX = Regex("""/channel/(UC[a-zA-Z0-9_-]{20,30})""")
        private val HTML_CHANNEL_PATTERNS = listOf(
            Regex("""<link[^>]+rel="canonical"[^>]+href="https?://(?:www\.)?youtube\.com/channel/(UC[a-zA-Z0-9_-]{20,30})""""),
            Regex(""""externalId"\s*:\s*"(UC[a-zA-Z0-9_-]{20,30})""""),
            Regex(""""channelId"\s*:\s*"(UC[a-zA-Z0-9_-]{20,30})""""),
            Regex(""""browseId"\s*:\s*"(UC[a-zA-Z0-9_-]{20,30})"""")
        )

        /**
         * HTML sayfasından YouTube `UC...` kanal kimliğini ayıklar.
         */
        fun extractChannelIdFromHtml(html: String): String? {
            if (html.isBlank()) return null
            for (pattern in HTML_CHANNEL_PATTERNS) {
                val match = pattern.find(html)?.groupValues?.getOrNull(1)
                if (!match.isNullOrBlank()) return match
            }
            return null
        }

        /**
         * YouTube Atom RSS XML metnini saf XML ayrıştırıcı (Jsoup XML Parser) ile ayrıştırır;
         * kanal kimliği, başlık ve en son `<entry>` video bilgilerini döndürür.
         */
        fun parseYouTubeAtomFeed(xml: String): ChannelFeedSnapshot? {
            if (xml.isBlank()) return null
            return runCatching {
                val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                val feed = doc.selectFirst("feed") ?: return@runCatching null

                val rawChannelId = feed.selectFirst("yt\\:channelId")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: feed.selectFirst("channelId")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: feed.selectFirst("> id")?.text()?.trim()
                        ?.substringAfter("yt:channel:", "")
                        ?.takeIf { it.isNotBlank() }
                    ?: ""

                val title = feed.selectFirst("> title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: feed.selectFirst("author > name")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: ""

                if (rawChannelId.isBlank() && title.isBlank()) {
                    return@runCatching null
                }

                val feedAlternateUrl = feed.select("> link")
                    .firstOrNull { it.attr("rel").equals("alternate", ignoreCase = true) }
                    ?.attr("href")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: if (rawChannelId.isNotBlank()) {
                        "https://www.youtube.com/channel/$rawChannelId"
                    } else {
                        ""
                    }

                val firstEntry = feed.selectFirst("entry")
                val latestVideoId = firstEntry?.let { entry ->
                    entry.selectFirst("yt\\:videoId")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: entry.selectFirst("videoId")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: entry.selectFirst("id")?.text()?.trim()
                            ?.substringAfter("yt:video:", "")
                            ?.takeIf { it.isNotBlank() }
                }

                val latestVideoTitle = firstEntry?.selectFirst("title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }

                val latestVideoUrl = firstEntry?.select("link")
                    ?.firstOrNull { it.attr("rel").equals("alternate", ignoreCase = true) }
                    ?.attr("href")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: latestVideoId?.let { "https://www.youtube.com/watch?v=$it" }

                ChannelFeedSnapshot(
                    channelId = rawChannelId,
                    title = title.ifBlank { rawChannelId },
                    canonicalUrl = feedAlternateUrl,
                    latestVideoId = latestVideoId,
                    latestVideoTitle = latestVideoTitle,
                    latestVideoUrl = latestVideoUrl
                )
            }.getOrNull()
        }
    }
}
