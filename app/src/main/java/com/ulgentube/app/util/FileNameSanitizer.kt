package com.ulgentube.app.util

/**
 * Dosya adlarından path traversal (`..`, `/`, `\`), kontrol karakterleri
 * ve dosya sistemleri için yasak karakterleri temizleyen yardımcı sınıf.
 */
object FileNameSanitizer {

    private const val FALLBACK_NAME = "ulgentube_media"

    private val FORBIDDEN_CHARS_REGEX = Regex(
        """[<>:"/\\|?*\u0000-\u001F\u007F\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]"""
    )
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val MULTIPLE_UNDERSCORES_REGEX = Regex("""_+""")
    private val RESERVED_DEVICE_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * Ham başlığı güvenli, taşınabilir bir dosya adına dönüştürür.
     */
    fun sanitize(rawTitle: String, maxLength: Int = 80): String {
        val safeMaxLength = maxLength.coerceAtLeast(16)

        var cleaned = rawTitle
            .replace(FORBIDDEN_CHARS_REGEX, " ")
        while (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", "")
        }
        cleaned = cleaned
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .trim('.', ' ', '_', '-')

        if (cleaned.isBlank()) {
            return FALLBACK_NAME
        }

        if (cleaned.length > safeMaxLength) {
            cleaned = cleaned.take(safeMaxLength).trimEnd('.', ' ', '_', '-')
        }

        cleaned = cleaned.replace(MULTIPLE_UNDERSCORES_REGEX, "_")

        if (cleaned.uppercase() in RESERVED_DEVICE_NAMES) {
            cleaned = "ulgen_$cleaned"
        }

        return cleaned.ifBlank { FALLBACK_NAME }
    }

    /**
     * Güvenli dosya adı ile uzantıyı birleştirir.
     */
    fun buildFileName(rawTitle: String, extension: String, suffix: String? = null): String {
        val cleanExt = extension
            .trim()
            .lowercase()
            .removePrefix(".")
            .replace(Regex("[^a-z0-9]"), "")
            .ifBlank { "mp4" }

        val base = sanitize(rawTitle)
        val fullBase = if (suffix.isNullOrBlank()) {
            base
        } else {
            "${base}_${sanitize(suffix, maxLength = 24)}"
        }
        return "$fullBase.$cleanExt"
    }
}
