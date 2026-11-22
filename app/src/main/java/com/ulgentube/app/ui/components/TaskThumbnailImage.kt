package com.ulgentube.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.util.MediaUriHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOCAL_THUMBNAIL_MAX_PX = 320
private val localThumbnailCache = LruCache<String, Bitmap>(32)

/**
 * İndirme kartları ve mini çalar için ortak akıllı kapak bileşeni.
 *
 * - Tamamlanan görsellerde doğrudan yerel `content://` URI'sini kullanır.
 * - Uzak kapak URL'si yoksa veya yüklenemezse tamamlanan yerel video/ses dosyasından
 *   kapak çıkarıp bellek içi LRU önbelleğinde saklar.
 * - Hiç kapak yoksa kategori ikonunu gösterir.
 */
@Composable
fun TaskThumbnailImage(
    task: DownloadTask,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp
) {
    val context = LocalContext.current
    val cacheKey = remember(task.id, task.savedUri, task.savedPath) {
        "${task.id}_${task.savedUri.orEmpty()}_${task.savedPath.orEmpty()}"
    }

    val localImageUri = remember(task.id, task.status, task.category, task.savedUri, task.savedPath) {
        if (task.status == DownloadStatus.COMPLETED && task.category == MediaCategory.IMAGE) {
            MediaUriHelper.resolveTaskUri(context, task)
        } else {
            null
        }
    }

    var localImageFailed by remember(cacheKey) { mutableStateOf(false) }
    var remoteFailed by remember(task.id, task.thumbnailUrl) { mutableStateOf(false) }
    var localBitmap by remember(cacheKey) {
        mutableStateOf(localThumbnailCache.get(cacheKey)?.takeIf { !it.isRecycled })
    }

    val shouldExtractLocal = task.status == DownloadStatus.COMPLETED &&
        task.category != MediaCategory.IMAGE &&
        (task.thumbnailUrl.isNullOrBlank() || remoteFailed)

    LaunchedEffect(cacheKey, shouldExtractLocal) {
        if (!shouldExtractLocal) return@LaunchedEffect

        val cached = localThumbnailCache.get(cacheKey)?.takeIf { !it.isRecycled }
        if (cached != null) {
            localBitmap = cached
            return@LaunchedEffect
        }

        val extracted = withContext(Dispatchers.IO) {
            extractLocalMediaThumbnail(context, task)
        }
        if (extracted != null && !extracted.isRecycled) {
            localThumbnailCache.put(cacheKey, extracted)
            localBitmap = extracted
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        when {
            localImageUri != null && !localImageFailed -> {
                AsyncImage(
                    model = localImageUri,
                    contentDescription = task.title,
                    contentScale = ContentScale.Crop,
                    onError = { localImageFailed = true },
                    modifier = Modifier.fillMaxSize()
                )
            }

            !task.thumbnailUrl.isNullOrBlank() && !remoteFailed -> {
                AsyncImage(
                    model = task.thumbnailUrl,
                    contentDescription = task.title,
                    contentScale = ContentScale.Crop,
                    onError = { remoteFailed = true },
                    modifier = Modifier.fillMaxSize()
                )
            }

            localBitmap != null && localBitmap?.isRecycled == false -> {
                Image(
                    bitmap = localBitmap!!.asImageBitmap(),
                    contentDescription = task.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                val icon = when (task.category) {
                    MediaCategory.VIDEO -> Icons.Rounded.Videocam
                    MediaCategory.AUDIO -> Icons.Rounded.MusicNote
                    MediaCategory.IMAGE -> Icons.Rounded.Image
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ElectricPurple,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

private fun extractLocalMediaThumbnail(
    context: Context,
    task: DownloadTask
): Bitmap? {
    val localUri = MediaUriHelper.resolveTaskUri(context, task) ?: return null
    return when (task.category) {
        MediaCategory.VIDEO -> extractVideoThumbnail(context, localUri)
        MediaCategory.AUDIO -> extractAudioEmbeddedCover(context, localUri)
        MediaCategory.IMAGE -> null
    }
}

private fun extractVideoThumbnail(
    context: Context,
    localUri: Uri
): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val systemThumb = runCatching {
            context.contentResolver.loadThumbnail(localUri, Size(320, 240), null)
        }.getOrNull()
        if (systemThumb != null) {
            return scaleBitmapDown(systemThumb, LOCAL_THUMBNAIL_MAX_PX)
        }
    }

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, localUri)
        val rawFrame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
        rawFrame?.let { scaleBitmapDown(it, LOCAL_THUMBNAIL_MAX_PX) }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun extractAudioEmbeddedCover(
    context: Context,
    localUri: Uri
): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, localUri)
        val embedded = retriever.embeddedPicture ?: return null
        if (embedded.isEmpty()) return null
        decodeSampledBitmap(embedded, LOCAL_THUMBNAIL_MAX_PX, LOCAL_THUMBNAIL_MAX_PX)
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun decodeSampledBitmap(
    bytes: ByteArray,
    reqWidth: Int,
    reqHeight: Int
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    var sampleSize = 1
    if (bounds.outHeight > reqHeight || bounds.outWidth > reqWidth) {
        val halfHeight = bounds.outHeight / 2
        val halfWidth = bounds.outWidth / 2
        while ((halfHeight / sampleSize) >= reqHeight && (halfWidth / sampleSize) >= reqWidth) {
            sampleSize *= 2
        }
    }

    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
}

private fun scaleBitmapDown(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
    val maxDim = maxOf(bitmap.width, bitmap.height)
    if (maxDim <= maxEdgePx || maxDim <= 0) return bitmap

    val scale = maxEdgePx.toFloat() / maxDim.toFloat()
    val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    if (scaled !== bitmap) {
        bitmap.recycle()
    }
    return scaled
}
