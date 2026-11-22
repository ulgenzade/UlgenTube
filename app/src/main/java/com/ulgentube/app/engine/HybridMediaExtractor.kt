package com.ulgentube.app.engine

import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Çift motorlu akıllı medya ayrıştırıcı:
 * - Doğrudan görsellerde direkt [SocialImageExtractor] kullanır.
 * - X/Twitter ve Reddit'te hızlı fotoğraf/galeri analizi ile [YtDlpEngine] video/ses analizini birleştirir.
 * - Diğer tüm platformlarda önce [YtDlpEngine] dener, başarısız olursa (fotoğraf postu, pin vb.)
 *   otomatik [SocialImageExtractor] fallback'ine geçer.
 */
class HybridMediaExtractor(
    private val ytDlpEngine: YtDlpEngine,
    private val socialImageExtractor: SocialImageExtractor,
    private val youTubeInnertubeEngine: YouTubeInnertubeEngine? = null,
    private val appContext: android.content.Context? = null,
    private val settingsRepository: com.ulgentube.app.data.local.SettingsRepository? = null,
    private val cookieRepository: CookieRepository? = null,
    private val poTokenProvider: YouTubePoTokenProvider? = null
) {

    /**
     * Ham metin veya URL'yi güvenli şekilde temizler ve birleşik [ExtractedMedia] döndürür.
     */
    suspend fun analyze(rawInput: String): Result<ExtractedMedia> = withContext(Dispatchers.IO) {
        val cleanUrl = UrlSanitizer.extractFirstValidUrl(rawInput)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Geçerli bir http:// veya https:// bağlantısı bulunamadı.")
            )

        val platform = UrlSanitizer.detectPlatform(cleanUrl)

        runCatching {
            if (platform != PlatformType.DIRECT_IMAGE && YtDlpEngine.isLikelyPlaylistUrl(cleanUrl)) {
                if (platform == PlatformType.YOUTUBE && youTubeInnertubeEngine != null) {
                    val fastAlbumOrPlaylist = runCatching {
                        youTubeInnertubeEngine.fetchPlaylistOrAlbumInfo(cleanUrl)
                    }.getOrNull()
                    if (fastAlbumOrPlaylist != null && fastAlbumOrPlaylist.playlistEntries.size >= 2) {
                        return@runCatching fastAlbumOrPlaylist.copy(isPlaylist = true)
                    }
                }
                val playlistMedia = runCatching {
                    ytDlpEngine.fetchPlaylistInfo(cleanUrl, platform)
                }.getOrNull()
                if (playlistMedia != null && playlistMedia.playlistEntries.size >= 2) {
                    return@runCatching playlistMedia.copy(isPlaylist = true)
                }
            }

            val extracted = when (platform) {
                PlatformType.DIRECT_IMAGE -> {
                    socialImageExtractor.extract(cleanUrl, platform)
                }

                PlatformType.YOUTUBE -> {
                    analyzeYouTubeFast(cleanUrl)
                }

                PlatformType.TWITTER_X -> {
                    analyzeSocialHybrid(cleanUrl, platform)
                }

                PlatformType.REDDIT -> {
                    analyzeRedditHybrid(cleanUrl)
                }

                PlatformType.INSTAGRAM -> {
                    analyzeInstagramFast(cleanUrl)
                }

                else -> {
                    analyzeYtDlpWithImageFallback(cleanUrl, platform)
                }
            }
            ensureAudioOptionsForVideo(extracted)
        }.onFailure { err ->
            com.ulgentube.app.util.DiagnosticLogManager.recordError(
                tag = "Analyze",
                summary = "Bağlantı çözümlenemedi (${platform.displayName})",
                throwable = err,
                metadata = mapOf("url" to cleanUrl, "platform" to platform.name)
            )
        }
    }

    /**
     * Twitter/X ve Reddit için önce hızlıca [SocialImageExtractor] çalıştırır.
     * Video içeriyorsa (veya görsel bulunamadıysa) [YtDlpEngine] ile yüksek kalite video/ses
     * seçeneklerini de alıp iki sonucu tek bir [ExtractedMedia] içinde birleştirir.
     */
    private suspend fun analyzeSocialHybrid(
        url: String,
        platform: PlatformType
    ): ExtractedMedia {
        val socialResult = runCatching {
            socialImageExtractor.extract(url, platform)
        }.getOrNull()

        val shouldQueryYtDlp = socialResult == null ||
            socialResult.videoFormats.isNotEmpty() ||
            socialResult.imageItems.isEmpty()

        val ytResult = if (shouldQueryYtDlp) {
            runCatching { ytDlpEngine.fetchMediaInfo(url, platform) }.getOrNull()
        } else {
            null
        }

        return when {
            ytResult != null && socialResult != null -> mergeResults(
                primary = ytResult,
                secondary = socialResult
            )
            ytResult != null -> ytResult
            socialResult != null -> socialResult
            else -> throw IllegalStateException(
                "${platform.displayName} içeriği analiz edilemedi. Bağlantının herkese açık olduğundan emin olun."
            )
        }
    }

    /**
     * Reddit için hızlı görsel/galeri/crosspost çıkarma ([SocialImageExtractor]) ile
     * sesli `v.redd.it` mux ([YtDlpEngine]) arasında akıllı hibrit yönlendirme yapar.
     * Saf görsel/galeri gönderileri yt-dlp hatasına takılmadan anında döndürülür.
     */
    private suspend fun analyzeRedditHybrid(url: String): ExtractedMedia {
        val socialAttempt = runCatching {
            socialImageExtractor.extract(url, PlatformType.REDDIT)
        }
        val socialResult = socialAttempt.getOrNull()

        // Saf görsel/galeri gönderisi ise yt-dlp'ye düşmeden doğrudan döndür (18+ yer tutucu sayfası hariç)
        val isRedditAgeGate = socialResult?.title?.let { t ->
            val lower = t.lowercase(java.util.Locale.ROOT)
            lower.contains("yetişkinlere yönelik") || lower.contains("mature content") || lower.contains("over 18")
        } == true
        if (socialResult != null && !isRedditAgeGate && socialResult.imageItems.isNotEmpty() && socialResult.videoFormats.isEmpty()) {
            return socialResult
        }

        val targetUrl = socialResult?.originalUrl?.takeIf { it.isNotBlank() }
            ?: runCatching { socialImageExtractor.resolveRedditCanonicalUrl(url) }.getOrDefault(url)

        val useCookies = cookieRepository?.hasCookies() == true
        val ytAttempt = runCatching {
            ytDlpEngine.fetchMediaInfo(targetUrl, PlatformType.REDDIT, useCookies = useCookies)
        }
        val ytResult = ytAttempt.getOrNull()

        return when {
            ytResult != null && socialResult != null -> mergeResults(
                primary = ytResult,
                secondary = socialResult
            )
            ytResult != null -> ytResult
            socialResult != null -> socialResult
            else -> throw IllegalStateException(
                com.ulgentube.app.util.ErrorMessageSanitizer.sanitize(
                    throwable = ytAttempt.exceptionOrNull() ?: socialAttempt.exceptionOrNull(),
                    fallback = "Reddit içeriği analiz edilemedi. Bağlantının herkese açık olduğundan emin olun."
                )
            )
        }
    }

    /**
     * YouTube için çerez etkin ve kayıtlıysa önce oturumlu akışları (`itag 356` vb.) yakalamak için
     * `yt-dlp` dener, aksi halde önce hızlı Innertube (`IOS_PRODUCER`/`IOS`/`ANDROID`) motorunu
     * çalıştırır; başarısız olursa diğer motora otomatik düşer.
     */
    private suspend fun analyzeYouTubeFast(url: String): ExtractedMedia {
        val settings = settingsRepository?.getCurrentSettings()
        val useCookies = settings?.cookiesEnabled == true &&
            cookieRepository?.hasCookies() == true

        val poPair = if (settings?.poTokenEnabled == true) {
            runCatching { poTokenProvider?.getOrFetchPoToken() }.getOrNull()
        } else {
            null
        }
        val poToken = poPair?.poToken?.takeIf { it.isNotBlank() }
        val visitorData = poPair?.visitorData?.takeIf { it.isNotBlank() }

        var ytError: Throwable? = null
        var innertubeError: Throwable? = null

        if (useCookies) {
            val ytResult = runCatching {
                ytDlpEngine.fetchMediaInfo(
                    url = url,
                    platform = PlatformType.YOUTUBE,
                    useCookies = true,
                    poToken = poToken,
                    visitorData = visitorData
                )
            }
            if (ytResult.isSuccess) return ytResult.getOrThrow()
            ytError = ytResult.exceptionOrNull()

            val innertubeResult = youTubeInnertubeEngine?.let { engine ->
                runCatching {
                    engine.fetchVideoInfo(
                        url = url,
                        poToken = poToken,
                        visitorData = visitorData
                    )
                }
            }
            if (innertubeResult != null && innertubeResult.isSuccess) return innertubeResult.getOrThrow()
            innertubeError = innertubeResult?.exceptionOrNull()
        } else {
            val innertubeResult = youTubeInnertubeEngine?.let { engine ->
                runCatching {
                    engine.fetchVideoInfo(
                        url = url,
                        poToken = poToken,
                        visitorData = visitorData
                    )
                }
            }
            if (innertubeResult != null && innertubeResult.isSuccess) return innertubeResult.getOrThrow()
            innertubeError = innertubeResult?.exceptionOrNull()

            val ytResult = runCatching {
                ytDlpEngine.fetchMediaInfo(
                    url = url,
                    platform = PlatformType.YOUTUBE,
                    useCookies = false,
                    poToken = poToken,
                    visitorData = visitorData
                )
            }
            if (ytResult.isSuccess) return ytResult.getOrThrow()
            ytError = ytResult.exceptionOrNull()
        }

        val err = ytError ?: innertubeError
        throw IllegalStateException(
            com.ulgentube.app.util.ErrorMessageSanitizer.sanitize(
                throwable = err,
                fallback = "YouTube videosu analiz edilemedi. Lütfen tekrar deneyin."
            )
        )
    }

    /**
     * Instagram gönderileri için 3 aşamalı Snaptube tarzı çözümleme:
     * 1) Hızlı HTTP Proxy (`vxinstagram`/`eeinstagram`) / Bot HTML (`facebookexternalhit`) / GraphQL / Embed ([SocialImageExtractor])
     * 2) Görünmez Android WebView + CDN `.mp4` ağ yakalayıcı ([WebViewMediaSniffer])
     * 3) `yt-dlp` (varsa `cookies.txt` ile)
     */
    private suspend fun analyzeInstagramFast(url: String): ExtractedMedia {
        if (SocialImageExtractor.isInstagramStoryOrProfileUrl(url)) {
            return analyzeInstagramStoryOrProfile(url)
        }

        val fastResult = runCatching {
            socialImageExtractor.extract(url, PlatformType.INSTAGRAM)
        }
        if (fastResult.isSuccess) {
            return fastResult.getOrThrow()
        }

        val ctx = appContext
        if (ctx != null) {
            val decodedUrl = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
            val shortcodeRegex = Regex("""instagram\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            val shortcode = shortcodeRegex.find(url)?.groupValues?.getOrNull(1)
                ?: shortcodeRegex.find(decodedUrl)?.groupValues?.getOrNull(1)
            val webViewMedia = runCatching {
                WebViewMediaSniffer.extractInstagramWithWebView(ctx, url, shortcode)
            }.getOrNull()
            if (webViewMedia != null && (webViewMedia.videoFormats.isNotEmpty() || webViewMedia.imageItems.isNotEmpty())) {
                return webViewMedia
            }
        }

        val ytResult = runCatching {
            ytDlpEngine.fetchMediaInfo(url, PlatformType.INSTAGRAM)
        }
        if (ytResult.isSuccess) {
            return ytResult.getOrThrow()
        }

        throw IllegalStateException(
            com.ulgentube.app.util.ErrorMessageSanitizer.sanitize(
                throwable = ytResult.exceptionOrNull() ?: fastResult.exceptionOrNull(),
                fallback = "Instagram içeriği analiz edilemedi. Ayarlar'dan 'Tarayıcı ile Giriş Yap' seçeneğiyle Instagram oturumu açabilirsiniz."
            )
        )
    }

    /**
     * Instagram Hikaye (`/stories/`), Öne Çıkan (`/highlights/`, `/s/`) ve Profil URL'lerini
     * çerez destekli [YtDlpEngine] (playlist/entries) ve [SocialImageExtractor] arasında
     * akıllıca yönlendirerek en zengin sonucu döndürür.
     */
    private suspend fun analyzeInstagramStoryOrProfile(url: String): ExtractedMedia {
        val hasCookies = cookieRepository?.hasCookies() == true

        val ytPlaylistResult = if (hasCookies) {
            runCatching { ytDlpEngine.fetchPlaylistInfo(url, PlatformType.INSTAGRAM) }.getOrNull()
        } else {
            null
        }

        val socialResult = runCatching {
            socialImageExtractor.extract(url, PlatformType.INSTAGRAM)
        }
        val socialMedia = socialResult.getOrNull()

        if (ytPlaylistResult != null && socialMedia != null) {
            val ytScore = ytPlaylistResult.playlistEntries.size * 2 +
                ytPlaylistResult.videoFormats.size +
                ytPlaylistResult.imageItems.size
            val socialScore = socialMedia.playlistEntries.size * 2 +
                socialMedia.videoFormats.size +
                socialMedia.imageItems.size

            return if (ytScore >= socialScore) {
                ytPlaylistResult.copy(
                    thumbnailUrl = ytPlaylistResult.thumbnailUrl ?: socialMedia.thumbnailUrl,
                    imageItems = (ytPlaylistResult.imageItems + socialMedia.imageItems).distinctBy { it.url }
                )
            } else {
                socialMedia.copy(
                    playlistEntries = socialMedia.playlistEntries.ifEmpty { ytPlaylistResult.playlistEntries },
                    isPlaylist = socialMedia.isPlaylist || ytPlaylistResult.playlistEntries.size >= 2
                )
            }
        }

        if (ytPlaylistResult != null) return ytPlaylistResult
        if (socialMedia != null) return socialMedia

        val ytSingleResult = runCatching {
            ytDlpEngine.fetchMediaInfo(url, PlatformType.INSTAGRAM, useCookies = hasCookies)
        }
        if (ytSingleResult.isSuccess) {
            return ytSingleResult.getOrThrow()
        }

        // Son yedek: Oturum açık dahili WebView üzerinden hikaye medyasını yakala
        val ctx = appContext
        if (ctx != null) {
            val webViewMedia = runCatching {
                WebViewMediaSniffer.extractInstagramWithWebView(ctx, url, null)
            }.getOrNull()
            if (webViewMedia != null && (webViewMedia.videoFormats.isNotEmpty() || webViewMedia.imageItems.isNotEmpty())) {
                return webViewMedia
            }
        }

        throw IllegalStateException(
            com.ulgentube.app.util.ErrorMessageSanitizer.sanitize(
                throwable = ytSingleResult.exceptionOrNull() ?: socialResult.exceptionOrNull(),
                fallback = "Instagram hikaye/profil içeriği analiz edilemedi. Ayarlar'dan Instagram oturumu açarak tekrar deneyin."
            )
        )
    }

    /**
     * YouTube, TikTok, Instagram, Facebook, Pinterest, SoundCloud vb. sitelerde
     * önce yt-dlp dener; fotoğraf gönderisi veya desteklenmeyen sayfa durumunda görsel çıkarıcıya düşer.
     */
    private suspend fun analyzeYtDlpWithImageFallback(
        url: String,
        platform: PlatformType
    ): ExtractedMedia {
        val ytResult = runCatching {
            ytDlpEngine.fetchMediaInfo(url, platform)
        }

        if (ytResult.isSuccess) {
            return ytResult.getOrThrow()
        }

        // Saf video/ses platformlarında (YouTube, Kick, Twitch vb.) yt-dlp başarısız olursa
        // kullanıcıyı yanıltacak şekilde yalnızca sayfa kapak fotoğrafına düşme; gerçek hatayı bildir.
        val allowImageFallback = platform !in setOf(
            PlatformType.YOUTUBE,
            PlatformType.KICK,
            PlatformType.TWITCH,
            PlatformType.SOUNDCLOUD,
            PlatformType.VIMEO
        )

        val imageFallback = if (allowImageFallback) {
            runCatching { socialImageExtractor.extract(url, platform) }
        } else {
            Result.failure(ytResult.exceptionOrNull() ?: IllegalStateException("Bağlantı analiz edilemedi."))
        }

        if (imageFallback.isSuccess) {
            return imageFallback.getOrThrow()
        }

        val ytError = ytResult.exceptionOrNull()
        val imgError = imageFallback.exceptionOrNull()
        throw IllegalStateException(
            com.ulgentube.app.util.ErrorMessageSanitizer.sanitize(
                throwable = ytError ?: imgError,
                fallback = "Bağlantı analiz edilemedi. Ayarlar'dan yt-dlp motorunu güncelleyip tekrar deneyin."
            )
        )
    }

    /**
     * İki motorun çıktılarını (video/ses formatları + tüm fotoğraflar/kapak görseli) tekilleştirerek birleştirir.
     */
    private fun mergeResults(
        primary: ExtractedMedia,
        secondary: ExtractedMedia
    ): ExtractedMedia {
        val mergedImages = buildList {
            val seen = linkedSetOf<String>()
            secondary.imageItems.forEach { item ->
                if (seen.add(item.url)) add(item)
            }
            primary.imageItems.forEach { item ->
                if (seen.add(item.url)) add(item)
            }
        }

        val mergedVideos = primary.videoFormats.ifEmpty { secondary.videoFormats }
        val duration = primary.durationSeconds ?: secondary.durationSeconds
        val mergedAudio = primary.audioFormats.ifEmpty { secondary.audioFormats }.ifEmpty {
            if (mergedVideos.isNotEmpty()) {
                YtDlpEngine.buildDefaultAudioFormats(
                    formats = emptyList(),
                    durationSeconds = duration,
                    directSourceUrl = mergedVideos.firstOrNull()?.directUrl
                )
            } else {
                emptyList()
            }
        }

        return ExtractedMedia(
            originalUrl = primary.originalUrl,
            title = primary.title.takeIf { it.isNotBlank() && it != "Başlıksız Medya" }
                ?: secondary.title,
            author = primary.author ?: secondary.author,
            durationSeconds = duration,
            thumbnailUrl = primary.thumbnailUrl ?: secondary.thumbnailUrl ?: mergedImages.firstOrNull()?.url,
            platform = primary.platform,
            videoFormats = mergedVideos,
            audioFormats = mergedAudio,
            imageItems = mergedImages,
            subtitles = primary.subtitles.ifEmpty { secondary.subtitles }
        )
    }

    /**
     * Video akışı bulunan hiçbir sonuçta sese çevirme seçeneklerinin boş kalmamasını garanti eder.
     */
    private fun ensureAudioOptionsForVideo(media: ExtractedMedia): ExtractedMedia {
        if (media.videoFormats.isEmpty() || media.audioFormats.isNotEmpty()) {
            return media
        }
        return media.copy(
            audioFormats = YtDlpEngine.buildDefaultAudioFormats(
                formats = emptyList(),
                durationSeconds = media.durationSeconds,
                directSourceUrl = media.videoFormats.firstOrNull()?.directUrl
            )
        )
    }
}
