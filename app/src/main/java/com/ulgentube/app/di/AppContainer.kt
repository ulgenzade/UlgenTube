package com.ulgentube.app.di

import android.content.Context
import com.ulgentube.app.data.DownloadRepository
import com.ulgentube.app.data.local.DownloadDao
import com.ulgentube.app.data.local.SettingsRepository
import com.ulgentube.app.data.local.UlgenTubeDatabase
import com.ulgentube.app.engine.CookieRepository
import com.ulgentube.app.engine.HybridMediaExtractor
import com.ulgentube.app.engine.LocalFfmpegProcessor
import com.ulgentube.app.engine.MediaStoreSaver
import com.ulgentube.app.engine.SocialImageExtractor
import com.ulgentube.app.engine.YtDlpEngine
import com.ulgentube.app.player.MiniAudioPlayerManager
import com.ulgentube.app.util.NetworkSecurityGuard
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * UlgenTube uygulama genelinde bağımlılıkları yöneten merkezi konteyner arayüzü.
 */
interface AppContainer {
    val applicationContext: Context
    val applicationScope: CoroutineScope
    val okHttpClient: OkHttpClient
    val database: UlgenTubeDatabase
    val downloadDao: DownloadDao
    val settingsRepository: SettingsRepository
    val cookieRepository: CookieRepository
    val ytDlpEngine: YtDlpEngine
    val socialImageExtractor: SocialImageExtractor
    val hybridMediaExtractor: HybridMediaExtractor
    val mediaStoreSaver: MediaStoreSaver
    val localFfmpegProcessor: LocalFfmpegProcessor
    val miniAudioPlayerManager: MiniAudioPlayerManager
    val downloadRepository: DownloadRepository
}

/**
 * Varsayılan [AppContainer] uygulaması (hafif ServiceLocator deseni).
 */
class DefaultAppContainer(
    override val applicationContext: Context,
    override val applicationScope: CoroutineScope,
    private val isAria2cReady: () -> Boolean = { false }
) : AppContainer {

    override val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(NetworkSecurityGuard.SafePublicDns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override val database: UlgenTubeDatabase by lazy {
        UlgenTubeDatabase.getInstance(applicationContext)
    }

    override val downloadDao: DownloadDao by lazy {
        database.downloadDao()
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }

    override val cookieRepository: CookieRepository by lazy {
        CookieRepository(applicationContext)
    }

    override val ytDlpEngine: YtDlpEngine by lazy {
        YtDlpEngine(
            isAria2cReady = isAria2cReady,
            cookieRepository = cookieRepository
        )
    }

    override val socialImageExtractor: SocialImageExtractor by lazy {
        SocialImageExtractor(okHttpClient = okHttpClient)
    }

    override val hybridMediaExtractor: HybridMediaExtractor by lazy {
        HybridMediaExtractor(
            ytDlpEngine = ytDlpEngine,
            socialImageExtractor = socialImageExtractor
        )
    }

    override val mediaStoreSaver: MediaStoreSaver by lazy {
        MediaStoreSaver(applicationContext)
    }

    override val localFfmpegProcessor: LocalFfmpegProcessor by lazy {
        LocalFfmpegProcessor(
            context = applicationContext,
            mediaStoreSaver = mediaStoreSaver
        )
    }

    override val miniAudioPlayerManager: MiniAudioPlayerManager by lazy {
        MiniAudioPlayerManager(applicationContext)
    }

    override val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(
            context = applicationContext,
            downloadDao = downloadDao,
            settingsRepository = settingsRepository,
            ytDlpEngine = ytDlpEngine,
            socialImageExtractor = socialImageExtractor,
            mediaStoreSaver = mediaStoreSaver
        )
    }
}
