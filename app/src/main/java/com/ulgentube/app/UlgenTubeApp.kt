package com.ulgentube.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.ulgentube.app.di.AppContainer
import com.ulgentube.app.di.DefaultAppContainer
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * youtubedl-android yerel motor bileşenlerinin (yt-dlp, FFmpeg, Aria2c)
 * başlatılma yaşam döngüsünü temsil eden durum modeli.
 */
sealed interface EngineInitState {
    /** Başlatma henüz tetiklenmedi. */
    data object Idle : EngineInitState

    /** Yerel ikili dosyalar arka planda hazırlanıyor. */
    data object Initializing : EngineInitState

    /** Temel indirme motoru kullanıma hazır. */
    data class Ready(
        val youtubeDlReady: Boolean,
        val ffmpegReady: Boolean,
        val aria2cReady: Boolean
    ) : EngineInitState

    /** Başlatma sırasında kritik bir hata oluştu. */
    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : EngineInitState
}

/**
 * UlgenTube ana Application sınıfı.
 * - Uygulama genelinde tekil [AppContainer] örneğini oluşturur.
 * - Bildirim kanallarını kaydeder (`downloads_channel`, `downloads_done_channel`).
 * - ANR oluşmaması için YoutubeDL, FFmpeg ve Aria2c başlatma işlemlerini
 *   [Dispatchers.IO] üzerinde arka planda güvenli şekilde yürütür.
 */
class UlgenTubeApp : Application() {

    /** Uygulama ömrü boyunca yaşayan arka plan Coroutine kapsamı. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Uygulama bağımlılık konteyneri. */
    lateinit var container: AppContainer
        private set

    private val _engineInitState = MutableStateFlow<EngineInitState>(EngineInitState.Idle)

    /** Yerel medya motorlarının anlık başlatılma durumu. */
    val engineInitState: StateFlow<EngineInitState> = _engineInitState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        container = DefaultAppContainer(
            applicationContext = applicationContext,
            applicationScope = applicationScope,
            isAria2cReady = {
                (_engineInitState.value as? EngineInitState.Ready)?.aria2cReady == true
            }
        )
        initializeEngines()
    }

    /**
     * İndirme ilerleme ve tamamlandı bildirim kanallarını Android 8.0+ (API 26+) için oluşturur.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return

        val progressChannel = NotificationChannel(
            CHANNEL_DOWNLOADS_PROGRESS,
            "Aktif İndirmeler",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Devam eden medya indirmelerinin canlı ilerleme durumu"
            setShowBadge(false)
        }

        val doneChannel = NotificationChannel(
            CHANNEL_DOWNLOADS_DONE,
            "Tamamlanan İndirmeler",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Tamamlanan medya indirme bildirimleri"
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(doneChannel)
    }

    /**
     * YoutubeDL, FFmpeg ve Aria2c kütüphanelerini arka plan iş parçacığında başlatır.
     * Hata durumunda tekrar çağrılabilir.
     */
    fun initializeEngines() {
        if (_engineInitState.value is EngineInitState.Initializing ||
            _engineInitState.value is EngineInitState.Ready
        ) {
            return
        }

        _engineInitState.value = EngineInitState.Initializing

        applicationScope.launch {
            val ytDlResult = runCatching {
                YoutubeDL.getInstance().init(this@UlgenTubeApp)
            }

            if (ytDlResult.isFailure) {
                val error = ytDlResult.exceptionOrNull()
                Log.e(TAG, "YoutubeDL başlatılamadı", error)
                _engineInitState.value = EngineInitState.Error(
                    message = error?.localizedMessage ?: "İndirme motoru başlatılamadı.",
                    throwable = error
                )
                return@launch
            }

            val ffmpegReady = runCatching {
                FFmpeg.getInstance().init(this@UlgenTubeApp)
                true
            }.onFailure { error ->
                Log.w(TAG, "FFmpeg başlatılamadı; dönüştürme özellikleri kısıtlı olabilir.", error)
            }.getOrDefault(false)

            val aria2cReady = runCatching {
                Aria2c.getInstance().init(this@UlgenTubeApp)
                true
            }.onFailure { error ->
                Log.w(TAG, "Aria2c başlatılamadı; standart indirici kullanılacak.", error)
            }.getOrDefault(false)

            Log.i(
                TAG,
                "Motorlar hazır -> YoutubeDL: true, FFmpeg: $ffmpegReady, Aria2c: $aria2cReady"
            )
            _engineInitState.value = EngineInitState.Ready(
                youtubeDlReady = true,
                ffmpegReady = ffmpegReady,
                aria2cReady = aria2cReady
            )
        }
    }

    companion object {
        private const val TAG = "UlgenTubeApp"

        const val CHANNEL_DOWNLOADS_PROGRESS = "downloads_channel"
        const val CHANNEL_DOWNLOADS_DONE = "downloads_done_channel"

        @Volatile
        lateinit var instance: UlgenTubeApp
            private set
    }
}
