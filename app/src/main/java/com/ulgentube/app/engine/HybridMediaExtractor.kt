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
    private val socialImageExtractor: SocialImageExtractor
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
            val extracted = when (platform) {
                PlatformType.DIRECT_IMAGE -> {
                    socialImageExtractor.extract(cleanUrl, platform)
                }

                PlatformType.TWITTER_X, PlatformType.REDDIT -> {
                    analyzeSocialHybrid(cleanUrl, platform)
                }

                else -> {
                    analyzeYtDlpWithImageFallback(cleanUrl, platform)
                }
            }
            ensureAudioOptionsForVideo(extracted)
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

        val imageFallback = runCatching {
            socialImageExtractor.extract(url, platform)
        }

        if (imageFallback.isSuccess) {
            return imageFallback.getOrThrow()
        }

        val ytError = ytResult.exceptionOrNull()
        val imgError = imageFallback.exceptionOrNull()
        throw IllegalStateException(
            ytError?.localizedMessage
                ?: imgError?.localizedMessage
                ?: "Bağlantı analiz edilemedi."
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
            imageItems = mergedImages
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
