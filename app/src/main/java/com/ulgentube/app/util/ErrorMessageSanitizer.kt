package com.ulgentube.app.util

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Ham istisna, Python/yt-dlp izleri ve dahili dosya yollarını kullanıcıya göstermeden
 * güvenli ve anlaşılır Türkçe hata mesajlarına dönüştürür.
 */
object ErrorMessageSanitizer {

    private val INTERNAL_PATH_REGEX = Regex("""(/data/|/storage/|[A-Za-z]:\\|Traceback|File ".*")""", RegexOption.IGNORE_CASE)

    fun sanitize(throwable: Throwable?, fallback: String = "İşlem sırasında bir hata oluştu."): String {
        if (throwable == null) return fallback

        return when (throwable) {
            is UnknownHostException -> "Sunucuya erişilemedi veya bağlantı engellendi. İnternet bağlantınızı kontrol edin."
            is SocketTimeoutException -> "Bağlantı zaman aşımına uğradı. Lütfen tekrar deneyin."
            is SSLException -> "Güvenli bağlantı (HTTPS) kurulamadı."
            is SecurityException -> "Gerekli izin sağlanamadığı için işlem tamamlanamadı."
            is IllegalArgumentException -> cleanSafeMessage(throwable.message, "Geçersiz bağlantı veya parametre.")
            is IOException -> cleanSafeMessage(throwable.message, "Ağ veya depolama hatası oluştu.")
            else -> cleanSafeMessage(throwable.message, fallback)
        }
    }

    private fun cleanSafeMessage(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        val lower = raw.lowercase()
        return when {
            lower.contains("cleartext") ->
                "Güvenlik nedeniyle yalnızca şifreli (HTTPS) bağlantılar desteklenir."
            lower.contains("private") || lower.contains("login") || lower.contains("sign in") ->
                "Bu içerik gizli veya giriş gerektiriyor. Herkese açık bir bağlantı deneyin."
            lower.contains("not available") || lower.contains("unavailable") || lower.contains("404") ->
                "İçerik bulunamadı veya erişime kapalı."
            lower.contains("429") || lower.contains("too many requests") ->
                "Platform geçici olarak hız sınırı uyguladı. Biraz sonra tekrar deneyin."
            lower.contains("unsupported url") ->
                "Bu bağlantı türü henüz desteklenmiyor."
            lower.contains("yerel veya özel ağ") ->
                "Yerel ağ adreslerine güvenlik nedeniyle erişilemez."
            lower.contains("boyut sınırı") || lower.contains("çok büyük") ->
                raw.take(140)
            INTERNAL_PATH_REGEX.containsMatchIn(raw) || raw.length > 160 || raw.contains('\n') ->
                fallback
            else -> raw.trim()
        }
    }
}
