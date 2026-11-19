package com.ulgentube.app.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Kullanıcı tarafından içe aktarılan Netscape formatındaki `cookies.txt` dosyasını
 * uygulamanın özel dahili dizininde (`filesDir/cookies/cookies.txt`) güvenli biçimde saklar.
 * Diğer uygulamalar bu dizine erişemez.
 */
class CookieRepository(private val context: Context) {

    private val cookiesDir: File
        get() = File(context.filesDir, COOKIES_DIR_NAME)

    private val storedCookieFile: File
        get() = File(cookiesDir, COOKIE_FILE_NAME)

    /**
     * Geçerli ve boş olmayan bir çerez dosyasının kayıtlı olup olmadığını döndürür.
     */
    fun hasCookies(): Boolean {
        val file = storedCookieFile
        return file.exists() && file.isFile && file.length() > 0L
    }

    /**
     * Kayıtlı çerez dosyasını döndürür; yoksa veya boşsa `null` döner.
     */
    fun getCookieFile(): File? {
        val file = storedCookieFile
        return if (file.exists() && file.isFile && file.length() > 0L) file else null
    }

    /**
     * Kullanıcının seçtiği `Uri` üzerinden Netscape çerez dosyasını okur (en fazla 2 MB),
     * çerez satırlarını doğrular ve uygulamanın özel dizinine güvenli şekilde yazar.
     *
     * @return İçe aktarılan geçerli çerez satırı sayısı.
     */
    suspend fun importFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val rawBytes = readLimitedBytes(uri, MAX_COOKIE_FILE_BYTES)
            if (rawBytes.isEmpty()) {
                throw IllegalArgumentException("Seçilen çerez dosyası boş.")
            }

            val content = rawBytes.toString(Charsets.UTF_8)
            val cookieLineCount = countValidNetscapeLines(content)
            if (cookieLineCount <= 0) {
                throw IllegalArgumentException(
                    "Geçerli Netscape cookies.txt biçiminde çerez satırı bulunamadı."
                )
            }

            val dir = cookiesDir
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Çerez dizini oluşturulamadı.")
            }

            val target = storedCookieFile
            val canonicalDir = dir.canonicalPath + File.separator
            if (!target.canonicalPath.startsWith(canonicalDir)) {
                throw SecurityException("Geçersiz çerez dosya yolu.")
            }

            val tempFile = File(dir, "$COOKIE_FILE_NAME.tmp")
            try {
                tempFile.writeBytes(rawBytes)
                tempFile.setReadable(false, false)
                tempFile.setReadable(true, true)
                tempFile.setWritable(false, false)
                tempFile.setWritable(true, true)

                if (target.exists() && !target.delete()) {
                    throw IOException("Eski çerez dosyası güncellenemedi.")
                }
                if (!tempFile.renameTo(target)) {
                    tempFile.copyTo(target, overwrite = true)
                    tempFile.delete()
                }
                target.setReadable(false, false)
                target.setReadable(true, true)
                target.setWritable(false, false)
                target.setWritable(true, true)
            } catch (e: Exception) {
                runCatching { tempFile.delete() }
                throw e
            }

            cookieLineCount
        }
    }

    /**
     * Kayıtlı çerez dosyasını kalıcı olarak siler.
     */
    fun clearCookies(): Boolean {
        val file = storedCookieFile
        if (!file.exists()) return true
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private fun readLimitedBytes(uri: Uri, maxBytes: Int): ByteArray {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Seçilen dosya açılamadı.")

        inputStream.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalRead = 0
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                totalRead += read
                if (totalRead > maxBytes) {
                    throw IllegalArgumentException("Çerez dosyası en fazla 2 MB olabilir.")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun countValidNetscapeLines(content: String): Int {
        var count = 0
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            val normalized = when {
                line.startsWith("#HttpOnly_", ignoreCase = true) ->
                    line.removePrefix("#HttpOnly_").removePrefix("#httponly_")
                line.startsWith("#") -> return@forEach
                else -> line
            }

            // Netscape formatı: domain \t flag \t path \t secure \t expiration \t name \t value (7 alan)
            val fields = normalized.split('\t')
            if (fields.size >= 7 && fields[0].isNotBlank() && fields[5].isNotBlank()) {
                count++
            }
        }
        return count
    }

    companion object {
        private const val COOKIES_DIR_NAME = "cookies"
        private const val COOKIE_FILE_NAME = "cookies.txt"
        private const val MAX_COOKIE_FILE_BYTES = 2 * 1024 * 1024 // 2 MB
    }
}
