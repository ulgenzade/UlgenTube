package com.ulgentube.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.ui.components.MediaDownloadSheetContent
import com.ulgentube.app.ui.components.MiniPlayerBar
import com.ulgentube.app.ui.screens.DownloadsScreen
import com.ulgentube.app.ui.screens.HomeScreen
import com.ulgentube.app.ui.screens.SettingsScreen
import com.ulgentube.app.ui.screens.openDownloadedFile
import com.ulgentube.app.ui.theme.UlgenTubeTheme
import com.ulgentube.app.ui.viewmodel.AnalyzeUiState
import com.ulgentube.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

private enum class UlgenDestination(
    val label: String,
    val icon: ImageVector
) {
    HOME("Ana Sayfa", Icons.Rounded.Home),
    DOWNLOADS("İndirmelerim", Icons.Rounded.Download),
    SETTINGS("Ayarlar", Icons.Rounded.Settings)
}

/**
 * UlgenTube ana uygulama aktivitesi.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.provideFactory((application as UlgenTubeApp).container)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Kullanıcı kararı sessizce kabul edilir */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            UlgenTubeTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                UlgenMainScaffold(
                    viewModel = viewModel,
                    onUpdateYtDlp = { viewModel.updateYtDlp(applicationContext) }
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UlgenMainScaffold(
    viewModel: MainViewModel,
    onUpdateYtDlp: () -> Unit
) {
    val context = LocalContext.current
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val analyzeState by viewModel.analyzeState.collectAsStateWithLifecycle()
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val ytDlpUpdateMessage by viewModel.ytDlpUpdateState.collectAsStateWithLifecycle()
    val isUpdatingYtDlp by viewModel.isUpdatingYtDlp.collectAsStateWithLifecycle()
    val miniPlayerState by viewModel.miniPlayerState.collectAsStateWithLifecycle()
    val studioBusy by viewModel.studioBusy.collectAsStateWithLifecycle()
    val studioMessage by viewModel.studioMessage.collectAsStateWithLifecycle()
    val hasCookies by viewModel.hasCookies.collectAsStateWithLifecycle()
    val cookieMessage by viewModel.cookieMessage.collectAsStateWithLifecycle()

    var currentDestination by rememberSaveable { mutableStateOf(UlgenDestination.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(studioMessage) {
        val msg = studioMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStudioMessage()
        }
    }

    LaunchedEffect(cookieMessage) {
        val msg = cookieMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearCookieMessage()
        }
    }

    val activeDownloadCount = remember(downloads) {
        downloads.count {
            it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.MERGING
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                MiniPlayerBar(
                    state = miniPlayerState,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onSeekTo = viewModel::seekTo,
                    onStop = viewModel::stopMiniPlayer
                )

                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp
                ) {
                    UlgenDestination.entries.forEach { destination ->
                        val isSelected = currentDestination == destination
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { currentDestination = destination },
                            icon = {
                                if (destination == UlgenDestination.DOWNLOADS && activeDownloadCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.secondary,
                                                contentColor = MaterialTheme.colorScheme.onSecondary
                                            ) {
                                                Text(text = activeDownloadCount.toString())
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = destination.label
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = destination.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (currentDestination) {
            UlgenDestination.HOME -> {
                HomeScreen(
                    urlInput = urlInput,
                    downloads = downloads,
                    settings = settings,
                    engineState = engineState,
                    miniPlayerState = miniPlayerState,
                    onUrlInputChange = viewModel::onUrlInputChange,
                    onClearUrl = viewModel::clearUrlInput,
                    onAnalyzeUrl = viewModel::analyzeUrl,
                    onBatchDownloadUrls = { urls, audioOnly ->
                        viewModel.startBatchUrlsDownload(urls, audioOnly)
                        scope.launch {
                            snackbarHostState.showSnackbar("${urls.size} bağlantı kuyruğa eklendi")
                        }
                    },
                    onRetryEngineInit = viewModel::retryEngineInit,
                    onViewAllDownloads = { currentDestination = UlgenDestination.DOWNLOADS },
                    onCancelDownload = viewModel::cancel,
                    onRetryDownload = viewModel::retry,
                    onDeleteDownload = viewModel::deleteDownload,
                    onPlayAudio = viewModel::playAudio,
                    modifier = contentModifier
                )
            }

            UlgenDestination.DOWNLOADS -> {
                DownloadsScreen(
                    downloads = downloads,
                    miniPlayerState = miniPlayerState,
                    studioBusy = studioBusy,
                    onCancel = viewModel::cancel,
                    onRetry = viewModel::retry,
                    onDelete = viewModel::deleteDownload,
                    onClearCompleted = viewModel::clearCompleted,
                    onPlayAudio = viewModel::playAudio,
                    onRunFfmpegStudio = viewModel::runFfmpegStudio,
                    modifier = contentModifier
                )
            }

            UlgenDestination.SETTINGS -> {
                SettingsScreen(
                    settings = settings,
                    ytDlpUpdateMessage = ytDlpUpdateMessage,
                    isUpdatingYtDlp = isUpdatingYtDlp,
                    hasCookies = hasCookies,
                    onSetThemeMode = viewModel::setThemeMode,
                    onSetDynamicColor = viewModel::setDynamicColor,
                    onSetDefaultVideoQuality = viewModel::setDefaultVideoQuality,
                    onSetDefaultAudioFormat = viewModel::setDefaultAudioFormat,
                    onSetUseAria2c = viewModel::setUseAria2c,
                    onSetEmbedThumbnail = viewModel::setEmbedThumbnail,
                    onSetAutoDetectClipboard = viewModel::setAutoDetectClipboard,
                    onSetSponsorBlockEnabled = viewModel::setSponsorBlockEnabled,
                    onSetEmbedChapters = viewModel::setEmbedChapters,
                    onSetWifiOnly = viewModel::setWifiOnly,
                    onSetZeroFrictionShareMode = viewModel::setZeroFrictionShareMode,
                    onSetPlatformSubfolders = viewModel::setPlatformSubfolders,
                    onSetFileNameTemplate = viewModel::setFileNameTemplate,
                    onSetCookiesEnabled = viewModel::setCookiesEnabled,
                    onImportCookies = viewModel::importCookies,
                    onClearCookies = viewModel::clearCookies,
                    onUpdateYtDlp = onUpdateYtDlp,
                    modifier = contentModifier
                )
            }
        }
    }

    // Analiz & Kalite Seçim ModalBottomSheet
    if (analyzeState !is AnalyzeUiState.Idle) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissAnalyzeSheet,
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            MediaDownloadSheetContent(
                state = analyzeState,
                settings = settings,
                onOpenDuplicate = { task ->
                    viewModel.dismissAnalyzeSheet()
                    openDownloadedFile(context, task)
                },
                onSelectFormat = { media, option, clipStart, clipEnd, subtitleLang, embedSubtitles ->
                    viewModel.startFormatDownload(
                        media = media,
                        option = option,
                        clipStartSeconds = clipStart,
                        clipEndSeconds = clipEnd,
                        subtitleLang = subtitleLang,
                        embedSubtitles = embedSubtitles
                    )
                    scope.launch {
                        snackbarHostState.showSnackbar("İndirme başlatıldı")
                    }
                },
                onSelectImage = { media, image ->
                    viewModel.startImageDownload(media, image)
                    scope.launch {
                        snackbarHostState.showSnackbar("İndirme başlatıldı")
                    }
                },
                onDownloadAllImages = { media ->
                    viewModel.startAllImagesDownload(media)
                    scope.launch {
                        snackbarHostState.showSnackbar("Tüm görseller kuyruğa eklendi")
                    }
                },
                onDownloadPlaylist = { entries, platform, audioOnly ->
                    viewModel.startPlaylistDownload(entries, platform, audioOnly)
                    scope.launch {
                        snackbarHostState.showSnackbar("${entries.size} öğe kuyruğa eklendi")
                    }
                },
                onRetry = { originalInput ->
                    viewModel.analyzeUrl(originalInput)
                },
                onDismiss = viewModel::dismissAnalyzeSheet
            )
        }
    }
}
