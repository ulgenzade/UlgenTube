package com.ulgentube.app.di

import android.content.Context
import com.ulgentube.app.data.BackupRestoreManager
import com.ulgentube.app.data.DownloadRepository
import com.ulgentube.app.data.local.DownloadDao
import com.ulgentube.app.data.local.FollowedChannelDao
import com.ulgentube.app.data.local.SettingsRepository
import com.ulgentube.app.data.local.UlgenTubeDatabase
import com.ulgentube.app.engine.ChannelFeedChecker
import com.ulgentube.app.engine.CookieRepository
import com.ulgentube.app.engine.HybridMediaExtractor
import com.ulgentube.app.engine.LocalFfmpegProcessor
import com.ulgentube.app.engine.LrcLyricsFetcher
import com.ulgentube.app.engine.MediaStoreSaver
import com.ulgentube.app.engine.ParallelRangeDownloader
import com.ulgentube.app.engine.SocialImageExtractor
import com.ulgentube.app.engine.YouTubeInnertubeEngine
import com.ulgentube.app.engine.YouTubePoTokenProvider
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
    val followedChannelDao: FollowedChannelDao
    val settingsRepository: SettingsRepository
    val cookieRepository: CookieRepository
    val parallelRangeDownloader: ParallelRangeDownloader
    val ytDlpEngine: YtDlpEngine
    val socialImageExtractor: SocialImageExtractor
    val youTubeInnertubeEngine: YouTubeInnertubeEngine
    val youTubePoTokenProvider: YouTubePoTokenProvider
    val lrcLyricsFetcher: LrcLyricsFetcher
    val channelFeedChecker: ChannelFeedChecker
    val backupRestoreManager: BackupRestoreManager
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

    override val followedChannelDao: FollowedChannelDao by lazy {
        database.followedChannelDao()
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }

    override val cookieRepository: CookieRepository by lazy {
        CookieRepository(applicationContext)
    }

    override val parallelRangeDownloader: ParallelRangeDownloader by lazy {
        ParallelRangeDownloader(okHttpClient)
    }

    override val ytDlpEngine: YtDlpEngine by lazy {
        YtDlpEngine(
            isAria2cReady = isAria2cReady,
            cookieRepository = cookieRepository
        )
    }

    override val socialImageExtractor: SocialImageExtractor by lazy {
        SocialImageExtractor(
            okHttpClient = okHttpClient,
            parallelRangeDownloader = parallelRangeDownloader,
            cookieRepository = cookieRepository
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

    override val youTubePoTokenProvider: YouTubePoTokenProvider by lazy {
        YouTubePoTokenProvider(
            context = applicationContext,
            settingsRepository = settingsRepository
        )
    }

    override val youTubeInnertubeEngine: YouTubeInnertubeEngine by lazy {
        YouTubeInnertubeEngine(
            okHttpClient = okHttpClient,
            localFfmpegProcessor = localFfmpegProcessor,
            parallelRangeDownloader = parallelRangeDownloader,
            cookieRepository = cookieRepository,
            poTokenProvider = youTubePoTokenProvider
        )
    }

    override val lrcLyricsFetcher: LrcLyricsFetcher by lazy {
        LrcLyricsFetcher(okHttpClient)
    }

    override val channelFeedChecker: ChannelFeedChecker by lazy {
        ChannelFeedChecker(okHttpClient)
    }

    override val backupRestoreManager: BackupRestoreManager by lazy {
        BackupRestoreManager(
            context = applicationContext,
            downloadDao = downloadDao,
            followedChannelDao = followedChannelDao,
            settingsRepository = settingsRepository
        )
    }

    override val hybridMediaExtractor: HybridMediaExtractor by lazy {
        HybridMediaExtractor(
            ytDlpEngine = ytDlpEngine,
            socialImageExtractor = socialImageExtractor,
            youTubeInnertubeEngine = youTubeInnertubeEngine,
            appContext = applicationContext,
            settingsRepository = settingsRepository,
            cookieRepository = cookieRepository,
            poTokenProvider = youTubePoTokenProvider
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
            youTubeInnertubeEngine = youTubeInnertubeEngine,
            mediaStoreSaver = mediaStoreSaver,
            localFfmpegProcessor = localFfmpegProcessor,
            followedChannelDao = followedChannelDao,
            channelFeedChecker = channelFeedChecker,
            lrcLyricsFetcher = lrcLyricsFetcher,
            youTubePoTokenProvider = youTubePoTokenProvider,
            backupRestoreManager = backupRestoreManager,
            parallelRangeDownloader = parallelRangeDownloader
        )
    }
}
