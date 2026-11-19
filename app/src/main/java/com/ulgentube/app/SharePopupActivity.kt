package com.ulgentube.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.service.ClipboardQuickTileService
import com.ulgentube.app.ui.components.MediaDownloadSheetContent
import com.ulgentube.app.ui.components.PlatformBadge
import com.ulgentube.app.ui.screens.openDownloadedFile
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.UlgenCardHighlightGradient
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.ui.theme.UlgenTubeTheme
import com.ulgentube.app.ui.viewmodel.AnalyzeUiState
import com.ulgentube.app.ui.viewmodel.SharePopupViewModel
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.delay

/**
 * Başka uygulamalardan "Paylaş -> UlgenTube ile İndir" veya Quick Settings kutucuğu
 * seçildiğinde şeffaf arka plan üzerinde açılan Snaptube tarzı hızlı indirme popup aktivitesi.
 */
class SharePopupActivity : ComponentActivity() {

    private val viewModel: SharePopupViewModel by viewModels {
        SharePopupViewModel.provideFactory((application as UlgenTubeApp).container)
    }

    private var pendingClipboardRead: Boolean = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val analyzeState by viewModel.analyzeState.collectAsStateWithLifecycle()
            val batchUrls by viewModel.batchUrls.collectAsStateWithLifecycle()
            var forceFullSheet by remember { mutableStateOf(false) }

            UlgenTubeTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    val successState = analyzeState as? AnalyzeUiState.Success
                    val showZeroFrictionCapsule = settings.zeroFrictionShareMode &&
                        !forceFullSheet &&
                        batchUrls.size < 2 &&
                        successState != null &&
                        !successState.media.isPlaylist &&
                        successState.duplicateTask == null

                    when {
                        // 1. Çoklu Link (Batch URL) BottomSheet
                        batchUrls.size >= 2 -> {
                            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                            ModalBottomSheet(
                                onDismissRequest = { finishPopup() },
                                sheetState = sheetState,
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrimColor = Color.Black.copy(alpha = 0.45f)
                            ) {
                                BatchUrlsSheetContent(
                                    urls = batchUrls,
                                    onDownloadAll = { audioOnly ->
                                        viewModel.startBatchUrlsDownload(batchUrls, audioOnly) {
                                            notifyAndClose("${batchUrls.size} bağlantı kuyruğa eklendi")
                                        }
                                    },
                                    onDismiss = { finishPopup() }
                                )
                            }
                        }

                        // 2. Zero-Friction 2.5 sn Geri Sayımlı Mini Kapsül
                        showZeroFrictionCapsule && successState != null -> {
                            ZeroFrictionMiniCapsule(
                                media = successState.media,
                                settings = settings,
                                onExpandFullSheet = { forceFullSheet = true },
                                onAutoDownload = {
                                    viewModel.startQuickDefaultDownload(
                                        media = successState.media,
                                        settings = settings
                                    ) {
                                        notifyAndClose()
                                    }
                                },
                                onDismiss = { finishPopup() },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 18.dp)
                            )
                        }

