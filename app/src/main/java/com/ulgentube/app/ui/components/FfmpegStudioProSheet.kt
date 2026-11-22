package com.ulgentube.app.ui.components

import android.media.MediaMetadataRetriever
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.FfmpegStudioAction
import com.ulgentube.app.domain.model.FfmpegStudioOptions
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.StudioAspectRatio
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.EmeraldSuccess
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.ui.theme.UlgenSubtleGradient
import com.ulgentube.app.util.FormatUtils
import com.ulgentube.app.util.MediaUriHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val STUDIO_AUDIO_FORMATS = listOf("mp3", "m4a", "aac", "flac")
private val STUDIO_SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)
private val STUDIO_GIF_FPS_OPTIONS = listOf(8, 12, 15, 24)
private val STUDIO_GIF_WIDTH_OPTIONS = listOf(320, 480, 640)

/**
 * 9 gelişmiş aksiyonlu FFmpeg Stüdyo Pro alt sayfası.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FfmpegStudioProSheet(
    task: DownloadTask,
    allVideoTasks: List<DownloadTask>,
    onRunStudio: (DownloadTask, FfmpegStudioAction, FfmpegStudioOptions) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedAction by remember { mutableStateOf(FfmpegStudioAction.TRIM_VIDEO) }

    // Video süresi (TRIM_VIDEO RangeSlider için)
    var durationSeconds by remember { mutableFloatStateOf(120f) }
    var trimRange by remember { mutableStateOf(0f..60f) }

    LaunchedEffect(task.id) {
        val detectedSec = withContext(Dispatchers.IO) {
            runCatching {
                val uri = MediaUriHelper.resolveTaskUri(context, task) ?: return@runCatching null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: return@runCatching null
                    (ms / 1000f).coerceAtLeast(5f)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
        val maxSec = detectedSec ?: 120f
        durationSeconds = maxSec
        trimRange = 0f..maxSec
    }

    // CONVERT_AUDIO seçenekleri
    var audioFormat by remember { mutableStateOf("mp3") }
    var saveAsRingtone by remember { mutableStateOf(false) }

    // CHANGE_SPEED seçenekleri
    var speedFactor by remember { mutableFloatStateOf(1.5f) }

    // CROP_ASPECT seçenekleri
    var aspectRatio by remember { mutableStateOf(StudioAspectRatio.RATIO_9_16) }

    // MAKE_GIF seçenekleri
    var gifFps by remember { mutableIntStateOf(12) }
    var gifMaxWidth by remember { mutableIntStateOf(480) }
    var useWebp by remember { mutableStateOf(false) }

    // MERGE_VIDEOS seçenekleri
    val mergeCandidates = remember(allVideoTasks, task.id) {
        allVideoTasks.filter {
            it.id != task.id &&
                it.status == DownloadStatus.COMPLETED &&
                it.category == MediaCategory.VIDEO &&
                (!it.savedUri.isNullOrBlank() || !it.savedPath.isNullOrBlank())
        }
    }
    val selectedMergeIds = remember { mutableStateListOf<String>() }

    val canExecute = when (selectedAction) {
        FfmpegStudioAction.MERGE_VIDEOS -> selectedMergeIds.isNotEmpty()
        FfmpegStudioAction.TRIM_VIDEO -> trimRange.endInclusive > trimRange.start + 0.5f
        else -> true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Başlık
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(UlgenPrimaryGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "FFmpeg Stüdyo Pro",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Kapat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 9 Aksiyon Seçici Izgarası (FlowRow)
            Text(
                text = "İşlem Seçin",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FfmpegStudioAction.entries.forEach { action ->
                    val isSelected = selectedAction == action
                    Surface(
                        onClick = { selectedAction = action },
                        shape = RoundedCornerShape(13.dp),
                        color = if (isSelected) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                        border = if (isSelected) {
                            null
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .then(
                                    if (isSelected) {
                                        Modifier.background(UlgenPrimaryGradient, RoundedCornerShape(13.dp))
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = studioActionIcon(action),
                                contentDescription = null,
                                tint = if (isSelected) Color.White else CoralPink,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = action.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Seçili Aksiyon Açıklaması ve Bağlamsal Ayar Kartı
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(UlgenSubtleGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = studioActionIcon(selectedAction),
                                contentDescription = null,
                                tint = ElectricPurple,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedAction.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = selectedAction.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Bağlamsal Parametreler
                    when (selectedAction) {
                        FfmpegStudioAction.TRIM_VIDEO -> {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Başlangıç: ${FormatUtils.formatDuration(trimRange.start.toLong()).ifBlank { "00:00" }}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ElectricPurple
                                )
                                Text(
                                    text = "Bitiş: ${FormatUtils.formatDuration(trimRange.endInclusive.toLong()).ifBlank { "00:00" }}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CoralPink
                                )
                            }
                            RangeSlider(
                                value = trimRange,
                                onValueChange = { trimRange = it },
                                valueRange = 0f..durationSeconds.coerceAtLeast(5f),
                                colors = SliderDefaults.colors(
                                    thumbColor = CoralPink,
                                    activeTrackColor = ElectricPurple
                                )
                            )
                        }

                        FfmpegStudioAction.CONVERT_AUDIO -> {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            Text(
                                text = "Hedef Ses Formatı",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                STUDIO_AUDIO_FORMATS.forEach { fmt ->
                                    val selected = audioFormat == fmt
                                    StudioOptionChip(
                                        label = fmt.uppercase(),
                                        selected = selected,
                                        onClick = { audioFormat = fmt },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { saveAsRingtone = !saveAsRingtone },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Zil Sesi Olarak Kaydet",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Ringtones klasörüne doğrudan aktarır",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = saveAsRingtone,
                                    onCheckedChange = { saveAsRingtone = it }
                                )
                            }
                        }

                        FfmpegStudioAction.CHANGE_SPEED -> {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            Text(
                                text = "Oynatma Hızı Çarpanı",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                STUDIO_SPEED_OPTIONS.forEach { spd ->
                                    StudioOptionChip(
                                        label = "${spd}x",
                                        selected = speedFactor == spd,
                                        onClick = { speedFactor = spd }
                                    )
                                }
                            }
                        }

                        FfmpegStudioAction.CROP_ASPECT -> {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StudioAspectRatio.entries.forEach { ratio ->
                                    val selected = aspectRatio == ratio
                                    Surface(
                                        onClick = { aspectRatio = ratio },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (selected) {
                                            ElectricPurple.copy(alpha = 0.16f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) ElectricPurple else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = ratio.label,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (selected) ElectricPurple else MaterialTheme.colorScheme.onSurface
                                            )
                                            Icon(
                                                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (selected) ElectricPurple else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        FfmpegStudioAction.MAKE_GIF -> {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            Text(
                                text = "Kare Hızı (FPS)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                STUDIO_GIF_FPS_OPTIONS.forEach { fps ->
                                    StudioOptionChip(
                                        label = "$fps FPS",
                                        selected = gifFps == fps,
                                        onClick = { gifFps = fps },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Text(
                                text = "Maksimum Genişlik",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                STUDIO_GIF_WIDTH_OPTIONS.forEach { widthPx ->
                                    StudioOptionChip(
                                        label = "${widthPx}px",
                                        selected = gifMaxWidth == widthPx,
                                        onClick = { gifMaxWidth = widthPx },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { useWebp = !useWebp },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "WebP Animasyon Olarak Kaydet",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "GIF yerine daha küçük dosya boyutlu WebP üretir",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = useWebp,
                                    onCheckedChange = { useWebp = it }
                                )
                            }
                        }

                        FfmpegStudioAction.MERGE_VIDEOS -> {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            if (mergeCandidates.isEmpty()) {
                                Text(
                                    text = "Birleştirilecek başka tamamlanmış video bulunamadı.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text(
                                    text = "Eklenecek Videoları Seçin (${selectedMergeIds.size} seçili)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    mergeCandidates.take(8).forEach { candidate ->
                                        val checked = candidate.id in selectedMergeIds
                                        Surface(
                                            onClick = {
                                                if (checked) {
                                                    selectedMergeIds.remove(candidate.id)
                                                } else {
                                                    selectedMergeIds.add(candidate.id)
                                                }
                                            },
                                            shape = RoundedCornerShape(11.dp),
                                            color = if (checked) {
                                                EmeraldSuccess.copy(alpha = 0.14f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            },
                                            border = BorderStroke(
                                                1.dp,
                                                if (checked) EmeraldSuccess else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                                    contentDescription = null,
                                                    tint = if (checked) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = candidate.title,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            AnimatedVisibility(visible = true) {
                                Text(
                                    text = "Bu işlem doğrudan seçili medya dosyası üzerinde optimize ayarlarla çalıştırılır.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // İşlemi Başlat Butonu
            Button(
                onClick = {
                    val mergeUris = mergeCandidates
                        .filter { it.id in selectedMergeIds }
                        .mapNotNull { it.savedUri ?: it.savedPath }
                    val options = FfmpegStudioOptions(
                        trimStartSeconds = trimRange.start,
                        trimEndSeconds = trimRange.endInclusive,
                        audioFormat = audioFormat,
                        saveAsRingtone = saveAsRingtone,
                        speedFactor = speedFactor,
                        aspectRatio = aspectRatio,
                        gifFps = gifFps,
                        gifMaxWidth = gifMaxWidth,
                        useWebp = useWebp,
                        mergeSourceUris = mergeUris
                    )
                    onRunStudio(task, selectedAction, options)
                },
                enabled = canExecute,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                contentPadding = PaddingValues()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (canExecute) {
                                Modifier.background(UlgenPrimaryGradient, RoundedCornerShape(15.dp))
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = if (canExecute) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            text = "Stüdyo İşlemini Başlat",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (canExecute) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (selected) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (selected) {
                        Modifier.background(UlgenPrimaryGradient, RoundedCornerShape(11.dp))
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun studioActionIcon(action: FfmpegStudioAction): ImageVector = when (action) {
    FfmpegStudioAction.EXTRACT_MP3 -> Icons.Rounded.MusicNote
    FfmpegStudioAction.COMPRESS_WHATSAPP -> Icons.Rounded.Compress
    FfmpegStudioAction.MAKE_GIF -> Icons.Rounded.GifBox
    FfmpegStudioAction.REMOVE_AUDIO -> Icons.Rounded.VolumeOff
    FfmpegStudioAction.TRIM_VIDEO -> Icons.Rounded.ContentCut
    FfmpegStudioAction.MERGE_VIDEOS -> Icons.Rounded.Merge
    FfmpegStudioAction.CONVERT_AUDIO -> Icons.Rounded.AudioFile
    FfmpegStudioAction.CHANGE_SPEED -> Icons.Rounded.Speed
    FfmpegStudioAction.CROP_ASPECT -> Icons.Rounded.AspectRatio
}
