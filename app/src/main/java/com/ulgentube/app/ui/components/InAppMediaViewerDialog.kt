package com.ulgentube.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.TextureView
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.player.SubtitleCue
import com.ulgentube.app.player.SubtitleParser
import com.ulgentube.app.player.SubtitleTrackItem
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.util.FormatUtils
import com.ulgentube.app.util.MediaUriHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private const val DOUBLE_TAP_TIMEOUT_MS = 275L
private const val SEEK_BURST_WINDOW_MS = 900L
private const val LONG_PRESS_2X_DELAY_MS = 300L

private enum class TapZoneSide { LEFT, RIGHT }

private enum class PlayerGestureMode {
    UNDECIDED,
    HOLD_2X,
    BRIGHTNESS,
    VOLUME,
    PINCH,
    IGNORED
}

private data class SeekOverlayState(
    val side: TapZoneSide,
    val totalSeconds: Int,
    val nonce: Int
)

private data class AdjustHudState(
    val icon: ImageVector,
    val label: String,
    val progress: Float,
    val nonce: Int
)

private enum class VideoScaleMode(val label: String) {
    FIT("Sığdır"),
    FILL("Doldur"),
    ZOOM("Yakınlaştır")
}

private data class VideoQualityTrackOption(
    val id: String,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val bitrate: Int,
    val codecLabel: String,
    val qualityLabel: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean
)

private data class ActiveVideoQualityInfo(
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val codecLabel: String = "",
    val bitrate: Int = 0
) {
    val shortSidePx: Int
        get() = if (width > 0 && height > 0) minOf(width, height) else 0

    val shortResolutionLabel: String
        get() = formatResolutionTier(shortSidePx)

    val badgeLabel: String
        get() = if (shortSidePx > 0) shortResolutionLabel else "Kalite"

    val detailedSummary: String
        get() {
            if (width <= 0 || height <= 0) return ""
            val meta = buildList {
                add("${width}×${height}")
                if (frameRate > 0.5f) {
                    add("${frameRate.roundToInt()} fps")
                }
                if (codecLabel.isNotBlank()) {
                    add(codecLabel)
                }
            }
            return "$shortResolutionLabel (${meta.joinToString(" • ")})"
        }
}

private data class VideoTracksSnapshot(
    val activeInfo: ActiveVideoQualityInfo?,
    val options: List<VideoQualityTrackOption>
)

private fun formatResolutionTier(shortSidePx: Int): String = when {
    shortSidePx >= 2160 -> "4K"
    shortSidePx >= 1440 -> "1440p"
    shortSidePx >= 1080 -> "1080p"
    shortSidePx >= 720 -> "720p"
    shortSidePx >= 480 -> "480p"
    shortSidePx >= 360 -> "360p"
    shortSidePx >= 240 -> "240p"
    shortSidePx > 0 -> "${shortSidePx}p"
    else -> "HD"
}

private fun formatCodecLabel(mimeType: String?, codecs: String?): String {
    val c = codecs?.lowercase().orEmpty()
    val m = mimeType?.lowercase().orEmpty()
    return when {
        c.startsWith("av01") || m.contains("av01") -> "AV1"
        c.startsWith("vp09") || c.startsWith("vp9") || m.contains("vp9") -> "VP9"
        c.startsWith("vp08") || c.startsWith("vp8") || m.contains("vp8") -> "VP8"
        c.startsWith("hvc1") || c.startsWith("hev1") || m.contains("hevc") -> "HEVC"
        c.startsWith("avc1") || c.startsWith("avc3") || m.contains("avc") -> "H.264"
        else -> ""
    }
}

private fun extractVideoTracksSnapshot(tracks: Tracks): VideoTracksSnapshot {
    var activeInfo: ActiveVideoQualityInfo? = null
    val rawOptions = mutableListOf<VideoQualityTrackOption>()

    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            if (!group.isTrackSupported(trackIndex)) continue
            val format = group.getTrackFormat(trackIndex)
            val width = format.width.coerceAtLeast(0)
            val height = format.height.coerceAtLeast(0)
            val fps = if (format.frameRate > 0f && format.frameRate.isFinite()) format.frameRate else 0f
            val bitrate = format.bitrate.coerceAtLeast(0)
            val codec = formatCodecLabel(format.sampleMimeType, format.codecs)
            val selected = group.isTrackSelected(trackIndex)

            if (selected && (width > 0 && height > 0 || fps > 0f || codec.isNotBlank())) {
                activeInfo = ActiveVideoQualityInfo(
                    width = width,
                    height = height,
                    frameRate = fps,
                    codecLabel = codec,
                    bitrate = bitrate
                )
            }

            if (width > 0 && height > 0) {
                val shortSide = minOf(width, height)
                val tier = formatResolutionTier(shortSide)
                val fpsRounded = if (fps > 0.5f) fps.roundToInt() else 0
                rawOptions += VideoQualityTrackOption(
                    id = "${groupIndex}_${trackIndex}_${width}x${height}_${fpsRounded}",
                    width = width,
                    height = height,
                    frameRate = fps,
                    bitrate = bitrate,
                    codecLabel = codec,
                    qualityLabel = tier,
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    isSelected = selected
                )
            }
        }
    }

    // Aynı çözünürlük etiketi ve FPS'e sahip tekrarları en yüksek bit hızı lehine tekilleştir
    val deduped = rawOptions
        .groupBy { opt ->
            val fpsKey = if (opt.frameRate >= 48f) "60" else "std"
            "${opt.qualityLabel}_$fpsKey"
        }
        .values
        .map { bucket ->
            bucket.firstOrNull { it.isSelected }
                ?: bucket.maxBy { it.bitrate }
        }
        .sortedWith(
            compareByDescending<VideoQualityTrackOption> { minOf(it.width, it.height) }
                .thenByDescending { it.frameRate }
                .thenByDescending { it.bitrate }
        )

    return VideoTracksSnapshot(
        activeInfo = activeInfo,
        options = deduped
    )
}

/**
 * Bağlam zinciri üzerinden barındırıcı [Activity] örneğini bulur.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Diyalog penceresini [DialogWindowProvider] üzerinden güvenli biçimde çözer.
 */
private fun resolveDialogWindow(view: View): Window? {
    var parent = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}

/**
 * Diyalog penceresi için uçtan uca (edge-to-edge) şeffaf sistem barları ve çentik modunu uygular.
 */
@Suppress("DEPRECATION")
private fun configureEdgeToEdgeDialogWindow(window: Window, view: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val params = window.attributes
        if (params.layoutInDisplayCutoutMode !=
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        ) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }
    }
    WindowInsetsControllerCompat(window, view).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}

/**
 * Sistem ekran parlaklığını `0f..1f` aralığında okur.
 */
private fun readCurrentBrightness(context: Context, dialogWindow: Window?): Float {
    val windowBrightness = dialogWindow?.attributes?.screenBrightness ?: -1f
    if (windowBrightness in 0f..1f) return windowBrightness
    return runCatching {
        val sysValue = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS
        )
        (sysValue / 255f).coerceIn(0.05f, 1f)
    }.getOrDefault(0.5f)
}

/**
 * Hız değerini okunabilir etikete dönüştürür (ör. `1x`, `1.25x`).
 */
private fun formatSpeedLabel(speed: Float): String {
    return if (speed % 1f == 0f) {
        "${speed.toInt()}x"
    } else {
        "${speed}x"
    }
}

/**
 * Saniyeyi en az `00:00` biçiminde formatlar.
 */
private fun formatTimeText(seconds: Long): String {
    return FormatUtils.formatDuration(seconds.coerceAtLeast(0L)).ifBlank { "00:00" }
}

/**
 * Android Picture-in-Picture (PiP) pencere içinde de kusursuz çalışabilen,
 * `Dialog` bağımlılığı olmayan tam ekran medya oynatıcı / görüntüleyici katmanı.
 */
