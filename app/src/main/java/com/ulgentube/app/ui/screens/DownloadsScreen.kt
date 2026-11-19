package com.ulgentube.app.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.ui.components.DownloadItemCard
import java.io.File

private enum class DownloadFilter(val label: String) {
    ALL("Tümü"),
    ACTIVE("Aktif"),
    VIDEO("Video"),
    AUDIO("Müzik"),
    IMAGE("Görsel")
}

@Composable
fun DownloadsScreen(
    downloads: List<DownloadTask>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedFilter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }

    val hasCompleted = remember(downloads) {
        downloads.any { it.status == DownloadStatus.COMPLETED }
    }

    val filteredDownloads = remember(downloads, selectedFilter) {
        when (selectedFilter) {
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
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
    ) {
        // Üst Başlık ve Temizle Butonu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
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

            if (hasCompleted) {
                TextButton(onClick = onClearCompleted) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Temizle")
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
                    DownloadItemCard(
                        task = task,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onDelete = onDelete,
                        onOpen = { openDownloadedFile(context, it) },
                        onShare = { shareDownloadedFile(context, it) }
                    )
                }

                item(key = "downloads_bottom_space") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloadsPlaceholder(
    filter: DownloadFilter,
    modifier: Modifier = Modifier
) {
    val message = when (filter) {
        DownloadFilter.ALL -> "Henüz indirilen bir medya yok.\nAna sayfadan bağlantı yapıştırın veya başka bir uygulamadan paylaşın."
        DownloadFilter.ACTIVE -> "Şu anda devam eden bir indirme yok."
        DownloadFilter.VIDEO -> "İndirilmiş video bulunamadı."
        DownloadFilter.AUDIO -> "İndirilmiş müzik bulunamadı."
        DownloadFilter.IMAGE -> "İndirilmiş görsel bulunamadı."
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
    val uri = resolveTaskUri(context, task)
    if (uri == null) {
        Toast.makeText(context, "Dosya konumuna erişilemedi.", Toast.LENGTH_SHORT).show()
        return
    }

    val mimeType = resolveMimeType(task.category)
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
    val uri = resolveTaskUri(context, task)
    if (uri == null) {
        Toast.makeText(context, "Paylaşılacak dosya bulunamadı.", Toast.LENGTH_SHORT).show()
        return
    }

    val mimeType = resolveMimeType(task.category)
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

private fun resolveTaskUri(context: Context, task: DownloadTask): Uri? {
    val rawUri = task.savedUri?.takeIf { it.isNotBlank() }
    if (rawUri != null) {
        val parsed = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (parsed != null) {
            when (parsed.scheme?.lowercase()) {
                "content" -> return parsed
                "file" -> {
                    val filePath = parsed.path?.takeIf { it.isNotBlank() }
                    if (filePath != null) {
                        val file = File(filePath)
                        if (file.exists()) {
                            return runCatching {
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                            }.getOrNull()
                        }
                    }
                    return null
                }
                // content:// dışındaki şemaları (file:// fallback dahil) asla expose etme
                else -> return null
            }
        }
    }

    val rawPath = task.savedPath?.takeIf { it.isNotBlank() } ?: return null
    val file = File(rawPath)
    if (!file.exists()) return null

    return runCatching {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }.getOrNull()
}

private fun resolveMimeType(category: MediaCategory): String = when (category) {
    MediaCategory.VIDEO -> "video/*"
    MediaCategory.AUDIO -> "audio/*"
    MediaCategory.IMAGE -> "image/*"
}
