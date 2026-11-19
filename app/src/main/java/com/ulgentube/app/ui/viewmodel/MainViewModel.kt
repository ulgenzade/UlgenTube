package com.ulgentube.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ulgentube.app.EngineInitState
import com.ulgentube.app.UlgenTubeApp
import com.ulgentube.app.di.AppContainer
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.FfmpegStudioAction
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.PlaylistEntry
import com.ulgentube.app.domain.model.ThemeMode
import com.ulgentube.app.player.MiniPlayerState
import com.ulgentube.app.util.ErrorMessageSanitizer
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

    val miniPlayerState: StateFlow<MiniPlayerState> = container.miniAudioPlayerManager.state

    private val _analyzeState = MutableStateFlow<AnalyzeUiState>(AnalyzeUiState.Idle)
    val analyzeState: StateFlow<AnalyzeUiState> = _analyzeState.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _ytDlpUpdateState = MutableStateFlow<String?>(null)
    val ytDlpUpdateState: StateFlow<String?> = _ytDlpUpdateState.asStateFlow()

    private val _isUpdatingYtDlp = MutableStateFlow(false)
    val isUpdatingYtDlp: StateFlow<Boolean> = _isUpdatingYtDlp.asStateFlow()

    private val _studioBusy = MutableStateFlow(false)
    val studioBusy: StateFlow<Boolean> = _studioBusy.asStateFlow()

    private val _studioMessage = MutableStateFlow<String?>(null)
    val studioMessage: StateFlow<String?> = _studioMessage.asStateFlow()

    private val _hasCookies = MutableStateFlow(container.cookieRepository.hasCookies())
    val hasCookies: StateFlow<Boolean> = _hasCookies.asStateFlow()

    private val _cookieMessage = MutableStateFlow<String?>(null)
    val cookieMessage: StateFlow<String?> = _cookieMessage.asStateFlow()

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

    fun startFormatDownload(
        media: ExtractedMedia,
        option: MediaFormatOption,
        clipStartSeconds: Long? = null,
        clipEndSeconds: Long? = null,
        subtitleLang: String? = null,
        embedSubtitles: Boolean = false
    ) {
        viewModelScope.launch {
            container.downloadRepository.enqueue(
                media = media,
                option = option,
                clipStartSeconds = clipStartSeconds,
                clipEndSeconds = clipEndSeconds,
                subtitleLang = subtitleLang,
                embedSubtitles = embedSubtitles
            )
        }
        _analyzeState.value = AnalyzeUiState.Idle
    }

    fun startPlaylistDownload(
        entries: List<PlaylistEntry>,
        platform: PlatformType,
        audioOnly: Boolean
    ) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            container.downloadRepository.enqueuePlaylistEntries(
                entries = entries,
                platform = platform,
                audioOnly = audioOnly
            )
        }
        _analyzeState.value = AnalyzeUiState.Idle
    }

    fun startBatchUrlsDownload(
        urls: List<String>,
        audioOnly: Boolean
    ) {
        if (urls.isEmpty()) return
        viewModelScope.launch {
            container.downloadRepository.enqueueBatchUrls(
                urls = urls,
                audioOnly = audioOnly
            )
        }
        _urlInput.value = ""
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

    // Mini Müzik Çalar Kontrolleri
    fun playAudio(task: DownloadTask) {
        val current = miniPlayerState.value.currentTask
        if (current?.id == task.id) {
            container.miniAudioPlayerManager.togglePlayPause()
        } else {
            container.miniAudioPlayerManager.play(task)
        }
    }

    fun togglePlayPause() {
        container.miniAudioPlayerManager.togglePlayPause()
    }

    fun seekTo(ms: Long) {
        container.miniAudioPlayerManager.seekTo(ms)
    }

    fun stopMiniPlayer() {
        container.miniAudioPlayerManager.stop()
    }

    // FFmpeg Medya Stüdyosu
    fun clearStudioMessage() {
        _studioMessage.value = null
    }

    fun runFfmpegStudio(
        sourceUri: Uri,
        baseName: String,
        action: FfmpegStudioAction
    ) {
        if (_studioBusy.value) return
        viewModelScope.launch {
            _studioBusy.value = true
            _studioMessage.value = "${action.displayName} işleniyor..."
            waitForEngineIfInitializing()
            val result = container.localFfmpegProcessor.process(
                inputUri = sourceUri,
                action = action,
                outputBaseName = baseName
            )
            _studioMessage.value = result.fold(
                onSuccess = { saved ->
                    "${action.displayName} tamamlandı: ${saved.displayPath}"
                },
                onFailure = { error ->
                    ErrorMessageSanitizer.sanitize(
                        throwable = error,
                        fallback = "Dönüştürme işlemi tamamlanamadı."
                    )
                }
            )
            _studioBusy.value = false
        }
    }

    // Çerez (Cookies) Yönetimi
    fun clearCookieMessage() {
        _cookieMessage.value = null
    }

    fun importCookies(uri: Uri) {
        viewModelScope.launch {
            val result = container.cookieRepository.importFromUri(uri)
            result.fold(
                onSuccess = { count ->
                    _hasCookies.value = container.cookieRepository.hasCookies()
                    container.settingsRepository.setCookiesEnabled(true)
                    _cookieMessage.value = "$count çerez satırı başarıyla içe aktarıldı."
                },
                onFailure = { error ->
                    _cookieMessage.value = ErrorMessageSanitizer.sanitize(
                        throwable = error,
                        fallback = "Çerez dosyası içe aktarılamadı."
                    )
                }
            )
        }
    }

    fun clearCookies() {
        viewModelScope.launch {
            container.cookieRepository.clearCookies()
            _hasCookies.value = container.cookieRepository.hasCookies()
            container.settingsRepository.setCookiesEnabled(false)
            _cookieMessage.value = "Kayıtlı çerezler temizlendi."
        }
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

    fun deleteDownload(taskId: String, deletePhysicalFile: Boolean) {
        if (miniPlayerState.value.currentTask?.id == taskId) {
            container.miniAudioPlayerManager.stop()
        }
        viewModelScope.launch {
            container.downloadRepository.delete(
                taskId = taskId,
                deletePhysicalFile = deletePhysicalFile
            )
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

    fun setSponsorBlockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setSponsorBlockEnabled(enabled)
        }
    }

    fun setEmbedChapters(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setEmbedChapters(enabled)
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setWifiOnly(enabled)
        }
    }

    fun setZeroFrictionShareMode(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setZeroFrictionShareMode(enabled)
        }
    }

    fun setPlatformSubfolders(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setPlatformSubfolders(enabled)
        }
    }

    fun setFileNameTemplate(template: String) {
        viewModelScope.launch {
            container.settingsRepository.setFileNameTemplate(template)
        }
    }

    fun setCookiesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setCookiesEnabled(enabled)
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
                        ErrorMessageSanitizer.sanitize(error, "Bilinmeyen hata")
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
