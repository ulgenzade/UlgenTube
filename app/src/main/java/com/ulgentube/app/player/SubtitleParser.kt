package com.ulgentube.app.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ulgentube.app.domain.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tek bir altyazı zaman aralığı ve temizlenmiş metin bloğu.
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Oynatıcıda seçilebilir yerel veya harici altyazı dosyası öğesi.
 */
data class SubtitleTrackItem(
    val label: String,
    val uri: Uri
)

/**
 * `.srt` ve `.vtt` (WebVTT) altyazı dosyalarını ayrıştıran ve yerel yan dosyaları bulan yardımcı nesne.
 */
object SubtitleParser {

    private val TIMESTAMP_LINE_REGEX = Regex(
        """^\s*(\d{1,2}:)?\d{2}:\d{2}[.,]\d{1,3}\s*-->\s*(\d{1,2}:)?\d{2}:\d{2}[.,]\d{1,3}.*$"""
    )

    private val HTML_AND_VTT_TAG_REGEX = Regex("""<[^>]+>""")
    private val ASS_STYLE_TAG_REGEX = Regex("""\{\\[^}]*\}""")

    /**
     * Ham `.srt` veya `.vtt` içeriğini [SubtitleCue] listesine dönüştürür.
     * HTML/VTT biçimlendirme etiketlerini temizler, çok satırlı altyazıları korur.
     */
    fun parse(rawContent: String): List<SubtitleCue> {
        if (rawContent.isBlank()) return emptyList()

        val normalized = rawContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val lines = normalized.split("\n")
        val cues = mutableListOf<SubtitleCue>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index].trim()

            // Boş satırları veya WebVTT başlık / stil / not bloklarını atla
            if (line.isEmpty() || line.startsWith("WEBVTT", ignoreCase = true)) {
                index++
                continue
            }

            if (line.startsWith("NOTE", ignoreCase = true) ||
                line.startsWith("STYLE", ignoreCase = true) ||
                line.startsWith("REGION", ignoreCase = true)
            ) {
                index++
                while (index < lines.size && lines[index].isNotBlank()) {
                    index++
                }
                continue
            }

            val timeLine = when {
                TIMESTAMP_LINE_REGEX.matches(line) -> line
                index + 1 < lines.size && TIMESTAMP_LINE_REGEX.matches(lines[index + 1].trim()) -> {
                    index++
                    lines[index].trim()
                }
                else -> {
                    index++
                    continue
                }
            }

            val arrowIndex = timeLine.indexOf("-->")
            if (arrowIndex <= 0) {
                index++
                continue
            }

            val startRaw = timeLine.substring(0, arrowIndex).trim()
            val endSegment = timeLine.substring(arrowIndex + 3).trim()
            // WebVTT satır sonundaki cue ayarlarını (align:start, position:% vb.) ayır
            val endRaw = endSegment.split(Regex("""\s+""")).firstOrNull().orEmpty()

            val startMs = parseTimestampToMs(startRaw)
            val endMs = parseTimestampToMs(endRaw)

            index++
            val textLines = mutableListOf<String>()
            while (index < lines.size && lines[index].isNotBlank()) {
                val cleaned = cleanCueLine(lines[index])
                if (cleaned.isNotEmpty()) {
                    textLines.add(cleaned)
                }
                index++
            }

