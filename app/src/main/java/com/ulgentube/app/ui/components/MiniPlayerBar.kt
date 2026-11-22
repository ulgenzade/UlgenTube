package com.ulgentube.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.EqualizerPreset
import com.ulgentube.app.engine.LrcLyricsFetcher
import com.ulgentube.app.player.MiniAudioPlayerManager
import com.ulgentube.app.player.MiniPlayerState
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.UlgenCardHighlightGradient
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.ui.theme.UlgenSubtleGradient
import com.ulgentube.app.util.FormatUtils

/**
 * Uygulama alt gezinme çubuğunun hemen üstünde beliren kompakt dahili mini müzik çalar.
 * Kuyruk ileri/geri, karışık çalma, tekrar modu, 5-bant ekolayzer ve senkronize LRC söz görüntüleyici içerir.
 */
@Composable
fun MiniPlayerBar(
    state: MiniPlayerState,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayNext: () -> Unit = {},
    onPlayPrevious: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onCycleRepeatMode: () -> Unit = {},
    onSelectEqualizerPreset: (EqualizerPreset) -> Unit = {},
    onFetchLyrics: (DownloadTask) -> Unit = {},
    isFetchingLyrics: Boolean = false,
    lyricsOverride: String? = null
) {
    val task = state.currentTask
    var showEqualizerDialog by remember { mutableStateOf(false) }
    var showLyricsDialog by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = task != null,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        modifier = modifier
    ) {
        if (task != null) {
            val durationMs = state.durationMs.coerceAtLeast(0L)
            val positionMs = state.positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))
            var draggingFraction by remember(task.id) { mutableStateOf<Float?>(null) }

            val currentFraction = draggingFraction ?: if (durationMs > 0L) {
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            val displayedPosSec = if (draggingFraction != null && durationMs > 0L) {
                ((draggingFraction!! * durationMs).toLong() / 1000L)
            } else {
                positionMs / 1000L
            }
            val totalDurSec = durationMs / 1000L

            val timeText = remember(displayedPosSec, totalDurSec, state.isBackgroundVideoAudio) {
                val posStr = FormatUtils.formatDuration(displayedPosSec).ifBlank { "00:00" }
                val durStr = FormatUtils.formatDuration(totalDurSec).ifBlank { "--:--" }
                if (state.isBackgroundVideoAudio) {
                    "Arka Plan Ses • $posStr / $durStr"
                } else {
                    "$posStr / $durStr"
                }
            }

            val hasQueueControls = state.queue.size > 1

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.45f)),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UlgenCardHighlightGradient)
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
                ) {
                    // Üst satır: Kapak + Başlık + Önceki / Oynat-Duraklat / Sonraki / Kapat
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(UlgenSubtleGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            TaskThumbnailImage(
                                task = task,
                                modifier = Modifier.fillMaxSize(),
                                iconSize = 22.dp
                            )
                            if (state.isBackgroundVideoAudio) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(2.dp)
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.72f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Headphones,
                                        contentDescription = "Arka plan ses",
                                        tint = CoralPink,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Önceki Parça
                        IconButton(
                            onClick = onPlayPrevious,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Önceki Parça",
                                tint = if (hasQueueControls || positionMs > 3_000L) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Oynat / Duraklat
                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(UlgenPrimaryGradient)
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) {
                                    Icons.Rounded.Pause
                                } else {
                                    Icons.Rounded.PlayArrow
                                },
                                contentDescription = if (state.isPlaying) "Duraklat" else "Oynat",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Sonraki Parça
                        IconButton(
                            onClick = onPlayNext,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Sonraki Parça",
                                tint = if (hasQueueControls) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Kapat
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Çaları Kapat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Kompakt yardımcı araç satırı: Kuyruk bilgisi + Shuffle, Repeat, Ekolayzer, Sözler
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val queueLabel = if (state.queue.size > 1 && state.currentIndex >= 0) {
                            "${state.currentIndex + 1} / ${state.queue.size}"
                        } else if (state.equalizerPreset != EqualizerPreset.FLAT) {
                            "EQ: ${state.equalizerPreset.displayName}"
                        } else {
                            task.author?.takeIf { it.isNotBlank() } ?: task.formatLabel
                        }

                        Text(
                            text = queueLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Karışık Çalma (Shuffle)
                            IconButton(
                                onClick = onToggleShuffle,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = "Karışık Çal",
                                    tint = if (state.shuffleEnabled) {
                                        CoralPink
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            // Tekrar Modu (OFF / ALL / ONE)
                            IconButton(
                                onClick = onCycleRepeatMode,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = if (state.repeatMode == MiniAudioPlayerManager.REPEAT_MODE_ONE) {
                                        Icons.Rounded.RepeatOne
                                    } else {
                                        Icons.Rounded.Repeat
                                    },
                                    contentDescription = when (state.repeatMode) {
                                        MiniAudioPlayerManager.REPEAT_MODE_ONE -> "Tek Parçayı Tekrarla"
                                        MiniAudioPlayerManager.REPEAT_MODE_ALL -> "Tümünü Tekrarla"
                                        else -> "Tekrar Kapalı"
                                    },
                                    tint = if (state.repeatMode != MiniAudioPlayerManager.REPEAT_MODE_OFF) {
                                        CoralPink
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            // 5-Bant Ekolayzer
                            IconButton(
                                onClick = { showEqualizerDialog = true },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = "Ekolayzer",
                                    tint = if (state.equalizerPreset != EqualizerPreset.FLAT) {
                                        CoralPink
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Şarkı Sözleri (LRC)
                            IconButton(
                                onClick = { showLyricsDialog = true },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lyrics,
                                    contentDescription = "Şarkı Sözleri",
                                    tint = if (!(lyricsOverride ?: task.lyricsText).isNullOrBlank()) {
                                        ElectricPurple
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Slider(
                        value = currentFraction,
                        onValueChange = { fraction ->
                            draggingFraction = fraction
                        },
                        onValueChangeFinished = {
                            val targetFraction = draggingFraction
                            draggingFraction = null
                            if (targetFraction != null && durationMs > 0L) {
                                onSeekTo((targetFraction * durationMs).toLong())
                            }
                        },
                        enabled = durationMs > 0L,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = CoralPink,
                            activeTrackColor = ElectricPurple,
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    )
                }
            }

            if (showEqualizerDialog) {
                EqualizerPresetDialog(
                    selectedPreset = state.equalizerPreset,
                    onSelectPreset = { preset ->
                        onSelectEqualizerPreset(preset)
                        showEqualizerDialog = false
                    },
                    onDismiss = { showEqualizerDialog = false }
                )
            }

            if (showLyricsDialog) {
                LyricsViewerDialog(
                    task = task,
                    lyricsText = lyricsOverride ?: task.lyricsText,
                    positionMs = positionMs,
                    isFetchingLyrics = isFetchingLyrics,
                    onSeekTo = onSeekTo,
                    onFetchLyrics = { onFetchLyrics(task) },
                    onDismiss = { showLyricsDialog = false }
                )
            }
        }
    }
}

/**
 * 5-Bant donanım ekolayzer ön ayar seçim diyaloğu.
 */
@Composable
private fun EqualizerPresetDialog(
    selectedPreset: EqualizerPreset,
    onSelectPreset: (EqualizerPreset) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = CoralPink,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "5-Bant Ekolayzer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Kapat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                EqualizerPreset.entries.forEach { preset ->
                    val isSelected = preset == selectedPreset
                    Surface(
                        onClick = { onSelectPreset(preset) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) {
                            ElectricPurple.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) {
                                ElectricPurple
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                EqualizerMiniBars(
                                    bandLevelsMb = preset.bandLevelsMb,
                                    highlight = isSelected
                                )
                                Text(
                                    text = preset.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Seçili",
                                    tint = CoralPink,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerMiniBars(
    bandLevelsMb: List<Short>,
    highlight: Boolean
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.height(22.dp)
    ) {
        bandLevelsMb.take(5).forEach { level ->
            // -500..500 mB aralığını 6dp..22dp yüksekliğe haritala
            val normalized = ((level.toInt() + 500) / 1000f).coerceIn(0.15f, 1f)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((22f * normalized).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (highlight) CoralPink else ElectricPurple.copy(alpha = 0.65f)
                    )
            )
        }
    }
}

/**
 * Senkronize LRC zaman damgalı veya düz metin şarkı sözü görüntüleme penceresi.
 */
@Composable
private fun LyricsViewerDialog(
    task: DownloadTask,
    lyricsText: String?,
    positionMs: Long,
    isFetchingLyrics: Boolean,
    onSeekTo: (Long) -> Unit,
    onFetchLyrics: () -> Unit,
    onDismiss: () -> Unit
) {
    val lrcLines = remember(lyricsText) {
        if (lyricsText.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { LrcLyricsFetcher.parseLrcTimestamps(lyricsText) }
                .getOrDefault(emptyList())
        }
    }

    val activeLineIndex = remember(lrcLines, positionMs) {
        if (lrcLines.isEmpty()) {
            -1
        } else {
            lrcLines.indexOfLast { it.timeMs <= positionMs }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex >= 0 && lrcLines.isNotEmpty()) {
            val targetScrollIndex = (activeLineIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(targetScrollIndex)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 520.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Başlık satırı
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lyrics,
                        contentDescription = null,
                        tint = CoralPink,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Şarkı Sözleri",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onFetchLyrics,
                        enabled = !isFetchingLyrics,
                        modifier = Modifier.size(34.dp)
                    ) {
                        if (isFetchingLyrics) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = CoralPink,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Söz Ara / Yenile",
                                tint = ElectricPurple,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Kapat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 380.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isFetchingLyrics && lyricsText.isNullOrBlank() -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = CoralPink,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "Şarkı sözleri aranıyor...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        lrcLines.isNotEmpty() -> {
                            LazyColumn(
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(lrcLines) { index, line ->
                                    val isActive = index == activeLineIndex
                                    Text(
                                        text = line.text,
                                        style = if (isActive) {
                                            MaterialTheme.typography.titleMedium
                                        } else {
                                            MaterialTheme.typography.bodyMedium
                                        },
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isActive) {
                                            CoralPink
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                                        },
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (isActive) {
                                                    ElectricPurple.copy(alpha = 0.14f)
                                                } else {
                                                    Color.Transparent
                                                }
                                            )
                                            .clickable { onSeekTo(line.timeMs) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        !lyricsText.isNullOrBlank() -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = lyricsText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        else -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = "Bu parça için kayıtlı şarkı sözü bulunamadı.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = onFetchLyrics,
                                    enabled = !isFetchingLyrics,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple)
                                ) {
                                    Text("Söz Ara", color = Color.White)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Kapat")
                    }
                }
            }
        }
    }
}
