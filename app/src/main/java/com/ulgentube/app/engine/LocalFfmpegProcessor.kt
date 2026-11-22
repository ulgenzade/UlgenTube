package com.ulgentube.app.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ulgentube.app.domain.model.FfmpegStudioAction
import com.ulgentube.app.domain.model.FfmpegStudioOptions
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.StudioAspectRatio
import com.ulgentube.app.util.ErrorMessageSanitizer
import com.ulgentube.app.util.FileNameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
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
     * Verilen yerel medya [inputUri]'si üzerinde seçilen [action] dönüştürmesini [options] ayarlarıyla
     * uygular ve sonucu `MediaStoreSaver` üzerinden cihaz galerisine (veya zil seslerine) kaydeder.
     */
    suspend fun process(
        inputUri: Uri,
        action: FfmpegStudioAction,
        outputBaseName: String? = null,
        options: FfmpegStudioOptions = FfmpegStudioOptions(),
        onProgress: (Float) -> Unit = {}
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
            onProgress(0.05f)
            val ffmpegBinary = resolveFfmpegBinary()
                ?: throw IllegalStateException(
                    "FFmpeg motoru henüz hazır değil. Lütfen birkaç saniye bekleyip tekrar deneyin."
                )

            val resolvedName = outputBaseName?.takeIf { it.isNotBlank() }
                ?: queryDisplayName(inputUri)?.substringBeforeLast('.')
                ?: "ulgentube_studio"

            val spec = buildSpecForAction(action, options, jobDir)

            if (action == FfmpegStudioAction.MERGE_VIDEOS) {
                executeMergeAction(
                    ffmpegPath = ffmpegBinary.absolutePath,
                    primaryInputUri = inputUri,
                    options = options,
                    jobDir = jobDir,
                    outputFile = spec.outputFile,
                    onProgress = onProgress
                )
            } else {
                val inputFile = File(jobDir, "input")
                copyUriToFileWithLimit(inputUri, inputFile, MAX_INPUT_FILE_BYTES)
                onProgress(0.25f)

                executeSingleInputAction(
                    ffmpegPath = ffmpegBinary.absolutePath,
                    inputPath = inputFile.absolutePath,
                    outputFile = spec.outputFile,
                    action = action,
                    options = options,
                    jobDir = jobDir,
                    onProgress = onProgress
                )
            }

            onProgress(0.92f)
            val saved = if (action == FfmpegStudioAction.CONVERT_AUDIO && options.saveAsRingtone) {
                mediaStoreSaver.saveRingtone(
                    sourceFile = spec.outputFile,
                    title = resolvedName,
                    extension = spec.extension
                ) ?: throw IOException("Zil sesi kaydedilemedi.")
            } else {
                val desiredFileName = FileNameSanitizer.buildFileName(
                    rawTitle = resolvedName,
                    extension = spec.extension,
                    suffix = spec.suffix
                )
                mediaStoreSaver.save(
                    sourceFile = spec.outputFile,
                    desiredFileName = desiredFileName,
                    category = spec.category
                )
            }

            onProgress(1.0f)
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

    /**
     * Doğrudan indirilen video/ses akışlarını yerel FFmpeg ikilisiyle birleştirir,
     * ses formatına (MP3/M4A/OPUS/FLAC/WAV) çevirir, kırpar veya ölçeklendirir.
     */
    suspend fun muxOrConvertFiles(
        videoFile: File?,
        audioFile: File,
        outputFile: File,
        isAudioOnly: Boolean,
        targetExtension: String,
        audioBitrateKbps: Int = 192,
        clipStartSeconds: Long? = null,
        clipEndSeconds: Long? = null,
        transcodeTargetHeight: Int? = null
    ): File = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val workDir = outputFile.parentFile ?: audioFile.parentFile ?: context.cacheDir
        val startSec = clipStartSeconds?.coerceAtLeast(0L)
        val endSec = clipEndSeconds?.takeIf { it > (startSec ?: 0L) }
        val hasClip = startSec != null || endSec != null

        // 0) ÖNCELİKLİ YOL: Android Native MediaMuxer (Snaptube / NewPipe mimarisi!)
        // Android 10+ (targetSdk=35) SELinux W^X kısıtına takılmaz, FFmpeg binary gerektirmez,
        // 1080p60/2K/4K video + ses akışlarını <1 saniyede kayıpsız birleştirir.
        val isNativeMuxEligible = !isAudioOnly && videoFile != null && !hasClip &&
            (transcodeTargetHeight == null || transcodeTargetHeight <= 0)
        if (isNativeMuxEligible && videoFile != null) {
            val nativeAttempt = runCatching {
                muxWithAndroidMediaMuxer(videoFile, audioFile, outputFile)
            }
            if (nativeAttempt.isSuccess) {
                return@withContext nativeAttempt.getOrThrow()
            } else {
                android.util.Log.w(
                    "UlgenTubeMuxer",
                    "Android MediaMuxer başarısız, FFmpeg yedeğine geçiliyor: ${nativeAttempt.exceptionOrNull()?.message}"
                )
                com.ulgentube.app.util.DiagnosticLogManager.recordError(
                    tag = "MediaMuxer",
                    summary = "Native MediaMuxer başarısız, FFmpeg yedeğine düşüldü",
                    throwable = nativeAttempt.exceptionOrNull(),
                    metadata = mapOf(
                        "videoBytes" to videoFile.length().toString(),
                        "audioBytes" to audioFile.length().toString(),
                        "targetExt" to targetExtension
                    )
                )
                runCatching { outputFile.delete() }
            }
        }

        val ffmpegBinary = resolveFfmpegExecutable()
            ?: throw IllegalStateException(
                "FFmpeg motoru henüz hazır değil. Lütfen birkaç saniye bekleyip tekrar deneyin."
            )

        fun MutableList<String>.addClipInputArgs() {
            if (startSec != null && startSec > 0L) {
                add("-ss")
                add(startSec.toString())
            }
            if (endSec != null) {
                add("-to")
                add(endSec.toString())
            }
        }

        val ext = targetExtension.lowercase(java.util.Locale.ROOT)
        val bitrate = "${audioBitrateKbps.coerceIn(96, 320)}k"

        if (isAudioOnly || videoFile == null) {
            if (ext == "m4a" && !hasClip) {
                val copyArgs = mutableListOf(ffmpegBinary.absolutePath, "-y", "-i", audioFile.absolutePath, "-vn", "-c:a", "copy", "-movflags", "+faststart", outputFile.absolutePath)
                val copyAttempt = runCatching {
                    runFfmpegCommand(copyArgs, workDir, outputFile, "Ses dosyası hazırlanamadı.")
                }
                if (copyAttempt.isSuccess) return@withContext outputFile
                outputFile.delete()
            }

            val audioArgs = mutableListOf(ffmpegBinary.absolutePath, "-y").apply {
                addClipInputArgs()
                add("-i")
                add(audioFile.absolutePath)
                add("-vn")
                when (ext) {
                    "m4a" -> addAll(listOf("-c:a", "aac", "-b:a", bitrate, "-movflags", "+faststart"))
                    "opus" -> addAll(listOf("-c:a", "libopus", "-b:a", "160k"))
                    "flac" -> addAll(listOf("-c:a", "flac"))
                    "wav" -> addAll(listOf("-c:a", "pcm_s16le"))
                    else -> addAll(listOf("-c:a", "libmp3lame", "-b:a", bitrate))
                }
                add(outputFile.absolutePath)
            }

            runFfmpegCommand(audioArgs, workDir, outputFile, "Ses dönüştürme işlemi tamamlanamadı.")
            return@withContext outputFile
        }

        val targetHeight = transcodeTargetHeight?.takeIf { it > 0 }
        if (targetHeight != null) {
            val scaleArgs = mutableListOf(ffmpegBinary.absolutePath, "-y").apply {
                addClipInputArgs()
                add("-i")
                add(videoFile.absolutePath)
                addClipInputArgs()
                add("-i")
                add(audioFile.absolutePath)
                addAll(
                    listOf(
                        "-map", "0:v:0",
                        "-map", "1:a:0",
                        "-vf", "scale=-2:$targetHeight",
                        "-c:v", "libx264",
                        "-preset", "veryfast",
                        "-crf", "23",
                        "-c:a", "aac",
                        "-b:a", "128k",
                        "-movflags", "+faststart",
                        "-shortest",
                        outputFile.absolutePath
                    )
                )
            }
            runFfmpegCommand(scaleArgs, workDir, outputFile, "Video ve ses birleştirilemedi.")
            return@withContext outputFile
        }

        // 1) FFmpeg yedeği: hem video hem ses akışını doğrudan kopyala (VP9/AV1/H.264 + M4A)
        val fastCopyArgs = mutableListOf(ffmpegBinary.absolutePath, "-y").apply {
            addClipInputArgs()
            add("-i")
            add(videoFile.absolutePath)
            addClipInputArgs()
            add("-i")
            add(audioFile.absolutePath)
            addAll(
                listOf(
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "copy",
                    "-strict", "-2",
                    "-movflags", "+faststart",
                    "-shortest",
                    outputFile.absolutePath
                )
            )
        }
        val fastCopyAttempt = runCatching {
            runFfmpegCommand(fastCopyArgs, workDir, outputFile, "Video ve ses birleştirilemedi.")
        }
        if (fastCopyAttempt.isSuccess) {
            return@withContext outputFile
        }

        // 2) Ses MP4 kapsayıcısına doğrudan kopyalanamadıysa (örn. Opus) yalnızca sesi AAC'ye çevir
        runCatching { outputFile.delete() }
        val aacAudioArgs = mutableListOf(ffmpegBinary.absolutePath, "-y").apply {
            addClipInputArgs()
            add("-i")
            add(videoFile.absolutePath)
            addClipInputArgs()
            add("-i")
            add(audioFile.absolutePath)
            addAll(
                listOf(
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-strict", "-2",
                    "-movflags", "+faststart",
                    "-shortest",
                    outputFile.absolutePath
                )
            )
        }
        val aacAudioAttempt = runCatching {
            runFfmpegCommand(aacAudioArgs, workDir, outputFile, "Video ve ses birleştirilemedi.")
        }
        if (aacAudioAttempt.isSuccess) {
            return@withContext outputFile
        }

        // 3) Video/ses kodeği MP4 ile uyumsuzsa kayıpsız MKV kapsayıcısında birleştir (360p düşüşünü önler)
        runCatching { outputFile.delete() }
        val mkvOutput = File(outputFile.parentFile ?: workDir, "${outputFile.nameWithoutExtension}.mkv")
        runCatching { mkvOutput.delete() }
        val mkvArgs = mutableListOf(ffmpegBinary.absolutePath, "-y").apply {
            addClipInputArgs()
            add("-i")
            add(videoFile.absolutePath)
            addClipInputArgs()
            add("-i")
            add(audioFile.absolutePath)
            addAll(
                listOf(
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "copy",
                    "-shortest",
                    mkvOutput.absolutePath
                )
            )
        }
        runFfmpegCommand(mkvArgs, workDir, mkvOutput, "Video ve ses birleştirilemedi.")
        mkvOutput
    }

    private fun runFfmpegCommand(
        args: List<String>,
        workDir: File,
        outputFile: File,
        failureFallback: String
    ) {
        val processBuilder = ProcessBuilder(args).apply {
            directory(workDir)
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
        if (exitCode != 0 || !outputFile.exists() || outputFile.length() <= 0L) {
            val rawReason = tailLog.lastOrNull { it.isNotBlank() } ?: "exitCode=$exitCode"
            throw IOException(
                ErrorMessageSanitizer.sanitize(
                    throwable = IOException(rawReason),
                    fallback = failureFallback
                )
            )
        }
    }

    /**
     * Android yerleşik [android.media.MediaExtractor] ve [android.media.MediaMuxer] API'si ile
     * video + ses akışlarını harici binary ve SELinux W^X kısıtına takılmadan, kalite kayıpsız
     * ve sıfır yeniden kodlama ile birleştirir (Snaptube / NewPipe mimarisi).
     */
    private fun muxWithAndroidMediaMuxer(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): File {
        val videoExtractor = android.media.MediaExtractor()
        val audioExtractor = android.media.MediaExtractor()
        var muxer: android.media.MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            var videoTrackIndex = -1
            var videoFormat: android.media.MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = fmt
                    break
                }
            }

            var audioTrackIndex = -1
            var audioFormat: android.media.MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val fmt = audioExtractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null || audioTrackIndex < 0 || audioFormat == null) {
                throw IOException("MediaExtractor geçerli video/ses akışı bulamadı.")
            }

            val videoMime = (videoFormat.getString(android.media.MediaFormat.KEY_MIME) ?: "").lowercase(Locale.ROOT)
            val audioMime = (audioFormat.getString(android.media.MediaFormat.KEY_MIME) ?: "").lowercase(Locale.ROOT)
            val needsWebm = videoMime.contains("vp8") || videoMime.contains("vp9") || audioMime.contains("opus") || audioMime.contains("vorbis")

            val actualOutput: File
            val muxerFormat: Int
            if (needsWebm) {
                actualOutput = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.webm")
                muxerFormat = android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                actualOutput = outputFile
                muxerFormat = android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            if (actualOutput.exists()) actualOutput.delete()

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            val videoMaxSize = runCatching { videoFormat.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
            val audioMaxSize = runCatching { audioFormat.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
            val bufferCapacity = maxOf(4 * 1024 * 1024, videoMaxSize, audioMaxSize).coerceAtMost(16 * 1024 * 1024)

            muxer = android.media.MediaMuxer(actualOutput.absolutePath, muxerFormat)
            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = java.nio.ByteBuffer.allocateDirect(bufferCapacity)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            fun mapExtractorFlags(rawFlags: Int): Int {
                return if ((rawFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
            }

            // Video örneklerini kopyala
            while (true) {
                buffer.clear()
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.flags = mapExtractorFlags(videoExtractor.sampleFlags)
                muxer.writeSampleData(outVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // Ses örneklerini kopyala
            while (true) {
                buffer.clear()
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = audioExtractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.flags = mapExtractorFlags(audioExtractor.sampleFlags)
                muxer.writeSampleData(outAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (!actualOutput.exists() || actualOutput.length() <= 0L) {
                throw IOException("MediaMuxer çıktı dosyası oluşturamadı.")
            }
            return actualOutput
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun resolveFfmpegExecutable(): File? = resolveFfmpegBinary()

    private fun resolveFfmpegBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val candidates = listOf(
            File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
            File(context.filesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
            File(nativeDir, "ffmpeg"),
            File(nativeDir, "libffmpeg.so")
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

    private fun executeSingleInputAction(
        ffmpegPath: String,
        inputPath: String,
        outputFile: File,
        action: FfmpegStudioAction,
        options: FfmpegStudioOptions,
        jobDir: File,
        onProgress: (Float) -> Unit
    ) {
        onProgress(0.45f)
        when (action) {
            FfmpegStudioAction.TRIM_VIDEO -> {
                val startSec = options.trimStartSeconds.coerceAtLeast(0f)
                val endSec = options.trimEndSeconds.takeIf { it > startSec }

                val fastCopyArgs = mutableListOf(ffmpegPath, "-y").apply {
                    if (startSec > 0f) {
                        add("-ss")
                        add(formatSeconds(startSec))
                    }
                    if (endSec != null) {
                        add("-to")
                        add(formatSeconds(endSec))
                    }
                    addAll(listOf("-i", inputPath, "-c", "copy", "-movflags", "+faststart", outputFile.absolutePath))
                }
                val copyAttempt = runCatching {
                    runFfmpegCommand(fastCopyArgs, jobDir, outputFile, "Video kırpma tamamlanamadı.")
                }
                if (copyAttempt.isFailure) {
                    outputFile.delete()
                    val reencodeArgs = mutableListOf(ffmpegPath, "-y").apply {
                        if (startSec > 0f) {
                            add("-ss")
                            add(formatSeconds(startSec))
                        }
                        if (endSec != null) {
                            add("-to")
                            add(formatSeconds(endSec))
                        }
                        addAll(
                            listOf(
                                "-i", inputPath,
                                "-c:v", "libx264",
                                "-preset", "veryfast",
                                "-crf", "23",
                                "-c:a", "aac",
                                "-b:a", "128k",
                                "-movflags", "+faststart",
                                outputFile.absolutePath
                            )
                        )
                    }
                    runFfmpegCommand(reencodeArgs, jobDir, outputFile, "Video kırpma tamamlanamadı.")
                }
            }

            FfmpegStudioAction.CHANGE_SPEED -> {
                val speed = options.speedFactor.coerceIn(0.25f, 4.0f)
                val ptsFactor = String.format(Locale.US, "%.4f*PTS", 1.0 / speed.toDouble())
                val atempo = buildAtempoFilterChain(speed)

                val withAudioArgs = listOf(
                    ffmpegPath, "-y",
                    "-i", inputPath,
                    "-vf", "setpts=$ptsFactor",
                    "-af", atempo,
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "23",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-movflags", "+faststart",
                    outputFile.absolutePath
                )
                val withAudioAttempt = runCatching {
                    runFfmpegCommand(withAudioArgs, jobDir, outputFile, "Oynatma hızı değiştirilemedi.")
                }
                if (withAudioAttempt.isFailure) {
                    outputFile.delete()
                    // Sessiz video dosyaları için ses filtresiz fallback
                    val videoOnlyArgs = listOf(
                        ffmpegPath, "-y",
                        "-i", inputPath,
                        "-vf", "setpts=$ptsFactor",
                        "-c:v", "libx264",
                        "-preset", "veryfast",
                        "-crf", "23",
                        "-an",
                        "-movflags", "+faststart",
                        outputFile.absolutePath
                    )
                    runFfmpegCommand(videoOnlyArgs, jobDir, outputFile, "Oynatma hızı değiştirilemedi.")
                }
            }

            else -> {
                val args = buildFfmpegArgs(
                    ffmpegPath = ffmpegPath,
                    inputPath = inputPath,
                    outputPath = outputFile.absolutePath,
                    action = action,
                    options = options
                )
                runFfmpegCommand(
                    args = args,
                    workDir = jobDir,
                    outputFile = outputFile,
                    failureFallback = "FFmpeg dönüştürme işlemi tamamlanamadı."
                )
            }
        }
        onProgress(0.88f)
    }

    private fun executeMergeAction(
        ffmpegPath: String,
        primaryInputUri: Uri,
        options: FfmpegStudioOptions,
        jobDir: File,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) {
        val rawUris = buildList {
            val fromOptions = options.mergeSourceUris
                .mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Uri.parse(it) }.getOrNull() } }
            if (fromOptions.size >= 2) {
                addAll(fromOptions)
            } else {
                add(primaryInputUri)
                fromOptions.forEach { uri ->
                    if (uri.toString() != primaryInputUri.toString()) {
                        add(uri)
                    }
                }
            }
        }

        if (rawUris.size < 2) {
            throw IllegalArgumentException("Birleştirmek için en az 2 video seçmelisiniz.")
        }

        val inputFiles = rawUris.mapIndexed { index, uri ->
            val file = File(jobDir, "merge_$index.mp4")
            copyUriToFileWithLimit(uri, file, MAX_INPUT_FILE_BYTES)
            onProgress(0.10f + (0.35f * (index + 1) / rawUris.size.toFloat()))
            file
        }

        // 1. Aşama: Hızlı concat demuxer (aynı kodek / çözünürlük)
        val concatListFile = File(jobDir, "concat_list.txt")
        concatListFile.writeText(
            inputFiles.joinToString("\n") { file ->
                val escaped = file.absolutePath.replace("'", "'\\''")
                "file '$escaped'"
            }
        )

        val demuxArgs = listOf(
            ffmpegPath, "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", concatListFile.absolutePath,
            "-c", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath
        )

        onProgress(0.55f)
        val demuxAttempt = runCatching {
            runFfmpegCommand(demuxArgs, jobDir, outputFile, "Videolar birleştirilemedi.")
        }
        if (demuxAttempt.isSuccess) {
            onProgress(0.88f)
            return
        }

        // 2. Aşama: Farklı çözünürlük/kodek klipler için güvenli filter_complex ölçekle-birleştir
        outputFile.delete()
        val filterInputs = mutableListOf<String>()
        val scaleChains = StringBuilder()
        val concatInputs = StringBuilder()

        inputFiles.forEachIndexed { idx, file ->
            filterInputs.add("-i")
            filterInputs.add(file.absolutePath)
            scaleChains.append(
                "[$idx:v]scale=1280:720:force_original_aspect_ratio=decrease," +
                    "pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30[v$idx];"
            )
            scaleChains.append("[$idx:a]aresample=44100[a$idx];")
            concatInputs.append("[v$idx][a$idx]")
        }
        val filterComplexWithAudio = "${scaleChains}${concatInputs}concat=n=${inputFiles.size}:v=1:a=1[vout][aout]"

        val reencodeWithAudioArgs = buildList {
            add(ffmpegPath)
            add("-y")
            addAll(filterInputs)
            addAll(
                listOf(
                    "-filter_complex", filterComplexWithAudio,
                    "-map", "[vout]",
                    "-map", "[aout]",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "23",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-movflags", "+faststart",
                    outputFile.absolutePath
                )
            )
        }

        val withAudioAttempt = runCatching {
            runFfmpegCommand(reencodeWithAudioArgs, jobDir, outputFile, "Videolar birleştirilemedi.")
        }
        if (withAudioAttempt.isFailure) {
            outputFile.delete()
            // Kliplerden biri sessizse yalnızca video birleştirme fallback
            val vOnlyChains = StringBuilder()
            val vOnlyConcat = StringBuilder()
            inputFiles.indices.forEach { idx ->
                vOnlyChains.append(
                    "[$idx:v]scale=1280:720:force_original_aspect_ratio=decrease," +
                        "pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30[v$idx];"
                )
                vOnlyConcat.append("[v$idx]")
            }
            val filterVideoOnly = "${vOnlyChains}${vOnlyConcat}concat=n=${inputFiles.size}:v=1:a=0[vout]"
            val videoOnlyArgs = buildList {
                add(ffmpegPath)
                add("-y")
                addAll(filterInputs)
                addAll(
                    listOf(
                        "-filter_complex", filterVideoOnly,
                        "-map", "[vout]",
                        "-c:v", "libx264",
                        "-preset", "veryfast",
                        "-crf", "23",
                        "-an",
                        "-movflags", "+faststart",
                        outputFile.absolutePath
                    )
                )
            }
            runFfmpegCommand(videoOnlyArgs, jobDir, outputFile, "Videolar birleştirilemedi.")
        }
        onProgress(0.88f)
    }

    private fun buildSpecForAction(
        action: FfmpegStudioAction,
        options: FfmpegStudioOptions,
        jobDir: File
    ): ActionOutputSpec {
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

            FfmpegStudioAction.MAKE_GIF -> {
                if (options.useWebp) {
                    ActionOutputSpec(
                        outputFile = File(jobDir, "output.webp"),
                        extension = "webp",
                        suffix = "webp",
                        category = MediaCategory.IMAGE
                    )
                } else {
                    ActionOutputSpec(
                        outputFile = File(jobDir, "output.gif"),
                        extension = "gif",
                        suffix = "gif",
                        category = MediaCategory.IMAGE
                    )
                }
            }

            FfmpegStudioAction.REMOVE_AUDIO -> ActionOutputSpec(
                outputFile = File(jobDir, "output.mp4"),
                extension = "mp4",
                suffix = "muted",
                category = MediaCategory.VIDEO
            )

            FfmpegStudioAction.TRIM_VIDEO -> ActionOutputSpec(
                outputFile = File(jobDir, "output.mp4"),
                extension = "mp4",
                suffix = "trimmed",
                category = MediaCategory.VIDEO
            )

            FfmpegStudioAction.MERGE_VIDEOS -> ActionOutputSpec(
                outputFile = File(jobDir, "output.mp4"),
                extension = "mp4",
                suffix = "merged",
                category = MediaCategory.VIDEO
            )

            FfmpegStudioAction.CONVERT_AUDIO -> {
                val ext = normalizeAudioExtension(options.audioFormat)
                ActionOutputSpec(
                    outputFile = File(jobDir, "output.$ext"),
                    extension = ext,
                    suffix = if (options.saveAsRingtone) "ringtone" else ext,
                    category = MediaCategory.AUDIO
                )
            }

            FfmpegStudioAction.CHANGE_SPEED -> ActionOutputSpec(
                outputFile = File(jobDir, "output.mp4"),
                extension = "mp4",
                suffix = "speed",
                category = MediaCategory.VIDEO
            )

            FfmpegStudioAction.CROP_ASPECT -> ActionOutputSpec(
                outputFile = File(jobDir, "output.mp4"),
                extension = "mp4",
                suffix = "crop_${options.aspectRatio.ratioW}x${options.aspectRatio.ratioH}",
                category = MediaCategory.VIDEO
            )
        }
    }

    private fun buildFfmpegArgs(
        ffmpegPath: String,
        inputPath: String,
        outputPath: String,
        action: FfmpegStudioAction,
        options: FfmpegStudioOptions
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

            FfmpegStudioAction.MAKE_GIF -> {
                val fps = options.gifFps.coerceIn(5, 30)
                val maxWidth = options.gifMaxWidth.coerceIn(160, 1080)
                val vf = "fps=$fps,scale=$maxWidth:-1:flags=lanczos"
                if (options.useWebp) {
                    listOf(
                        ffmpegPath,
                        "-y",
                        "-i", inputPath,
                        "-t", "15",
                        "-vf", vf,
                        "-c:v", "libwebp",
                        "-lossless", "0",
                        "-q:v", "75",
                        "-loop", "0",
                        "-an",
                        outputPath
                    )
                } else {
                    listOf(
                        ffmpegPath,
                        "-y",
                        "-i", inputPath,
                        "-t", "15",
                        "-vf", vf,
                        outputPath
                    )
                }
            }

            FfmpegStudioAction.REMOVE_AUDIO -> listOf(
                ffmpegPath,
                "-y",
                "-i", inputPath,
                "-c:v", "copy",
                "-an",
                outputPath
            )

            FfmpegStudioAction.CONVERT_AUDIO -> {
                val ext = normalizeAudioExtension(options.audioFormat)
                mutableListOf(ffmpegPath, "-y", "-i", inputPath, "-vn").apply {
                    when (ext) {
                        "m4a" -> addAll(listOf("-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart"))
                        "aac" -> addAll(listOf("-c:a", "aac", "-b:a", "256k"))
                        "flac" -> addAll(listOf("-c:a", "flac"))
                        "opus" -> addAll(listOf("-c:a", "libopus", "-b:a", "160k"))
                        "wav" -> addAll(listOf("-c:a", "pcm_s16le"))
                        else -> addAll(listOf("-c:a", "libmp3lame", "-b:a", "320k"))
                    }
                    add(outputPath)
                }
            }

            FfmpegStudioAction.CROP_ASPECT -> listOf(
                ffmpegPath,
                "-y",
                "-i", inputPath,
                "-vf", buildCropFilter(options.aspectRatio),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-c:a", "copy",
                "-movflags", "+faststart",
                outputPath
            )

            FfmpegStudioAction.TRIM_VIDEO,
            FfmpegStudioAction.MERGE_VIDEOS,
            FfmpegStudioAction.CHANGE_SPEED -> error("Bu eylem özel yürütücü tarafından işlenir: $action")
        }
    }

    private fun normalizeAudioExtension(rawFormat: String): String {
        return when (rawFormat.trim().lowercase(Locale.ROOT)) {
            "m4a" -> "m4a"
            "aac" -> "aac"
            "flac" -> "flac"
            "opus" -> "opus"
            "wav" -> "wav"
            else -> "mp3"
        }
    }

    private fun formatSeconds(seconds: Float): String {
        return String.format(Locale.US, "%.2f", seconds.coerceAtLeast(0f))
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

        /**
         * 0.25x..4.0x arasındaki oynatma hızı çarpanını FFmpeg `atempo` filtresinin
         * 0.5..2.0 aralığı kısıtına uygun zincire dönüştürür.
         * Örnek: `4.0f -> "atempo=2.0,atempo=2.0"`, `2.5f -> "atempo=2.0,atempo=1.25"`.
         */
        fun buildAtempoFilterChain(speedFactor: Float): String {
            val clamped = speedFactor.coerceIn(0.25f, 4.0f).toDouble()
            val filters = mutableListOf<String>()
            var remaining = clamped

            while (remaining > 2.0001) {
                filters.add("atempo=2.0")
                remaining /= 2.0
            }
            while (remaining < 0.4999) {
                filters.add("atempo=0.5")
                remaining /= 0.5
            }

            if (filters.isEmpty() || kotlin.math.abs(remaining - 1.0) > 0.0005) {
                filters.add("atempo=${formatAtempoValue(remaining)}")
            }
            return filters.joinToString(",")
        }

        /**
         * Seçilen sosyal medya en-boy oranına göre H.264/yuv420p uyumlu çift piksel
         * (`trunc(.../2)*2`) merkez kırpma FFmpeg video filtresini üretir.
         */
        fun buildCropFilter(aspectRatio: StudioAspectRatio): String = when (aspectRatio) {
            StudioAspectRatio.RATIO_9_16 ->
                "crop=trunc(min(iw\\,ih*9/16)/2)*2:trunc(min(ih\\,iw*16/9)/2)*2"
            StudioAspectRatio.RATIO_1_1 ->
                "crop=trunc(min(iw\\,ih)/2)*2:trunc(min(ih\\,iw)/2)*2"
            StudioAspectRatio.RATIO_16_9 ->
                "crop=trunc(min(iw\\,ih*16/9)/2)*2:trunc(min(ih\\,iw*9/16)/2)*2"
        }

        private fun formatAtempoValue(value: Double): String {
            val rounded = kotlin.math.round(value * 100.0) / 100.0
            val formatted = String.format(Locale.US, "%.2f", rounded)
            return when {
                formatted.endsWith(".00") -> formatted.dropLast(1) // "2.00" -> "2.0"
                formatted.endsWith("0") -> formatted.dropLast(1)   // "0.50" -> "0.5"
                else -> formatted                                   // "1.25" -> "1.25"
            }
        }
    }
}
