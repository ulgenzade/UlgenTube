package com.ulgentube.app

import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.util.UrlSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSanitizerTest {

    // -------------------------------------------------------------------------
    // Karışık Türkçe / İngilizce paylaşım metinlerinden URL çıkarma testleri
    // -------------------------------------------------------------------------

    @Test
    fun extractFirstValidUrl_youtubeAndYoutuBeFromTurkishShareText() {
        val ytText = "Bu videoyu mutlaka izle! https://www.youtube.com/watch?v=dQw4w9WgXcQ çok iyi."
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            UrlSanitizer.extractFirstValidUrl(ytText)
        )

        val shortText = "Harika klip (https://youtu.be/abc123XYZ?si=test_param)!"
        assertEquals(
            "https://youtu.be/abc123XYZ?si=test_param",
            UrlSanitizer.extractFirstValidUrl(shortText)
        )
    }

    @Test
    fun extractFirstValidUrl_instagramReelsAndPost() {
        val reelShare = "Check out this Reel on Instagram: https://www.instagram.com/reel/Cx123AbCdEf/?igsh=xyz,"
        assertEquals(
            "https://www.instagram.com/reel/Cx123AbCdEf/?igsh=xyz",
            UrlSanitizer.extractFirstValidUrl(reelShare)
        )

        val postShare = "Gönderi paylaşımı: https://instagram.com/p/Cy987ZyXwVu/"
        assertEquals(
            "https://instagram.com/p/Cy987ZyXwVu/",
            UrlSanitizer.extractFirstValidUrl(postShare)
        )
    }

    @Test
    fun extractFirstValidUrl_tiktokTwitterRedditPinterestSoundcloud() {
        val tiktok = "TikTok'ta şuna bak: https://vm.tiktok.com/ZM6abc123/ #kesfet"
        assertEquals("https://vm.tiktok.com/ZM6abc123/", UrlSanitizer.extractFirstValidUrl(tiktok))

        val twitter = "Gündem haberi -> https://x.com/ulgentube/status/1899999999999999999."
        assertEquals(
            "https://x.com/ulgentube/status/1899999999999999999",
            UrlSanitizer.extractFirstValidUrl(twitter)
        )

        val reddit = "Look at this thread https://www.reddit.com/r/androiddev/comments/1abcde/ulgentube_test/"
        assertEquals(
            "https://www.reddit.com/r/androiddev/comments/1abcde/ulgentube_test/",
            UrlSanitizer.extractFirstValidUrl(reddit)
        )

        val pinterest = "İlham panosu https://pin.it/5AbCdEf"
        assertEquals("https://pin.it/5AbCdEf", UrlSanitizer.extractFirstValidUrl(pinterest))

        val soundcloud = "Listen to track on #SoundCloud https://on.soundcloud.com/abc123"
        assertEquals("https://on.soundcloud.com/abc123", UrlSanitizer.extractFirstValidUrl(soundcloud))
    }

    @Test
    fun extractFirstValidUrl_directImageJpgAndPng() {
        val jpgText = "Duvar kağıdı: https://cdn.example.com/images/wallpaper_4k.jpg?quality=90"
        assertEquals(
            "https://cdn.example.com/images/wallpaper_4k.jpg?quality=90",
            UrlSanitizer.extractFirstValidUrl(jpgText)
        )

        val pngText = "Logo dosyası http://static.example.org/assets/logo.png!"
        assertEquals(
            "http://static.example.org/assets/logo.png",
            UrlSanitizer.extractFirstValidUrl(pngText)
        )
    }

    // -------------------------------------------------------------------------
    // Zararlı şema engelleme güvenlik testleri
    // -------------------------------------------------------------------------

    @Test
    fun extractFirstValidUrl_blocksMaliciousSchemes() {
        assertNull(UrlSanitizer.extractFirstValidUrl("javascript:alert(1)"))
        assertNull(UrlSanitizer.extractFirstValidUrl("JAVASCRIPT:alert('xss')"))
        assertNull(UrlSanitizer.extractFirstValidUrl("file:///etc/passwd"))
        assertNull(UrlSanitizer.extractFirstValidUrl("file:///data/data/com.ulgentube.app/databases/db"))
        assertNull(UrlSanitizer.extractFirstValidUrl("intent://scan/#Intent;scheme=zxing;package=com.google.zxing;end"))
        assertNull(UrlSanitizer.extractFirstValidUrl("content://com.ulgentube.app.fileprovider/files/secret.mp4"))
        assertNull(UrlSanitizer.extractFirstValidUrl("data:text/html,<script>alert(1)</script>"))
        assertNull(UrlSanitizer.extractFirstValidUrl("vbscript:msgbox(1)"))
    }

    @Test
    fun extractFirstValidUrl_blocksHttpUrlEmbeddedInsideMaliciousScheme() {
        assertNull(
            UrlSanitizer.extractFirstValidUrl(
                "Tıkla intent://evil.com/#Intent;scheme=https;S.browser_fallback_url=https://evil.com/phish;end"
            )
        )
        assertNull(
            UrlSanitizer.extractFirstValidUrl(
                "Şunu aç javascript:fetch('https://evil.com/steal')"
            )
        )
        assertNull(
            UrlSanitizer.extractFirstValidUrl(
                "Dosya file:///storage/emulated/0/https://fake.example.com"
            )
        )
        assertNull(
            UrlSanitizer.extractFirstValidUrl(
                "Veri content://com.example.provider/https://fake.example.com"
            )
        )
    }

    @Test
    fun extractFirstValidUrl_handlesNullBlankAndNonUrlText() {
        assertNull(UrlSanitizer.extractFirstValidUrl(null))
        assertNull(UrlSanitizer.extractFirstValidUrl(""))
        assertNull(UrlSanitizer.extractFirstValidUrl("   \n\t  "))
        assertNull(UrlSanitizer.extractFirstValidUrl("Sadece normal bir Türkçe mesaj, bağlantı yok."))
        assertNull(UrlSanitizer.extractFirstValidUrl("ftp://files.example.com/video.mp4"))
    }

    // -------------------------------------------------------------------------
    // Tüm PlatformType tespit testleri
    // -------------------------------------------------------------------------

    @Test
    fun detectPlatform_identifiesAllSupportedPlatforms() {
        assertEquals(PlatformType.YOUTUBE, UrlSanitizer.detectPlatform("https://www.youtube.com/watch?v=123"))
        assertEquals(PlatformType.YOUTUBE, UrlSanitizer.detectPlatform("https://youtu.be/123"))
        assertEquals(PlatformType.YOUTUBE, UrlSanitizer.detectPlatform("https://music.youtube.com/watch?v=123"))
        assertEquals(PlatformType.YOUTUBE, UrlSanitizer.detectPlatform("https://m.youtube.com/shorts/123"))

        assertEquals(PlatformType.INSTAGRAM, UrlSanitizer.detectPlatform("https://www.instagram.com/reel/abc/"))
        assertEquals(PlatformType.INSTAGRAM, UrlSanitizer.detectPlatform("https://instagr.am/p/abc/"))

        assertEquals(PlatformType.TIKTOK, UrlSanitizer.detectPlatform("https://www.tiktok.com/@user/video/123"))
        assertEquals(PlatformType.TIKTOK, UrlSanitizer.detectPlatform("https://vm.tiktok.com/ZM6abc/"))

        assertEquals(PlatformType.TWITTER_X, UrlSanitizer.detectPlatform("https://x.com/user/status/123"))
        assertEquals(PlatformType.TWITTER_X, UrlSanitizer.detectPlatform("https://twitter.com/user/status/123"))
        assertEquals(PlatformType.TWITTER_X, UrlSanitizer.detectPlatform("https://fxtwitter.com/user/status/123"))
        assertEquals(PlatformType.TWITTER_X, UrlSanitizer.detectPlatform("https://vxtwitter.com/user/status/123"))
        assertEquals(PlatformType.TWITTER_X, UrlSanitizer.detectPlatform("https://t.co/abc123"))

        assertEquals(PlatformType.FACEBOOK, UrlSanitizer.detectPlatform("https://www.facebook.com/watch/?v=123"))
        assertEquals(PlatformType.FACEBOOK, UrlSanitizer.detectPlatform("https://fb.watch/abc123/"))

        assertEquals(PlatformType.REDDIT, UrlSanitizer.detectPlatform("https://www.reddit.com/r/kotlin/comments/123/test"))
        assertEquals(PlatformType.REDDIT, UrlSanitizer.detectPlatform("https://redd.it/abc123"))

        assertEquals(PlatformType.PINTEREST, UrlSanitizer.detectPlatform("https://tr.pinterest.com/pin/123456/"))
        assertEquals(PlatformType.PINTEREST, UrlSanitizer.detectPlatform("https://pin.it/abc123"))

        assertEquals(PlatformType.SOUNDCLOUD, UrlSanitizer.detectPlatform("https://soundcloud.com/artist/track"))
        assertEquals(PlatformType.SOUNDCLOUD, UrlSanitizer.detectPlatform("https://on.soundcloud.com/abc"))

        assertEquals(PlatformType.VIMEO, UrlSanitizer.detectPlatform("https://vimeo.com/123456789"))

        assertEquals(PlatformType.TWITCH, UrlSanitizer.detectPlatform("https://www.twitch.tv/videos/123456"))
        assertEquals(PlatformType.TWITCH, UrlSanitizer.detectPlatform("https://clips.twitch.tv/FunClip"))

        assertEquals(PlatformType.KICK, UrlSanitizer.detectPlatform("https://kick.com/streamer/videos/12345"))
        assertEquals(PlatformType.KICK, UrlSanitizer.detectPlatform("https://www.kick.com/streamer?clip=clip_01ABC"))
        assertEquals(PlatformType.KICK, UrlSanitizer.detectPlatform("https://clips.kick.com/clip_01XYZ"))

        assertEquals(PlatformType.DIRECT_IMAGE, UrlSanitizer.detectPlatform("https://example.com/photo.jpg"))
        assertEquals(PlatformType.DIRECT_IMAGE, UrlSanitizer.detectPlatform("https://example.com/banner.png?size=large"))
        assertEquals(PlatformType.DIRECT_IMAGE, UrlSanitizer.detectPlatform("https://example.com/anim.webp#top"))

        assertEquals(PlatformType.OTHER, UrlSanitizer.detectPlatform("https://dailymotion.com/video/x8abcde"))
        assertEquals(PlatformType.OTHER, UrlSanitizer.detectPlatform("not a valid uri :::"))
    }

    @Test
    fun isDirectImagePath_andGuessImageExtension_workCorrectly() {
        assertTrue(UrlSanitizer.isDirectImagePath("https://cdn.site.com/pic.jpeg?w=1080"))
        assertTrue(UrlSanitizer.isDirectImagePath("/path/to/image.avif"))
        assertFalse(UrlSanitizer.isDirectImagePath("https://example.com/video.mp4"))

        assertEquals("jpg", UrlSanitizer.guessImageExtension("https://site.com/a.jpeg"))
        assertEquals("png", UrlSanitizer.guessImageExtension("https://pbs.twimg.com/media/abc?format=png&name=orig"))
        assertEquals("webp", UrlSanitizer.guessImageExtension("https://site.com/img?format=webp"))
        assertEquals("jpg", UrlSanitizer.guessImageExtension("https://site.com/no-extension"))
    }
}
