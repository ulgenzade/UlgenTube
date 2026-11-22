package com.ulgentube.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.EmeraldSuccess
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.ui.theme.UlgenSubtleGradient
import java.net.URLEncoder
import java.util.Locale

private data class QuickPlatformLink(
    val name: String,
    val url: String
)

private val QUICK_BROWSER_LINKS = listOf(
    QuickPlatformLink("YouTube", "https://m.youtube.com"),
    QuickPlatformLink("Instagram", "https://www.instagram.com"),
    QuickPlatformLink("X", "https://x.com"),
    QuickPlatformLink("TikTok", "https://www.tiktok.com"),
    QuickPlatformLink("Reddit", "https://www.reddit.com"),
    QuickPlatformLink("Vimeo", "https://vimeo.com")
)

private val SNIFFER_MEDIA_EXTENSIONS = listOf(
    ".mp4", ".m3u8", ".mpd", ".webm", ".m4a", ".mp3"
)

/**
 * Uygulama içi Sniffer Tarayıcı ekranı.
 * Ağ isteklerinden doğrudan medya akışlarını (.mp4/.m3u8/.mpd/.webm/.m4a/.mp3) yakalar
 * ve mevcut sayfa ya da yakalanan akışı tek dokunuşla indirme analizine gönderir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onAnalyzeUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var addressInput by remember { mutableStateOf("https://m.youtube.com") }
    var currentUrl by remember { mutableStateOf("https://m.youtube.com") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val sniffedUrls = remember { mutableStateListOf<String>() }
    var showSniffedSheet by remember { mutableStateOf(false) }

    fun navigateTo(rawInput: String) {
        val target = resolveBrowserInput(rawInput)
        addressInput = target
        currentUrl = target
        keyboardController?.hide()
        webViewRef?.loadUrl(target)
    }

    DisposableEffect(Unit) {
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Üst Adres Çubuğu ve Navigasyon Kontrolleri
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { webViewRef?.goBack() },
                            enabled = canGoBack,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Geri",
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        IconButton(
                            onClick = { webViewRef?.goForward() },
                            enabled = canGoForward,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = "İleri",
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        OutlinedTextField(
                            value = addressInput,
                            onValueChange = { addressInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = "URL girin veya DuckDuckGo'da arayın...",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = ElectricPurple,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = if (addressInput.isNotEmpty()) {
                                {
                                    IconButton(
                                        onClick = { addressInput = "" },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Temizle",
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            } else null,
                            shape = RoundedCornerShape(14.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { navigateTo(addressInput) }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricPurple,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )

                        IconButton(
                            onClick = { webViewRef?.reload() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Yenile",
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    // Hızlı Platform Kısayol Çipleri
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        items(
                            items = QUICK_BROWSER_LINKS,
                            key = { it.name }
                        ) { link ->
                            val isActive = currentUrl.contains(
                                link.url.removePrefix("https://").removePrefix("m.").removePrefix("www."),
                                ignoreCase = true
                            )
                            Surface(
                                onClick = { navigateTo(link.url) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isActive) {
                                    ElectricPurple.copy(alpha = 0.2f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isActive) ElectricPurple else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Text(
                                    text = link.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isActive) ElectricPurple else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Sayfa Yükleme İlerleme Çizgisi
            AnimatedVisibility(visible = loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = CoralPink,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }

            // Gömülü Sniffer WebView
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        val cleanUa = settings.userAgentString
                            .replace("; wv)", ")")
                            .replace("; wv", "")
                            .replace(Regex("""\s*Version/\d+\.\d+\s*"""), " ")
                            .trim()
                        settings.userAgentString = cleanUa

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (!url.isNullOrBlank() && url != "about:blank") {
                                    currentUrl = url
                                    addressInput = url
                                    sniffedUrls.clear()
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (!url.isNullOrBlank() && url != "about:blank") {
                                    currentUrl = url
                                    addressInput = url
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString()
                                if (!reqUrl.isNullOrBlank() && isSniffableMediaUrl(reqUrl)) {
                                    mainHandler.post {
                                        if (reqUrl !in sniffedUrls) {
                                            sniffedUrls.add(0, reqUrl)
                                            if (sniffedUrls.size > 25) {
                                                sniffedUrls.removeAt(sniffedUrls.lastIndex)
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        webViewRef = this
                        loadUrl(currentUrl)
                    }
                }
            )
        }

        // Kompakt Yüzen İndir & Yakalananlar FAB'ı (Site alt barını kapatmamak için sağ-üstte konumlanır)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 68.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (sniffedUrls.isNotEmpty()) {
                Surface(
                    onClick = { showSniffedSheet = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, EmeraldSuccess),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Radar,
                            contentDescription = "Yakalanan Akışlar",
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "${sniffedUrls.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Surface(
                onClick = { onAnalyzeUrl(currentUrl) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Medyayı Çözümle",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    // Yakalanan Ağ Akışları Alt Sayfası
    if (showSniffedSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSniffedSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(UlgenSubtleGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Radar,
                                contentDescription = null,
                                tint = EmeraldSuccess,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Yakalanan Medya Akışları",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "İndirmek için bir akış seçin",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { showSniffedSheet = false }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Kapat")
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(
                        items = sniffedUrls,
                        key = { it }
                    ) { mediaUrl ->
                        val extBadge = detectMediaExtBadge(mediaUrl)
                        Surface(
                            onClick = {
                                showSniffedSheet = false
                                onAnalyzeUrl(mediaUrl)
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ElectricPurple.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = extBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricPurple,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Text(
                                    text = mediaUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Çözümle",
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

private fun resolveBrowserInput(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "https://m.youtube.com"
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return trimmed
    }
    if (!trimmed.contains(" ") && trimmed.contains(".")) {
        return "https://$trimmed"
    }
    val encoded = URLEncoder.encode(trimmed, "UTF-8")
    return "https://duckduckgo.com/?q=$encoded"
}

private fun isSniffableMediaUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.ROOT)
    val pathPart = lower.substringBefore("?").substringBefore("#")
    return SNIFFER_MEDIA_EXTENSIONS.any { ext ->
        pathPart.endsWith(ext) || lower.contains("$ext?") || lower.contains("$ext&")
    }
}

private fun detectMediaExtBadge(url: String): String {
    val lower = url.lowercase(Locale.ROOT)
    val found = SNIFFER_MEDIA_EXTENSIONS.firstOrNull { lower.contains(it) }
    return found?.removePrefix(".")?.uppercase(Locale.ROOT) ?: "MEDYA"
}
