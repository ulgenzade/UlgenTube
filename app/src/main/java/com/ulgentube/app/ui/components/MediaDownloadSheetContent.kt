package com.ulgentube.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.PlaylistEntry
import com.ulgentube.app.domain.model.SubtitleOption
import com.ulgentube.app.ui.theme.AmberWarning
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.EmeraldSuccess
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.ui.theme.UlgenSubtleGradient
import com.ulgentube.app.ui.viewmodel.AnalyzeUiState
import com.ulgentube.app.ui.viewmodel.SharePopupViewModel
import com.ulgentube.app.util.FormatUtils
import java.util.Locale

/**
 * Hem [com.ulgentube.app.SharePopupActivity] hem [com.ulgentube.app.MainActivity]
 * tarafından kullanılan Snaptube tarzı medya analiz ve kalite seçim BottomSheet içeriği.
 */
@Composable
fun MediaDownloadSheetContent(
    state: AnalyzeUiState,
    settings: AppSettings = AppSettings(),
    onOpenDuplicate: (DownloadTask) -> Unit = {},
    onSelectFormat: (
        media: ExtractedMedia,
        option: MediaFormatOption,
        clipStartSeconds: Long?,
        clipEndSeconds: Long?,
        subtitleLang: String?,
        embedSubtitles: Boolean
    ) -> Unit,
    onSelectImage: (ExtractedMedia, ImageItem) -> Unit,
    onDownloadAllImages: (ExtractedMedia) -> Unit,
    onDownloadPlaylist: (List<PlaylistEntry>, PlatformType, Boolean) -> Unit = { _, _, _ -> },
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "AnalyzeSheetTransition"
        ) { currentState ->
            when (currentState) {
                is AnalyzeUiState.Idle -> {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                is AnalyzeUiState.Loading -> {
                    LoadingSheetSection(
                        url = currentState.url,
                        platform = currentState.platform,
                        onDismiss = onDismiss
                    )
                }

                is AnalyzeUiState.Error -> {
                    ErrorSheetSection(
                        message = currentState.message,
                        originalInput = currentState.originalInput,
                        onRetry = { onRetry(currentState.originalInput) },
                        onDismiss = onDismiss
                    )
                }

                is AnalyzeUiState.Success -> {
                    SuccessSheetSection(
                        media = currentState.media,
                        settings = settings,
                        duplicateTask = currentState.duplicateTask,
                        onOpenDuplicate = onOpenDuplicate,
                        onSelectFormat = { option, clipStart, clipEnd, subtitleLang, embedSub ->
                            onSelectFormat(
                                currentState.media,
                                option,
                                clipStart,
                                clipEnd,
                                subtitleLang,
                                embedSub
                            )
                        },
                        onSelectImage = { image -> onSelectImage(currentState.media, image) },
                        onDownloadAllImages = { onDownloadAllImages(currentState.media) },
                        onDownloadPlaylist = { entries, audioOnly ->
                            onDownloadPlaylist(entries, currentState.media.platform, audioOnly)
                        },
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingSheetSection(
    url: String,
    platform: PlatformType,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlatformBadge(platform = platform)
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Kapat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(UlgenSubtleGradient),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(44.dp),
                strokeWidth = 3.5.dp,
                color = ElectricPurple,
                trackColor = CoralPink.copy(alpha = 0.22f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Bağlantı analiz ediliyor...",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun ErrorSheetSection(
    message: String,
    originalInput: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Bağlantı Çözümlenemedi",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Kapat")
            }

            if (originalInput.isNotBlank()) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(UlgenPrimaryGradient, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Tekrar Dene",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessSheetSection(
    media: ExtractedMedia,
    settings: AppSettings,
    duplicateTask: DownloadTask? = null,
    onOpenDuplicate: (DownloadTask) -> Unit = {},
    onSelectFormat: (MediaFormatOption, Long?, Long?, String?, Boolean) -> Unit,
    onSelectImage: (ImageItem) -> Unit,
    onDownloadAllImages: () -> Unit,
    onDownloadPlaylist: (List<PlaylistEntry>, Boolean) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    // 1. Duplicate Uyarı Banner'ı
    Column(modifier = Modifier.fillMaxWidth()) {
        if (duplicateTask != null) {
            DuplicateWarningBanner(
                onOpen = { onOpenDuplicate(duplicateTask) }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Üst Medya Özet Kartı
        MediaSummaryHeader(
            media = media,
            onDismiss = onDismiss
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Oynatma Listesi (Playlist) Görünümü
        if (media.isPlaylist && media.playlistEntries.isNotEmpty()) {
            PlaylistSelectionSection(
                entries = media.playlistEntries,
                onDownloadSelected = onDownloadPlaylist
            )
            return
        }

        // 3. Normal Medya Görünümü
        val availableTabs = remember(media) {
            buildList {
                if (media.videoFormats.isNotEmpty()) add(MediaCategory.VIDEO)
                if (media.audioFormats.isNotEmpty()) add(MediaCategory.AUDIO)
                if (media.imageItems.isNotEmpty()) add(MediaCategory.IMAGE)
            }
        }

        val initialTab = remember(media) {
            when {
                media.videoFormats.isNotEmpty() -> MediaCategory.VIDEO
                media.audioFormats.isNotEmpty() -> MediaCategory.AUDIO
                else -> MediaCategory.IMAGE
            }
        }

        var selectedTab by remember(media.originalUrl) { mutableStateOf(initialTab) }

        // Kırpma (Clip / Trim) Durumu
        val totalDuration = media.durationSeconds ?: 0L
        val supportsClipping = (media.videoFormats.isNotEmpty() || media.audioFormats.isNotEmpty()) &&
            totalDuration > 5L
        var clipExpanded by remember(media.originalUrl) { mutableStateOf(false) }
        var clipStartText by remember(media.originalUrl) { mutableStateOf("00:00") }
        var clipEndText by remember(media.originalUrl) {
            mutableStateOf(FormatUtils.formatDuration(totalDuration).ifBlank { "00:00" })
        }

        // Altyazı Seçim Durumu
        var selectedSubtitleLang by remember(media.originalUrl) { mutableStateOf<String?>(null) }
        var embedSubtitles by remember(media.originalUrl) { mutableStateOf(true) }

        fun resolveClipBounds(): Pair<Long?, Long?> {
            if (!supportsClipping || !clipExpanded) return null to null
            val start = parseTimeToSeconds(clipStartText)?.coerceIn(0L, totalDuration) ?: 0L
            val end = parseTimeToSeconds(clipEndText)?.coerceIn(0L, totalDuration) ?: totalDuration
            return if (end > start && (start > 0L || end < totalDuration)) {
                start to end
            } else {
                null to null
            }
        }

        fun dispatchFormat(option: MediaFormatOption) {
            val (clipStart, clipEnd) = resolveClipBounds()
            val subLang = if (option.category == MediaCategory.VIDEO) selectedSubtitleLang else null
            val embed = subLang != null && embedSubtitles
            onSelectFormat(option, clipStart, clipEnd, subLang, embed)
        }

        // Önerilen / Tek Tıkla Hızlı İndir Kartı
        val bestVideo = remember(media.videoFormats, settings.defaultVideoQuality) {
            SharePopupViewModel.pickPreferredVideoFormat(
                media.videoFormats,
                settings.defaultVideoQuality
            )
        }
        val bestAudio = remember(media.audioFormats, settings.defaultAudioFormat) {
            SharePopupViewModel.pickPreferredAudioFormat(
                media.audioFormats,
                settings.defaultAudioFormat
            )
        }

        if (bestVideo != null || bestAudio != null) {
            QuickDownloadActionRow(
                bestVideo = bestVideo,
                bestAudio = bestAudio,
                onQuickVideo = { bestVideo?.let { dispatchFormat(it) } },
                onQuickAudio = { bestAudio?.let { dispatchFormat(it) } }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Zaman Aralığı Kırpma (Clip / Trim) Açılır Paneli
        if (supportsClipping) {
            ClipTrimAccordion(
                expanded = clipExpanded,
                totalDurationSeconds = totalDuration,
                startText = clipStartText,
                endText = clipEndText,
                onToggleExpanded = { clipExpanded = !clipExpanded },
                onStartChange = { clipStartText = it },
                onEndChange = { clipEndText = it },
                onReset = {
                    clipStartText = "00:00"
                    clipEndText = FormatUtils.formatDuration(totalDuration).ifBlank { "00:00" }
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Altyazı Seçici (varsa ve video formatı varsa)
        if (media.subtitles.isNotEmpty() && media.videoFormats.isNotEmpty()) {
            SubtitleSelectorSection(
                subtitles = media.subtitles,
                selectedLang = selectedSubtitleLang,
                embedSubtitles = embedSubtitles,
                onSelectLang = { selectedSubtitleLang = it },
                onEmbedChange = { embedSubtitles = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Akıllı Kategori Sekmeleri (yalnızca mevcut kategoriler görünür)
        if (availableTabs.size > 1) {
            CategorySegmentedTabs(
                tabs = availableTabs,
                selectedTab = selectedTab,
                videoCount = media.videoFormats.size,
                audioCount = media.audioFormats.size,
                imageCount = media.imageItems.size,
                onSelect = { selectedTab = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Seçili Sekme İçeriği
        when (selectedTab) {
            MediaCategory.VIDEO -> {
                FormatOptionList(
                    options = media.videoFormats,
                    emptyText = "Bu içerik için video formatı bulunamadı.",
                    onSelectFormat = { dispatchFormat(it) }
                )
            }

            MediaCategory.AUDIO -> {
                FormatOptionList(
                    options = media.audioFormats,
                    emptyText = "Bu içerik için ses formatı bulunamadı.",
                    onSelectFormat = { dispatchFormat(it) }
                )
            }

            MediaCategory.IMAGE -> {
                ImageGridSection(
                    images = media.imageItems,
                    onSelectImage = onSelectImage,
                    onDownloadAll = onDownloadAllImages
                )
            }
        }
    }
}

@Composable
private fun DuplicateWarningBanner(
    onOpen: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AmberWarning.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.45f))
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = AmberWarning,
                    modifier = Modifier.size(19.dp)
                )
                Text(
                    text = "Bu medya zaten kütüphanende var",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            TextButton(
                onClick = onOpen,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = AmberWarning,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Aç",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = AmberWarning
                )
            }
        }
    }
}

@Composable
private fun QuickDownloadActionRow(
    bestVideo: MediaFormatOption?,
    bestAudio: MediaFormatOption?,
    onQuickVideo: () -> Unit,
    onQuickAudio: () -> Unit
) {
    val videoBadge = remember(bestVideo) {
        when {
            bestVideo == null -> ""
            bestVideo.resolutionHeight >= 2160 -> "4K"
            bestVideo.resolutionHeight >= 1440 -> "2K"
            bestVideo.resolutionHeight > 0 -> "${bestVideo.resolutionHeight}p"
            else -> bestVideo.extension.uppercase(Locale.ROOT)
        }
    }
    val audioBadge = remember(bestAudio) {
        bestAudio?.extension?.uppercase(Locale.ROOT).orEmpty()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (bestVideo != null) {
            Button(
                onClick = onQuickVideo,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(UlgenPrimaryGradient, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Hızlı Video ($videoBadge)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (bestAudio != null) {
            Surface(
                onClick = onQuickAudio,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = CoralPink,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Hızlı Ses ($audioBadge)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipTrimAccordion(
    expanded: Boolean,
    totalDurationSeconds: Long,
    startText: String,
    endText: String,
    onToggleExpanded: () -> Unit,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onReset: () -> Unit
) {
    val totalFormatted = remember(totalDurationSeconds) {
        FormatUtils.formatDuration(totalDurationSeconds)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (expanded) ElectricPurple.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCut,
                        contentDescription = null,
                        tint = if (expanded) CoralPink else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Kırp (Başlangıç – Bitiş)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Daralt" else "Genişlet",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = onStartChange,
                            label = { Text("Başlangıç") },
                            placeholder = { Text("00:00") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricPurple,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = onEndChange,
                            label = { Text("Bitiş") },
                            placeholder = { Text(totalFormatted) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricPurple,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Toplam süre: $totalFormatted (mm:ss veya saniye)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = onReset,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Sıfırla",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleSelectorSection(
    subtitles: List<SubtitleOption>,
    selectedLang: String?,
    embedSubtitles: Boolean,
    onSelectLang: (String?) -> Unit,
    onEmbedChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ClosedCaption,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = "Altyazı",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "sub_none") {
                FilterChip(
                    selected = selectedLang == null,
                    onClick = { onSelectLang(null) },
                    label = {
                        Text(
                            text = "Altyazı Yok",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            items(
                items = subtitles,
                key = { "${it.langCode}_${it.isAutoGenerated}" }
            ) { sub ->
                val isSelected = selectedLang == sub.langCode
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectLang(sub.langCode) },
                    label = {
                        val suffix = if (sub.isAutoGenerated) " (Oto)" else ""
                        Text(
                            text = "${sub.label}$suffix",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        AnimatedVisibility(
            visible = selectedLang != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEmbedChange(!embedSubtitles) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Videoya Göm",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = embedSubtitles,
                        onCheckedChange = onEmbedChange
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistSelectionSection(
    entries: List<PlaylistEntry>,
    onDownloadSelected: (List<PlaylistEntry>, Boolean) -> Unit
) {
    var selectedIds by remember(entries) {
        mutableStateOf(entries.map { it.id }.toSet())
    }
    val selectedEntries = remember(entries, selectedIds) {
        entries.filter { it.id in selectedIds }
    }
    val allSelected = selectedIds.size == entries.size

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlaylistPlay,
                    contentDescription = null,
                    tint = ElectricPurple,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Oynatma Listesi (${entries.size} Öğe)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            TextButton(
                onClick = {
                    selectedIds = if (allSelected) emptySet() else entries.map { it.id }.toSet()
                }
            ) {
                Text(
                    text = if (allSelected) "Temizle" else "Tümünü Seç",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = entries,
                key = { it.id }
            ) { entry ->
                val checked = entry.id in selectedIds
                Surface(
                    onClick = {
                        selectedIds = if (checked) {
                            selectedIds - entry.id
                        } else {
                            selectedIds + entry.id
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (checked) ElectricPurple.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                selectedIds = if (isChecked) {
                                    selectedIds + entry.id
                                } else {
                                    selectedIds - entry.id
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = ElectricPurple
                            )
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .size(width = 58.dp, height = 42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(UlgenSubtleGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!entry.thumbnailUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = entry.thumbnailUrl,
                                    contentDescription = entry.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Videocam,
                                    contentDescription = null,
                                    tint = ElectricPurple,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subLine = buildList {
                                entry.author?.takeIf { it.isNotBlank() }?.let { add(it) }
                                FormatUtils.formatDuration(entry.durationSeconds)
                                    .takeIf { it.isNotBlank() }
                                    ?.let { add(it) }
                            }.joinToString(" • ")
                            if (subLine.isNotBlank()) {
                                Text(
                                    text = subLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val count = selectedEntries.size
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { onDownloadSelected(selectedEntries, false) },
                enabled = count > 0,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
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
                            if (count > 0) Modifier.background(UlgenPrimaryGradient, RoundedCornerShape(15.dp))
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Seçilenleri Video İndir ($count)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (count > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            OutlinedButton(
                onClick = { onDownloadSelected(selectedEntries, true) },
                enabled = count > 0,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(
                    1.dp,
                    if (count > 0) ElectricPurple.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    text = "Seçilenleri MP3 İndir ($count)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MediaSummaryHeader(
    media: ExtractedMedia,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Kapak Görseli
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 74.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(UlgenSubtleGradient),
                contentAlignment = Alignment.Center
            ) {
                if (!media.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = media.thumbnailUrl,
                        contentDescription = media.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Videocam,
                        contentDescription = null,
                        tint = ElectricPurple,
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Süre etiketi (sağ alt köşe)
                val durationText = FormatUtils.formatDuration(media.durationSeconds)
                if (durationText.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.78f)
                    ) {
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlatformBadge(platform = media.platform)
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Kapat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val metaParts = buildList {
                    media.author?.takeIf { it.isNotBlank() }?.let { add(it) }
                    FormatUtils.formatDuration(media.durationSeconds)
                        .takeIf { it.isNotBlank() }
                        ?.let { add(it) }
                }

                if (metaParts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!media.author.isNullOrBlank()) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = media.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        val dur = FormatUtils.formatDuration(media.durationSeconds)
                        if (dur.isNotBlank()) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = dur,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySegmentedTabs(
    tabs: List<MediaCategory>,
    selectedTab: MediaCategory,
    videoCount: Int,
    audioCount: Int,
    imageCount: Int,
    onSelect: (MediaCategory) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { category ->
                val isSelected = category == selectedTab
                val (label, icon) = when (category) {
                    MediaCategory.VIDEO -> "Video ($videoCount)" to Icons.Rounded.Videocam
                    MediaCategory.AUDIO -> "Müzik / Ses ($audioCount)" to Icons.Rounded.MusicNote
                    MediaCategory.IMAGE -> "Görsel ($imageCount)" to Icons.Rounded.Image
                }

                val tabModifier = if (isSelected) {
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(UlgenPrimaryGradient)
                        .clickable { onSelect(category) }
                } else {
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable { onSelect(category) }
                }

                Row(
                    modifier = tabModifier.padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatOptionList(
    options: List<MediaFormatOption>,
    emptyText: String,
    onSelectFormat: (MediaFormatOption) -> Unit
) {
    if (options.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(
            items = options,
            key = { it.id }
        ) { option ->
            FormatOptionCard(
                option = option,
                onClick = { onSelectFormat(option) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatOptionCard(
    option: MediaFormatOption,
    onClick: () -> Unit
) {
    val badgeText = remember(option) {
        when {
            option.category == MediaCategory.AUDIO -> option.extension.uppercase(Locale.ROOT)
            option.resolutionHeight >= 2160 -> "4K"
            option.resolutionHeight >= 1440 -> "2K"
            option.resolutionHeight > 0 -> "${option.resolutionHeight}p"
            else -> option.extension.uppercase(Locale.ROOT)
        }
    }

    val isHighQuality = option.resolutionHeight >= 1080 ||
        option.bitrateKbps >= 256 ||
        option.isHdrOrHighBit
    val formattedSize = remember(option.fileSizeBytes) {
        FormatUtils.formatBytes(option.fileSizeBytes)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (isHighQuality) {
                ElectricPurple.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Kalite Rozeti
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isHighQuality) UlgenPrimaryGradient else UlgenSubtleGradient
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isHighQuality) Color.White else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val detailLine = buildList {
                    if (option.sublabel.isNotBlank()) add(option.sublabel)
                    add(option.extension.uppercase(Locale.ROOT))
                    if (formattedSize.isNotBlank() && !option.sublabel.contains(formattedSize)) {
                        add(formattedSize)
                    }
                }.joinToString(" • ")

                Text(
                    text = detailLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (option.badgeTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        option.badgeTags.forEach { tag ->
                            FormatBadgePill(tag = tag)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "İndir",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun FormatBadgePill(tag: String) {
    val lower = tag.lowercase(Locale.ROOT)
    val (bgColor, textColor, borderColor) = when {
        lower.contains("hdr") || lower.contains("10-bit") -> Triple(
            AmberWarning.copy(alpha = 0.18f),
            AmberWarning,
            AmberWarning.copy(alpha = 0.45f)
        )

        lower.contains("yüksek bitrate") || lower.contains("high bitrate") -> Triple(
            CoralPink.copy(alpha = 0.16f),
            CoralPink,
            CoralPink.copy(alpha = 0.42f)
        )

        lower.contains("120 fps") || lower.contains("60 fps") || lower.contains("50 fps") || lower.contains("fps") -> Triple(
            EmeraldSuccess.copy(alpha = 0.16f),
            EmeraldSuccess,
            EmeraldSuccess.copy(alpha = 0.42f)
        )

        lower.contains("ffmpeg") -> Triple(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
        )

        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(0.8.dp, borderColor)
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ImageGridSection(
    images: List<ImageItem>,
    onSelectImage: (ImageItem) -> Unit,
    onDownloadAll: () -> Unit
) {
    if (images.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "İndirilebilir görsel bulunamadı.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (images.size > 1) {
            Button(
                onClick = onDownloadAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(UlgenPrimaryGradient, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Collections,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Tümünü İndir (${images.size} Görsel)",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = images,
                key = { it.id }
            ) { image ->
                ImageOptionCard(
                    image = image,
                    onClick = { onSelectImage(image) }
                )
            }
        }
    }
}

@Composable
private fun ImageOptionCard(
    image: ImageItem,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.15f)
                    .background(UlgenSubtleGradient)
            ) {
                AsyncImage(
                    model = image.url,
                    contentDescription = image.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.72f)
                ) {
                    Text(
                        text = image.extension.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = image.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (image.width != null && image.height != null) {
                    Text(
                        text = "${image.width} × ${image.height}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "İndir",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * Platform adına ve rengine göre şık rozet bileşeni.
 */
@Composable
fun PlatformBadge(
    platform: PlatformType,
    modifier: Modifier = Modifier
) {
    val accentColor = remember(platform) { Color(platform.badgeColorHex) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = accentColor.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = platform.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * `mm:ss`, `hh:mm:ss` veya düz saniye metnini saniye değerine çevirir.
 */
private fun parseTimeToSeconds(raw: String): Long? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null
    val parts = clean.split(':')
    return when (parts.size) {
        1 -> parts[0].toLongOrNull()?.coerceAtLeast(0L)
        2 -> {
            val mins = parts[0].toLongOrNull() ?: return null
            val secs = parts[1].toLongOrNull() ?: return null
            if (mins < 0 || secs < 0) null else mins * 60L + secs
        }
        3 -> {
            val hours = parts[0].toLongOrNull() ?: return null
            val mins = parts[1].toLongOrNull() ?: return null
            val secs = parts[2].toLongOrNull() ?: return null
            if (hours < 0 || mins < 0 || secs < 0) null else hours * 3600L + mins * 60L + secs
        }
        else -> null
    }
}