            if (startMs != null && endMs != null && endMs >= startMs && textLines.isNotEmpty()) {
                cues.add(
                    SubtitleCue(
                        startMs = startMs,
                        endMs = endMs,
                        text = textLines.joinToString("\n")
                    )
                )
            }
        }

        return cues.sortedBy { it.startMs }
    }

    /**
     * Verilen oynatma konumunda (`positionMs`) görünmesi gereken aktif altyazı metnini döndürür.
     */
    fun activeCueAt(cues: List<SubtitleCue>, positionMs: Long): String? {
        if (cues.isEmpty() || positionMs < 0L) return null
        val matched = cues.filter { positionMs in it.startMs..it.endMs }
        if (matched.isEmpty()) return null
        val uniqueLines = matched
            .flatMap { it.text.lines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return uniqueLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * İndirilen medya dosyasının klasöründe ve Downloads / MediaStore üzerinde
     * aynı dosya adı önekiyle eşleşen `.srt` / `.vtt` yan altyazı dosyalarını tarar.
     */
    suspend fun findMatchingSubtitleUris(
        context: Context,
        task: DownloadTask
    ): List<SubtitleTrackItem> = withContext(Dispatchers.IO) {
        val candidateBaseNames = buildSet {
            extractBaseName(task.fileName)?.let { add(it) }
            task.savedPath?.let { path ->
                extractBaseName(File(path).name)?.let { add(it) }
            }
        }
        if (candidateBaseNames.isEmpty()) return@withContext emptyList()

        val found = LinkedHashMap<String, SubtitleTrackItem>()

        fun registerFile(file: File) {
            if (!file.isFile || !file.canRead()) return
            val ext = file.extension.lowercase()
            if (ext != "srt" && ext != "vtt") return
            val lowerName = file.name.lowercase()
            val matches = candidateBaseNames.any { base ->
                lowerName.startsWith(base.lowercase())
            }
            if (matches) {
                val key = lowerName
                if (!found.containsKey(key)) {
                    found[key] = SubtitleTrackItem(
                        label = formatSubtitleLabel(file.name, candidateBaseNames),
                        uri = Uri.fromFile(file)
                    )
                }
            }
        }

        // 0. Uygulamanın dahili subtitles klasörünü tara (Android 10+ Scoped Storage garantili yol)
        runCatching {
            val internalSubDir = File(context.filesDir, "subtitles")
            if (internalSubDir.isDirectory) {
                internalSubDir.listFiles()?.forEach { registerFile(it) }
            }
        }

        // 1. Doğrudan görev dosyasının bulunduğu klasörü tara
        runCatching {
            val savedFile = task.savedPath?.let { File(it) }
            val parentDir = savedFile?.parentFile
            if (parentDir != null && parentDir.isDirectory) {
                parentDir.listFiles()?.forEach { registerFile(it) }
            }
        }

        // 2. Public Downloads ve Downloads/UlgenTube klasörlerini tara
        runCatching {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (downloadsDir != null && downloadsDir.isDirectory) {
                downloadsDir.listFiles()?.forEach { child ->
                    if (child.isFile) {
                        registerFile(child)
                    } else if (child.isDirectory && child.name.equals("UlgenTube", ignoreCase = true)) {
                        child.listFiles()?.forEach { nested ->
                            if (nested.isFile) {
                                registerFile(nested)
                            } else if (nested.isDirectory) {
                                nested.listFiles()?.forEach { sub -> registerFile(sub) }
                            }
                        }
                    }
                }
            }
        }

        // 3. MediaStore üzerinden eşleşen .srt / .vtt dosyalarını tara (Scoped Storage uyumlu)
        runCatching {
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )

            for (baseName in candidateBaseNames) {
                val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("$baseName%.srt", "$baseName%.vtt")

                context.contentResolver.query(
                    collectionUri,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val displayName = cursor.getString(nameCol) ?: continue
                        val lowerName = displayName.lowercase()
                        if (!lowerName.endsWith(".srt") && !lowerName.endsWith(".vtt")) continue
                        if (!found.containsKey(lowerName)) {
                            val itemUri = ContentUris.withAppendedId(collectionUri, id)
                            found[lowerName] = SubtitleTrackItem(
                                label = formatSubtitleLabel(displayName, candidateBaseNames),
                                uri = itemUri
                            )
                        }
                    }
                }
            }
        }

        found.values.toList()
    }

    private fun parseTimestampToMs(raw: String): Long? {
        val cleaned = raw.trim().replace(',', '.')
        val parts = cleaned.split(":")
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                val secParts = parts[2].split(".")
                val seconds = secParts[0].toLongOrNull() ?: return null
                val millis = parseMillisPart(secParts.getOrNull(1))
                (hours * 3_600_000L) + (minutes * 60_000L) + (seconds * 1_000L) + millis
            }
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                val secParts = parts[1].split(".")
                val seconds = secParts[0].toLongOrNull() ?: return null
                val millis = parseMillisPart(secParts.getOrNull(1))
                (minutes * 60_000L) + (seconds * 1_000L) + millis
            }
            else -> null
        }
    }

    private fun parseMillisPart(rawMillis: String?): Long {
        if (rawMillis.isNullOrBlank()) return 0L
        val digits = rawMillis.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return 0L
        return when (digits.length) {
            1 -> (digits.toLongOrNull() ?: 0L) * 100L
            2 -> (digits.toLongOrNull() ?: 0L) * 10L
            else -> digits.take(3).toLongOrNull() ?: 0L
        }
    }

    private fun cleanCueLine(rawLine: String): String {
        return rawLine
            .replace(HTML_AND_VTT_TAG_REGEX, "")
            .replace(ASS_STYLE_TAG_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    private fun extractBaseName(fileName: String): String? {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty()) return null
        val dotIndex = trimmed.lastIndexOf('.')
        val base = if (dotIndex > 0) trimmed.substring(0, dotIndex) else trimmed
        return base.takeIf { it.length >= 2 }
    }

    private fun formatSubtitleLabel(fileName: String, baseNames: Set<String>): String {
        val ext = fileName.substringAfterLast('.', "").uppercase()
        val matchingBase = baseNames.firstOrNull { base ->
            fileName.startsWith(base, ignoreCase = true)
        }
        if (matchingBase != null) {
            val suffix = fileName
                .substring(matchingBase.length)
                .substringBeforeLast('.')
                .trim('.', '_', '-', ' ')
            if (suffix.isNotEmpty()) {
                return "${suffix.uppercase()} ($ext)"
            }
        }
        return fileName
    }
}
