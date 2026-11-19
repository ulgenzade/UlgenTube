package com.ulgentube.app.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ulgentube.app.domain.model.FfmpegStudioAction
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.util.ErrorMessageSanitizer
import com.ulgentube.app.util.FileNameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Çevrimdışı FFmpeg Medya Stüdyosu:
 * Uygulamanın kendi native dizinindeki `libffmpeg.so` (veya paketlenmiş `ffmpeg`) ikilisini
 * doğrudan `ProcessBuilder` argüman dizisiyle (sıfır shell, komut enjeksiyonsuz) çalıştırarak
 * yerel video/ses dosyalarını MP3, sıkıştırılmış MP4, GIF veya sessiz MP4 formatına dönüştürür.
 */
class LocalFfmpegProcessor(
    private val context: Context,
    private val mediaStoreSaver: MediaStoreSaver
) {

    /**
     * Verilen yerel medya [inputUri]'si üzerinde seçilen [action] dönüştürmesini uygular
     * ve sonucu `MediaStoreSaver` üzerinden cihaz galerisine kaydeder.
     */
    suspend fun process(
        inputUri: Uri,
        action: FfmpegStudioAction,
        outputBaseName: String? = null
    ): Result<SavedMediaResult> = withContext(Dispatchers.IO) {
        val workRoot = File(context.cacheDir, STUDIO_WORK_DIR).apply { mkdirs() }
        val jobDir = File(workRoot, UUID.randomUUID().toString())
        val canonicalWorkRoot = workRoot.canonicalPath + File.separator
        if (!jobDir.canonicalPath.startsWith(canonicalWorkRoot)) {
            return@withContext Result.failure(SecurityException("Geçersiz çalışma dizini."))
        }
        if (!jobDir.mkdirs()) {
            return@withContext Result.failure(IOException("Geçici çalışma klasörü oluşturulamadı."))
        }

        try {
            val ffmpegBinary = resolveFfmpegBinary()
                ?: throw IllegalStateException(
                    "FFmpeg motoru henüz hazır değil. Lütfen birkaç saniye bekleyip tekrar deneyin."
                )

            val resolvedName = outputBaseName?.takeIf { it.isNotBlank() }
                ?: queryDisplayName(inputUri)?.substringBeforeLast('.')
                ?: "ulgentube_studio"

            val inputFile = File(jobDir, "input")
            copyUriToFileWithLimit(inputUri, inputFile, MAX_INPUT_FILE_BYTES)

            val spec = buildSpecForAction(action, jobDir)
            val args = buildFfmpegArgs(
                ffmpegPath = ffmpegBinary.absolutePath,
                inputPath = inputFile.absolutePath,
                outputPath = spec.outputFile.absolutePath,
                action = action
            )

            val processBuilder = ProcessBuilder(args).apply {
                directory(jobDir)
                redirectErrorStream(true)
                configureLibraryPath(environment())
            }

            val process = processBuilder.start()
            val tailLog = ArrayDeque<String>(24)
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (tailLog.size >= 24) {
                        tailLog.removeFirst()
                    }
                    tailLog.addLast(line)
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0 || !spec.outputFile.exists() || spec.outputFile.length() <= 0L) {
                val rawReason = tailLog.lastOrNull { it.isNotBlank() } ?: "exitCode=$exitCode"
                throw IOException(
                    ErrorMessageSanitizer.sanitize(
                        throwable = IOException(rawReason),
                        fallback = "FFmpeg dönüştürme işlemi tamamlanamadı."
                    )
                )
            }

            val desiredFileName = FileNameSanitizer.buildFileName(
                rawTitle = resolvedName,
                extension = spec.extension,
                suffix = spec.suffix
            )

            val saved = mediaStoreSaver.save(
                sourceFile = spec.outputFile,
                desiredFileName = desiredFileName,
                category = spec.category
            )
            Result.success(saved)
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    ErrorMessageSanitizer.sanitize(
                        throwable = e,
                        fallback = "Medya dönüştürme sırasında bir hata oluştu."
                    ),
                    e
                )
            )
        } finally {
            runCatching { jobDir.deleteRecursively() }
        }
    }

    private fun resolveFfmpegBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val candidates = listOf(
            File(nativeDir, "libffmpeg.so"),
            File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
            File(context.filesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
            File(nativeDir, "ffmpeg")
        )
        return candidates.firstOrNull { file ->
            file.exists() && file.isFile && (file.canExecute() || file.setExecutable(true))
        }
    }

    private fun configureLibraryPath(env: MutableMap<String, String>) {
        val libPaths = buildList {
            context.applicationInfo.nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { add(it) }
            val noBackupLib = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
            if (noBackupLib.isDirectory) add(noBackupLib.absolutePath)
            val filesLib = File(context.filesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
            if (filesLib.isDirectory) add(filesLib.absolutePath)
            env["LD_LIBRARY_PATH"]?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (libPaths.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = libPaths.distinct().joinToString(File.pathSeparator)
        }
    }

    private fun buildSpecForAction(action: FfmpegStudioAction, jobDir: File): ActionOutputSpec {
        return when (action) {
            FfmpegStudioAction.EXTRACT_MP3 -> ActionOutputSpec(
                outputFile = File(jobDir, "output.mp3"),
                extension = "mp3",
                suffix = "mp3",
                category = MediaCategory.AUDIO
            )

            FfmpegStudioAction.COMPRESS_WHATSAPP -> ActionOutputSpec(
                outputFile = File(jobDir, "output.mp4"),
                extension = "mp4",
                suffix = "compressed",
                category = MediaCategory.VIDEO
            )

            FfmpegStudioAction.MAKE_GIF -> ActionOutputSpec(
                outputFile = File(jobDir, "output.gif"),
                extension = "gif",
                suffix = "gif",
                category = MediaCategory.IMAGE
            )

            FfmpegStudioAction.REMOVE_AUDIO -> ActionOutputSpec(
                outputFile = File(jobDir, "output.mp4"),
                extension = "mp4",
                suffix = "muted",
                category = MediaCategory.VIDEO
            )
        }
    }

    private fun buildFfmpegArgs(
        ffmpegPath: String,
        inputPath: String,
        outputPath: String,
        action: FfmpegStudioAction
    ): List<String> {
        return when (action) {
            FfmpegStudioAction.EXTRACT_MP3 -> listOf(
                ffmpegPath,
                "-y",
                "-i", inputPath,
                "-vn",
                "-c:a", "libmp3lame",
                "-b:a", "320k",
                outputPath
            )

            FfmpegStudioAction.COMPRESS_WHATSAPP -> listOf(
                ffmpegPath,
                "-y",
                "-i", inputPath,
                "-vf", "scale=-2:min(720\\,ih)",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "28",
                "-c:a", "aac",
                "-b:a", "96k",
                "-movflags", "+faststart",
                outputPath
            )

            FfmpegStudioAction.MAKE_GIF -> listOf(
                ffmpegPath,
                "-y",
                "-i", inputPath,
                "-t", "15",
                "-vf", "fps=12,scale=480:-1:flags=lanczos",
                outputPath
            )

            FfmpegStudioAction.REMOVE_AUDIO -> listOf(
                ffmpegPath,
                "-y",
                "-i", inputPath,
                "-c:v", "copy",
                "-an",
                outputPath
            )
        }
    }

    private fun copyUriToFileWithLimit(uri: Uri, targetFile: File, maxBytes: Long) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Seçilen medya dosyası açılamadı.")

        inputStream.use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                var totalBytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    if (totalBytes > maxBytes) {
                        throw IllegalArgumentException(
                            "Seçilen dosya 500 MB işlem sınırını aşıyor."
                        )
                    }
                    output.write(buffer, 0, read)
                }
                if (totalBytes <= 0L) {
                    throw IOException("Seçilen medya dosyası boş.")
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }

    private data class ActionOutputSpec(
        val outputFile: File,
        val extension: String,
        val suffix: String,
        val category: MediaCategory
    )

    companion object {
        private const val STUDIO_WORK_DIR = "studio_work"
        private const val MAX_INPUT_FILE_BYTES = 500L * 1024L * 1024L // 500 MB
    }
}
