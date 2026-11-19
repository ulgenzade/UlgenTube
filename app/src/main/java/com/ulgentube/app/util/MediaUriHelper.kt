package com.ulgentube.app.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.MediaCategory
import java.io.File

/**
 * İndirilen medya dosyalarının güvenli `content://` URI çözümlemesi,
 * izin verilen depolama dizini doğrulaması ve MIME türü yardımcıları.
 */
object MediaUriHelper {

    /**
     * İndirme görevine ait dosya için güvenli bir `content://` URI döndürür.
     * `file://` şemasını doğrudan dışarıya sızdırmaz; yalnızca izin verilen
     * `UlgenTube` medya klasörleri içindeki dosyaları `FileProvider` üzerinden paylaşır.
     */
    fun resolveTaskUri(context: Context, task: DownloadTask): Uri? {
        val rawUri = task.savedUri?.takeIf { it.isNotBlank() }
        if (rawUri != null) {
            val parsed = runCatching { Uri.parse(rawUri) }.getOrNull()
            if (parsed != null) {
                when (parsed.scheme?.lowercase()) {
                    "content" -> return parsed
                    "file" -> {
                        val filePath = parsed.path?.takeIf { it.isNotBlank() }
                        if (filePath != null) {
                            val file = File(filePath)
                            if (file.exists() && isWithinAllowedMediaDirs(file)) {
                                return runCatching {
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                }.getOrNull()
                            }
                        }
                        return null
                    }
                    // content:// dışındaki şemaları asla expose etme
                    else -> return null
                }
            }
        }

        val rawPath = task.savedPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(rawPath)
        if (!file.exists() || !isWithinAllowedMediaDirs(file)) return null

        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrNull()
    }

    /**
     * Verilen dosyanın UlgenTube'un izin verilen ortak medya klasörleri
     * (`Movies/UlgenTube`, `Music/UlgenTube`, `Pictures/UlgenTube` ve tüm alt klasörleri)
     * içinde olup olmadığını canonical path üzerinden doğrular.
     */
    @Suppress("DEPRECATION")
    fun isWithinAllowedMediaDirs(file: File): Boolean {
        val canonicalFilePath = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val allowedDirs = listOf(
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_PICTURES
        ).mapNotNull { dirType ->
            runCatching {
                File(Environment.getExternalStoragePublicDirectory(dirType), "UlgenTube")
                    .canonicalPath + File.separator
            }.getOrNull()
        }
        return allowedDirs.any { canonicalFilePath.startsWith(it) }
    }

    /**
     * Medya kategorisine uygun genel MIME türünü döndürür.
     */
    fun resolveMimeType(category: MediaCategory): String = when (category) {
        MediaCategory.VIDEO -> "video/*"
        MediaCategory.AUDIO -> "audio/*"
        MediaCategory.IMAGE -> "image/*"
    }
}
