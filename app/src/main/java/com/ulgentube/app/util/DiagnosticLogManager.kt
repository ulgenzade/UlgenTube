package com.ulgentube.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.ulgentube.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uygulama içi hata ve teşhis günlüğü yöneticisi:
 * - İndirme, çözümleme, muxing ve yakalanmayan çökmeleri (crash) kalıcı günlük dosyasına kaydeder.
 * - Hassas verileri (çerez, token, sessionid, imza) otomatik maskeler.
 * - Tek tıkla panoya kopyalama veya `.txt` dosyası olarak paylaşma imkânı sunar.
 */
object DiagnosticLogManager {

    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "ulgentube_diagnostics.log"
    private const val MAX_LOG_BYTES = 512 * 1024L // 512 KB dönen sınır
    private const val MAX_STACK_LINES = 14

    private val lock = Any()
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var initialized = false

    private val sensitivePatterns = listOf(
        Regex("""(sessionid|csrftoken|ds_user_id|cookie|authorization|pot|sig|lsig|s)=([^&\s;,"']+)""", RegexOption.IGNORE_CASE),
        Regex("""("poToken"\s*:\s*")([^"]+)(")""", RegexOption.IGNORE_CASE)
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    recordError(
                        tag = "CRASH",
                        summary = "Yakalanmayan uygulama çökmesi (${thread.name})",
                        throwable = throwable
                    )
                }
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun recordEvent(tag: String, message: String, metadata: Map<String, String?> = emptyMap()) {
        appendEntry(level = "INFO", tag = tag, summary = message, throwable = null, metadata = metadata)
    }

    fun recordError(
        tag: String,
        summary: String,
        throwable: Throwable? = null,
        metadata: Map<String, String?> = emptyMap()
    ) {
        appendEntry(level = "ERROR", tag = tag, summary = summary, throwable = throwable, metadata = metadata)
    }

    fun readFullReport(context: Context? = appContext): String {
        val ctx = context?.applicationContext ?: appContext
        val header = buildDeviceHeader()
        val body = synchronized(lock) {
            val file = ctx?.let { logFile(it) }
            if (file == null || !file.exists() || file.length() <= 0L) {
                "(Henüz kayıtlı hata günlüğü yok.)"
            } else {
                runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("(Günlük okunamadı.)")
            }
        }
        return "$header\n\n$body"
    }

    fun readRecentReport(context: Context? = appContext, maxChars: Int = 12_000): String {
        val full = readFullReport(context)
        if (full.length <= maxChars) return full
        val header = buildDeviceHeader()
        val tail = full.takeLast((maxChars - header.length - 64).coerceAtLeast(1000))
        return "$header\n\n...[eski kayıtlar kısaltıldı]...\n$tail"
    }

    fun getEntryCount(context: Context? = appContext): Int {
        val ctx = context?.applicationContext ?: appContext ?: return 0
        return synchronized(lock) {
            val file = logFile(ctx)
            if (!file.exists()) return@synchronized 0
            runCatching {
                file.useLines { lines -> lines.count { it.startsWith("[20") && it.contains("] [") } }
            }.getOrDefault(0)
        }
    }

    fun clear(context: Context? = appContext) {
        val ctx = context?.applicationContext ?: appContext ?: return
        synchronized(lock) {
            runCatching { logFile(ctx).delete() }
        }
    }

    fun copyReportToClipboard(context: Context, extraContext: String? = null): Boolean {
        val report = buildString {
            if (!extraContext.isNullOrBlank()) {
                appendLine("=== SEÇİLİ HATA BAĞLAMI ===")
                appendLine(sanitizeSensitive(extraContext))
                appendLine()
            }
            append(readRecentReport(context))
        }
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("UlgenTube Hata Raporu", report))
            true
        }.getOrDefault(false)
    }

    fun shareReportAsFile(context: Context): Result<Unit> = runCatching {
        val reportText = readFullReport(context)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.cacheDir, "ulgentube_hata_raporu_$stamp.txt")
        outFile.writeText(reportText, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "UlgenTube Hata Raporu ($stamp)")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "Hata Raporunu Paylaş").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun appendEntry(
        level: String,
        tag: String,
        summary: String,
        throwable: Throwable?,
        metadata: Map<String, String?>
    ) {
        val ctx = appContext ?: return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            appendLine("[$timestamp] [$level] [$tag] ${sanitizeSensitive(summary)}")
            metadata.forEach { (k, v) ->
                if (!v.isNullOrBlank()) {
                    appendLine("  $k: ${sanitizeSensitive(v.take(240))}")
                }
            }
            if (throwable != null) {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackLines = sw.toString()
                    .lineSequence()
                    .take(MAX_STACK_LINES)
                    .map { "  $it" }
                    .joinToString("\n")
                appendLine(sanitizeSensitive(stackLines))
            }
            appendLine("------------------------------------------------------------")
        }

        synchronized(lock) {
            runCatching {
                val file = logFile(ctx)
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() > MAX_LOG_BYTES) {
                    val tail = file.readText(Charsets.UTF_8).takeLast((MAX_LOG_BYTES / 2).toInt())
                    file.writeText(tail, Charsets.UTF_8)
                }
                file.appendText(entry, Charsets.UTF_8)
            }
        }
    }

    private fun buildDeviceHeader(): String {
        return buildString {
            appendLine("=== UlgenTube Teşhis Raporu ===")
            appendLine("Uygulama: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT})")
            appendLine("Tarih: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        }.trimEnd()
    }

    internal fun sanitizeSensitive(raw: String): String {
        var result = raw
        result = sensitivePatterns[0].replace(result) { m -> "${m.groupValues[1]}=***" }
        result = sensitivePatterns[1].replace(result) { m -> "${m.groupValues[1]}***${m.groupValues[3]}" }
        return result
    }

    private fun logFile(context: Context): File = File(File(context.filesDir, LOG_DIR_NAME), LOG_FILE_NAME)
}
