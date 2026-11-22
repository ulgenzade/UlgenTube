package com.ulgentube.app.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.FfmpegStudioAction
import com.ulgentube.app.domain.model.FfmpegStudioOptions
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.player.MiniPlayerState
import com.ulgentube.app.ui.components.AppLockOverlay
import com.ulgentube.app.ui.components.DownloadItemCard
import com.ulgentube.app.ui.components.FfmpegStudioProSheet
import com.ulgentube.app.ui.theme.AmberWarning
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.ui.theme.UlgenSubtleGradient
import com.ulgentube.app.util.MediaUriHelper
import java.util.Locale

private enum class DownloadFilter(val label: String) {
    ALL("Tümü"),
    ACTIVE("Aktif"),
    VIDEO("Video"),
    AUDIO("Müzik"),
    IMAGE("Görsel"),
    FAVORITES("Favoriler"),
    VAULT("Özel Kasa")
}

private enum class DownloadSort(val label: String) {
    NEWEST("En Yeni"),
    OLDEST("En Eski"),
    LARGEST("En Büyük"),
    TITLE_ASC("A → Z")
}

private data class StudioTarget(
    val uri: Uri,
    val baseName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<DownloadTask>,
    miniPlayerState: MiniPlayerState,
    studioBusy: Boolean,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String, Boolean) -> Unit,
    onClearCompleted: () -> Unit,
    onPlayAudio: (DownloadTask) -> Unit,
    onPreviewMedia: (DownloadTask) -> Unit,
    onRunFfmpegStudio: (Uri, String, FfmpegStudioAction) -> Unit,
    onRunFfmpegStudioPro: (DownloadTask, FfmpegStudioAction, FfmpegStudioOptions) -> Unit = { _, _, _ -> },
    onPlayAudioQueue: (List<DownloadTask>) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    onToggleVault: (String) -> Unit = {},
    onPause: ((String) -> Unit)? = null,
    onPauseAll: (() -> Unit)? = null,
    onResumeAll: (() -> Unit)? = null,
    isVaultUnlocked: Boolean = false,
    onUnlockVault: () -> Unit = {},
    onLockVault: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedFilter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSort by rememberSaveable { mutableStateOf(DownloadSort.NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    var pendingDeleteTaskId by remember { mutableStateOf<String?>(null) }
    var selectedTaskIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    val isSelectionMode = selectedTaskIds.isNotEmpty()
    var studioTarget by remember { mutableStateOf<StudioTarget?>(null) }
    var studioProTask by remember { mutableStateOf<DownloadTask?>(null) }
    var showVaultLockOverlay by remember { mutableStateOf(false) }

    val galleryVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            studioTarget = StudioTarget(
                uri = uri,
                baseName = "ulgentube_studio"
            )
        }
    }

    val visiblePublicDownloads = remember(downloads) {
        downloads.filter { !it.isInVault }
    }

    val hasCompleted = remember(visiblePublicDownloads) {
        visiblePublicDownloads.any { it.status == DownloadStatus.COMPLETED }
    }

    val completedVideoTasks = remember(visiblePublicDownloads) {
        visiblePublicDownloads.filter {
            it.status == DownloadStatus.COMPLETED && it.category == MediaCategory.VIDEO
        }
    }

    val completedAudioTasks = remember(visiblePublicDownloads) {
        visiblePublicDownloads.filter {
            it.status == DownloadStatus.COMPLETED && it.category == MediaCategory.AUDIO
        }
    }

    val filteredDownloads = remember(downloads, selectedFilter, searchQuery, selectedSort, isVaultUnlocked) {
        val byCategory = when (selectedFilter) {
            DownloadFilter.ALL -> downloads.filter { !it.isInVault }
            DownloadFilter.ACTIVE -> downloads.filter {
                !it.isInVault && (
                    it.status == DownloadStatus.QUEUED ||
                        it.status == DownloadStatus.DOWNLOADING ||
                        it.status == DownloadStatus.PAUSED ||
                        it.status == DownloadStatus.MERGING
                    )
            }
            DownloadFilter.VIDEO -> downloads.filter { !it.isInVault && it.category == MediaCategory.VIDEO }
            DownloadFilter.AUDIO -> downloads.filter { !it.isInVault && it.category == MediaCategory.AUDIO }
            DownloadFilter.IMAGE -> downloads.filter { !it.isInVault && it.category == MediaCategory.IMAGE }
            DownloadFilter.FAVORITES -> downloads.filter { !it.isInVault && it.isFavorite }
            DownloadFilter.VAULT -> if (isVaultUnlocked) downloads.filter { it.isInVault } else emptyList()
        }

        val query = searchQuery.trim().lowercase(Locale.getDefault())
        val byQuery = if (query.isEmpty()) {
            byCategory
        } else {
            byCategory.filter { task ->
                task.title.lowercase(Locale.getDefault()).contains(query) ||
                    (task.author?.lowercase(Locale.getDefault())?.contains(query) == true) ||
                    task.platform.displayName.lowercase(Locale.getDefault()).contains(query)
            }
        }

        when (selectedSort) {
            DownloadSort.NEWEST -> byQuery.sortedByDescending { it.createdAt }
            DownloadSort.OLDEST -> byQuery.sortedBy { it.createdAt }
            DownloadSort.LARGEST -> byQuery.sortedByDescending { it.fileSizeBytes }
            DownloadSort.TITLE_ASC -> byQuery.sortedBy { it.title.lowercase(Locale.getDefault()) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
    ) {
        // Çoklu Seçim Üst Çubuğu (uzun basıldığında görünür)
        if (isSelectionMode) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { selectedTaskIds = emptySet() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Seçimi Kapat",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "${selectedTaskIds.size} seçildi",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = {
                                selectedTaskIds = if (selectedTaskIds.size == filteredDownloads.size) {
                                    emptySet()
                                } else {
                                    filteredDownloads.map { it.id }.toSet()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (selectedTaskIds.size == filteredDownloads.size) "Kaldır" else "Tümü",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        IconButton(
                            onClick = {
                                selectedTaskIds.forEach { id -> onToggleVault(id) }
                                selectedTaskIds = emptySet()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = "Özel Kasaya Taşı",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { showBatchDeleteConfirm = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteForever,
                                contentDescription = "Seçilenleri Sil",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        } else {
            // Canlı Depolama Çubuğu (Kalan boş alan + UlgenTube toplam boyut)
            val storageSnapshot = remember(downloads.size) {
                runCatching {
                    val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                    val freeBytes = stat.availableBytes
                    val totalBytes = stat.totalBytes.coerceAtLeast(1L)
                    val appUsedBytes = downloads
                        .filter { it.status == DownloadStatus.COMPLETED && it.fileSizeBytes > 0L }
                        .sumOf { it.fileSizeBytes }
                    Triple(freeBytes, totalBytes, appUsedBytes)
                }.getOrNull()
            }
            if (storageSnapshot != null) {
                val (freeBytes, totalBytes, appUsedBytes) = storageSnapshot
                val usedFraction = ((totalBytes - freeBytes).toFloat() / totalBytes.toFloat()).coerceIn(0.02f, 1f)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "UlgenTube: ${com.ulgentube.app.util.FormatUtils.formatBytes(appUsedBytes).ifBlank { "0 B" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Boş alan: ${com.ulgentube.app.util.FormatUtils.formatBytes(freeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { usedFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            }
        }

        // Üst Başlık, FFmpeg Stüdyo ve Temizle Butonu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "İndirmelerim",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${downloads.size} öğe kayıtlı",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val activeRunningCount = visiblePublicDownloads.count {
                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
            }
            val pausedCount = visiblePublicDownloads.count { it.status == DownloadStatus.PAUSED }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activeRunningCount > 0 && onPauseAll != null) {
                    Surface(
                        onClick = onPauseAll,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Pause,
                                contentDescription = "Tümünü Duraklat",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Duraklat",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else if (pausedCount > 0 && onResumeAll != null) {
                    Surface(
                        onClick = onResumeAll,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Tümünü Devam Ettir",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Devam Et",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Surface(
                    onClick = {
                        if (!studioBusy) {
                            galleryVideoPicker.launch("video/*")
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (studioBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AutoFixHigh,
                                contentDescription = "FFmpeg Stüdyo",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "Stüdyo",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (hasCompleted) {
                    TextButton(
                        onClick = onClearCompleted,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Temizle",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Arama Çubuğu ve Sıralama Menüsü (Tek Temiz 48.dp Yükseklik, 10.dp Aralıklı)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Başlık, kanal veya platform ara...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Aramayı Temizle",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Box {
                Surface(
                    onClick = { sortMenuExpanded = true },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = "Sırala",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = selectedSort.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    DownloadSort.entries.forEach { sortOption ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = sortOption.label,
                                    fontWeight = if (sortOption == selectedSort) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    color = if (sortOption == selectedSort) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            },
                            onClick = {
                                selectedSort = sortOption
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Minimal Filtre Çipleri
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = DownloadFilter.entries,
                key = { it.name }
            ) { filter ->
                val isSelected = selectedFilter == filter
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (filter == DownloadFilter.VAULT && !isVaultUnlocked) {
                            showVaultLockOverlay = true
                        } else {
                            selectedFilter = filter
                        }
                    },
                    leadingIcon = when (filter) {
                        DownloadFilter.FAVORITES -> {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else AmberWarning,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        DownloadFilter.VAULT -> {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        else -> null
                    },
                    label = {
                        Text(
                            text = filter.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = null,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        // Ses kategorisi için "Tüm Sesleri Kuyrukta Çal" butonu
        if (selectedFilter == DownloadFilter.AUDIO && completedAudioTasks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onPlayAudioQueue(completedAudioTasks) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tüm Sesleri Kuyrukta Çal (${completedAudioTasks.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Özel Kasa açıkken kilit bilgisi & kilitleme çubuğu
        if (selectedFilter == DownloadFilter.VAULT && isVaultUnlocked) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Özel Kasa Açık",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TextButton(
                        onClick = {
                            onLockVault()
                            selectedFilter = DownloadFilter.ALL
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Kasayı Kilitle", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredDownloads.isEmpty()) {
            EmptyDownloadsPlaceholder(
                filter = selectedFilter,
                hasSearchQuery = searchQuery.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = filteredDownloads,
                    key = { it.id }
                ) { task ->
                    val isPlayingHere = miniPlayerState.currentTask?.id == task.id &&
                        miniPlayerState.isPlaying
                    DownloadItemCard(
                        task = task,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onDelete = { pendingDeleteTaskId = it },
                        onOpen = { openDownloadedFile(context, it) },
                        onShare = { shareDownloadedFile(context, it) },
                        onPause = onPause,
                        isPlayingInMiniPlayer = isPlayingHere,
                        onPlayAudio = { audioTask -> onPlayAudio(audioTask) },
                        onPreviewMedia = onPreviewMedia,
                        onOpenStudio = { videoTask ->
                            studioProTask = videoTask
                        },
                        onToggleFavorite = onToggleFavorite,
                        onToggleVault = onToggleVault,
                        isSelectionMode = isSelectionMode,
                        isSelected = task.id in selectedTaskIds,
                        onLongPress = { id ->
                            selectedTaskIds = selectedTaskIds + id
                        },
                        onToggleSelect = { id ->
                            selectedTaskIds = if (id in selectedTaskIds) {
                                selectedTaskIds - id
                            } else {
                                selectedTaskIds + id
                            }
                        }
                    )
                }

                item(key = "downloads_bottom_space") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (showBatchDeleteConfirm && selectedTaskIds.isNotEmpty()) {
        DeleteDownloadConfirmDialog(
            onDeleteWithFile = {
                val targets = selectedTaskIds.toList()
                showBatchDeleteConfirm = false
                selectedTaskIds = emptySet()
                targets.forEach { onDelete(it, true) }
            },
            onDeleteHistoryOnly = {
                val targets = selectedTaskIds.toList()
                showBatchDeleteConfirm = false
                selectedTaskIds = emptySet()
                targets.forEach { onDelete(it, false) }
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }

    // Gerçek Dosya Silme Onay Diyaloğu
    val targetDeleteId = pendingDeleteTaskId
    if (targetDeleteId != null) {
        DeleteDownloadConfirmDialog(
            onDeleteWithFile = {
                pendingDeleteTaskId = null
                onDelete(targetDeleteId, true)
            },
            onDeleteHistoryOnly = {
                pendingDeleteTaskId = null
                onDelete(targetDeleteId, false)
            },
            onDismiss = { pendingDeleteTaskId = null }
        )
    }

    // FFmpeg Stüdyo Pro BottomSheet (İndirilen Görevler İçin)
    val activeProTask = studioProTask
    if (activeProTask != null) {
        FfmpegStudioProSheet(
            task = activeProTask,
            allVideoTasks = completedVideoTasks,
            onRunStudio = { targetTask, action, options ->
                studioProTask = null
                onRunFfmpegStudioPro(targetTask, action, options)
            },
            onDismiss = { studioProTask = null }
        )
    }

    // Özel Kasa PIN / Biyometri Kilidi
    if (showVaultLockOverlay) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showVaultLockOverlay = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            AppLockOverlay(
                title = "Özel Kasa Kilitli",
                onUnlocked = {
                    showVaultLockOverlay = false
                    onUnlockVault()
                    selectedFilter = DownloadFilter.VAULT
                },
                onCancel = { showVaultLockOverlay = false }
            )
        }
    }

    // FFmpeg Medya Stüdyosu BottomSheet (Galeriden seçilen harici videolar için)
    val activeStudioTarget = studioTarget
    if (activeStudioTarget != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { studioTarget = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            FfmpegStudioBottomSheetContent(
                baseName = activeStudioTarget.baseName,
                busy = studioBusy,
                onSelectAction = { action ->
                    studioTarget = null
                    onRunFfmpegStudio(activeStudioTarget.uri, activeStudioTarget.baseName, action)
                },
                onDismiss = { studioTarget = null }
            )
        }
    }
}

/**
 * Hem [DownloadsScreen] hem [HomeScreen] tarafından kullanılan gerçek dosya silme onay diyaloğu.
 */
@Composable
internal fun DeleteDownloadConfirmDialog(
    onDeleteWithFile: () -> Unit,
    onDeleteHistoryOnly: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "İndirmeyi Sil",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = "Bu öğeyi yalnızca uygulama geçmişinden mi kaldırmak istersiniz, yoksa cihaz depolamasından da kalıcı olarak silinsin mi?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDeleteWithFile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(
                        text = "Cihazdan da Sil",
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("İptal")
                    }
                    TextButton(
                        onClick = onDeleteHistoryOnly,
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Text(
                            text = "Sadece Geçmişten Sil",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun FfmpegStudioBottomSheetContent(
    baseName: String,
    busy: Boolean,
    onSelectAction: (FfmpegStudioAction) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoFixHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FFmpeg Medya Stüdyosu",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = baseName,
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

        FfmpegStudioAction.entries.forEach { action ->
            val icon: ImageVector = when (action) {
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

            Surface(
                onClick = { if (!busy) onSelectAction(action) },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = action.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = action.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloadsPlaceholder(
    filter: DownloadFilter,
    hasSearchQuery: Boolean,
    modifier: Modifier = Modifier
) {
    val message = if (hasSearchQuery) {
        "Aramanızla eşleşen bir indirme bulunamadı."
    } else {
        when (filter) {
            DownloadFilter.ALL -> "Henüz indirilen bir medya yok.\nAna sayfadan bağlantı yapıştırın veya başka bir uygulamadan paylaşın."
            DownloadFilter.ACTIVE -> "Şu anda devam eden bir indirme yok."
            DownloadFilter.VIDEO -> "İndirilmiş video bulunamadı."
            DownloadFilter.AUDIO -> "İndirilmiş müzik bulunamadı."
            DownloadFilter.IMAGE -> "İndirilmiş görsel bulunamadı."
            DownloadFilter.FAVORITES -> "Henüz favorilere eklenen bir medya yok.\nKartlardaki yıldız ikonuna dokunarak ekleyebilirsiniz."
            DownloadFilter.VAULT -> "Özel Kasa şu anda boş.\nKartlardaki kilit ikonuna dokunarak dosyaları gizleyebilirsiniz."
        }
    }

    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * İndirilen dosyayı uygun sistem uygulaması ile güvenli şekilde açar.
 */
internal fun openDownloadedFile(context: Context, task: DownloadTask) {
    val uri = MediaUriHelper.resolveTaskUri(context, task)
    if (uri == null) {
        Toast.makeText(context, "Dosya konumuna erişilemedi.", Toast.LENGTH_SHORT).show()
        return
    }

    val mimeType = MediaUriHelper.resolveMimeType(task.category)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    runCatching {
        context.startActivity(Intent.createChooser(intent, "Dosyayı Aç"))
    }.onFailure {
        Toast.makeText(
            context,
            "Bu dosyayı açabilecek bir uygulama bulunamadı.",
            Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * İndirilen dosyayı sistem paylaşım menüsü üzerinden güvenli şekilde paylaşır.
 */
internal fun shareDownloadedFile(context: Context, task: DownloadTask) {
    val uri = MediaUriHelper.resolveTaskUri(context, task)
    if (uri == null) {
        Toast.makeText(context, "Paylaşılacak dosya bulunamadı.", Toast.LENGTH_SHORT).show()
        return
    }

    val mimeType = MediaUriHelper.resolveMimeType(task.category)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, task.title)
        clipData = ClipData.newRawUri(task.title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    runCatching {
        context.startActivity(Intent.createChooser(intent, "Medyayı Paylaş"))
    }.onFailure {
        Toast.makeText(
            context,
            "Paylaşım menüsü açılamadı.",
            Toast.LENGTH_SHORT
        ).show()
    }
}
