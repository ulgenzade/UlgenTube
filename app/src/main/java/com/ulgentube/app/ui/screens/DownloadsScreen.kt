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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.FfmpegStudioAction
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.player.MiniPlayerState
import com.ulgentube.app.ui.components.DownloadItemCard
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
    IMAGE("Görsel")
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
    onRunFfmpegStudio: (Uri, String, FfmpegStudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedFilter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSort by rememberSaveable { mutableStateOf(DownloadSort.NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    var pendingDeleteTaskId by remember { mutableStateOf<String?>(null) }
    var studioTarget by remember { mutableStateOf<StudioTarget?>(null) }

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

    val hasCompleted = remember(downloads) {
        downloads.any { it.status == DownloadStatus.COMPLETED }
    }

    val filteredDownloads = remember(downloads, selectedFilter, searchQuery, selectedSort) {
        val byCategory = when (selectedFilter) {
            DownloadFilter.ALL -> downloads
            DownloadFilter.ACTIVE -> downloads.filter {
                it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.MERGING
            }
            DownloadFilter.VIDEO -> downloads.filter { it.category == MediaCategory.VIDEO }
            DownloadFilter.AUDIO -> downloads.filter { it.category == MediaCategory.AUDIO }
            DownloadFilter.IMAGE -> downloads.filter { it.category == MediaCategory.IMAGE }
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
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${downloads.size} öğe kayıtlı",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    onClick = {
                        if (!studioBusy) {
                            galleryVideoPicker.launch("video/*")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (studioBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = ElectricPurple
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AutoFixHigh,
                                contentDescription = "FFmpeg Stüdyo",
                                tint = ElectricPurple,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "Stüdyo",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                if (hasCompleted) {
                    TextButton(
                        onClick = onClearCompleted,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("Temizle")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Arama Çubuğu ve Sıralama Menüsü
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "Başlık, kanal veya platform ara...",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
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
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricPurple,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )

            Box {
                Surface(
                    onClick = { sortMenuExpanded = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = "Sırala",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = selectedSort.label,
                            style = MaterialTheme.typography.labelMedium,
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
                                        FontWeight.Bold
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

        Spacer(modifier = Modifier.height(10.dp))

        // Filtre Çipleri
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
                    onClick = { selectedFilter = filter },
                    label = {
                        Text(
                            text = filter.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
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
                        isPlayingInMiniPlayer = isPlayingHere,
                        onPlayAudio = { audioTask -> onPlayAudio(audioTask) },
                        onOpenStudio = { videoTask ->
                            val resolvedUri = MediaUriHelper.resolveTaskUri(context, videoTask)
                            if (resolvedUri != null) {
                                studioTarget = StudioTarget(
                                    uri = resolvedUri,
                                    baseName = videoTask.title
                                )
                            } else {
                                Toast.makeText(
                                    context,
                                    "Video dosyasına erişilemedi.",
                                    Toast.LENGTH_SHORT
                                ).show()
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

    // FFmpeg Medya Stüdyosu BottomSheet
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
                        .clip(RoundedCornerShape(12.dp))
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
            }

            Surface(
                onClick = { if (!busy) onSelectAction(action) },
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
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
                            .clip(RoundedCornerShape(12.dp))
                            .background(UlgenSubtleGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = CoralPink,
                            modifier = Modifier.size(21.dp)
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
