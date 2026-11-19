package com.ulgentube.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ulgentube.app.ui.components.MediaDownloadSheetContent
import com.ulgentube.app.ui.theme.UlgenTubeTheme
import com.ulgentube.app.ui.viewmodel.SharePopupViewModel
import com.ulgentube.app.util.UrlSanitizer

/**
 * Başka uygulamalardan "Paylaş -> UlgenTube ile İndir" seçildiğinde
 * şeffaf arka plan üzerinde açılan Snaptube tarzı hızlı indirme popup aktivitesi.
 */
class SharePopupActivity : ComponentActivity() {

    private val viewModel: SharePopupViewModel by viewModels {
        SharePopupViewModel.provideFactory((application as UlgenTubeApp).container)
    }

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
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            UlgenTubeTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    ModalBottomSheet(
                        onDismissRequest = { finishPopup() },
                        sheetState = sheetState,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrimColor = Color.Black.copy(alpha = 0.45f)
                    ) {
                        MediaDownloadSheetContent(
                            state = analyzeState,
                            onSelectFormat = { media, option ->
                                viewModel.startFormatDownload(media, option) {
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
                                    notifyAndClose()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) {
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
        val safeUrl = UrlSanitizer.extractFirstValidUrl(sharedText)
        if (safeUrl == null) {
            Toast.makeText(this, "Paylaşılan içerikte güvenli bir bağlantı bulunamadı.", Toast.LENGTH_SHORT).show()
            finishPopup()
            return
        }

        viewModel.analyze(safeUrl)
    }

    private companion object {
        const val MAX_SHARED_TEXT_LENGTH = 8192
    }

    private fun notifyAndClose() {
        Toast.makeText(
            applicationContext,
            "İndirme arka planda başlatıldı",
            Toast.LENGTH_SHORT
        ).show()
        finishPopup()
    }

    private fun finishPopup() {
        finish()
    }
}
