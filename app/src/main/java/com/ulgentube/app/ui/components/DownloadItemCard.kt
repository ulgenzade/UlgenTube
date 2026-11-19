package com.ulgentube.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.EmeraldSuccess
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.ui.theme.UlgenSubtleGradient
import com.ulgentube.app.util.FormatUtils

/**
 * Aktif, tamamlanan, iptal edilen veya hatalı indirme öğesi kartı.
 */
@Composable
fun DownloadItemCard(
    task: DownloadTask,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (DownloadTask) -> Unit,
    onShare: (DownloadTask) -> Unit,
    isPlayingInMiniPlayer: Boolean = false,
    onPlayAudio: ((DownloadTask) -> Unit)? = null,
    onOpenStudio: ((DownloadTask) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isActive = task.status == DownloadStatus.QUEUED ||
        task.status == DownloadStatus.DOWNLOADING ||
        task.status == DownloadStatus.MERGING

    val borderColor = when {
        isPlayingInMiniPlayer -> CoralPink.copy(alpha = 0.65f)
        task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.MERGING ->
            ElectricPurple.copy(alpha = 0.45f)
        task.status == DownloadStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Kapak Görseli / Kategori İkonu
                Box(
                    modifier = Modifier
                        .size(width = 74.dp, height = 60.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(UlgenSubtleGradient),
                    contentAlignment = Alignment.Center
                ) {
                    if (!task.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = task.thumbnailUrl,
                            contentDescription = task.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val icon = when (task.category) {
                            MediaCategory.VIDEO -> Icons.Rounded.Videocam
                            MediaCategory.AUDIO -> Icons.Rounded.MusicNote
                            MediaCategory.IMAGE -> Icons.Rounded.Image
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = ElectricPurple,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlatformBadge(platform = task.platform)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = task.formatLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Duruma Göre Alt Bölüm
            when {
                isActive -> {
                    ActiveProgressSection(
                        task = task,
                        onCancel = { onCancel(task.id) }
                    )
                }

                task.status == DownloadStatus.COMPLETED -> {
                    CompletedActionsSection(
                        task = task,
                        isPlayingInMiniPlayer = isPlayingInMiniPlayer,
                        onPlayAudio = onPlayAudio?.let { cb -> { cb(task) } },
                        onOpenStudio = onOpenStudio?.let { cb -> { cb(task) } },
                        onOpen = { onOpen(task) },
                        onShare = { onShare(task) },
                        onDelete = { onDelete(task.id) }
                    )
                }

                else -> {
                    FailedOrCancelledSection(
                        task = task,
                        onRetry = { onRetry(task.id) },
                        onDelete = { onDelete(task.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveProgressSection(
    task: DownloadTask,
    onCancel: () -> Unit
) {
    val progressFraction = (task.progress / 100f).coerceIn(0f, 1f)
    val percentInt = task.progress.toInt().coerceIn(0, 100)

    val statusText = remember(task.status, task.speedText, task.etaSeconds, percentInt) {
        when (task.status) {
            DownloadStatus.QUEUED -> "Kuyrukta bekleniyor..."
            DownloadStatus.MERGING -> "Birleştiriliyor ve kaydediliyor..."
            else -> buildList {
                add("%$percentInt")
                task.speedText?.takeIf { it.isNotBlank() }?.let { add(it) }
                task.etaSeconds?.takeIf { it > 0L }?.let { add(formatEta(it)) }
            }.joinToString(" • ")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.MERGING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = ElectricPurple,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = CoralPink,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "İptal Et",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CompletedActionsSection(
    task: DownloadTask,
    isPlayingInMiniPlayer: Boolean,
    onPlayAudio: (() -> Unit)?,
    onOpenStudio: (() -> Unit)?,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val sizeText = remember(task.fileSizeBytes) {
        FormatUtils.formatBytes(task.fileSizeBytes).ifBlank { "Kaydedildi" }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = EmeraldSuccess,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = sizeText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ses kartlarında dahili mini çalarda Çal / Duraklat
            if (task.category == MediaCategory.AUDIO && onPlayAudio != null) {
                IconButton(
                    onClick = onPlayAudio,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlayingInMiniPlayer) {
                                UlgenPrimaryGradient
                            } else {
                                UlgenSubtleGradient
                            }
                        )
                ) {
                    Icon(
                        imageVector = if (isPlayingInMiniPlayer) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (isPlayingInMiniPlayer) "Duraklat" else "Uygulamada Çal",
                        tint = if (isPlayingInMiniPlayer) Color.White else ElectricPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Video kartlarında FFmpeg Stüdyo aksiyonu
            if (task.category == MediaCategory.VIDEO && onOpenStudio != null) {
                IconButton(
                    onClick = onOpenStudio,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoFixHigh,
                        contentDescription = "FFmpeg Stüdyo",
                        tint = CoralPink,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            IconButton(
                onClick = onOpen,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInNew,
                    contentDescription = "Aç",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onShare,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Paylaş",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Sil",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun FailedOrCancelledSection(
    task: DownloadTask,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val fallbackMsg = if (task.status == DownloadStatus.CANCELLED) {
        "İndirme iptal edildi."
    } else {
        "İndirme başarısız oldu."
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = task.errorMessage?.takeIf { it.isNotBlank() } ?: fallbackMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onRetry,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Yeniden Dene",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Sil",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun formatEta(seconds: Long): String {
    return when {
        seconds < 60L -> "$seconds sn"
        seconds < 3600L -> "${seconds / 60} dk ${seconds % 60} sn"
        else -> "${seconds / 3600} sa ${(seconds % 3600) / 60} dk"
    }
}
