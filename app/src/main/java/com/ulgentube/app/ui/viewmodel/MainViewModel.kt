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
import com.ulgentube.app.domain.model.EqualizerPreset
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.FfmpegStudioAction
import com.ulgentube.app.domain.model.FfmpegStudioOptions
import com.ulgentube.app.domain.model.FollowedChannel
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.domain.model.PlaylistEntry
import com.ulgentube.app.domain.model.ThemeMode
import com.ulgentube.app.player.MiniPlayerState
import com.ulgentube.app.util.ErrorMessageSanitizer
import com.ulgentube.app.util.FormatUtils
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

    val followedChannels: StateFlow<List<FollowedChannel>> =
        container.downloadRepository.followedChannelsFlow
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

    private val _tempCacheBytes = MutableStateFlow(0L)
    val tempCacheBytes: StateFlow<Long> = _tempCacheBytes.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(false)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    private val _isVaultUnlocked = MutableStateFlow(false)
    val isVaultUnlocked: StateFlow<Boolean> = _isVaultUnlocked.asStateFlow()

    private val _isFetchingLyrics = MutableStateFlow(false)
    val isFetchingLyrics: StateFlow<Boolean> = _isFetchingLyrics.asStateFlow()

    private val _lyricsOverride = MutableStateFlow<String?>(null)
    val lyricsOverride: StateFlow<String?> = _lyricsOverride.asStateFlow()

    private val _fetchingPoToken = MutableStateFlow(false)
    val fetchingPoToken: StateFlow<Boolean> = _fetchingPoToken.asStateFlow()

    private val _isCheckingChannels = MutableStateFlow(false)
    val isCheckingChannels: StateFlow<Boolean> = _isCheckingChannels.asStateFlow()

    private var analyzeJob: Job? = null

    init {
        container.miniAudioPlayerManager.onSavePosition = { taskId, positionMs ->
            savePlaybackPosition(taskId, positionMs)
        }
        refreshTempCacheSize()
    }

    override fun onCleared() {
        container.miniAudioPlayerManager.onSavePosition = null
        super.onCleared()
    }

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
            _lyricsOverride.value = task.lyricsText
            container.miniAudioPlayerManager.play(task)
            if (settings.value.autoFetchLyrics && task.lyricsText.isNullOrBlank()) {
                fetchLyricsForTask(task.id)
            }
        }
    }

    fun playAudioQueue(tasks: List<DownloadTask>, startIndex: Int = 0) {
        if (tasks.isEmpty()) return
        val target = tasks.getOrNull(startIndex) ?: tasks.first()
        _lyricsOverride.value = target.lyricsText
        container.miniAudioPlayerManager.playQueue(
            tasks = tasks,
            startIndex = startIndex,
            playAsBackgroundVideoAudio = false
        )
        if (settings.value.autoFetchLyrics && target.lyricsText.isNullOrBlank()) {
            fetchLyricsForTask(target.id)
        }
    }

    fun playVideoAsBackgroundAudio(
        task: DownloadTask,
        startPositionMs: Long = task.lastPlaybackPositionMs
    ) {
        _lyricsOverride.value = task.lyricsText
        container.miniAudioPlayerManager.playAsBackgroundAudio(
            task = task,
            startPositionMs = startPositionMs
        )
    }

    fun playNextAudio() {
        container.miniAudioPlayerManager.playNext()
        val nextTask = miniPlayerState.value.currentTask
        _lyricsOverride.value = nextTask?.lyricsText
    }

    fun playPreviousAudio() {
        container.miniAudioPlayerManager.playPrevious()
        val prevTask = miniPlayerState.value.currentTask
        _lyricsOverride.value = prevTask?.lyricsText
    }

    fun toggleAudioShuffle() {
        container.miniAudioPlayerManager.toggleShuffle()
    }

    fun cycleAudioRepeatMode() {
        container.miniAudioPlayerManager.cycleRepeatMode()
    }

    fun togglePlayPause() {
        container.miniAudioPlayerManager.togglePlayPause()
    }

    fun pauseMiniPlayer() {
        container.miniAudioPlayerManager.pause()
    }

    fun seekTo(ms: Long) {
        container.miniAudioPlayerManager.seekTo(ms)
    }

    fun stopMiniPlayer() {
        _lyricsOverride.value = null
        container.miniAudioPlayerManager.stop()
    }

    fun savePlaybackPosition(taskId: String, positionMs: Long) {
        viewModelScope.launch {
            container.downloadRepository.savePlaybackPosition(taskId, positionMs)
        }
    }

    fun toggleFavorite(taskId: String) {
        viewModelScope.launch {
            container.downloadRepository.toggleFavorite(taskId)
        }
    }

    fun toggleVault(taskId: String) {
        viewModelScope.launch {
            val result = container.downloadRepository.toggleVault(taskId)
            result.fold(
                onSuccess = { inVault ->
                    _studioMessage.value = if (inVault) {
                        "Medya Özel Kasaya taşındı."
                    } else {
                        "Medya Özel Kasadan çıkarıldı."
                    }
                },
                onFailure = { error ->
                    _studioMessage.value = ErrorMessageSanitizer.sanitize(
                        throwable = error,
                        fallback = "Özel Kasa işlemi gerçekleştirilemedi."
                    )
                }
            )
        }
    }

    fun fetchLyricsForTask(taskId: String) {
        if (_isFetchingLyrics.value) return
        viewModelScope.launch {
            _isFetchingLyrics.value = true
            try {
                val lyrics = runCatching {
                    container.downloadRepository.fetchOrRefreshLyrics(taskId)
                }.getOrNull()
                if (!lyrics.isNullOrBlank()) {
                    _lyricsOverride.value = lyrics
                    val current = miniPlayerState.value.currentTask
                    if (current?.id == taskId) {
                        container.miniAudioPlayerManager.updateCurrentTaskMetadata(
                            current.copy(lyricsText = lyrics)
                        )
                    }
                }
            } finally {
                _isFetchingLyrics.value = false
            }
        }
    }

    fun unlockApp() {
        _isAppUnlocked.value = true
    }

    fun unlockVault() {
        _isVaultUnlocked.value = true
    }

    fun lockVault() {
        _isVaultUnlocked.value = false
    }

    // FFmpeg Medya Stüdyosu Pro
    fun runFfmpegStudioPro(
        task: DownloadTask,
        action: FfmpegStudioAction,
        options: FfmpegStudioOptions
    ) {
        if (_studioBusy.value) return
        viewModelScope.launch {
            _studioBusy.value = true
            _studioMessage.value = "${action.displayName} işleniyor..."
            waitForEngineIfInitializing()
            val result = container.downloadRepository.processFfmpegStudioPro(
                task = task,
                action = action,
                options = options
            )
            _studioMessage.value = result.fold(
                onSuccess = { created ->
                    "${action.displayName} tamamlandı: ${created.title}"
                },
                onFailure = { error ->
                    ErrorMessageSanitizer.sanitize(
                        throwable = error,
                        fallback = "Stüdyo işlemi tamamlanamadı."
                    )
                }
            )
            _studioBusy.value = false
            refreshTempCacheSize()
        }
    }

    // Takip Edilen Kanallar
    fun followChannel(urlOrHandle: String, autoDownload: Boolean = false) {
        viewModelScope.launch {
            val result = container.downloadRepository.followChannel(urlOrHandle, autoDownload)
            _studioMessage.value = result.fold(
                onSuccess = { channel -> "${channel.title} takibe alındı." },
                onFailure = { error ->
                    ErrorMessageSanitizer.sanitize(
                        throwable = error,
                        fallback = "Kanal takip edilemedi."
                    )
                }
            )
        }
    }

    fun unfollowChannel(channelId: String) {
        viewModelScope.launch {
            container.downloadRepository.unfollowChannel(channelId)
            _studioMessage.value = "Kanal takipten çıkarıldı."
        }
    }

    fun toggleChannelAutoDownload(channelId: String, enabled: Boolean) {
        viewModelScope.launch {
            container.downloadRepository.toggleChannelAutoDownload(channelId, enabled)
        }
    }

    fun checkFollowedChannels() {
        if (_isCheckingChannels.value) return
        viewModelScope.launch {
            _isCheckingChannels.value = true
            val result = container.downloadRepository.checkFollowedChannels()
            _studioMessage.value = result.fold(
                onSuccess = { updated ->
                    if (updated.isEmpty()) {
                        "Yeni video bulunamadı."
                    } else {
                        "${updated.size} kanalda yeni içerik tespit edildi!"
                    }
                },
                onFailure = { error ->
                    ErrorMessageSanitizer.sanitize(
                        throwable = error,
                        fallback = "Kanallar kontrol edilemedi."
                    )
                }
            )
            _isCheckingChannels.value = false
        }
    }

    // Depolama, PoToken ve Yedekleme
    fun refreshTempCacheSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { container.downloadRepository.calculateTempCacheBytes() }.getOrDefault(0L)
            }
            _tempCacheBytes.value = bytes
        }
    }

    fun clearTempCache() {
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) {
                runCatching { container.downloadRepository.clearTempCache() }.getOrDefault(0L)
            }
            _tempCacheBytes.value = withContext(Dispatchers.IO) {
                runCatching { container.downloadRepository.calculateTempCacheBytes() }.getOrDefault(0L)
            }
            val sizeLabel = FormatUtils.formatBytes(cleared).ifBlank { "0 B" }
            _studioMessage.value = "Geçici önbellek temizlendi ($sizeLabel boşaltıldı)."
        }
    }

    fun refreshPoToken() {
        if (_fetchingPoToken.value) return
        viewModelScope.launch {
            _fetchingPoToken.value = true
            val ok = runCatching {
                container.downloadRepository.refreshYouTubePoToken()
            }.getOrDefault(false)
            _studioMessage.value = if (ok) {
                "YouTube PoToken başarıyla yenilendi."
            } else {
                "PoToken yenilenemedi. İnternet bağlantınızı kontrol edin."
            }
            _fetchingPoToken.value = false
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val result = container.downloadRepository.exportBackup(uri)
            _studioMessage.value = result.fold(
                onSuccess = { count -> "Yedek dışa aktarıldı ($count öğe)." },
                onFailure = { error ->
                    ErrorMessageSanitizer.sanitize(
                        throwable = error,
                        fallback = "Yedek dışa aktarılamadı."
                    )
                }
            )
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val result = container.downloadRepository.importBackup(uri)
            _studioMessage.value = result.fold(
                onSuccess = { summary -> summary },
                onFailure = { error ->
                    ErrorMessageSanitizer.sanitize(
                        throwable = error,
                        fallback = "Yedek içe aktarılamadı."
                    )
                }
            )
        }
    }

    fun pause(taskId: String) {
        viewModelScope.launch {
            container.downloadRepository.pause(taskId)
        }
    }

    fun pauseAllActive() {
        viewModelScope.launch {
            container.downloadRepository.pauseAllActive()
        }
    }

    fun resumeAllPaused() {
        viewModelScope.launch {
            container.downloadRepository.resumeAllPaused()
        }
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

    fun syncWebViewCookies() {
        viewModelScope.launch {
            val result = container.cookieRepository.syncFromWebViewCookieManager()
            result.fold(
                onSuccess = { count ->
                    _hasCookies.value = container.cookieRepository.hasCookies()
                    container.settingsRepository.setCookiesEnabled(true)
                    _cookieMessage.value = "Tarayıcı oturumu kaydedildi ($count çerez)."
                },
                onFailure = { error ->
                    _cookieMessage.value = ErrorMessageSanitizer.sanitize(
                        throwable = error,
                        fallback = "Tarayıcı çerezleri kaydedilemedi."
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

    fun setParallelDownloadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setParallelDownloadEnabled(enabled)
        }
    }

    fun setParallelConnectionCount(count: Int) {
        viewModelScope.launch {
            container.settingsRepository.setParallelConnectionCount(count)
        }
    }

    fun setMaxConcurrentDownloads(count: Int) {
        viewModelScope.launch {
            container.settingsRepository.setMaxConcurrentDownloads(count)
        }
    }

    fun setMaxDownloadSpeedKbps(kbps: Int) {
        viewModelScope.launch {
            container.settingsRepository.setMaxDownloadSpeedKbps(kbps)
        }
    }

    fun setScheduledDownloadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setScheduledDownloadEnabled(enabled)
        }
    }

    fun setScheduleWindowHours(startHour: Int, endHour: Int) {
        viewModelScope.launch {
            container.settingsRepository.setScheduleWindowHours(startHour, endHour)
        }
    }

    fun setPoTokenEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setPoTokenEnabled(enabled)
        }
    }

    fun setAutoFetchLyrics(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setAutoFetchLyrics(enabled)
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setAppLockEnabled(enabled)
            if (enabled) {
                _isAppUnlocked.value = true
            }
        }
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        container.miniAudioPlayerManager.applyEqualizerPreset(preset)
        viewModelScope.launch {
            container.settingsRepository.setEqualizerPreset(preset)
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
