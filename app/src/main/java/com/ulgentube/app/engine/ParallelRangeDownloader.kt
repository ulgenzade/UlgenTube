package com.ulgentube.app.engine

import com.ulgentube.app.util.NetworkSecurityGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP `Range` (206 Partial Content) destekleyen kaynaklar için dinamik iş kuyruklu
 * çok bağlantılı paralel indirme motoru; Range desteklemeyen veya küçük dosyalarda
 * otomatik olarak tek bağlantılı güvenli streaming indirmeye düşer.
 */
class ParallelRangeDownloader(
    private val okHttpClient: OkHttpClient
) {

    class HttpStatusException(
        val statusCode: Int,
        message: String
    ) : IOException(message)

    private class RetryableChunkException(message: String) : IOException(message)

    private class SharedSpeedLimiter(private val maxBytesPerSec: Long) {
        private val lock = Any()
        private var windowStartMs: Long = System.currentTimeMillis()
        private var bytesInWindow: Long = 0L

        suspend fun throttle(bytesRead: Long) {
            if (maxBytesPerSec <= 0L || bytesRead <= 0L) return
            val sleepMs = synchronized(lock) {
                val now = System.currentTimeMillis()
                val elapsed = now - windowStartMs
                if (elapsed >= 1000L) {
                    windowStartMs = now
                    bytesInWindow = 0L
                }
                bytesInWindow += bytesRead
                if (bytesInWindow > maxBytesPerSec) {
                    val expectedElapsedMs = (bytesInWindow * 1000L) / maxBytesPerSec
                    val currentElapsedMs = (now - windowStartMs).coerceAtLeast(0L)
                    (expectedElapsedMs - currentElapsedMs).coerceIn(10L, 2000L)
                } else {
                    0L
                }
            }
            if (sleepMs > 0L) {
                delay(sleepMs)
            }
        }
    }

    suspend fun download(
        url: String,
        targetFile: File,
        userAgent: String,
        connections: Int = 4,
        maxBytes: Long = 25L * 1024L * 1024L * 1024L,
        maxSpeedBytesPerSec: Long = 0L,
        onProgress: (percent: Float, speedText: String?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (!NetworkSecurityGuard.isSafePublicUrl(url)) {
            throw SecurityException("Güvensiz veya geçersiz indirme adresi reddedildi.")
        }
        targetFile.parentFile?.mkdirs()
        val speedLimiter = if (maxSpeedBytesPerSec > 0L) SharedSpeedLimiter(maxSpeedBytesPerSec) else null

        try {
            val probeRequest = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-0")
                .header("Accept", "*/*")
                .build()

            var acceptsRanges = false
            var totalBytes = -1L
            var effectiveUrl = url

            okHttpClient.newCall(probeRequest).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (!NetworkSecurityGuard.isSafePublicUrl(finalUrl)) {
                    throw SecurityException("Redirect sonrası güvensiz adres reddedildi.")
                }
                effectiveUrl = finalUrl

                if (!resp.isSuccessful) {
                    throw HttpStatusException(resp.code, "Dosya akışı açılamadı (HTTP ${resp.code}).")
                }

                acceptsRanges = resp.code == 206
                if (acceptsRanges) {
                    val contentRange = resp.header("Content-Range")
                    if (!contentRange.isNullOrBlank()) {
                        totalBytes = contentRange.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                    }
                    if (totalBytes > maxBytes) {
                        throw IOException("Dosya güvenlik boyut sınırını aşıyor.")
                    }
                } else {
                    val body = resp.body ?: throw IOException("Sunucu boş yanıt döndürdü.")
                    val contentLength = body.contentLength()
                    if (contentLength > maxBytes) {
                        throw IOException("Dosya güvenlik boyut sınırını aşıyor.")
                    }
                    // Sunucu Range görmezden gelip 200 ile tüm akışı başlattıysa aynı bağlantıdan streaming indir
                    streamBodyToFile(
                        body = body,
                        knownTotalBytes = contentLength,
                        targetFile = targetFile,
                        maxBytes = maxBytes,
                        speedLimiter = speedLimiter,
                        onProgress = onProgress
                    )
                    onProgress(100f, null)
                    return@withContext targetFile
                }
            }

            if (!acceptsRanges || totalBytes < MIN_PARALLEL_BYTES) {
                downloadSingleStream(
                    url = effectiveUrl,
                    targetFile = targetFile,
                    userAgent = userAgent,
                    maxBytes = maxBytes,
                    probedTotalBytes = totalBytes,
                    speedLimiter = speedLimiter,
                    onProgress = onProgress
                )
                onProgress(100f, null)
                return@withContext targetFile
            }

            if (connections <= 1) {
                // Tek bağlantıda bile YouTube/CDN hız kısıtlamasını (90KB/s-1MB/s) aşmak için
                // sıralı HTTP Range parçalarıyla indir.
                downloadSequentialRanges(
                    url = effectiveUrl,
                    targetFile = targetFile,
                    userAgent = userAgent,
                    totalBytes = totalBytes,
                    maxBytes = maxBytes,
                    speedLimiter = speedLimiter,
                    onProgress = onProgress
                )
                onProgress(100f, null)
                return@withContext targetFile
            }

            downloadParallelRanges(
                url = effectiveUrl,
                targetFile = targetFile,
                userAgent = userAgent,
                totalBytes = totalBytes,
                connections = connections,
                maxBytes = maxBytes,
                speedLimiter = speedLimiter,
                onProgress = onProgress
            )
            onProgress(100f, null)
            targetFile
        } catch (e: Exception) {
            runCatching { targetFile.delete() }
            throw e
        }
    }

    private suspend fun downloadSequentialRanges(
        url: String,
        targetFile: File,
        userAgent: String,
        totalBytes: Long,
        maxBytes: Long,
        speedLimiter: SharedSpeedLimiter?,
        onProgress: (percent: Float, speedText: String?) -> Unit
    ) {
        val chunkSize = 4L * 1024L * 1024L // 4 MB sıralı Range parçaları
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        var downloadedBytes = 0L
        var lastEmitMs = System.currentTimeMillis()
        var lastSnapshotBytes = 0L

        FileOutputStream(targetFile, false).use { output ->
            var start = 0L
            while (start < totalBytes) {
                currentCoroutineContext().ensureActive()
                val end = minOf(start + chunkSize - 1L, totalBytes - 1L)
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=$start-$end")
                    .header("Accept", "*/*")
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    if (!NetworkSecurityGuard.isSafePublicUrl(finalUrl)) {
                        throw SecurityException("Redirect sonrası güvensiz adres reddedildi.")
                    }
                    if (!resp.isSuccessful) {
                        throw HttpStatusException(resp.code, "Parça indirilemedi (HTTP ${resp.code}).")
                    }
                    val body = resp.body ?: throw IOException("Sunucu boş yanıt döndürdü.")
                    var chunkRead = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            downloadedBytes += read
                            chunkRead += read
                            if (downloadedBytes > maxBytes) {
                                throw IOException("İndirme güvenlik boyut sınırını aşıyor.")
                            }
                            output.write(buffer, 0, read)
                            speedLimiter?.throttle(read.toLong())

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastEmitMs
                            if (elapsed >= 250L) {
                                val delta = downloadedBytes - lastSnapshotBytes
                                val speedBps = if (elapsed > 0L) (delta * 1000.0) / elapsed else 0.0
                                val pct = ((downloadedBytes.toFloat() / totalBytes.toFloat()) * 100f)
                                    .coerceIn(0f, 100f)
                                onProgress(pct, if (speedBps > 0) formatSpeed(speedBps) else null)
                                lastEmitMs = now
                                lastSnapshotBytes = downloadedBytes
                            }
                        }
                    }
                    if (chunkRead <= 0L) {
                        throw IOException("Parça akışı beklenmedik şekilde kesildi.")
                    }
                    start += chunkRead
                }
            }
        }
    }

    private suspend fun downloadSingleStream(
        url: String,
        targetFile: File,
        userAgent: String,
        maxBytes: Long,
        probedTotalBytes: Long,
        speedLimiter: SharedSpeedLimiter?,
        onProgress: (percent: Float, speedText: String?) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            if (!NetworkSecurityGuard.isSafePublicUrl(finalUrl)) {
                throw SecurityException("Redirect sonrası güvensiz adres reddedildi.")
            }
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, "Dosya indirilemedi (HTTP ${resp.code}).")
            }
            val body = resp.body ?: throw IOException("Sunucu boş yanıt döndürdü.")
            val cl = body.contentLength()
            val knownTotal = if (cl > 0L) cl else probedTotalBytes
            if (knownTotal > maxBytes) {
                throw IOException("Dosya güvenlik boyut sınırını aşıyor.")
            }

            streamBodyToFile(
                body = body,
                knownTotalBytes = knownTotal,
                targetFile = targetFile,
                maxBytes = maxBytes,
                speedLimiter = speedLimiter,
                onProgress = onProgress
            )
        }
    }

    private suspend fun streamBodyToFile(
        body: ResponseBody,
        knownTotalBytes: Long,
        targetFile: File,
        maxBytes: Long,
        speedLimiter: SharedSpeedLimiter?,
        onProgress: (percent: Float, speedText: String?) -> Unit
    ) {
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        var downloadedBytes = 0L
        var lastEmitMs = System.currentTimeMillis()
        var lastSnapshotBytes = 0L

        body.byteStream().use { input ->
            FileOutputStream(targetFile, false).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    downloadedBytes += read
                    if (downloadedBytes > maxBytes) {
                        throw IOException("İndirme güvenlik boyut sınırını aşıyor.")
                    }
                    output.write(buffer, 0, read)
                    speedLimiter?.throttle(read.toLong())

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastEmitMs
                    if (elapsed >= PROGRESS_THROTTLE_MS) {
                        val delta = downloadedBytes - lastSnapshotBytes
                        val speedBytesPerSec = (delta * 1000.0) / elapsed.coerceAtLeast(1L)
                        val speedText = if (speedBytesPerSec > 0) formatSpeed(speedBytesPerSec) else null
                        val percent = if (knownTotalBytes > 0L) {
                            ((downloadedBytes.toFloat() / knownTotalBytes.toFloat()) * 100f).coerceIn(0f, 100f)
                        } else {
                            0f
                        }
                        onProgress(percent, speedText)
                        lastEmitMs = now
                        lastSnapshotBytes = downloadedBytes
                    }
                }
                output.flush()
            }
        }
    }

    private suspend fun downloadParallelRanges(
        url: String,
        targetFile: File,
        userAgent: String,
        totalBytes: Long,
        connections: Int,
        maxBytes: Long,
        speedLimiter: SharedSpeedLimiter?,
        onProgress: (percent: Float, speedText: String?) -> Unit
    ) {
        RandomAccessFile(targetFile, "rw").use { it.setLength(totalBytes) }

        val nextOffset = AtomicLong(0L)
        val totalDownloaded = AtomicLong(0L)
        val progressLock = Any()
        var lastEmitMs = System.currentTimeMillis()
        var lastSnapshotBytes = 0L

        fun reportProgress(currentTotal: Long) {
            var emitPayload: Pair<Float, String?>? = null
            synchronized(progressLock) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastEmitMs
                if (elapsed >= PROGRESS_THROTTLE_MS) {
                    val delta = currentTotal - lastSnapshotBytes
                    val speedBytesPerSec = (delta * 1000.0) / elapsed.coerceAtLeast(1L)
                    val speedText = if (speedBytesPerSec > 0) formatSpeed(speedBytesPerSec) else null
                    val percent = ((currentTotal.toFloat() / totalBytes.toFloat()) * 100f).coerceIn(0f, 100f)
                    lastEmitMs = now
                    lastSnapshotBytes = currentTotal
                    emitPayload = percent to speedText
                }
            }
            emitPayload?.let { (percent, speedText) ->
                onProgress(percent, speedText)
            }
        }

        val workerCount = connections.coerceIn(1, MAX_CONNECTIONS)
        coroutineScope {
            List(workerCount) {
                async(Dispatchers.IO) {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val start = nextOffset.getAndAdd(CHUNK_BYTES)
                        if (start >= totalBytes) break
                        val end = minOf(start + CHUNK_BYTES - 1L, totalBytes - 1L)
                        downloadChunkWithRetry(
                            url = url,
                            targetFile = targetFile,
                            userAgent = userAgent,
                            start = start,
                            end = end,
                            maxBytes = maxBytes,
                            totalDownloaded = totalDownloaded,
                            speedLimiter = speedLimiter,
                            onBytesAdded = ::reportProgress
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadChunkWithRetry(
        url: String,
        targetFile: File,
        userAgent: String,
        start: Long,
        end: Long,
        maxBytes: Long,
        totalDownloaded: AtomicLong,
        speedLimiter: SharedSpeedLimiter?,
        onBytesAdded: (Long) -> Unit
    ) {
        var writePos = start
        var attempt = 0

        while (writePos <= end) {
            currentCoroutineContext().ensureActive()
            try {
                val chunkReq = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=$writePos-$end")
                    .header("Accept", "*/*")
                    .build()

                okHttpClient.newCall(chunkReq).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    if (!NetworkSecurityGuard.isSafePublicUrl(finalUrl)) {
                        throw SecurityException("Redirect sonrası güvensiz adres reddedildi.")
                    }

                    if (resp.code == 403 || resp.code == 404 || resp.code == 416) {
                        throw HttpStatusException(resp.code, "Parçalı akış reddedildi (HTTP ${resp.code}).")
                    }
                    if (resp.code != 206) {
                        throw RetryableChunkException("Beklenmeyen parça yanıtı (HTTP ${resp.code}).")
                    }

                    val body = resp.body ?: throw RetryableChunkException("Boş akış parçası.")
                    val buffer = ByteArray(BUFFER_SIZE_BYTES)

                    // Paylaşılan RandomAccessFile yerine her coroutine/parça kendi tanıtıcısını açar (seek race condition önlemi)
                    RandomAccessFile(targetFile, "rw").use { raf ->
                        raf.seek(writePos)
                        body.byteStream().use { input ->
                            while (writePos <= end) {
                                currentCoroutineContext().ensureActive()
                                val maxToRead = minOf(buffer.size.toLong(), end - writePos + 1L).toInt()
                                val read = input.read(buffer, 0, maxToRead)
                                if (read == -1) break
                                raf.write(buffer, 0, read)
                                writePos += read
                                speedLimiter?.throttle(read.toLong())
                                val currentTotal = totalDownloaded.addAndGet(read.toLong())
                                if (currentTotal > maxBytes) {
                                    throw IOException("İndirme güvenlik boyut sınırını aşıyor.")
                                }
                                onBytesAdded(currentTotal)
                            }
                        }
                    }

                    if (writePos <= end) {
                        throw RetryableChunkException("Parça beklenenden erken sonlandı.")
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (sec: SecurityException) {
                throw sec
            } catch (httpErr: HttpStatusException) {
                throw httpErr
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_CHUNK_RETRIES) {
                    throw (e as? IOException ?: IOException("Parçalı indirme başarısız oldu.", e))
                }
                delay(RETRY_BASE_DELAY_MS * attempt)
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        return when {
            bytesPerSec >= mb -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / mb)
            bytesPerSec >= kb -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / kb)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    companion object {
        private const val CHUNK_BYTES = 2L * 1024L * 1024L // 2 MB
        private const val MIN_PARALLEL_BYTES = 1_048_576L // 1 MB
        private const val BUFFER_SIZE_BYTES = 16 * 1024 // 16 KB
        private const val PROGRESS_THROTTLE_MS = 250L
        private const val MAX_CONNECTIONS = 16
        private const val MAX_CHUNK_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 250L
    }
}
