package com.ulgentube.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.ui.components.AppLockOverlay
import com.ulgentube.app.ui.components.InAppMediaViewerOverlay
import com.ulgentube.app.ui.components.MediaDownloadSheetContent
import com.ulgentube.app.ui.components.MiniPlayerBar
import com.ulgentube.app.ui.screens.BrowserScreen
import com.ulgentube.app.ui.screens.DownloadsScreen
import com.ulgentube.app.ui.screens.HomeScreen
import com.ulgentube.app.ui.screens.SettingsScreen
import com.ulgentube.app.ui.screens.openDownloadedFile
import com.ulgentube.app.ui.screens.shareDownloadedFile
import com.ulgentube.app.ui.theme.UlgenTubeTheme
import com.ulgentube.app.ui.viewmodel.AnalyzeUiState
import com.ulgentube.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

private enum class UlgenDestination(
    val label: String,
    val icon: ImageVector
) {
    HOME("Ana Sayfa", Icons.Rounded.Home),
    BROWSER("Tarayıcı", Icons.Rounded.Language),
    DOWNLOADS("İndirmelerim", Icons.Rounded.Download),
    SETTINGS("Ayarlar", Icons.Rounded.Settings)
}

/**
 * UlgenTube ana uygulama aktivitesi.
 * Android Picture-in-Picture (PiP), uygulama kilidi ve tam ekran video overlay katmanını yönetir.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.provideFactory((application as UlgenTubeApp).container)
    }

    private var isInPipModeState by mutableStateOf(false)
    private var hasActiveVideoOverlay: Boolean = false

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
                    isInPipMode = isInPipModeState,
                    onRequestPip = ::enterVideoPipMode,
                    onVideoOverlayChanged = { active -> hasActiveVideoOverlay = active },
                    onUpdateYtDlp = { viewModel.updateYtDlp(applicationContext) }
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (hasActiveVideoOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterVideoPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
    }

    private fun enterVideoPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
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
    isInPipMode: Boolean,
    onRequestPip: () -> Unit,
    onVideoOverlayChanged: (Boolean) -> Unit,
    onUpdateYtDlp: () -> Unit
) {
    val context = LocalContext.current
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val followedChannels by viewModel.followedChannels.collectAsStateWithLifecycle()
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
    val tempCacheBytes by viewModel.tempCacheBytes.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val isVaultUnlocked by viewModel.isVaultUnlocked.collectAsStateWithLifecycle()
    val isFetchingLyrics by viewModel.isFetchingLyrics.collectAsStateWithLifecycle()
    val lyricsOverride by viewModel.lyricsOverride.collectAsStateWithLifecycle()
    val fetchingPoToken by viewModel.fetchingPoToken.collectAsStateWithLifecycle()
    val isCheckingChannels by viewModel.isCheckingChannels.collectAsStateWithLifecycle()

    var currentDestination by rememberSaveable { mutableStateOf(UlgenDestination.HOME) }
    var previewTask by remember { mutableStateOf<DownloadTask?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(previewTask) {
        onVideoOverlayChanged(previewTask?.category == MediaCategory.VIDEO)
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = {
                if (!isInPipMode) {
                    SnackbarHost(hostState = snackbarHostState)
                }
            },
            bottomBar = {
                if (!isInPipMode) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MiniPlayerBar(
                            state = miniPlayerState,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onSeekTo = viewModel::seekTo,
                            onStop = viewModel::stopMiniPlayer,
                            onPlayNext = viewModel::playNextAudio,
                            onPlayPrevious = viewModel::playPreviousAudio,
                            onToggleShuffle = viewModel::toggleAudioShuffle,
                            onCycleRepeatMode = viewModel::cycleAudioRepeatMode,
                            onSelectEqualizerPreset = viewModel::setEqualizerPreset,
                            onFetchLyrics = {
                                miniPlayerState.currentTask?.id?.let(viewModel::fetchLyricsForTask)
                            },
                            isFetchingLyrics = isFetchingLyrics,
                            lyricsOverride = lyricsOverride
                        )

                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 0.dp
                        ) {
                            UlgenDestination.entries.forEach { destination ->
                                val isSelected = currentDestination == destination
                                val iconScale by animateFloatAsState(
                                    targetValue = if (isSelected) 1.05f else 1.0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    ),
                                    label = "nav_icon_scale"
                                )
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = { currentDestination = destination },
                                    icon = {
                                        if (destination == UlgenDestination.DOWNLOADS && activeDownloadCount > 0) {
                                            BadgedBox(
                                                badge = {
                                                    Badge(
                                                        containerColor = MaterialTheme.colorScheme.primary,
                                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                                    ) {
                                                        Text(text = activeDownloadCount.toString())
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = destination.icon,
                                                    contentDescription = destination.label,
                                                    modifier = Modifier.graphicsLayer {
                                                        scaleX = iconScale
                                                        scaleY = iconScale
                                                    }
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = destination.icon,
                                                contentDescription = destination.label,
                                                modifier = Modifier.graphicsLayer {
                                                    scaleX = iconScale
                                                    scaleY = iconScale
                                                }
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
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)

            AnimatedContent(
                targetState = currentDestination,
                transitionSpec = {
                    fadeIn(tween(210)) togetherWith fadeOut(tween(150))
                },
                label = "main_nav_transition"
            ) { destination ->
                when (destination) {
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
                            onPreviewMedia = { task ->
                                viewModel.pauseMiniPlayer()
                                previewTask = task
                            },
                            followedChannels = followedChannels,
                            isCheckingChannels = isCheckingChannels,
                            onFollowChannel = viewModel::followChannel,
                            onUnfollowChannel = viewModel::unfollowChannel,
                            onToggleChannelAutoDownload = viewModel::toggleChannelAutoDownload,
                            onCheckFollowedChannels = viewModel::checkFollowedChannels,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onToggleVault = viewModel::toggleVault,
                            modifier = contentModifier
                        )
                    }

                    UlgenDestination.BROWSER -> {
                        BrowserScreen(
                            onAnalyzeUrl = viewModel::analyzeUrl,
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
                            onPreviewMedia = { task ->
                                viewModel.pauseMiniPlayer()
                                previewTask = task
                            },
                            onRunFfmpegStudio = viewModel::runFfmpegStudio,
                            onRunFfmpegStudioPro = viewModel::runFfmpegStudioPro,
                            onPlayAudioQueue = { tasks -> viewModel.playAudioQueue(tasks, 0) },
                            onToggleFavorite = viewModel::toggleFavorite,
                            onToggleVault = viewModel::toggleVault,
                            onPause = viewModel::pause,
                            onPauseAll = viewModel::pauseAllActive,
                            onResumeAll = viewModel::resumeAllPaused,
                            isVaultUnlocked = isVaultUnlocked,
                            onUnlockVault = viewModel::unlockVault,
                            onLockVault = viewModel::lockVault,
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
                            onSetParallelDownloadEnabled = viewModel::setParallelDownloadEnabled,
                            onSetParallelConnectionCount = viewModel::setParallelConnectionCount,
                            onSetMaxConcurrentDownloads = viewModel::setMaxConcurrentDownloads,
                            onImportCookies = viewModel::importCookies,
                            onSyncWebViewCookies = viewModel::syncWebViewCookies,
                            onClearCookies = viewModel::clearCookies,
                            onUpdateYtDlp = onUpdateYtDlp,
                            tempCacheBytes = tempCacheBytes,
                            isRefreshingPoToken = fetchingPoToken,
                            onSetMaxDownloadSpeedKbps = viewModel::setMaxDownloadSpeedKbps,
                            onSetScheduledDownloadEnabled = viewModel::setScheduledDownloadEnabled,
                            onSetScheduleWindowHours = viewModel::setScheduleWindowHours,
                            onSetPoTokenEnabled = viewModel::setPoTokenEnabled,
                            onRefreshPoToken = viewModel::refreshPoToken,
                            onSetAutoFetchLyrics = viewModel::setAutoFetchLyrics,
                            onSetEqualizerPreset = viewModel::setEqualizerPreset,
                            onSetAppLockEnabled = viewModel::setAppLockEnabled,
                            onClearTempCache = viewModel::clearTempCache,
                            onExportBackup = viewModel::exportBackup,
                            onImportBackup = viewModel::importBackup,
                            modifier = contentModifier
                        )
                    }
                }
            }
        }

        // Analiz & Kalite Seçim ModalBottomSheet
        if (analyzeState !is AnalyzeUiState.Idle && !isInPipMode) {
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
                        if (task.category != MediaCategory.AUDIO) {
                            viewModel.pauseMiniPlayer()
                            previewTask = task
                        } else {
                            openDownloadedFile(context, task)
                        }
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
                    onPreviewStream = { media, streamUrl ->
                        viewModel.dismissAnalyzeSheet()
                        viewModel.pauseMiniPlayer()
                        previewTask = com.ulgentube.app.domain.model.DownloadTask(
                            id = "preview_${System.currentTimeMillis()}",
                            url = media.originalUrl,
                            title = media.title,
                            author = media.author,
                            platform = media.platform,
                            category = MediaCategory.VIDEO,
                            formatLabel = "Canlı Önizleme",
                            fileName = "${media.title}.mp4",
                            thumbnailUrl = media.thumbnailUrl,
                            status = DownloadStatus.COMPLETED,
                            progress = 100f,
                            savedUri = streamUrl
                        )
                    },
                    onRetry = { originalInput ->
                        viewModel.analyzeUrl(originalInput)
                    },
                    onDismiss = viewModel::dismissAnalyzeSheet
                )
            }
        }

        // Root Box üzerinde Dahili Video Oynatıcı & Görsel Görüntüleyici Overlay (PiP uyumlu!)
        previewTask?.let { task ->
            BackHandler(enabled = !isInPipMode) {
                previewTask = null
            }

            val overlayPlaylist = remember(downloads, task.category, task.isInVault) {
                downloads.filter {
                    it.status == DownloadStatus.COMPLETED &&
                        it.category == task.category &&
                        it.isInVault == task.isInVault
                }.ifEmpty { listOf(task) }
            }

            InAppMediaViewerOverlay(
                task = task,
                playlist = overlayPlaylist,
                isInPipMode = isInPipMode,
                initialPositionMs = task.lastPlaybackPositionMs,
                onSavePosition = viewModel::savePlaybackPosition,
                onRequestPip = onRequestPip,
                onSwitchToBackgroundAudio = { targetTask, positionMs ->
                    previewTask = null
                    viewModel.playVideoAsBackgroundAudio(targetTask, positionMs)
                },
                onSelectTask = { selected -> previewTask = selected },
                onDismiss = { previewTask = null },
                onShare = { shareDownloadedFile(context, it) },
                onOpenExternal = { openDownloadedFile(context, it) },
                onRequestQualityChange = { qualityTask ->
                    previewTask = null
                    viewModel.analyzeUrl(qualityTask.url)
                }
            )
        }

        // Tam Ekran Uygulama Kilidi Katmanı
        if (settings.appLockEnabled && !isAppUnlocked && !isInPipMode) {
            AppLockOverlay(
                title = "UlgenTube Kilitli",
                onUnlocked = viewModel::unlockApp
            )
        }
    }
}