@Composable
fun InAppMediaViewerOverlay(
    task: DownloadTask,
    playlist: List<DownloadTask> = listOf(task),
    isInPipMode: Boolean = false,
    initialPositionMs: Long = task.lastPlaybackPositionMs,
    onSavePosition: (String, Long) -> Unit = { _, _ -> },
    onRequestPip: () -> Unit = {},
    onSwitchToBackgroundAudio: (DownloadTask, Long) -> Unit = { _, _ -> },
    onSelectTask: (DownloadTask) -> Unit = {},
    onDismiss: () -> Unit,
    onShare: (DownloadTask) -> Unit,
    onOpenExternal: (DownloadTask) -> Unit,
    onRequestQualityChange: ((DownloadTask) -> Unit)? = null,
    onQuickTranscodeQuality: ((DownloadTask, Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val targetWindow = remember(view, activity) { resolveDialogWindow(view) ?: activity?.window }
    val resolvedUri = remember(task.id, task.savedUri, task.savedPath) {
        MediaUriHelper.resolveTaskUri(context, task)
    }

    SideEffect {
        if (!isInPipMode) {
            targetWindow?.let { configureEdgeToEdgeDialogWindow(it, view) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (resolvedUri == null) {
            ViewerErrorContent(
                task = task,
                message = "Medya dosyasına erişilemedi veya dosya cihazdan taşınmış olabilir.",
                onOpenExternal = { onOpenExternal(task) },
                onDismiss = onDismiss
            )
        } else {
            when (task.category) {
                MediaCategory.VIDEO -> {
                    InAppVideoPlayerContent(
                        task = task,
                        uri = resolvedUri,
                        playlist = playlist,
                        isInPipMode = isInPipMode,
                        initialPositionMs = initialPositionMs,
                        dialogWindow = targetWindow,
                        view = view,
                        onSavePosition = onSavePosition,
                        onRequestPip = onRequestPip,
                        onSwitchToBackgroundAudio = onSwitchToBackgroundAudio,
                        onSelectTask = onSelectTask,
                        onOpenExternal = { onOpenExternal(task) },
                        onShare = { onShare(task) },
                        onDismiss = onDismiss,
                        onRequestQualityChange = onRequestQualityChange,
                        onQuickTranscodeQuality = onQuickTranscodeQuality
                    )
                }

                MediaCategory.IMAGE -> {
                    InAppImageViewerContent(
                        task = task,
                        uri = resolvedUri,
                        onOpenExternal = { onOpenExternal(task) },
                        onShare = { onShare(task) },
                        onDismiss = onDismiss
                    )
                }

                MediaCategory.AUDIO -> {
                    ViewerErrorContent(
                        task = task,
                        message = "Ses dosyalarını mini oynatıcı veya harici uygulama üzerinden açabilirsiniz.",
                        onOpenExternal = { onOpenExternal(task) },
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

/**
 * İndirilen video ve görselleri uygulama içinde oynatan/görüntüleyen tam ekran diyalog.
 * Geriye dönük uyumluluk için [InAppMediaViewerOverlay] bileşenini saran ince bir [Dialog] sarmalayıcısıdır.
 */
@Composable
fun InAppMediaViewerDialog(
    task: DownloadTask,
    onOpenExternal: (DownloadTask) -> Unit,
    onShare: (DownloadTask) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        InAppMediaViewerOverlay(
            task = task,
            onDismiss = onDismiss,
            onShare = onShare,
            onOpenExternal = onOpenExternal
        )
    }
}

/**
 * YouTube mobil oynatıcı deneyimine sahip tam ekran dahili ExoPlayer video oynatıcısı.
 */
@Composable
private fun InAppVideoPlayerContent(
    task: DownloadTask,
    uri: Uri,
    playlist: List<DownloadTask>,
    isInPipMode: Boolean,
    initialPositionMs: Long,
    dialogWindow: Window?,
    view: View,
    onSavePosition: (String, Long) -> Unit,
    onRequestPip: () -> Unit,
    onSwitchToBackgroundAudio: (DownloadTask, Long) -> Unit,
    onSelectTask: (DownloadTask) -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    onRequestQualityChange: ((DownloadTask) -> Unit)? = null,
    onQuickTranscodeQuality: ((DownloadTask, Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val latestIsInPipMode by rememberUpdatedState(isInPipMode)
    val latestOnSavePosition by rememberUpdatedState(onSavePosition)

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            if (initialPositionMs > 2_000L) {
                seekTo(initialPositionMs)
            }
            playWhenReady = true
        }
    }

    var textureViewRef by remember(uri) { mutableStateOf<TextureView?>(null) }
    var videoAspectRatio by remember(uri) { mutableFloatStateOf(16f / 9f) }
    var isPlaying by remember(uri) { mutableStateOf(true) }
    var isBuffering by remember(uri) { mutableStateOf(true) }
    var isEnded by remember(uri) { mutableStateOf(false) }
    var hasError by remember(uri) { mutableStateOf(false) }
    var currentPositionMs by remember(uri) {
        mutableLongStateOf(if (initialPositionMs > 2_000L) initialPositionMs else 0L)
    }
    var durationMs by remember(uri) { mutableLongStateOf(0L) }

    var isMuted by remember(uri) { mutableStateOf(false) }
    var loopEnabled by remember(uri) { mutableStateOf(false) }
    var selectedSpeed by remember(uri) { mutableFloatStateOf(1f) }
    var isHolding2x by remember(uri) { mutableStateOf(false) }
    var showSpeedMenu by remember(uri) { mutableStateOf(false) }

    // Canlı video kalitesi ve ExoPlayer video track durumları
    var activeQualityInfo by remember(uri) { mutableStateOf(ActiveVideoQualityInfo()) }
    var videoQualityTracks by remember(uri) { mutableStateOf<List<VideoQualityTrackOption>>(emptyList()) }
    var selectedTrackQualityId by remember(uri) { mutableStateOf<String?>(null) }
    var showQualityMenu by remember(uri) { mutableStateOf(false) }

    // Dahili altyazı (.srt / .vtt) durumları
    var availableSubtitles by remember(task.id) { mutableStateOf<List<SubtitleTrackItem>>(emptyList()) }
    var selectedSubtitleUri by remember(task.id) { mutableStateOf<Uri?>(null) }
    var selectedSubtitleLabel by remember(task.id) { mutableStateOf<String?>(null) }
    var subtitleCues by remember(task.id) { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var showSubtitleMenu by remember(task.id) { mutableStateOf(false) }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri ->
        if (pickedUri != null) {
            selectedSubtitleUri = pickedUri
            selectedSubtitleLabel = pickedUri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }
                ?: "Harici Altyazı"
            showSubtitleMenu = false
        }
    }

    LaunchedEffect(task.id) {
        availableSubtitles = SubtitleParser.findMatchingSubtitleUris(context, task)
    }

    LaunchedEffect(selectedSubtitleUri) {
        val targetUri = selectedSubtitleUri
        if (targetUri == null) {
            subtitleCues = emptyList()
        } else {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(targetUri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?.let { SubtitleParser.parse(it) }
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
            subtitleCues = parsed
        }
    }

    val activeSubtitleText = remember(subtitleCues, currentPositionMs) {
        SubtitleParser.activeCueAt(subtitleCues, currentPositionMs)
    }

    var isFillScreen by remember(uri) { mutableStateOf(false) }
    var zoomScale by remember(uri) { mutableFloatStateOf(1f) }
    var panOffset by remember(uri) { mutableStateOf(Offset.Zero) }
    var fitBannerText by remember(uri) { mutableStateOf<String?>(null) }
    var fitBannerNonce by remember(uri) { mutableIntStateOf(0) }

    var controlsVisible by remember(uri) { mutableStateOf(true) }
    var isLocked by remember(uri) { mutableStateOf(false) }
    var lockBannerVisible by remember(uri) { mutableStateOf(false) }
    var lockBannerNonce by remember(uri) { mutableIntStateOf(0) }

    var interactionNonce by remember(uri) { mutableIntStateOf(0) }
    var draggingFraction by remember(uri) { mutableStateOf<Float?>(null) }

    var seekOverlay by remember(uri) { mutableStateOf<SeekOverlayState?>(null) }
    var adjustHud by remember(uri) { mutableStateOf<AdjustHudState?>(null) }

    // Diyalog penceresi ve Activity yönelim yaşam döngüsü yönetimi
    DisposableEffect(dialogWindow, view, activity) {
        if (!latestIsInPipMode) {
            dialogWindow?.let { configureEdgeToEdgeDialogWindow(it, view) }
        }
        onDispose {
            dialogWindow?.let { win ->
                win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                win.attributes = win.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                WindowInsetsControllerCompat(win, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Video oynatılırken ekranı açık tut
    DisposableEffect(dialogWindow, isPlaying) {
        if (dialogWindow != null) {
            if (isPlaying) {
                dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Immersive sistem bar kontrolü (PiP modunda devre dışı)
    LaunchedEffect(dialogWindow, view, isLandscape, controlsVisible, isLocked, isInPipMode) {
        if (isInPipMode) return@LaunchedEffect
        val window = dialogWindow ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, view)
        if (isLandscape || !controlsVisible || isLocked) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ExoPlayer ve Lifecycle dinleyicileri
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) /
                        videoSize.height.toFloat()
                    if (ratio.isFinite() && ratio > 0f) {
                        videoAspectRatio = ratio
                    }
                    activeQualityInfo = activeQualityInfo.copy(
                        width = videoSize.width,
                        height = videoSize.height
                    )
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val snapshot = extractVideoTracksSnapshot(tracks)
                videoQualityTracks = snapshot.options
                snapshot.activeInfo?.let { info ->
                    activeQualityInfo = ActiveVideoQualityInfo(
                        width = if (info.width > 0) info.width else activeQualityInfo.width,
                        height = if (info.height > 0) info.height else activeQualityInfo.height,
                        frameRate = if (info.frameRate > 0f) info.frameRate else activeQualityInfo.frameRate,
                        codecLabel = info.codecLabel.ifBlank { activeQualityInfo.codecLabel },
                        bitrate = if (info.bitrate > 0) info.bitrate else activeQualityInfo.bitrate
                    )
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                currentPositionMs = pos
                if (!playing) {
                    latestOnSavePosition(task.id, pos)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                isEnded = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_ENDED) {
                    controlsVisible = true
                    latestOnSavePosition(task.id, 0L)
                }
                val rawDur = exoPlayer.duration
                if (rawDur > 0L && rawDur != C.TIME_UNSET) {
                    durationMs = rawDur
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                hasError = true
                isBuffering = false
                controlsVisible = true
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (!latestIsInPipMode) {
                        latestOnSavePosition(task.id, exoPlayer.currentPosition.coerceAtLeast(0L))
                        exoPlayer.pause()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    latestOnSavePosition(task.id, exoPlayer.currentPosition.coerceAtLeast(0L))
                    exoPlayer.pause()
                }
                else -> Unit
            }
        }

        exoPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        // Listener bağlanmadan önce hazır olan track / boyut bilgisini anında senkronize et
        runCatching {
            val initialSize = exoPlayer.videoSize
            if (initialSize.width > 0 && initialSize.height > 0) {
                listener.onVideoSizeChanged(initialSize)
            }
            val initialTracks = exoPlayer.currentTracks
            if (!initialTracks.isEmpty) {
                listener.onTracksChanged(initialTracks)
            }
        }

        onDispose {
            val finalPos = if (exoPlayer.playbackState == Player.STATE_ENDED) {
                0L
            } else {
                exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            latestOnSavePosition(task.id, finalPos)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(listener)
            textureViewRef?.let { exoPlayer.clearVideoTextureView(it) }
            textureViewRef = null
            exoPlayer.release()
        }
    }

    // Oynatma hızını (normal veya 2x basılı tutma) ve döngü modunu senkronize et
    LaunchedEffect(exoPlayer, selectedSpeed, isHolding2x) {
        exoPlayer.setPlaybackSpeed(if (isHolding2x) 2f else selectedSpeed)
    }

    LaunchedEffect(exoPlayer, loopEnabled) {
        exoPlayer.repeatMode = if (loopEnabled) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    // Konum / süre takip döngüsü
    LaunchedEffect(exoPlayer, isPlaying) {
        while (isActive) {
            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val rawDur = exoPlayer.duration
            if (rawDur > 0L && rawDur != C.TIME_UNSET) {
                durationMs = rawDur
            }
            delay(if (isPlaying) 200L else 450L)
        }
    }

    // Kontrolleri otomatik gizleme sayacı
    LaunchedEffect(
        controlsVisible,
        isPlaying,
        interactionNonce,
        draggingFraction,
        showSpeedMenu,
        showSubtitleMenu,
        showQualityMenu,
        isLocked,
        isInPipMode
    ) {
        if (controlsVisible &&
            isPlaying &&
            draggingFraction == null &&
            !showSpeedMenu &&
            !showSubtitleMenu &&
            !showQualityMenu &&
            !isLocked &&
            !isInPipMode
        ) {
            delay(3_500L)
            controlsVisible = false
        }
    }

    // Kilit uyarı kapsülü zamanlayıcısı (2.5 sn)
    LaunchedEffect(lockBannerVisible, lockBannerNonce) {
        if (lockBannerVisible) {
            delay(2_500L)
            lockBannerVisible = false
        }
    }

    // Ekranı Doldur / Orijinal Boyut kapsülü zamanlayıcısı (1.2 sn)
    LaunchedEffect(fitBannerNonce) {
        if (fitBannerText != null) {
            delay(1_200L)
            fitBannerText = null
        }
    }

    // Çift tık sarma görsel efekti zamanlayıcısı
    LaunchedEffect(seekOverlay?.nonce) {
        if (seekOverlay != null) {
            delay(SEEK_BURST_WINDOW_MS)
            seekOverlay = null
        }
    }

    // Parlaklık / Ses HUD zamanlayıcısı
    LaunchedEffect(adjustHud?.nonce) {
        if (adjustHud != null) {
            delay(950L)
            adjustHud = null
        }
    }

    fun seekByDeltaMs(deltaMs: Long) {
        val rawTarget = exoPlayer.currentPosition + deltaMs
        val target = if (durationMs > 0L) {
            rawTarget.coerceIn(0L, durationMs)
        } else {
            rawTarget.coerceAtLeast(0L)
        }
        exoPlayer.seekTo(target)
        currentPositionMs = target
        interactionNonce++
    }

    fun toggleFullscreen() {
        val targetOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        activity?.requestedOrientation = targetOrientation
        interactionNonce++
    }

    val safeAspectRatio = videoAspectRatio.coerceIn(0.45f, 2.5f)
    val subtitle = remember(task.platform, task.formatLabel) {
        "${task.platform.displayName} • ${task.formatLabel}"
    }

    // Alt barın asla sistem gezinti çubuğunun arkasında kalmaması için kesin alt boşluk
    val density = LocalDensity.current
    val safeNavInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout).asPaddingValues()
    val rootBottomInsetDp = remember(view, activity, density, configuration) {
        val targetView = activity?.window?.decorView ?: view
        val rootInsets = ViewCompat.getRootWindowInsets(targetView)
        val bottomPx = rootInsets?.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
        )?.bottom ?: 0
        with(density) { bottomPx.toDp() }
    }
    val safeBottomPadding = safeNavInsets.calculateBottomPadding()
        .coerceAtLeast(rootBottomInsetDp) + 24.dp
    val safeStartPadding = safeNavInsets.calculateStartPadding(layoutDirection)
    val safeEndPadding = safeNavInsets.calculateEndPadding(layoutDirection)

    // Güncel durum referansları (tekil pointerInput yeniden başlamadan okunur)
    val latestIsLocked by rememberUpdatedState(isLocked)
    val latestShowSpeedMenu by rememberUpdatedState(showSpeedMenu)
    val latestShowSubtitleMenu by rememberUpdatedState(showSubtitleMenu)
    val latestShowQualityMenu by rememberUpdatedState(showQualityMenu)

    val currentPlaylistIndex = remember(playlist, task.id) {
        playlist.indexOfFirst { it.id == task.id }
    }
    val previousPlaylistTask = if (playlist.size > 1 && currentPlaylistIndex > 0) {
        playlist[currentPlaylistIndex - 1]
    } else {
        null
    }
    val nextPlaylistTask = if (playlist.size > 1 && currentPlaylistIndex in 0 until playlist.lastIndex) {
        playlist[currentPlaylistIndex + 1]
    } else {
        null
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val containerAspect = if (maxHeight.value > 0f) {
            maxWidth.value / maxHeight.value
        } else {
            16f / 9f
        }

        val fillMultiplier = remember(containerAspect, safeAspectRatio) {
            val ratio = if (containerAspect > safeAspectRatio) {
                containerAspect / safeAspectRatio
            } else {
                safeAspectRatio / containerAspect
            }
            ratio.coerceAtLeast(1f)
        }

        val animatedFillScale by animateFloatAsState(
            targetValue = if (isFillScreen) fillMultiplier else 1f,
            animationSpec = tween(durationMillis = 220),
            label = "video_fill_scale"
        )
        val effectiveScale = animatedFillScale * zoomScale

        val videoModifier = if (containerAspect > safeAspectRatio) {
            Modifier
                .fillMaxHeight()
                .aspectRatio(safeAspectRatio)
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(safeAspectRatio)
        }

        // Video yüzeyi konteyneri
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        textureViewRef = tv
                        exoPlayer.setVideoTextureView(tv)
                    }
                },
                modifier = videoModifier.graphicsLayer {
                    scaleX = effectiveScale
                    scaleY = effectiveScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
            )
        }

        // Birleşik çakışmasız jest algılama katmanı (tek/çift tık, basılı tut 2x, parlaklık/ses, pinch zoom)
        if (!hasError && !isInPipMode) {
            val pendingTapJobRef = remember { mutableStateOf<Job?>(null) }
            val lastTapTimeRef = remember { mutableLongStateOf(0L) }
            val lastTapSideRef = remember { mutableStateOf<TapZoneSide?>(null) }
            val burstSideRef = remember { mutableStateOf<TapZoneSide?>(null) }
            val burstUntilMsRef = remember { mutableLongStateOf(0L) }
            val burstAccumulatedSecRef = remember { mutableIntStateOf(0) }
            val seekNonceRef = remember { mutableIntStateOf(0) }
            val hudNonceRef = remember { mutableIntStateOf(0) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uri, dialogWindow) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            if (latestShowSpeedMenu || latestShowSubtitleMenu || latestShowQualityMenu) {
                                // Hız, altyazı veya kalite menüsü açıksa dış tıklama overlay'i tarafından kapatılır
                                return@awaitEachGesture
                            }

                            val startPos = firstDown.position
                            var mode = PlayerGestureMode.UNDECIDED
                            var hadMultiTouch = false
                            var movedPastSlop = false
                            var prevSpan = 0f
                            var prevCentroid = Offset.Zero
                            var currentBrightness = readCurrentBrightness(context, dialogWindow)
                            val maxVol = audioManager
                                ?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                ?.coerceAtLeast(1) ?: 15
                            var currentVolFloat = (audioManager
                                ?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat()

                            val hold2xJob = coroutineScope.launch {
                                delay(LONG_PRESS_2X_DELAY_MS)
                                if (mode == PlayerGestureMode.UNDECIDED &&
                                    !hadMultiTouch &&
                                    !movedPastSlop &&
                                    !latestIsLocked
                                ) {
                                    mode = PlayerGestureMode.HOLD_2X
                                    isHolding2x = true
                                    exoPlayer.setPlaybackSpeed(2f)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break

                                    if (pressed.size >= 2) {
                                        hold2xJob.cancel()
                                        if (isHolding2x) {
                                            isHolding2x = false
                                            exoPlayer.setPlaybackSpeed(selectedSpeed)
                                        }
                                        hadMultiTouch = true

                                        if (!latestIsLocked) {
                                            val p1 = pressed[0].position
                                            val p2 = pressed[1].position
                                            val span = (p1 - p2).getDistance()
                                            val centroid = (p1 + p2) / 2f

                                            if (mode != PlayerGestureMode.PINCH) {
                                                mode = PlayerGestureMode.PINCH
                                                prevSpan = span
                                                prevCentroid = centroid
                                            } else {
                                                if (prevSpan > 1f && span > 1f) {
                                                    val nextZoom = (zoomScale * (span / prevSpan))
                                                        .coerceIn(1f, 3f)
                                                    zoomScale = nextZoom
                                                    if (nextZoom > 1.02f) {
                                                        val deltaPan = centroid - prevCentroid
                                                        val maxX = ((containerWidthPx * (nextZoom - 1f)) / 2f)
                                                            .coerceAtLeast(0f)
                                                        val maxY = ((containerHeightPx * (nextZoom - 1f)) / 2f)
                                                            .coerceAtLeast(0f)
                                                        panOffset = Offset(
                                                            x = (panOffset.x + deltaPan.x).coerceIn(-maxX, maxX),
                                                            y = (panOffset.y + deltaPan.y).coerceIn(-maxY, maxY)
                                                        )
                                                    } else {
                                                        panOffset = Offset.Zero
                                                    }
                                                }
                                                prevSpan = span
                                                prevCentroid = centroid
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } else {
                                        if (hadMultiTouch) {
                                            event.changes.forEach { it.consume() }
                                            continue
                                        }

                                        val change = pressed.first()
                                        val dx = change.position.x - startPos.x
                                        val dy = change.position.y - startPos.y
                                        if (hypot(dx, dy) > touchSlop) {
                                            movedPastSlop = true
                                        }

                                        when (mode) {
                                            PlayerGestureMode.UNDECIDED -> {
                                                if (movedPastSlop) {
                                                    hold2xJob.cancel()
                                                    if (!latestIsLocked && abs(dy) > abs(dx) * 1.15f) {
                                                        mode = if (startPos.x < size.width * 0.5f) {
                                                            currentBrightness = readCurrentBrightness(
                                                                context,
                                                                dialogWindow
                                                            )
                                                            PlayerGestureMode.BRIGHTNESS
                                                        } else {
                                                            currentVolFloat = (audioManager
                                                                ?.getStreamVolume(AudioManager.STREAM_MUSIC)
                                                                ?: 0).toFloat()
                                                            PlayerGestureMode.VOLUME
                                                        }
                                                        change.consume()
                                                    } else {
                                                        mode = PlayerGestureMode.IGNORED
                                                    }
                                                }
                                            }

                                            PlayerGestureMode.HOLD_2X -> {
                                                change.consume()
                                            }

                                            PlayerGestureMode.BRIGHTNESS -> {
                                                val stepDy = change.position.y - change.previousPosition.y
                                                val normDelta = -stepDy / (size.height.coerceAtLeast(1) * 0.65f)
                                                currentBrightness = (currentBrightness + normDelta)
                                                    .coerceIn(0.01f, 1f)
                                                dialogWindow?.let { win ->
                                                    win.attributes = win.attributes.apply {
                                                        screenBrightness = currentBrightness
                                                    }
                                                }
                                                val pct = (currentBrightness * 100f).roundToInt()
                                                    .coerceIn(1, 100)
                                                hudNonceRef.intValue++
                                                adjustHud = AdjustHudState(
                                                    icon = Icons.Rounded.BrightnessHigh,
                                                    label = "%$pct",
                                                    progress = currentBrightness,
                                                    nonce = hudNonceRef.intValue
                                                )
                                                change.consume()
                                            }

                                            PlayerGestureMode.VOLUME -> {
                                                val stepDy = change.position.y - change.previousPosition.y
                                                val volDelta = (-stepDy / (size.height.coerceAtLeast(1) * 0.65f)) * maxVol
                                                currentVolFloat = (currentVolFloat + volDelta)
                                                    .coerceIn(0f, maxVol.toFloat())
                                                val newVol = currentVolFloat.roundToInt().coerceIn(0, maxVol)
                                                audioManager?.setStreamVolume(
                                                    AudioManager.STREAM_MUSIC,
                                                    newVol,
                                                    0
                                                )
                                                if (newVol > 0 && isMuted) {
                                                    isMuted = false
                                                    exoPlayer.volume = 1f
                                                }
                                                val ratio = newVol.toFloat() / maxVol.toFloat()
                                                val pct = (ratio * 100f).roundToInt().coerceIn(0, 100)
                                                hudNonceRef.intValue++
                                                adjustHud = AdjustHudState(
                                                    icon = if (newVol == 0) {
                                                        Icons.AutoMirrored.Rounded.VolumeOff
                                                    } else {
                                                        Icons.AutoMirrored.Rounded.VolumeUp
                                                    },
                                                    label = "%$pct",
                                                    progress = ratio,
                                                    nonce = hudNonceRef.intValue
                                                )
                                                change.consume()
                                            }

                                            PlayerGestureMode.PINCH,
                                            PlayerGestureMode.IGNORED -> Unit
                                        }
                                    }
                                }
                            } finally {
                                hold2xJob.cancel()
                                if (isHolding2x) {
                                    isHolding2x = false
                                    exoPlayer.setPlaybackSpeed(selectedSpeed)
                                }
                            }

                            // Temiz tek/çift dokunma değerlendirmesi
                            if (mode == PlayerGestureMode.UNDECIDED &&
                                !hadMultiTouch &&
                                !movedPastSlop
                            ) {
                                if (latestIsLocked) {
                                    lockBannerVisible = true
                                    lockBannerNonce++
                                    return@awaitEachGesture
                                }

                                val widthF = size.width.toFloat().coerceAtLeast(1f)
                                val tapSide = when {
                                    startPos.x < widthF * 0.40f -> TapZoneSide.LEFT
                                    startPos.x > widthF * 0.60f -> TapZoneSide.RIGHT
                                    else -> null
                                }

                                if (tapSide == null) {
                                    // Orta %20 alanda beklemeden kontrolleri aç/kapat
                                    pendingTapJobRef.value?.cancel()
                                    pendingTapJobRef.value = null
                                    lastTapSideRef.value = null
                                    controlsVisible = !controlsVisible
                                    interactionNonce++
                                } else {
                                    val now = System.currentTimeMillis()
                                    val isBurstActive = burstSideRef.value == tapSide &&
                                        now <= burstUntilMsRef.value
                                    val isDoubleTap = lastTapSideRef.value == tapSide &&
                                        (now - lastTapTimeRef.longValue) <= DOUBLE_TAP_TIMEOUT_MS

                                    if (isBurstActive || isDoubleTap) {
                                        pendingTapJobRef.value?.cancel()
                                        pendingTapJobRef.value = null
                                        lastTapSideRef.value = null

                                        val deltaSec = if (tapSide == TapZoneSide.LEFT) -10 else 10
                                        val nextTotal = if (isBurstActive) {
                                            burstAccumulatedSecRef.intValue + deltaSec
                                        } else {
                                            deltaSec
                                        }

                                        burstSideRef.value = tapSide
                                        burstUntilMsRef.value = now + SEEK_BURST_WINDOW_MS
                                        burstAccumulatedSecRef.intValue = nextTotal
                                        seekNonceRef.intValue++

                                        seekOverlay = SeekOverlayState(
                                            side = tapSide,
                                            totalSeconds = nextTotal,
                                            nonce = seekNonceRef.intValue
                                        )
                                        seekByDeltaMs(deltaSec * 1_000L)
                                    } else {
                                        lastTapTimeRef.longValue = now
                                        lastTapSideRef.value = tapSide
                                        pendingTapJobRef.value?.cancel()
                                        pendingTapJobRef.value = coroutineScope.launch {
                                            delay(DOUBLE_TAP_TIMEOUT_MS)
                                            lastTapSideRef.value = null
                                            controlsVisible = !controlsVisible
                                            interactionNonce++
                                        }
                                    }
                                }
                            }
                        }
                    }
            )
        }

        // Çift tık -10 / +10 sn oval geri bildirim efektleri (Sol / Sağ)
        if (!isInPipMode) {
            DoubleTapSeekOverlay(
                state = seekOverlay,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Yüklenme göstergesi
        if (isBuffering && !hasError) {
            CircularProgressIndicator(
                color = CoralPink,
                strokeWidth = 3.dp,
                modifier = Modifier.size(if (isInPipMode) 28.dp else 44.dp)
            )
        }

        // Hata görünümü
        if (hasError) {
            ViewerErrorContent(
                task = task,
                message = "Bu video formatı dahili oynatıcıda açılamadı.",
                onOpenExternal = {
                    exoPlayer.pause()
                    onOpenExternal()
                },
                onDismiss = onDismiss
            )
            return@BoxWithConstraints
        }

        // Canlı Dahili Altyazı Kutusu (.srt / .vtt) — hem normal hem PiP modunda görünür
        if (!activeSubtitleText.isNullOrBlank()) {
            val subtitleBottomPadding = when {
                isInPipMode -> 8.dp
                controlsVisible && !isLocked -> safeBottomPadding + 92.dp
                else -> safeBottomPadding + 14.dp
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp + safeStartPadding,
                        end = 16.dp + safeEndPadding,
                        bottom = subtitleBottomPadding
                    )
                    .zIndex(3f)
            ) {
                Surface(
                    shape = RoundedCornerShape(if (isInPipMode) 6.dp else 10.dp),
                    color = Color.Black.copy(alpha = 0.74f)
                ) {
                    Text(
                        text = activeSubtitleText,
                        style = if (isInPipMode) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            horizontal = if (isInPipMode) 8.dp else 14.dp,
                            vertical = if (isInPipMode) 4.dp else 7.dp
                        )
                    )
                }
            }
        }

        // Ana YouTube Tarzı Kontroller (Üst Bar, Orta Butonlar, Alt Bar) — PiP modunda tamamen gizli
        AnimatedVisibility(
            visible = controlsVisible && !isLocked && !isInPipMode,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.34f))
            ) {
                // Üst Bar
                VideoPlayerTopBar(
                    title = task.title,
                    subtitle = subtitle,
                    speedLabel = formatSpeedLabel(selectedSpeed),
                    loopEnabled = loopEnabled,
                    isLocked = isLocked,
                    onDismiss = onDismiss,
                    onRequestPip = {
                        interactionNonce++
                        onRequestPip()
                    },
                    onSwitchToBackgroundAudio = {
                        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                        exoPlayer.pause()
                        latestOnSavePosition(task.id, pos)
                        onSwitchToBackgroundAudio(task, pos)
                    },
                    onOpenSpeedMenu = {
                        showSpeedMenu = true
                        interactionNonce++
                    },
                    onToggleLoop = {
                        loopEnabled = !loopEnabled
                        interactionNonce++
                    },
                    onToggleLock = {
                        isLocked = true
                        controlsVisible = false
                        lockBannerVisible = true
                        lockBannerNonce++
                    },
                    onShare = {
                        interactionNonce++
                        onShare()
                    },
                    onOpenExternal = {
                        exoPlayer.pause()
                        onOpenExternal()
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // Orta Kontroller ([Önceki], -10 sn, Oynat/Duraklat/Tekrar, +10 sn, [Sonraki])
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 30.dp else 16.dp)
                ) {
                    if (playlist.size > 1) {
                        IconButton(
                            onClick = {
                                previousPlaylistTask?.let { prev ->
                                    latestOnSavePosition(task.id, exoPlayer.currentPosition.coerceAtLeast(0L))
                                    onSelectTask(prev)
                                }
                            },
                            enabled = previousPlaylistTask != null,
                            modifier = Modifier
                                .bounceClick(pressedScale = 0.90f)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.50f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Önceki Video",
                                tint = if (previousPlaylistTask != null) {
                                    Color.White
                                } else {
                                    Color.White.copy(alpha = 0.35f)
                                },
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { seekByDeltaMs(-10_000L) },
                        modifier = Modifier
                            .bounceClick(pressedScale = 0.90f)
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.56f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Replay10,
                            contentDescription = "10 saniye geri sar",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                if (isEnded || exoPlayer.playbackState == Player.STATE_ENDED) {
                                    exoPlayer.seekTo(0L)
                                    currentPositionMs = 0L
                                }
                                exoPlayer.play()
                            }
                            interactionNonce++
                        },
                        modifier = Modifier
                            .bounceClick(pressedScale = 0.92f)
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(UlgenPrimaryGradient)
                    ) {
                        Icon(
                            imageVector = when {
                                isPlaying -> Icons.Rounded.Pause
                                isEnded -> Icons.Rounded.Replay
                                else -> Icons.Rounded.PlayArrow
                            },
                            contentDescription = when {
                                isPlaying -> "Duraklat"
                                isEnded -> "Yeniden Oynat"
                                else -> "Oynat"
                            },
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = { seekByDeltaMs(10_000L) },
                        modifier = Modifier
                            .bounceClick(pressedScale = 0.90f)
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.56f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Forward10,
                            contentDescription = "10 saniye ileri sar",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    if (playlist.size > 1) {
                        IconButton(
                            onClick = {
                                nextPlaylistTask?.let { next ->
                                    latestOnSavePosition(task.id, exoPlayer.currentPosition.coerceAtLeast(0L))
                                    onSelectTask(next)
                                }
                            },
                            enabled = nextPlaylistTask != null,
                            modifier = Modifier
                                .bounceClick(pressedScale = 0.90f)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.50f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Sonraki Video",
                                tint = if (nextPlaylistTask != null) {
                                    Color.White
                                } else {
                                    Color.White.copy(alpha = 0.35f)
                                },
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // Alt Bar (Üst satır: Süre + Sessiz + Büyüt + Tam Ekran, Alt satır: YouTubeSeekBar)
                val safeDurationMs = durationMs.coerceAtLeast(0L)
                val clampedPosMs = currentPositionMs.coerceIn(0L, safeDurationMs.coerceAtLeast(1L))
                val sliderFraction = draggingFraction ?: if (safeDurationMs > 0L) {
                    (clampedPosMs.toFloat() / safeDurationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }

                val displayedPosSec = if (draggingFraction != null && safeDurationMs > 0L) {
                    (draggingFraction!! * safeDurationMs).toLong() / 1000L
                } else {
                    clampedPosMs / 1000L
                }
                val totalDurSec = safeDurationMs / 1000L
                val timeLabel = remember(displayedPosSec, totalDurSec, safeDurationMs) {
                    val posStr = formatTimeText(displayedPosSec)
                    val durStr = if (safeDurationMs > 0L) formatTimeText(totalDurSec) else "--:--"
                    "$posStr / $durStr"
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f))
                            )
                        )
                        .padding(
                            start = 16.dp + safeStartPadding,
                            end = 16.dp + safeEndPadding,
                            top = 10.dp,
                            bottom = safeBottomPadding
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // YouTube tarzı kompakt Kalite / Çözünürlük rozeti (ör. HD • 1080p)
                            Surface(
                                onClick = {
                                    showQualityMenu = true
                                    interactionNonce++
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (showQualityMenu || selectedTrackQualityId != null) {
                                    ElectricPurple.copy(alpha = 0.42f)
                                } else {
                                    Color.White.copy(alpha = 0.14f)
                                },
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (showQualityMenu || selectedTrackQualityId != null) {
                                        CoralPink.copy(alpha = 0.85f)
                                    } else {
                                        Color.White.copy(alpha = 0.24f)
                                    }
                                ),
                                modifier = Modifier.bounceClick(pressedScale = 0.94f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.HighQuality,
                                        contentDescription = "Video Kalitesi",
                                        tint = if (activeQualityInfo.shortSidePx >= 720) {
                                            CoralPink
                                        } else {
                                            Color.White
                                        },
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = activeQualityInfo.badgeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    showSubtitleMenu = true
                                    interactionNonce++
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ClosedCaption,
                                    contentDescription = "Altyazı (.srt / .vtt)",
                                    tint = if (selectedSubtitleUri != null) CoralPink else Color.White,
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    isMuted = !isMuted
                                    exoPlayer.volume = if (isMuted) 0f else 1f
                                    interactionNonce++
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = if (isMuted) {
                                        Icons.AutoMirrored.Rounded.VolumeOff
                                    } else {
                                        Icons.AutoMirrored.Rounded.VolumeUp
                                    },
                                    contentDescription = if (isMuted) "Sesi Aç" else "Sessize Al",
                                    tint = if (isMuted) CoralPink else Color.White,
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    isFillScreen = !isFillScreen
                                    zoomScale = 1f
                                    panOffset = Offset.Zero
                                    fitBannerText = if (isFillScreen) {
                                        "Ekranı Doldur"
                                    } else {
                                        "Orijinal Boyut"
                                    }
                                    fitBannerNonce++
                                    interactionNonce++
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FitScreen,
                                    contentDescription = if (isFillScreen) {
                                        "Orijinal Boyut"
                                    } else {
                                        "Ekranı Doldur"
                                    },
                                    tint = if (isFillScreen) CoralPink else Color.White,
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            IconButton(
                                onClick = { toggleFullscreen() },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLandscape) {
                                        Icons.Rounded.FullscreenExit
                                    } else {
                                        Icons.Rounded.Fullscreen
                                    },
                                    contentDescription = if (isLandscape) {
                                        "Tam Ekrandan Çık"
                                    } else {
                                        "Tam Ekran"
                                    },
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    YouTubeSeekBar(
                        progress = sliderFraction,
                        durationMs = safeDurationMs,
                        enabled = safeDurationMs > 0L,
                        isDragging = draggingFraction != null,
                        onScrub = { fraction ->
                            draggingFraction = fraction
                            interactionNonce++
                        },
                        onSeekFinished = { finalFraction ->
                            draggingFraction = null
                            if (safeDurationMs > 0L) {
                                val targetMs = (finalFraction * safeDurationMs).toLong()
                                exoPlayer.seekTo(targetMs)
                                currentPositionMs = targetMs
                            }
                            interactionNonce++
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(2f)
                    )
                }
            }
        }

        // Üst Orta "2x hızında oynatılıyor >>" Kapsülü
        AnimatedVisibility(
            visible = isHolding2x && !isInPipMode,
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.92f),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.92f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.80f),
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.70f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "2x hızında oynatılıyor",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Icon(
                        imageVector = Icons.Rounded.FastForward,
                        contentDescription = null,
                        tint = CoralPink,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Parlaklık / Ses Dikey Kaydırma HUD Göstergesi
        AnimatedVisibility(
            visible = adjustHud != null && !isInPipMode,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 60.dp)
        ) {
            adjustHud?.let { hud ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.78f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = hud.icon,
                            contentDescription = null,
                            tint = CoralPink,
                            modifier = Modifier.size(20.dp)
                        )
                        Box(
                            modifier = Modifier
                                .width(84.dp)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.24f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(hud.progress.coerceIn(0f, 1f))
                                    .background(UlgenPrimaryGradient)
                            )
                        }
                        Text(
                            text = hud.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // "Ekranı Doldur" / "Orijinal Boyut" 1.2 sn Kapsülü
        AnimatedVisibility(
            visible = fitBannerText != null && !isInPipMode,
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.92f),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            fitBannerText?.let { text ->
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.80f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                    )
                }
            }
        }

        // Ekran Kilidi Aktifken Kilidi Aç Kapsülü
        AnimatedVisibility(
            visible = isLocked && lockBannerVisible && !isInPipMode,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = safeBottomPadding + 8.dp)
        ) {
            Surface(
                onClick = {
                    isLocked = false
                    lockBannerVisible = false
                    controlsVisible = true
                    interactionNonce++
                },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.84f),
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.75f)),
                modifier = Modifier.bounceClick(pressedScale = 0.94f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = CoralPink,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Ekran Kilitli — Kilidi Aç",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        // Gelişmiş Oynatma Hızı Seçim Paneli (0.25x .. 3x)
        AnimatedVisibility(
            visible = showSpeedMenu && !isLocked && !isInPipMode,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(5f)
        ) {
            SpeedSelectorOverlay(
                selectedSpeed = selectedSpeed,
                onSelectSpeed = { speed ->
                    selectedSpeed = speed
                    exoPlayer.setPlaybackSpeed(if (isHolding2x) 2f else speed)
                    showSpeedMenu = false
                    interactionNonce++
                },
                onDismiss = {
                    showSpeedMenu = false
                    interactionNonce++
                }
            )
        }

        // Dahili Altyazı (.srt / .vtt) Seçim Paneli
        AnimatedVisibility(
            visible = showSubtitleMenu && !isLocked && !isInPipMode,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(5f)
        ) {
            SubtitleSelectorOverlay(
                tracks = availableSubtitles,
                selectedUri = selectedSubtitleUri,
                selectedCustomLabel = selectedSubtitleLabel,
                onSelectTrack = { track ->
                    selectedSubtitleUri = track?.uri
                    selectedSubtitleLabel = track?.label
                    showSubtitleMenu = false
                    interactionNonce++
                },
                onPickExternalFile = {
                    subtitlePickerLauncher.launch(arrayOf("*/*"))
                },
                onDismiss = {
                    showSubtitleMenu = false
                    interactionNonce++
                }
            )
        }

        // YouTube Tarzı Video Kalitesi & Çözünürlük Ayarları Paneli
        AnimatedVisibility(
            visible = showQualityMenu && !isLocked && !isInPipMode,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(5f)
        ) {
            val currentScaleMode = when {
                zoomScale > 1.05f -> VideoScaleMode.ZOOM
                isFillScreen -> VideoScaleMode.FILL
                else -> VideoScaleMode.FIT
            }
            QualitySelectorOverlay(
                task = task,
                activeQualityInfo = activeQualityInfo,
                tracks = videoQualityTracks,
                selectedTrackId = selectedTrackQualityId,
                currentScaleMode = currentScaleMode,
                onSelectAutoQuality = {
                    selectedTrackQualityId = null
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .clearVideoSizeConstraints()
                        .build()
                    showQualityMenu = false
                    interactionNonce++
                },
                onSelectTrackOption = { option ->
                    selectedTrackQualityId = option.id
                    val groups = exoPlayer.currentTracks.groups
                    val targetGroup = groups.getOrNull(option.groupIndex)
                    val builder = exoPlayer.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .setMaxVideoSize(option.width, option.height)
                    if (targetGroup != null &&
                        targetGroup.type == C.TRACK_TYPE_VIDEO &&
                        option.trackIndex in 0 until targetGroup.length
                    ) {
                        builder.setOverrideForType(
                            TrackSelectionOverride(targetGroup.mediaTrackGroup, option.trackIndex)
                        )
                    }
                    exoPlayer.trackSelectionParameters = builder.build()
                    showQualityMenu = false
                    interactionNonce++
                },
                onSelectScaleMode = { mode ->
                    when (mode) {
                        VideoScaleMode.FIT -> {
                            isFillScreen = false
                            zoomScale = 1f
                            panOffset = Offset.Zero
                            fitBannerText = "Orijinal Boyut"
                        }
                        VideoScaleMode.FILL -> {
                            isFillScreen = true
                            zoomScale = 1f
                            panOffset = Offset.Zero
                            fitBannerText = "Ekranı Doldur"
                        }
                        VideoScaleMode.ZOOM -> {
                            isFillScreen = false
                            zoomScale = 1.5f
                            panOffset = Offset.Zero
                            fitBannerText = "Yakınlaştır (%150)"
                        }
                    }
                    fitBannerNonce++
                    showQualityMenu = false
                    interactionNonce++
                },
                onRequestQualityChange = onRequestQualityChange?.let { callback ->
                    {
                        showQualityMenu = false
                        exoPlayer.pause()
                        latestOnSavePosition(task.id, exoPlayer.currentPosition.coerceAtLeast(0L))
                        callback(task)
                    }
                },
                onQuickTranscodeQuality = onQuickTranscodeQuality?.let { callback ->
                    { targetHeight ->
                        showQualityMenu = false
                        exoPlayer.pause()
                        latestOnSavePosition(task.id, exoPlayer.currentPosition.coerceAtLeast(0L))
                        callback(task, targetHeight)
                    }
                },
                onDismiss = {
                    showQualityMenu = false
                    interactionNonce++
                }
            )
        }
    }
}

/**
 * YouTube mobil oynatıcı tarzında ince dokunmatik ilerleme çubuğu (`YouTubeSeekBar`).
 */
@Composable
private fun YouTubeSeekBar(
    progress: Float,
    durationMs: Long,
    enabled: Boolean,
    isDragging: Boolean,
    onScrub: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnScrub by rememberUpdatedState(onScrub)
    val latestOnSeekFinished by rememberUpdatedState(onSeekFinished)

    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    var pillWidthPx by remember { mutableFloatStateOf(0f) }

    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 5.5.dp else 3.5.dp,
        animationSpec = tween(120),
        label = "seekbar_track_height"
    )
    val thumbDiameter by animateDpAsState(
        targetValue = if (isDragging) 18.dp else 12.dp,
        animationSpec = tween(120),
        label = "seekbar_thumb_diameter"
    )

    val clampedProgress = progress.coerceIn(0f, 1f)
    val activeBrush = remember {
        Brush.horizontalGradient(colors = listOf(CoralPink, ElectricPurple))
    }

    Box(
        modifier = modifier
            .height(36.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!latestEnabled) return@awaitEachGesture

                    down.consume()
                    val widthF = size.width.toFloat().coerceAtLeast(1f)
                    var lastFraction = (down.position.x / widthF).coerceIn(0f, 1f)
                    latestOnScrub(lastFraction)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) {
                            change?.consume()
                            break
                        }
                        change.consume()
                        lastFraction = (change.position.x / widthF).coerceIn(0f, 1f)
                        latestOnScrub(lastFraction)
                    }
                    latestOnSeekFinished(lastFraction)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barH = trackHeight.toPx()
            val barY = (size.height - barH) / 2f
            val cornerRadius = CornerRadius(barH / 2f, barH / 2f)

            // Arka plan izi
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(0f, barY),
                size = Size(size.width, barH),
                cornerRadius = cornerRadius
            )

            // Oynanan kısım (CoralPink -> ElectricPurple gradient)
            val playedWidth = (size.width * clampedProgress).coerceIn(0f, size.width)
            if (playedWidth > 0f) {
                drawRoundRect(
                    brush = activeBrush,
                    topLeft = Offset(0f, barY),
                    size = Size(playedWidth, barH),
                    cornerRadius = cornerRadius
                )
            }

            // Thumb noktası
            val thumbRadius = (thumbDiameter.toPx() / 2f)
            val thumbCenterX = playedWidth.coerceIn(thumbRadius, (size.width - thumbRadius).coerceAtLeast(thumbRadius))
            val thumbCenterY = size.height / 2f

            if (isDragging) {
                drawCircle(
                    color = CoralPink.copy(alpha = 0.30f),
                    radius = thumbRadius * 1.45f,
                    center = Offset(thumbCenterX, thumbCenterY)
                )
            }
            drawCircle(
                color = CoralPink,
                radius = thumbRadius,
                center = Offset(thumbCenterX, thumbCenterY)
            )
        }

        // Sürüklenirken parmak üstünde hedef süreyi gösteren koyu hap kapsül
        if (isDragging && durationMs > 0L) {
            val targetSec = ((clampedProgress * durationMs).toLong() / 1000L).coerceAtLeast(0L)
            val bubbleText = formatTimeText(targetSec)
            val rawX = clampedProgress * trackWidthPx - pillWidthPx / 2f
            val maxX = (trackWidthPx - pillWidthPx).coerceAtLeast(0f)
            val clampedX = rawX.coerceIn(0f, maxX)
            val liftY = with(density) { -38.dp.toPx() }

            Surface(
                shape = CircleShape,
                color = Color(0xFF10141E).copy(alpha = 0.94f),
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.70f)),
                modifier = Modifier
                    .onSizeChanged { pillWidthPx = it.width.toFloat() }
                    .graphicsLayer {
                        alpha = if (pillWidthPx > 0f) 1f else 0f
                        translationX = clampedX
                        translationY = liftY
                    }
            ) {
                Text(
                    text = bubbleText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Video oynatıcı üst kontrol çubuğu (başlık, PiP, arka plan ses, hız çipi, döngü, kilit, paylaş, harici aç).
 */
@Composable
private fun VideoPlayerTopBar(
    title: String,
    subtitle: String,
    speedLabel: String,
    loopEnabled: Boolean,
    isLocked: Boolean,
    onDismiss: () -> Unit,
    onRequestPip: () -> Unit,
    onSwitchToBackgroundAudio: () -> Unit,
    onOpenSpeedMenu: () -> Unit,
    onToggleLoop: () -> Unit,
    onToggleLock: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Kapat",
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Surface(
                onClick = onOpenSpeedMenu,
                shape = RoundedCornerShape(10.dp),
                color = ElectricPurple.copy(alpha = 0.34f),
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.72f))
            ) {
                Text(
                    text = speedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                )
            }

            IconButton(
                onClick = onRequestPip,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PictureInPictureAlt,
                    contentDescription = "Resim İçinde Resim (PiP)",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onSwitchToBackgroundAudio,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Headphones,
                    contentDescription = "Arka Planda Ses Olarak Dinle",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onToggleLoop,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (loopEnabled) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    },
                    contentDescription = if (loopEnabled) "Döngüyü Kapat" else "Tekrarla",
                    tint = if (loopEnabled) CoralPink else Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onToggleLock,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    contentDescription = "Ekranı Kilitle",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onShare,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Paylaş",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onOpenExternal,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = "Harici Uygulamada Aç",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

/**
 * Çift tıklama ile -10 / +10 sn sarma sırasında sol veya sağ yarıda beliren oval yarı saydam efekt.
 */
@Composable
private fun DoubleTapSeekOverlay(
    state: SeekOverlayState?,
    modifier: Modifier = Modifier
) {
    var lastLeftSeconds by remember { mutableIntStateOf(-10) }
    var lastRightSeconds by remember { mutableIntStateOf(10) }

    if (state != null) {
        if (state.side == TapZoneSide.LEFT) {
            lastLeftSeconds = state.totalSeconds
        } else {
            lastRightSeconds = state.totalSeconds
        }
    }

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = state != null && state.side == TapZoneSide.LEFT,
            enter = fadeIn(tween(110)),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight(0.74f)
                .fillMaxWidth(0.42f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topEnd = 180.dp, bottomEnd = 180.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay10,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "$lastLeftSeconds sn",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state != null && state.side == TapZoneSide.RIGHT,
            enter = fadeIn(tween(110)),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(0.74f)
                .fillMaxWidth(0.42f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 180.dp, bottomStart = 180.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forward10,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "+$lastRightSeconds sn",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * `0.25x` .. `3x` oynatma hızlarını tek tıkla seçtiren yarı saydam oynatıcı içi kart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedSelectorOverlay(
    selectedSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .displayCutoutPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF0E131D).copy(alpha = 0.90f),
            border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.55f)),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Oynatma Hızı",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PLAYBACK_SPEEDS.forEach { speed ->
                        val isSelected = abs(speed - selectedSpeed) < 0.001f
                        val chipModifier = Modifier
                            .bounceClick(pressedScale = 0.94f)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (isSelected) {
                                    Modifier.background(UlgenPrimaryGradient)
                                } else {
                                    Modifier
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .border(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                }
                            )
                            .clickable { onSelectSpeed(speed) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)

                        Box(
                            modifier = chipModifier,
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatSpeedLabel(speed),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dahili `.srt` / `.vtt` altyazı seçimi ve harici altyazı dosyası açma paneli.
 */
@Composable
private fun SubtitleSelectorOverlay(
    tracks: List<SubtitleTrackItem>,
    selectedUri: Uri?,
    selectedCustomLabel: String?,
    onSelectTrack: (SubtitleTrackItem?) -> Unit,
    onPickExternalFile: () -> Unit,
    onDismiss: () -> Unit
) {
    val isCustomSelected = selectedUri != null && tracks.none { it.uri == selectedUri }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .displayCutoutPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF0E131D).copy(alpha = 0.94f),
            border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.55f)),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ClosedCaption,
                        contentDescription = null,
                        tint = CoralPink,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Altyazı (.srt / .vtt)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Altyazı Kapalı seçeneği
                SubtitleOptionRow(
                    label = "Altyazı Kapalı",
                    selected = selectedUri == null,
                    onClick = { onSelectTrack(null) }
                )

                // Eşleşen yerel altyazılar
                tracks.forEach { track ->
                    SubtitleOptionRow(
                        label = track.label,
                        selected = selectedUri == track.uri,
                        onClick = { onSelectTrack(track) }
                    )
                }

                // Harici seçilmiş özel altyazı varsa göster
                if (isCustomSelected && !selectedCustomLabel.isNullOrBlank()) {
                    SubtitleOptionRow(
                        label = selectedCustomLabel,
                        selected = true,
                        onClick = onDismiss
                    )
                }

                if (tracks.isEmpty() && !isCustomSelected) {
                    Text(
                        text = "Bu video ile eşleşen yerel altyazı dosyası bulunamadı.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                OutlinedButton(
                    onClick = onPickExternalFile,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.75f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = CoralPink,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Dosyadan Seç (.srt / .vtt)",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            ElectricPurple.copy(alpha = 0.28f)
        } else {
            Color.White.copy(alpha = 0.06f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                ElectricPurple.copy(alpha = 0.85f)
            } else {
                Color.White.copy(alpha = 0.14f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Seçili",
                    tint = CoralPink,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * YouTube tarzı Video Kalitesi, canlı akış çözünürlük seçici ve Görüntü Ölçekleme paneli.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualitySelectorOverlay(
    task: DownloadTask,
    activeQualityInfo: ActiveVideoQualityInfo,
    tracks: List<VideoQualityTrackOption>,
    selectedTrackId: String?,
    currentScaleMode: VideoScaleMode,
    onSelectAutoQuality: () -> Unit,
    onSelectTrackOption: (VideoQualityTrackOption) -> Unit,
    onSelectScaleMode: (VideoScaleMode) -> Unit,
    onRequestQualityChange: (() -> Unit)?,
    onQuickTranscodeQuality: ((Int) -> Unit)?,
    onDismiss: () -> Unit
) {
    val hasMultipleTracks = tracks.size > 1
    val qualitySummary = activeQualityInfo.detailedSummary.ifBlank {
        task.formatLabel.ifBlank { "Bilinmiyor" }
    }
    val localRecordedText = if (activeQualityInfo.shortSidePx > 0) {
        "Bu video cihazda ${activeQualityInfo.shortResolutionLabel} olarak kayıtlı."
    } else {
        "Bu video cihazda tekil dosya olarak kayıtlı."
    }
    val transcodeTargets = remember(activeQualityInfo.shortSidePx) {
        val current = activeQualityInfo.shortSidePx
        listOf(720, 480, 360).filter { target -> current == 0 || target < current }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .displayCutoutPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF0E131D).copy(alpha = 0.95f),
            border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.55f)),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Başlık
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = CoralPink,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Kalite Ayarları",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Mevcut teknik kalite özeti kartı
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.HighQuality,
                            contentDescription = null,
                            tint = CoralPink,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mevcut Kalite",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                            Text(
                                text = qualitySummary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (hasMultipleTracks) {
                    // Çoklu akış (HLS / DASH): Canlı çözünürlük listesi
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QualityOptionRow(
                            title = if (activeQualityInfo.shortSidePx > 0) {
                                "Otomatik (${activeQualityInfo.shortResolutionLabel})"
                            } else {
                                "Otomatik"
                            },
                            meta = "Önerilen",
                            selected = selectedTrackId == null,
                            onClick = onSelectAutoQuality
                        )

                        tracks.forEach { option ->
                            val metaParts = buildList {
                                if (option.frameRate > 0.5f) {
                                    add("${option.frameRate.roundToInt()} fps")
                                }
                                if (option.codecLabel.isNotBlank()) {
                                    add(option.codecLabel)
                                }
                                if (option.bitrate > 0) {
                                    val mbps = option.bitrate / 1_000_000f
                                    if (mbps >= 1f) {
                                        add(String.format("%.1f Mbps", mbps))
                                    } else {
                                        add("${option.bitrate / 1_000} kbps")
                                    }
                                }
                            }
                            QualityOptionRow(
                                title = option.qualityLabel,
                                meta = metaParts.joinToString(" • "),
                                selected = selectedTrackId == option.id,
                                onClick = { onSelectTrackOption(option) }
                            )
                        }
                    }
                } else {
                    // Tekil yerel dosya: dürüst bilgilendirme + eylem butonları
                    Text(
                        text = localRecordedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )

                    if (onRequestQualityChange != null) {
                        Button(
                            onClick = onRequestQualityChange,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricPurple,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Farklı Kalitede İndir (Kaynak Link)",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (onQuickTranscodeQuality != null && transcodeTargets.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Yerel Boyutu Küçült",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                transcodeTargets.forEach { targetHeight ->
                                    OutlinedButton(
                                        onClick = { onQuickTranscodeQuality(targetHeight) },
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.70f))
                                    ) {
                                        Text(
                                            text = "${targetHeight}p'ye Dönüştür",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Görüntü Ölçekleme Modu (Sığdır / Doldur / Yakınlaştır)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Görüntü Ölçekleme Modu",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoScaleMode.entries.forEach { mode ->
                            val isSelected = currentScaleMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .bounceClick(pressedScale = 0.95f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .then(
                                        if (isSelected) {
                                            Modifier.background(UlgenPrimaryGradient)
                                        } else {
                                            Modifier
                                                .background(Color.White.copy(alpha = 0.08f))
                                                .border(
                                                    width = 1.dp,
                                                    color = Color.White.copy(alpha = 0.16f),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                        }
                                    )
                                    .clickable { onSelectScaleMode(mode) }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityOptionRow(
    title: String,
    meta: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            ElectricPurple.copy(alpha = 0.28f)
        } else {
            Color.White.copy(alpha = 0.06f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                ElectricPurple.copy(alpha = 0.85f)
            } else {
                Color.White.copy(alpha = 0.14f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Seçili",
                    tint = CoralPink,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * İndirilen görselleri yakınlaştırma/kaydırma destekli görüntüleyen içerik.
 */
@Composable
private fun InAppImageViewerContent(
    task: DownloadTask,
    uri: Uri,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var showOverlay by remember(uri) { mutableStateOf(true) }

    val subtitle = remember(task.platform, task.formatLabel) {
        "${task.platform.displayName} • ${task.formatLabel}"
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(uri) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                        onTap = {
                            showOverlay = !showOverlay
                        }
                    )
                }
                .pointerInput(uri, widthPx, heightPx) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = nextScale
                        if (nextScale > 1f) {
                            val maxX = (widthPx * (nextScale - 1f)) / 2f
                            val maxY = (heightPx * (nextScale - 1f)) / 2f
                            offset = Offset(
                                x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        } else {
                            offset = Offset.Zero
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = uri,
                contentDescription = task.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ViewerTopBar(
                title = task.title,
                subtitle = subtitle,
                onDismiss = onDismiss,
                onShare = onShare,
                onOpenExternal = onOpenExternal
            )
        }
    }
}

@Composable
private fun ViewerTopBar(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                )
            )
            .displayCutoutPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Kapat",
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onShare) {
            Icon(
                imageVector = Icons.Rounded.Share,
                contentDescription = "Paylaş",
                tint = Color.White
            )
        }

        IconButton(onClick = onOpenExternal) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = "Harici Uygulamada Aç",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun ViewerErrorContent(
    task: DownloadTask,
    message: String,
    onOpenExternal: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Kapat",
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = CoralPink,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onOpenExternal,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Harici Aç", color = Color.White)
                }
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple)
                ) {
                    Text("Kapat", color = Color.White)
                }
            }
        }
    }
}