                        // 3. Normal / Tam ModalBottomSheet
                        analyzeState !is AnalyzeUiState.Idle -> {
                            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                            ModalBottomSheet(
                                onDismissRequest = { finishPopup() },
                                sheetState = sheetState,
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrimColor = Color.Black.copy(alpha = 0.45f)
                            ) {
                                MediaDownloadSheetContent(
                                    state = analyzeState,
                                    settings = settings,
                                    onOpenDuplicate = { task ->
                                        openDownloadedFile(this@SharePopupActivity, task)
                                        finishPopup()
                                    },
                                    onSelectFormat = { media, option, clipStart, clipEnd, subtitleLang, embedSubtitles ->
                                        viewModel.startFormatDownload(
                                            media = media,
                                            option = option,
                                            clipStartSeconds = clipStart,
                                            clipEndSeconds = clipEnd,
                                            subtitleLang = subtitleLang,
                                            embedSubtitles = embedSubtitles
                                        ) {
                                            notifyAndClose()
                                        }
                                    },
                                    onSelectImage = { media, image ->
                                        viewModel.startImageDownload(media, image) {
                                            notifyAndClose()
                                        }
                                    },
                                    onDownloadAllImages = { media ->
                                        viewModel.startAllImagesDownload(media) {
                                            notifyAndClose("Tüm görseller kuyruğa eklendi")
                                        }
                                    },
                                    onDownloadPlaylist = { entries, platform, audioOnly ->
                                        viewModel.startPlaylistDownload(entries, platform, audioOnly) {
                                            notifyAndClose("${entries.size} öğe kuyruğa eklendi")
                                        }
                                    },
                                    onRetry = { originalInput ->
                                        viewModel.analyze(originalInput)
                                    },
                                    onDismiss = { finishPopup() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingClipboardRead) {
            pendingClipboardRead = false
            readClipboardAndAnalyze()
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) {
            finishPopup()
            return
        }

        // Quick Settings kutucuğu ile güvenli pano okuma akışı
        if (intent.action == ClipboardQuickTileService.ACTION_QUICK_TILE_CLIPBOARD) {
            pendingClipboardRead = true
            if (hasWindowFocus()) {
                pendingClipboardRead = false
                readClipboardAndAnalyze()
            }
            return
        }

        if (intent.action != Intent.ACTION_SEND) {
            finishPopup()
            return
        }

        // Explicit intent ile yetkisiz MIME tiplerinin gönderilmesini engelle
        val mimeType = intent.type
        if (mimeType == null || !mimeType.startsWith("text/plain", ignoreCase = true)) {
            Toast.makeText(this, "Desteklenmeyen paylaşım türü.", Toast.LENGTH_SHORT).show()
            finishPopup()
            return
        }

        val sharedText = runCatching {
            intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        }.getOrNull()?.take(MAX_SHARED_TEXT_LENGTH)

        if (sharedText.isNullOrBlank()) {
            Toast.makeText(this, "Paylaşılan bağlantı okunamadı.", Toast.LENGTH_SHORT).show()
            finishPopup()
            return
        }

        // Yalnızca geçerli http:// veya https:// URL'leri kabul et
        val safeUrls = UrlSanitizer.extractAllValidUrls(sharedText)
        if (safeUrls.isEmpty()) {
            Toast.makeText(this, "Paylaşılan içerikte güvenli bir bağlantı bulunamadı.", Toast.LENGTH_SHORT).show()
            finishPopup()
            return
        }

        viewModel.analyze(safeUrls.joinToString("\n"))
    }

    private fun readClipboardAndAnalyze() {
        val rawClip = runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()?.take(MAX_SHARED_TEXT_LENGTH)

        val safeUrls = UrlSanitizer.extractAllValidUrls(rawClip)
        if (safeUrls.isEmpty()) {
            Toast.makeText(this, "Panoda geçerli bir bağlantı bulunamadı.", Toast.LENGTH_SHORT).show()
            finishPopup()
            return
        }

        viewModel.analyze(safeUrls.joinToString("\n"))
    }

    private companion object {
        const val MAX_SHARED_TEXT_LENGTH = 8192
    }

    private fun notifyAndClose(message: String = "İndirme arka planda başlatıldı") {
        Toast.makeText(
            applicationContext,
            message,
            Toast.LENGTH_SHORT
        ).show()
        finishPopup()
    }

    private fun finishPopup() {
        finish()
    }
}

@Composable
private fun ZeroFrictionMiniCapsule(
    media: ExtractedMedia,
    settings: AppSettings,
    onExpandFullSheet: () -> Unit,
    onAutoDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var progress by remember(media.originalUrl) { mutableFloatStateOf(0f) }

    val preferredQualityText = remember(media, settings) {
        val video = SharePopupViewModel.pickPreferredVideoFormat(
            media.videoFormats,
            settings.defaultVideoQuality
        )
        when {
            video != null && video.resolutionHeight >= 2160 -> "4K"
            video != null && video.resolutionHeight >= 1440 -> "2K"
            video != null && video.resolutionHeight > 0 -> "${video.resolutionHeight}p"
            video != null -> settings.defaultVideoQuality
            media.audioFormats.isNotEmpty() -> settings.defaultAudioFormat.uppercase()
            else -> "Medya"
        }
    }

    LaunchedEffect(media.originalUrl) {
        val totalMs = 2_500L
        val stepMs = 50L
        var elapsed = 0L
        while (elapsed < totalMs) {
            delay(stepMs)
            elapsed += stepMs
            progress = (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        }
        onAutoDownload()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.2.dp, ElectricPurple.copy(alpha = 0.55f)),
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UlgenCardHighlightGradient)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(UlgenPrimaryGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$preferredQualityText indiriliyor...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = onExpandFullSheet,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = CoralPink,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Kalite Değiştir",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CoralPink
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "İptal",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = CoralPink,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun BatchUrlsSheetContent(
    urls: List<String>,
    onDownloadAll: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(UlgenPrimaryGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Column {
                    Text(
                        text = "${urls.size} bağlantı algılandı",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Varsayılan kalite tercihleriyle toplu indir",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            urls.take(5).forEach { url ->
                val platform = remember(url) { UrlSanitizer.detectPlatform(url) }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlatformBadge(platform = platform)
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (urls.size > 5) {
                Text(
                    text = "+ ${urls.size - 5} bağlantı daha...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { onDownloadAll(false) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
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
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Tümünü Video İndir",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { onDownloadAll(true) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = CoralPink,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Tümünü MP3 İndir",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
