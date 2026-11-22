package com.ulgentube.app.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.ulgentube.app.domain.model.DownloadTask
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
     * Ses dosyasını cihazın zil sesleri (Ringtones/UlgenTube) deposuna kaydeder.
     * API 29+ cihazlarda `MediaStore.Audio.Media.IS_RINGTONE = 1` ile Scoped Storage uyumlu yazar.
     */
    @Suppress("DEPRECATION")
    suspend fun saveRingtone(
        sourceFile: File,
        title: String,
        extension: String
    ): SavedMediaResult? = withContext(Dispatchers.IO) {
        runCatching {
            if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                throw IOException("Zil sesi kaynağı bulunamadı veya boş.")
            }

            val actualExt = extension.trim().lowercase(Locale.ROOT)
                .ifBlank { sourceFile.extension.lowercase(Locale.ROOT).ifBlank { "mp3" } }
            val safeTitle = title.trim().ifBlank { "ulgentube_ringtone" }
            val safeFileName = FileNameSanitizer.buildFileName(safeTitle, actualExt)
            val mimeType = resolveMimeType(actualExt, MediaCategory.AUDIO)
            val fileSize = sourceFile.length()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val relativePath = "${Environment.DIRECTORY_RINGTONES}/$APP_FOLDER_NAME"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.Audio.Media.TITLE, safeTitle)
                    put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                    put(MediaStore.Audio.Media.IS_MUSIC, 0)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val itemUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Zil sesi MediaStore kaydı oluşturulamadı.")

                try {
                    resolver.openOutputStream(itemUri)?.use { output ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE * 4)
                        }
                    } ?: throw IOException("Zil sesi çıkış akışı açılamadı.")

                    val publishValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, publishValues, null, null)

                    SavedMediaResult(
                        uri = itemUri.toString(),
                        displayPath = "$relativePath/$safeFileName",
                        fileSizeBytes = fileSize
                    )
                } catch (e: Exception) {
                    runCatching { resolver.delete(itemUri, null, null) }
                    throw e
                }
            } else {
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)
                val targetDir = File(publicDir, APP_FOLDER_NAME)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val targetFile = uniqueTargetFile(targetDir, safeFileName)
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

                SavedMediaResult(
                    uri = Uri.fromFile(targetFile).toString(),
                    displayPath = "${Environment.DIRECTORY_RINGTONES}/$APP_FOLDER_NAME/${targetFile.name}",
                    fileSizeBytes = fileSize
                )
            }
        }.getOrNull()
    }

    /**
     * İndirilen medyayı galeri/genel depolamadan uygulamanın gizli `.nomedia` korumalı
     * Özel Kasa (`filesDir/vault/`) klasörüne taşır ve orijinal genel kopyayı temizler.
     *
     * @return `(vaultContentUri, vaultAbsolutePath)` çifti; başarısız olursa `null`.
     */
    suspend fun moveToPrivateVault(task: DownloadTask): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val vaultDir = MediaUriHelper.getVaultDir(context)
            val canonicalVaultRoot = vaultDir.canonicalPath + File.separator

            // Zaten kasa içindeyse mevcut yolu koru
            val existingPathFile = task.savedPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            if (existingPathFile != null &&
                existingPathFile.exists() &&
                existingPathFile.canonicalPath.startsWith(canonicalVaultRoot)
            ) {
                val existingUri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        existingPathFile
                    )
                }.getOrElse { Uri.fromFile(existingPathFile) }
                return@runCatching existingUri.toString() to existingPathFile.absolutePath
            }

            val baseName = task.fileName.substringBeforeLast('.', task.title.ifBlank { "vault_item" })
            val ext = task.fileName.substringAfterLast('.', task.targetExtension)
                .lowercase(Locale.ROOT)
                .ifBlank { defaultExtensionFor(task.category) }
            val safeFileName = FileNameSanitizer.buildFileName(baseName, ext)
            val targetFile = uniqueTargetFile(vaultDir, safeFileName)
            if (!targetFile.canonicalPath.startsWith(canonicalVaultRoot)) {
                throw SecurityException("Geçersiz özel kasa dosya yolu.")
            }

            val sourceUri = MediaUriHelper.resolveTaskUri(context, task)
                ?: task.savedUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }

            var copiedBytes = 0L
            try {
                val inputStream = when {
                    sourceUri != null -> context.contentResolver.openInputStream(sourceUri)
                    existingPathFile != null && existingPathFile.exists() -> existingPathFile.inputStream()
                    else -> null
                } ?: throw IOException("Kasa için kaynak medya dosyası açılamadı.")

                inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        copiedBytes = input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE * 4)
                    }
                }
                if (copiedBytes <= 0L || !targetFile.exists()) {
                    throw IOException("Kasa dosyası boş kopyalandı.")
                }
            } catch (e: Exception) {
                runCatching { targetFile.delete() }
                throw e
            }

            // Orijinal genel kopyayı güvenli şekilde kaldır
            deleteMediaFile(task)

            val vaultUri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    targetFile
                )
            }.getOrElse { Uri.fromFile(targetFile) }

            vaultUri.toString() to targetFile.absolutePath
        }.getOrNull()
    }

    /**
     * Özel Kasa (`filesDir/vault/`) içindeki medya dosyasını tekrar genel galeri/İndirilenler
     * klasörüne taşır ve kasadaki kopyayı temizler.
     */
    suspend fun restoreFromPrivateVault(task: DownloadTask): SavedMediaResult? = withContext(Dispatchers.IO) {
        runCatching {
            val vaultDir = MediaUriHelper.getVaultDir(context)
            val canonicalVaultRoot = vaultDir.canonicalPath + File.separator

            val vaultFile = task.savedPath?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.exists() && it.canonicalPath.startsWith(canonicalVaultRoot) }
                ?: throw IOException("Özel kasa dosyası bulunamadı.")

            val saved = save(
                sourceFile = vaultFile,
                desiredFileName = task.fileName.ifBlank { vaultFile.name },
                category = task.category
            )

            runCatching { vaultFile.delete() }
            saved
        }.getOrNull()
    }

    suspend fun deleteMediaFile(task: DownloadTask): Boolean {
        return deleteMediaFile(savedUri = task.savedUri, savedPath = task.savedPath)
    }

    /**
     * Galeriye, izin verilen ortak UlgenTube medya klasörüne veya Özel Kasa'ya kaydedilmiş
     * fiziksel dosyayı güvenli şekilde siler.
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
                            if (file.exists() && MediaUriHelper.isWithinAllowedMediaDirs(file, context)) {
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
        if (!file.exists() || !MediaUriHelper.isWithinAllowedMediaDirs(file, context)) {
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
        if (normalized == "webm") {
            return if (category == MediaCategory.AUDIO) "audio/webm" else "video/webm"
        }
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
