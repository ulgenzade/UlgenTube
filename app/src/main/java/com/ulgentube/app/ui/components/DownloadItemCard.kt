package com.ulgentube.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.ui.theme.AmberWarning
import com.ulgentube.app.util.FormatUtils

/**
 * Aktif, tamamlanan, iptal edilen veya hatalı indirme öğesi kartı.
 * Minimalist, yüksek kontrastlı ve dokunma dostu mobil kart düzeni.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadItemCard(
    task: DownloadTask,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (DownloadTask) -> Unit,
    onShare: (DownloadTask) -> Unit,
    onPause: ((String) -> Unit)? = null,
    isPlayingInMiniPlayer: Boolean = false,
    onPlayAudio: ((DownloadTask) -> Unit)? = null,
    onOpenStudio: ((DownloadTask) -> Unit)? = null,
    onPreviewMedia: ((DownloadTask) -> Unit)? = null,
    onToggleFavorite: ((String) -> Unit)? = null,
    onToggleVault: ((String) -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongPress: ((String) -> Unit)? = null,
    onToggleSelect: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isActive = task.status == DownloadStatus.QUEUED ||
        task.status == DownloadStatus.DOWNLOADING ||
        task.status == DownloadStatus.MERGING
    val isCompleted = task.status == DownloadStatus.COMPLETED

    var menuExpanded by remember { mutableStateOf(false) }

    val primaryAction: (() -> Unit)? = when {
        !isCompleted -> null
        task.category == MediaCategory.AUDIO && onPlayAudio != null -> ({ onPlayAudio(task) })
        task.category != MediaCategory.AUDIO && onPreviewMedia != null -> ({ onPreviewMedia(task) })
        else -> ({ onOpen(task) })
    }

    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isPlayingInMiniPlayer -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.MERGING ->
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        task.status == DownloadStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    val metaLine = remember(
        task.platform,
        task.formatLabel,
        task.fileSizeBytes,
        task.status,
        task.lastPlaybackPositionMs
    ) {
        buildList {
            add(task.platform.displayName)
            val cleanFormat = task.formatLabel.trim()
            if (cleanFormat.isNotBlank()) {
                add(cleanFormat.take(18))
            }
            if (isCompleted) {
                FormatUtils.formatBytes(task.fileSizeBytes)
                    .takeIf { it.isNotBlank() }
                    ?.let { add(it) }
            }
            if (task.lastPlaybackPositionMs > 3_000L) {
                FormatUtils.formatDuration(task.lastPlaybackPositionMs / 1000L)
                    .takeIf { it.isNotBlank() }
                    ?.let { add(it) }
            }
        }.joinToString(" • ")
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(if (isSelected) 1.5.dp else 0.5.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (isSelectionMode) {
                            onToggleSelect?.invoke(task.id)
                        } else {
                            primaryAction?.invoke()
                        }
                    },
                    onLongClick = {
                        onLongPress?.invoke(task.id)
                    }
                )
                .padding(16.dp)
        ) {
            // Üst Bölüm: Kapak + Başlık/Meta + ⋮ Menü
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Sol Kapak (84x60)
                Box(
                    modifier = Modifier
                        .size(width = 84.dp, height = 60.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    TaskThumbnailImage(
                        task = task,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isCompleted && (task.category == MediaCategory.VIDEO || task.category == MediaCategory.AUDIO)) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.56f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlayingInMiniPlayer) {
                                    Icons.Rounded.Pause
                                } else {
                                    Icons.Rounded.PlayArrow
                                },
                                contentDescription = if (isPlayingInMiniPlayer) "Duraklat" else "Oynat",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Kaldığı yer ilerleme çizgisi (3.dp)
                    if (task.lastPlaybackPositionMs > 3_000L) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.42f)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Orta: 2 Satır Başlık + Tek Satır Sade Meta
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (task.isFavorite) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = "Favori",
                                tint = AmberWarning,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        if (task.isInVault) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = "Özel Kasa",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                // Sağ Üst: Tek Temiz ⋮ Menü (Tamamlanan öğelerde)
                if (isCompleted) {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Diğer Seçenekler",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if ((task.category == MediaCategory.VIDEO || task.category == MediaCategory.AUDIO) &&
                                onOpenStudio != null
                            ) {
                                DropdownMenuItem(
                                    text = { Text("FFmpeg Stüdyo") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoFixHigh,
                                            contentDescription = null,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenStudio(task)
                                    }
                                )
                            }

                            if (onToggleFavorite != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (task.isFavorite) "Favorilerden Çıkar" else "Favorilere Ekle"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (task.isFavorite) {
                                                Icons.Rounded.Star
                                            } else {
                                                Icons.Rounded.StarBorder
                                            },
                                            contentDescription = null,
                                            tint = if (task.isFavorite) {
                                                AmberWarning
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(19.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onToggleFavorite(task.id)
                                    }
                                )
                            }

                            if (onToggleVault != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (task.isInVault) "Özel Kasadan Çıkar" else "Özel Kasaya Taşı"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (task.isInVault) {
                                                Icons.Rounded.LockOpen
                                            } else {
                                                Icons.Rounded.Lock
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onToggleVault(task.id)
                                    }
                                )
                            }

                            DropdownMenuItem(
                                text = { Text("Harici Uygulamada Aç") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onOpen(task)
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Paylaş") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onShare(task)
                                }
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Sil",
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(19.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete(task.id)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Duruma Göre Alt Bölüm
            when {
                isActive -> {
                    ActiveProgressSection(
                        task = task,
                        onPause = onPause?.let { cb -> { cb(task.id) } },
                        onCancel = { onCancel(task.id) }
                    )
                }

                isCompleted -> {
                    CompletedActionsSection(
                        task = task,
                        isPlayingInMiniPlayer = isPlayingInMiniPlayer,
                        onPrimaryAction = { primaryAction?.invoke() },
                        onOpenStudio = onOpenStudio?.let { cb -> { cb(task) } },
                        onShare = { onShare(task) }
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
    onPause: (() -> Unit)? = null,
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.MERGING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onPause != null && task.status != DownloadStatus.MERGING) {
            IconButton(
                onClick = onPause,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Pause,
                    contentDescription = "Duraklat",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(36.dp)
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
    onPrimaryAction: () -> Unit,
    onOpenStudio: (() -> Unit)?,
    onShare: () -> Unit
) {
    val primaryLabel = when (task.category) {
        MediaCategory.VIDEO -> "İzle"
        MediaCategory.AUDIO -> if (isPlayingInMiniPlayer) "Duraklat" else "Dinle"
        MediaCategory.IMAGE -> "Görüntüle"
    }
    val primaryIcon = when (task.category) {
        MediaCategory.VIDEO -> Icons.Rounded.PlayArrow
        MediaCategory.AUDIO -> if (isPlayingInMiniPlayer) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
        MediaCategory.IMAGE -> Icons.Rounded.Visibility
    }

    val showStudioButton = (task.category == MediaCategory.VIDEO || task.category == MediaCategory.AUDIO) &&
        onOpenStudio != null

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Birincil Tonal Buton (İzle / Dinle / Görüntüle)
        Surface(
            onClick = onPrimaryAction,
            modifier = Modifier
                .weight(1f)
                .height(38.dp),
            shape = RoundedCornerShape(10.dp),
            color = if (isPlayingInMiniPlayer) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = primaryIcon,
                    contentDescription = null,
                    tint = if (isPlayingInMiniPlayer) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = primaryLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isPlayingInMiniPlayer) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    maxLines = 1
                )
            }
        }

        // İkincil Hedef 1: Stüdyo (Video / Ses)
        if (showStudioButton) {
            Surface(
                onClick = { onOpenStudio?.invoke() },
                modifier = Modifier.height(38.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoFixHigh,
                        contentDescription = "Stüdyo",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Stüdyo",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }

        // İkincil Hedef 2: Paylaş
        Surface(
            onClick = onShare,
            modifier = Modifier.height(38.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Paylaş",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Paylaş",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val isPaused = task.status == DownloadStatus.PAUSED
    val isFailed = task.status == DownloadStatus.FAILED
    val fallbackMsg = when (task.status) {
        DownloadStatus.PAUSED -> {
            val pct = task.progress.toInt().coerceIn(0, 100)
            if (pct > 0) "Duraklatıldı • %$pct" else "Duraklatıldı"
        }
        DownloadStatus.CANCELLED -> "İndirme iptal edildi."
        else -> "İndirme başarısız oldu."
    }
    val statusColor = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Rounded.Pause else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = if (isPaused) fallbackMsg else (task.errorMessage?.takeIf { it.isNotBlank() } ?: fallbackMsg),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isFailed) {
            IconButton(
                onClick = {
                    val copied = com.ulgentube.app.util.DiagnosticLogManager.copyReportToClipboard(
                        context = context,
                        extraContext = "Görev: ${task.title}\nPlatform: ${task.platform.displayName}\nFormat: ${task.formatLabel}\nURL: ${task.url}\nHata: ${task.errorMessage.orEmpty()}"
                    )
                    android.widget.Toast.makeText(
                        context,
                        if (copied) "Teknik hata raporu panoya kopyalandı" else "Rapor kopyalanamadı",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.ContentCopy,
                    contentDescription = "Hata Raporunu Kopyala",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Surface(
            onClick = onRetry,
            modifier = Modifier.height(36.dp),
            shape = RoundedCornerShape(10.dp),
            color = if (isPaused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Refresh,
                    contentDescription = if (isPaused) "Devam Et" else "Yeniden Dene",
                    tint = if (isPaused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isPaused) "Devam Et" else "Dene",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isPaused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Sil",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(19.dp)
            )
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
