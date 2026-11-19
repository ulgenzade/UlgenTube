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
import com.ulgentube.app.util.ErrorMessageSanitizer
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Paylaşım menüsünden açılan şeffaf [com.ulgentube.app.SharePopupActivity] için ViewModel.
 */
class SharePopupViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _analyzeState = MutableStateFlow<AnalyzeUiState>(AnalyzeUiState.Idle)
    val analyzeState: StateFlow<AnalyzeUiState> = _analyzeState.asStateFlow()

    val settings: StateFlow<AppSettings> = container.settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AppSettings()
        )

    private var analyzeJob: Job? = null

    fun analyze(rawText: String?) {
        val trimmed = rawText?.trim().orEmpty()
        val cleanUrl = UrlSanitizer.extractFirstValidUrl(trimmed)
        if (cleanUrl == null) {
            _analyzeState.value = AnalyzeUiState.Error(
                message = "Paylaşılan içerikte geçerli bir bağlantı bulunamadı.",
                originalInput = trimmed
            )
            return
        }

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
                onSuccess = { media -> AnalyzeUiState.Success(media) },
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
        onStarted: () -> Unit = {}
    ) {
        container.applicationScope.launch {
            runCatching { container.downloadRepository.enqueue(media, option) }
            withContext(Dispatchers.Main) { onStarted() }
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
