package com.ulgentube.app.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.util.FileNameSanitizer
import com.ulgentube.app.util.MediaUriHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

data class SavedMediaResult(
    val uri: String,
    val displayPath: String,
    val fileSizeBytes: Long
)

/**
 * İndirilen medya dosyalarını kategorisine göre cihazın ortak galerisine / medya deposuna kaydeder:
 * - Video: `Movies/UlgenTube` (veya `Movies/UlgenTube/<Platform>`)
 * - Ses: `Music/UlgenTube` (veya `Music/UlgenTube/<Platform>`)
 * - Görsel: `Pictures/UlgenTube` (veya `Pictures/UlgenTube/<Platform>`)
 *
 * API 29+ cihazlarda `MediaStore` + `IS_PENDING` kullanarak Scoped Storage uyumlu güvenli yazım yapar.
 * API 24-28 cihazlarda ortak klasöre kopyalayıp `MediaScannerConnection` ile galeriye bildirir.
 */
class MediaStoreSaver(private val context: Context) {

    suspend fun save(
        sourceFile: File,
        desiredFileName: String,
        category: MediaCategory,
        platformSubfolder: String? = null
    ): SavedMediaResult = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            throw IOException("Kaydedilecek kaynak dosya bulunamadı veya boş.")
        }

        val actualExt = sourceFile.extension
            .lowercase(Locale.ROOT)
            .ifBlank { desiredFileName.substringAfterLast('.', defaultExtensionFor(category)) }

        val rawBaseName = desiredFileName.substringBeforeLast('.', desiredFileName)
        val safeFileName = FileNameSanitizer.buildFileName(rawBaseName, actualExt)
        val mimeType = resolveMimeType(actualExt, category)
        val fileSize = sourceFile.length()
        val safeSubfolder = sanitizeSubfolder(platformSubfolder)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(
                sourceFile = sourceFile,
                fileName = safeFileName,
                mimeType = mimeType,
                category = category,
                fileSize = fileSize,
                safeSubfolder = safeSubfolder
            )
        } else {
            saveLegacyExternal(
                sourceFile = sourceFile,
                fileName = safeFileName,
                mimeType = mimeType,
                category = category,
                fileSize = fileSize,
                safeSubfolder = safeSubfolder
            )
        }
    }

    /**
     * Galeriye veya izin verilen ortak UlgenTube medya klasörüne kaydedilmiş fiziksel dosyayı
     * güvenli şekilde siler.
     */
    suspend fun deleteMediaFile(
        savedUri: String?,
        savedPath: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val rawUri = savedUri?.trim()?.takeIf { it.isNotEmpty() }
        if (rawUri != null) {
            val parsed = runCatching { Uri.parse(rawUri) }.getOrNull()
            if (parsed != null) {
                when (parsed.scheme?.lowercase(Locale.ROOT)) {
                    "content" -> {
                        val deleted = runCatching {
                            context.contentResolver.delete(parsed, null, null) > 0
                        }.getOrDefault(false)
                        if (deleted) return@withContext true
                    }

                    "file" -> {
                        val filePath = parsed.path?.takeIf { it.isNotBlank() }
                        if (filePath != null) {
                            val file = File(filePath)
                            if (file.exists() && MediaUriHelper.isWithinAllowedMediaDirs(file)) {
                                if (runCatching { file.delete() }.getOrDefault(false)) {
                                    return@withContext true
                                }
                            }
                        }
                    }
                }
            }
        }

        val rawPath = savedPath?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext false
        val file = File(rawPath)
        if (!file.exists() || !MediaUriHelper.isWithinAllowedMediaDirs(file)) {
            return@withContext false
        }
        runCatching { file.delete() }.getOrDefault(false)
    }

    private fun saveViaMediaStore(
        sourceFile: File,
        fileName: String,
        mimeType: String,
        category: MediaCategory,
        fileSize: Long,
        safeSubfolder: String?
    ): SavedMediaResult {
        val resolver = context.contentResolver
        val folderSuffix = if (safeSubfolder != null) {
            "$APP_FOLDER_NAME/$safeSubfolder"
        } else {
            APP_FOLDER_NAME
        }
        val (collectionUri, relativePath) = when (category) {
            MediaCategory.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to
                "${Environment.DIRECTORY_MOVIES}/$folderSuffix"
            MediaCategory.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to
                "${Environment.DIRECTORY_MUSIC}/$folderSuffix"
            MediaCategory.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to
                "${Environment.DIRECTORY_PICTURES}/$folderSuffix"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(collectionUri, values)
            ?: throw IOException("MediaStore kaydı oluşturulamadı.")

        try {
            resolver.openOutputStream(itemUri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE * 4)
                }
            } ?: throw IOException("MediaStore çıkış akışı açılamadı.")

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, publishValues, null, null)

            return SavedMediaResult(
                uri = itemUri.toString(),
                displayPath = "$relativePath/$fileName",
                fileSizeBytes = fileSize
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacyExternal(
        sourceFile: File,
        fileName: String,
        mimeType: String,
        category: MediaCategory,
        fileSize: Long,
        safeSubfolder: String?
    ): SavedMediaResult {
        val baseDirType = when (category) {
            MediaCategory.VIDEO -> Environment.DIRECTORY_MOVIES
            MediaCategory.AUDIO -> Environment.DIRECTORY_MUSIC
            MediaCategory.IMAGE -> Environment.DIRECTORY_PICTURES
        }

        val publicDir = Environment.getExternalStoragePublicDirectory(baseDirType)
        val appRootDir = File(publicDir, APP_FOLDER_NAME)
        val targetDir = if (safeSubfolder != null) {
            File(appRootDir, safeSubfolder)
        } else {
            appRootDir
        }

        val canonicalAppRoot = appRootDir.canonicalPath
        val canonicalTargetDir = targetDir.canonicalPath
        if (canonicalTargetDir != canonicalAppRoot &&
            !canonicalTargetDir.startsWith(canonicalAppRoot + File.separator)
        ) {
            throw SecurityException("Geçersiz hedef klasör yolu.")
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val targetFile = uniqueTargetFile(targetDir, fileName)
        try {
            sourceFile.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE * 4)
                }
            }
        } catch (e: Exception) {
            runCatching { targetFile.delete() }
            throw e
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf(mimeType),
            null
        )

        val folderDisplay = if (safeSubfolder != null) {
            "$baseDirType/$APP_FOLDER_NAME/$safeSubfolder"
        } else {
            "$baseDirType/$APP_FOLDER_NAME"
        }

        return SavedMediaResult(
            uri = Uri.fromFile(targetFile).toString(),
            displayPath = "$folderDisplay/${targetFile.name}",
            fileSizeBytes = fileSize
        )
    }

    private fun sanitizeSubfolder(rawSubfolder: String?): String? {
        val trimmed = rawSubfolder?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleaned = FileNameSanitizer.sanitize(trimmed, maxLength = 32)
            .replace(File.separatorChar, '_')
            .replace('/', '_')
            .replace('\\', '_')
            .trim('.', ' ', '_', '-')
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun uniqueTargetFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate

        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val numberedName = if (ext.isNotBlank()) "${base}_$index.$ext" else "${base}_$index"
            candidate = File(directory, numberedName)
            index++
        }
        return candidate
    }

    private fun resolveMimeType(extension: String, category: MediaCategory): String {
        val normalized = extension.lowercase(Locale.ROOT)
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(normalized)
        if (!fromMap.isNullOrBlank()) return fromMap

        return when (normalized) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "m4v" -> "video/x-m4v"
            "ts" -> "video/mp2t"
            "3gp" -> "video/3gpp"
            "webm" -> if (category == MediaCategory.AUDIO) "audio/webm" else "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "heic", "heif" -> "image/heic"
            else -> when (category) {
                MediaCategory.VIDEO -> "video/mp4"
                MediaCategory.AUDIO -> "audio/mpeg"
                MediaCategory.IMAGE -> "image/jpeg"
            }
        }
    }

    private fun defaultExtensionFor(category: MediaCategory): String = when (category) {
        MediaCategory.VIDEO -> "mp4"
        MediaCategory.AUDIO -> "mp3"
        MediaCategory.IMAGE -> "jpg"
    }

    companion object {
        private const val APP_FOLDER_NAME = "UlgenTube"
    }
}
