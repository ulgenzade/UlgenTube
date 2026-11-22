package com.ulgentube.app.util

import com.ulgentube.app.domain.model.PlatformType
import java.net.URI
import java.util.Locale

/**
 * Paylaşım metinlerinden güvenli URL ayıklama ve platform tespit yardımcısı.
 * Zararlı URI şemalarını (file://, javascript:, content://, intent:// vb.) reddeder.
 */
object UrlSanitizer {

    private val URL_REGEX = Regex(
        pattern = """https?://[^\s<>"'`{}\[\]|\\^]+""",
        option = RegexOption.IGNORE_CASE
    )

    private val BLOCKED_SCHEMES = listOf(
        "file://",
        "javascript:",
        "content://",
        "intent://",
        "data:",
        "vbscript:"
    )

    private val DIRECT_IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif"
    )

    /**
     * Ham metin veya paylaşım içeriğinden ilk geçerli `http://` veya `https://` URL'sini çıkarır.
     * Geçerli bir URL bulunamazsa veya zararlı şema tespit edilirse `null` döner.
     */
    fun extractFirstValidUrl(rawText: String?): String? {
        return extractAllValidUrls(rawText).firstOrNull()
    }

    /**
     * Ham metin içindeki tüm geçerli, benzersiz ve güvenli `http://` / `https://` URL'lerini
     * metindeki sırasını koruyarak döndürür. Zararlı şemaları ve yerel/özel ağ adreslerini reddeder.
     */
    fun extractAllValidUrls(rawText: String?): List<String> {
        if (rawText.isNullOrBlank()) return emptyList()

        val trimmed = rawText.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        if (BLOCKED_SCHEMES.any { lower.startsWith(it) }) {
            return emptyList()
        }

        val validUrls = LinkedHashSet<String>()
        for (match in URL_REGEX.findAll(trimmed)) {
            if (isEmbeddedInBlockedScheme(trimmed, match.range.first)) {
                continue
            }
            val candidate = match.value.trimEnd('.', ',', ';', ')', ']', '!', '?', '"', '\'')
            val validated = runCatching {
                val uri = URI(candidate)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                val host = uri.host
                if ((scheme == "http" || scheme == "https") &&
                    !host.isNullOrBlank() &&
                    NetworkSecurityGuard.isSafePublicHost(host)
                ) {
                    candidate
                } else {
                    null
                }
            }.getOrNull()

            if (validated != null) {
                validUrls.add(validated)
            }
        }

        return validUrls.toList()
    }

    private fun isEmbeddedInBlockedScheme(text: String, matchStart: Int): Boolean {
        var tokenStart = matchStart
        while (tokenStart > 0 && !text[tokenStart - 1].isWhitespace()) {
            tokenStart--
        }
        val tokenPrefix = text.substring(tokenStart, matchStart)
            .trimStart('(', '[', '{', '<', '"', '\'')
            .lowercase(Locale.ROOT)
        if (tokenPrefix.isEmpty()) return false
        return BLOCKED_SCHEMES.any { blocked -> tokenPrefix.contains(blocked) }
    }

    /**
     * Verilen URL'nin ait olduğu sosyal medya veya içerik platformunu host ve yol bazlı tespit eder.
     */
    fun detectPlatform(url: String): PlatformType {
        val uri = runCatching { URI(url) }.getOrNull() ?: return PlatformType.OTHER
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.")
            ?: return PlatformType.OTHER
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()

        // Doğrudan görsel bağlantısı kontrolü (Twitter/Reddit dışındaki doğrudan dosya yolları)
        if (isDirectImagePath(path)) {
            return PlatformType.DIRECT_IMAGE
        }

        return when {
            host == "youtu.be" ||
                host == "youtube.com" ||
                host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" -> PlatformType.YOUTUBE

            host == "instagram.com" ||
                host.endsWith(".instagram.com") ||
                host == "instagr.am" -> PlatformType.INSTAGRAM

            host == "tiktok.com" ||
                host.endsWith(".tiktok.com") -> PlatformType.TIKTOK

            host == "twitter.com" ||
                host.endsWith(".twitter.com") ||
                host == "x.com" ||
                host.endsWith(".x.com") ||
                host == "t.co" ||
                host == "fxtwitter.com" ||
                host == "vxtwitter.com" -> PlatformType.TWITTER_X

            host == "facebook.com" ||
                host.endsWith(".facebook.com") ||
                host == "fb.watch" ||
                host == "fb.com" -> PlatformType.FACEBOOK

            host == "reddit.com" ||
                host.endsWith(".reddit.com") ||
                host == "redd.it" ||
                host == "v.redd.it" ||
                host == "rxddit.com" ||
                host.endsWith(".rxddit.com") ||
                host == "vxreddit.com" ||
                host.endsWith(".vxreddit.com") -> PlatformType.REDDIT

            host == "pinterest.com" ||
                host.endsWith(".pinterest.com") ||
                host.contains(".pinterest.") ||
                host == "pin.it" -> PlatformType.PINTEREST

            host == "soundcloud.com" ||
                host.endsWith(".soundcloud.com") ||
                host == "on.soundcloud.com" -> PlatformType.SOUNDCLOUD

            host == "vimeo.com" ||
                host.endsWith(".vimeo.com") -> PlatformType.VIMEO

            host == "twitch.tv" ||
                host.endsWith(".twitch.tv") -> PlatformType.TWITCH

            host == "kick.com" ||
                host.endsWith(".kick.com") -> PlatformType.KICK

            else -> PlatformType.OTHER
        }
    }

    /**
     * URL yolunun bilinen bir doğrudan görsel uzantısı ile bitip bitmediğini kontrol eder.
     */
    fun isDirectImagePath(pathOrUrl: String): Boolean {
        val cleanPath = pathOrUrl.substringBefore('?').substringBefore('#')
        val ext = cleanPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in DIRECT_IMAGE_EXTENSIONS
    }

    /**
     * URL'den görsel uzantısını çıkarır, bulunamazsa varsayılan olarak "jpg" döner.
     */
    fun guessImageExtension(url: String): String {
        val cleanPath = url.substringBefore('?').substringBefore('#')
        val ext = cleanPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            "avif" -> "avif"
            else -> {
                val lower = url.lowercase(Locale.ROOT)
                when {
                    lower.contains("format=png") -> "png"
                    lower.contains("format=webp") -> "webp"
                    lower.contains("format=gif") -> "gif"
                    else -> "jpg"
                }
            }
        }
    }
}
