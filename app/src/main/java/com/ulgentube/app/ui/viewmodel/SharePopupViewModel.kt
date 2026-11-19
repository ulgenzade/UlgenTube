package com.ulgentube.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ulgentube.app.EngineInitState
import com.ulgentube.app.UlgenTubeApp
import com.ulgentube.app.di.AppContainer
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.PlaylistEntry
import com.ulgentube.app.util.ErrorMessageSanitizer
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * Paylaşım menüsünden veya Quick Settings kutucuğundan açılan şeffaf
 * [com.ulgentube.app.SharePopupActivity] için ViewModel.
 */
class SharePopupViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _analyzeState = MutableStateFlow<AnalyzeUiState>(AnalyzeUiState.Idle)
    val analyzeState: StateFlow<AnalyzeUiState> = _analyzeState.asStateFlow()

    private val _batchUrls = MutableStateFlow<List<String>>(emptyList())
    val batchUrls: StateFlow<List<String>> = _batchUrls.asStateFlow()

    val settings: StateFlow<AppSettings> = container.settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AppSettings()
        )

    private var analyzeJob: Job? = null

    fun analyze(rawText: String?) {
        val trimmed = rawText?.trim().orEmpty()
        val allUrls = UrlSanitizer.extractAllValidUrls(trimmed)
        if (allUrls.isEmpty()) {
            _batchUrls.value = emptyList()
            _analyzeState.value = AnalyzeUiState.Error(
                message = "Paylaşılan içerikte geçerli bir bağlantı bulunamadı.",
                originalInput = trimmed
            )
            return
        }

        if (allUrls.size >= 2) {
            analyzeJob?.cancel()
            _analyzeState.value = AnalyzeUiState.Idle
            _batchUrls.value = allUrls
            return
        }

        _batchUrls.value = emptyList()
        val cleanUrl = allUrls.first()
        val platform = UrlSanitizer.detectPlatform(cleanUrl)
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _analyzeState.value = AnalyzeUiState.Loading(
                url = cleanUrl,
                platform = platform
            )

            // Soğuk başlangıçta yerel motorun hazır olmasını bekle (doğrudan görsel değilse)
            if (platform != PlatformType.DIRECT_IMAGE) {
                waitForEngineIfInitializing()
            }

            val result = container.hybridMediaExtractor.analyze(cleanUrl)
            _analyzeState.value = result.fold(
                onSuccess = { media ->
                    val duplicate = container.downloadRepository.findDuplicateCompleted(media.originalUrl)
                    AnalyzeUiState.Success(
                        media = media,
                        duplicateTask = duplicate
                    )
                },
                onFailure = { error ->
                    AnalyzeUiState.Error(
                        message = ErrorMessageSanitizer.sanitize(
                            throwable = error,
                            fallback = "Bağlantı analiz edilemedi. Lütfen tekrar deneyin."
                        ),
                        originalInput = cleanUrl
                    )
                }
            )
        }
    }

    fun startFormatDownload(
        media: ExtractedMedia,
        option: MediaFormatOption,
        clipStartSeconds: Long? = null,
        clipEndSeconds: Long? = null,
        subtitleLang: String? = null,
        embedSubtitles: Boolean = false,
        onStarted: () -> Unit = {}
    ) {
        container.applicationScope.launch {
            runCatching {
                container.downloadRepository.enqueue(
                    media = media,
                    option = option,
                    clipStartSeconds = clipStartSeconds,
                    clipEndSeconds = clipEndSeconds,
                    subtitleLang = subtitleLang,
                    embedSubtitles = embedSubtitles
                )
            }
            withContext(Dispatchers.Main) { onStarted() }
        }
    }

    fun startPlaylistDownload(
        entries: List<PlaylistEntry>,
        platform: PlatformType,
        audioOnly: Boolean,
        onStarted: () -> Unit = {}
    ) {
        if (entries.isEmpty()) return
        container.applicationScope.launch {
            runCatching {
                container.downloadRepository.enqueuePlaylistEntries(
                    entries = entries,
                    platform = platform,
                    audioOnly = audioOnly
                )
            }
            withContext(Dispatchers.Main) { onStarted() }
        }
    }

    fun startBatchUrlsDownload(
        urls: List<String>,
        audioOnly: Boolean,
        onStarted: () -> Unit = {}
    ) {
        if (urls.isEmpty()) return
        container.applicationScope.launch {
            runCatching {
                container.downloadRepository.enqueueBatchUrls(
                    urls = urls,
                    audioOnly = audioOnly
                )
            }
            withContext(Dispatchers.Main) { onStarted() }
        }
    }

    /**
     * Kullanıcının [AppSettings.defaultVideoQuality] tercihine en yakın video formatını
     * (yoksa ilk video, yoksa varsayılan ses formatını, o da yoksa ilk görseli) seçip tek tıkla indirir.
     */
    fun startQuickDefaultDownload(
        media: ExtractedMedia,
        settings: AppSettings,
        onStarted: () -> Unit = {}
    ) {
        val preferredVideo = pickPreferredVideoFormat(media.videoFormats, settings.defaultVideoQuality)
        if (preferredVideo != null) {
            startFormatDownload(media = media, option = preferredVideo, onStarted = onStarted)
            return
        }

        val preferredAudio = pickPreferredAudioFormat(media.audioFormats, settings.defaultAudioFormat)
        if (preferredAudio != null) {
            startFormatDownload(media = media, option = preferredAudio, onStarted = onStarted)
            return
        }

        val firstImage = media.imageItems.firstOrNull()
        if (firstImage != null) {
            startImageDownload(media = media, image = firstImage, onStarted = onStarted)
        }
    }

    fun startImageDownload(
        media: ExtractedMedia,
        image: ImageItem,
        onStarted: () -> Unit = {}
    ) {
        container.applicationScope.launch {
            runCatching { container.downloadRepository.enqueueImage(media, image) }
            withContext(Dispatchers.Main) { onStarted() }
        }
    }

    fun startAllImagesDownload(
        media: ExtractedMedia,
        onStarted: () -> Unit = {}
    ) {
        container.applicationScope.launch {
            runCatching { container.downloadRepository.enqueueAllImages(media) }
            withContext(Dispatchers.Main) { onStarted() }
        }
    }

    private suspend fun waitForEngineIfInitializing() {
        val app = container.applicationContext.applicationContext as? UlgenTubeApp ?: return
        val current = app.engineInitState.value
        if (current is EngineInitState.Idle || current is EngineInitState.Initializing) {
            app.initializeEngines()
            app.engineInitState.first {
                it is EngineInitState.Ready || it is EngineInitState.Error
            }
        }
    }

    companion object {
        fun pickPreferredVideoFormat(
            videoFormats: List<MediaFormatOption>,
            defaultQuality: String
        ): MediaFormatOption? {
            if (videoFormats.isEmpty()) return null
            val targetHeight = parseTargetHeight(defaultQuality)
            if (targetHeight != null) {
                val exact = videoFormats.firstOrNull { it.resolutionHeight == targetHeight }
                if (exact != null) return exact

                val closestLowerOrEqual = videoFormats
                    .filter { it.resolutionHeight in 1..targetHeight }
                    .maxByOrNull { it.resolutionHeight }
                if (closestLowerOrEqual != null) return closestLowerOrEqual

                val closestAny = videoFormats
                    .filter { it.resolutionHeight > 0 }
                    .minByOrNull { abs(it.resolutionHeight - targetHeight) }
                if (closestAny != null) return closestAny
            }
            return videoFormats.firstOrNull()
        }

        fun pickPreferredAudioFormat(
            audioFormats: List<MediaFormatOption>,
            defaultAudioFormat: String
        ): MediaFormatOption? {
            if (audioFormats.isEmpty()) return null
            val targetExt = defaultAudioFormat.trim().lowercase(Locale.ROOT)
            return audioFormats.firstOrNull {
                it.extension.lowercase(Locale.ROOT) == targetExt
            } ?: audioFormats.firstOrNull()
        }

        private fun parseTargetHeight(qualityLabel: String): Int? = when {
            qualityLabel.contains("2160") || qualityLabel.contains("4K", ignoreCase = true) -> 2160
            qualityLabel.contains("1440") || qualityLabel.contains("2K", ignoreCase = true) -> 1440
            qualityLabel.contains("1080") -> 1080
            qualityLabel.contains("720") -> 720
            qualityLabel.contains("480") -> 480
            qualityLabel.contains("360") -> 360
            else -> null
        }

        fun provideFactory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SharePopupViewModel::class.java)) {
                        return SharePopupViewModel(container) as T
                    }
                    throw IllegalArgumentException("Bilinmeyen ViewModel sınıfı: ${modelClass.name}")
                }
            }
    }
}
