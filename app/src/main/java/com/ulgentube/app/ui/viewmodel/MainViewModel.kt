package com.ulgentube.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ulgentube.app.EngineInitState
import com.ulgentube.app.UlgenTubeApp
import com.ulgentube.app.di.AppContainer
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.ThemeMode
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Ana uygulama ekranları (Ana Sayfa, İndirmelerim, Ayarlar) için merkezi ViewModel.
 */
class MainViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val appInstance: UlgenTubeApp?
        get() = container.applicationContext.applicationContext as? UlgenTubeApp

    val downloads: StateFlow<List<DownloadTask>> = container.downloadRepository.downloadsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    val settings: StateFlow<AppSettings> = container.settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AppSettings()
        )

    val engineState: StateFlow<EngineInitState> =
        appInstance?.engineInitState ?: MutableStateFlow(EngineInitState.Idle)

    private val _analyzeState = MutableStateFlow<AnalyzeUiState>(AnalyzeUiState.Idle)
    val analyzeState: StateFlow<AnalyzeUiState> = _analyzeState.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _ytDlpUpdateState = MutableStateFlow<String?>(null)
    val ytDlpUpdateState: StateFlow<String?> = _ytDlpUpdateState.asStateFlow()

    private val _isUpdatingYtDlp = MutableStateFlow(false)
    val isUpdatingYtDlp: StateFlow<Boolean> = _isUpdatingYtDlp.asStateFlow()

    private var analyzeJob: Job? = null

    fun onUrlInputChange(value: String) {
        _urlInput.value = value
    }

    fun clearUrlInput() {
        _urlInput.value = ""
    }

    fun analyzeUrl(rawInput: String = _urlInput.value) {
        val trimmed = rawInput.trim()
        val cleanUrl = UrlSanitizer.extractFirstValidUrl(trimmed)
        if (cleanUrl == null) {
            _analyzeState.value = AnalyzeUiState.Error(
                message = "Lütfen geçerli bir http:// veya https:// bağlantısı girin.",
                originalInput = trimmed
            )
            return
        }

        _urlInput.value = cleanUrl
        val platform = UrlSanitizer.detectPlatform(cleanUrl)

        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _analyzeState.value = AnalyzeUiState.Loading(
                url = cleanUrl,
                platform = platform
            )

            if (platform != PlatformType.DIRECT_IMAGE) {
                waitForEngineIfInitializing()
            }

            val result = container.hybridMediaExtractor.analyze(cleanUrl)
            _analyzeState.value = result.fold(
                onSuccess = { media -> AnalyzeUiState.Success(media) },
                onFailure = { error ->
                    AnalyzeUiState.Error(
                        message = com.ulgentube.app.util.ErrorMessageSanitizer.sanitize(
                            throwable = error,
                            fallback = "Bağlantı analiz edilemedi. Bağlantının herkese açık olduğundan emin olun."
                        ),
                        originalInput = cleanUrl
                    )
                }
            )
        }
    }

    fun dismissAnalyzeSheet() {
        analyzeJob?.cancel()
        _analyzeState.value = AnalyzeUiState.Idle
    }

    fun startFormatDownload(media: ExtractedMedia, option: MediaFormatOption) {
        viewModelScope.launch {
            container.downloadRepository.enqueue(media, option)
        }
        _analyzeState.value = AnalyzeUiState.Idle
    }

    fun startImageDownload(media: ExtractedMedia, image: ImageItem) {
        viewModelScope.launch {
            container.downloadRepository.enqueueImage(media, image)
        }
        _analyzeState.value = AnalyzeUiState.Idle
    }

    fun startAllImagesDownload(media: ExtractedMedia) {
        viewModelScope.launch {
            container.downloadRepository.enqueueAllImages(media)
        }
        _analyzeState.value = AnalyzeUiState.Idle
    }

    // İndirme Yönetimi
    fun cancel(taskId: String) {
        viewModelScope.launch {
            container.downloadRepository.cancel(taskId)
        }
    }

    fun retry(taskId: String) {
        viewModelScope.launch {
            container.downloadRepository.retry(taskId)
        }
    }

    fun delete(taskId: String) {
        viewModelScope.launch {
            container.downloadRepository.delete(taskId)
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            container.downloadRepository.clearCompleted()
        }
    }

    // Ayar Güncellemeleri
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            container.settingsRepository.setThemeMode(mode)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setDynamicColor(enabled)
        }
    }

    fun setDefaultVideoQuality(quality: String) {
        viewModelScope.launch {
            container.settingsRepository.setDefaultVideoQuality(quality)
        }
    }

    fun setDefaultAudioFormat(format: String) {
        viewModelScope.launch {
            container.settingsRepository.setDefaultAudioFormat(format)
        }
    }

    fun setUseAria2c(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setUseAria2c(enabled)
        }
    }

    fun setAutoDetectClipboard(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setAutoDetectClipboard(enabled)
        }
    }

    fun setEmbedThumbnail(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setEmbedThumbnail(enabled)
        }
    }

    fun retryEngineInit() {
        appInstance?.initializeEngines()
    }

    fun updateYtDlp(context: Context) {
        if (_isUpdatingYtDlp.value) return
        viewModelScope.launch {
            _isUpdatingYtDlp.value = true
            _ytDlpUpdateState.value = "yt-dlp güncellemeleri denetleniyor..."
            waitForEngineIfInitializing()
            val result = container.ytDlpEngine.updateYoutubeDL(context.applicationContext)
            _ytDlpUpdateState.value = result.fold(
                onSuccess = { message -> message },
                onFailure = { error ->
                    "Güncelleme başarısız: ${
                        com.ulgentube.app.util.ErrorMessageSanitizer.sanitize(error, "Bilinmeyen hata")
                    }"
                }
            )
            _isUpdatingYtDlp.value = false
        }
    }

    private suspend fun waitForEngineIfInitializing() {
        val app = appInstance ?: return
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
                    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                        return MainViewModel(container) as T
                    }
                    throw IllegalArgumentException("Bilinmeyen ViewModel sınıfı: ${modelClass.name}")
                }
            }
    }
}
