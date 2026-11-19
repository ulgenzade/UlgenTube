package com.ulgentube.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ulgentube.app.EngineInitState
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.ui.components.DownloadItemCard
import com.ulgentube.app.ui.components.PlatformBadge
import com.ulgentube.app.ui.theme.AmberWarning
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.EmeraldSuccess
import com.ulgentube.app.ui.theme.UlgenCardHighlightGradient
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.util.UrlSanitizer

private val SUPPORTED_PLATFORMS = listOf(
    PlatformType.YOUTUBE,
    PlatformType.KICK,
    PlatformType.TWITCH,
    PlatformType.INSTAGRAM,
    PlatformType.TIKTOK,
    PlatformType.TWITTER_X,
    PlatformType.FACEBOOK,
    PlatformType.REDDIT,
    PlatformType.PINTEREST,
    PlatformType.SOUNDCLOUD,
    PlatformType.VIMEO
)

@Composable
fun HomeScreen(
    urlInput: String,
    downloads: List<DownloadTask>,
    settings: AppSettings,
    engineState: EngineInitState,
    onUrlInputChange: (String) -> Unit,
    onClearUrl: () -> Unit,
    onAnalyzeUrl: (String) -> Unit,
    onRetryEngineInit: () -> Unit,
    onViewAllDownloads: () -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDeleteDownload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var detectedClipboardUrl by remember { mutableStateOf<String?>(null) }
    var dismissedClipboardUrl by remember { mutableStateOf<String?>(null) }

    fun checkClipboard() {
        if (!settings.autoDetectClipboard) {
            detectedClipboardUrl = null
            return
        }
        val clipUrl = readClipboardUrl(context)
        detectedClipboardUrl = if (clipUrl != null && clipUrl != dismissedClipboardUrl) {
            clipUrl
        } else {
            null
        }
    }

    LaunchedEffect(settings.autoDetectClipboard) {
        checkClipboard()
    }

    DisposableEffect(lifecycleOwner, settings.autoDetectClipboard) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkClipboard()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val recentDownloads = remember(downloads) { downloads.take(3) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Üst Marka Başlığı & Motor Durumu
        item(key = "header") {
            HomeBrandHeader(
                engineState = engineState,
                onRetryEngineInit = onRetryEngineInit
            )
        }

        // Pano Link Algılama Kartı
        item(key = "clipboard_banner") {
            AnimatedVisibility(
                visible = detectedClipboardUrl != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val clipUrl = detectedClipboardUrl
                if (clipUrl != null) {
                    ClipboardDetectedCard(
                        url = clipUrl,
                        onAnalyze = {
                            dismissedClipboardUrl = clipUrl
                            detectedClipboardUrl = null
                            onAnalyzeUrl(clipUrl)
                        },
                        onDismiss = {
                            dismissedClipboardUrl = clipUrl
                            detectedClipboardUrl = null
                        }
                    )
                }
            }
        }

        // Akıllı URL Giriş Kartı
        item(key = "url_input_card") {
            UrlInputHeroCard(
                urlInput = urlInput,
                onUrlInputChange = onUrlInputChange,
                onClear = onClearUrl,
                onPaste = {
                    val pasted = readClipboardRaw(context)
                    if (!pasted.isNullOrBlank()) {
                        onUrlInputChange(pasted)
                    }
                },
                onAnalyze = {
                    keyboardController?.hide()
                    onAnalyzeUrl(urlInput)
                }
            )
        }

        // Desteklenen Platformlar Şeridi
        item(key = "supported_platforms") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Desteklenen Platformlar",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = SUPPORTED_PLATFORMS,
                        key = { it.name }
                    ) { platform ->
                        PlatformBadge(platform = platform)
                    }
                }
            }
        }

        // Nasıl Kullanılır İpucu Kartı
        item(key = "how_to_use") {
            HowToUseTipCard()
        }

        // Son İndirilenler
        if (recentDownloads.isNotEmpty()) {
            item(key = "recent_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Son İndirilenler",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = onViewAllDownloads) {
                        Text(
                            text = "Tümünü Gör",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            items(
                items = recentDownloads,
                key = { "recent_${it.id}" }
            ) { task ->
                DownloadItemCard(
                    task = task,
                    onCancel = onCancelDownload,
                    onRetry = onRetryDownload,
                    onDelete = onDeleteDownload,
                    onOpen = { openDownloadedFile(context, it) },
                    onShare = { shareDownloadedFile(context, it) }
                )
            }
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HomeBrandHeader(
    engineState: EngineInitState,
    onRetryEngineInit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(UlgenPrimaryGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Column {
                Text(
                    text = "UlgenTube",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = CoralPink,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Reklamsız • Sınırsız",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        EngineStatusChip(
            engineState = engineState,
            onRetry = onRetryEngineInit
        )
    }
}

@Composable
private fun EngineStatusChip(
    engineState: EngineInitState,
    onRetry: () -> Unit
) {
    when (engineState) {
        is EngineInitState.Ready -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = EmeraldSuccess.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(EmeraldSuccess)
                    )
                    Text(
                        text = if (engineState.aria2cReady) "Turbo Hazır" else "Motor Hazır",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = EmeraldSuccess
                    )
                }
            }
        }

        is EngineInitState.Initializing, EngineInitState.Idle -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AmberWarning.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = AmberWarning
                    )
                    Text(
                        text = "Hazırlanıyor",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarning
                    )
                }
            }
        }

        is EngineInitState.Error -> {
            Surface(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Tekrar Başlat",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardDetectedCard(
    url: String,
    onAnalyze: () -> Unit,
    onDismiss: () -> Unit
) {
    val platform = remember(url) { UrlSanitizer.detectPlatform(url) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.45f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(UlgenCardHighlightGradient)
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = CoralPink,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Panoda bağlantı algılandı",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Kapat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Row(
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

                Button(
                    onClick = onAnalyze,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(UlgenPrimaryGradient, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tek Tıkla Analiz Et",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlInputHeroCard(
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit,
    onAnalyze: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Video, Müzik veya Fotoğraf İndir",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlInputChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "Bağlantıyı buraya yapıştırın...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = ElectricPurple
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (urlInput.isNotBlank()) {
                            IconButton(onClick = onClear) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Temizle",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            IconButton(onClick = onPaste) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentPaste,
                                    contentDescription = "Yapıştır",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onAnalyze() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricPurple,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )

            Button(
                onClick = onAnalyze,
                enabled = urlInput.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
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
                            if (urlInput.isNotBlank()) {
                                Modifier.background(UlgenPrimaryGradient, RoundedCornerShape(16.dp))
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
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = if (urlInput.isNotBlank()) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            text = "Analiz Et & İndir",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (urlInput.isNotBlank()) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HowToUseTipCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(21.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nasıl Kullanılır?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Herhangi bir uygulamada Paylaş → UlgenTube ile İndir seçin, anında popup açılsın!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun readClipboardRaw(context: Context): String? {
    return runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()
}

private fun readClipboardUrl(context: Context): String? {
    return UrlSanitizer.extractFirstValidUrl(readClipboardRaw(context))
}
