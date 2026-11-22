package com.ulgentube.app

import com.ulgentube.app.domain.model.StudioAspectRatio
import com.ulgentube.app.engine.ChannelFeedChecker
import com.ulgentube.app.engine.LocalFfmpegProcessor
import com.ulgentube.app.engine.LrcLyricsFetcher
import com.ulgentube.app.engine.SocialImageExtractor
import com.ulgentube.app.engine.YouTubePoTokenProvider
import com.ulgentube.app.service.DownloadForegroundService
import com.ulgentube.app.ui.components.PinHashUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesLogicTest {

    @Test
    fun pinHashUtil_hashesWithSaltAndVerifiesCorrectly() {
        val hash1 = PinHashUtil.hashPin("2468", "saltA")
        val hash2 = PinHashUtil.hashPin("2468", "saltB")
        assertNotEquals(hash1, hash2)
        assertTrue(PinHashUtil.verifyPin("2468", hash1))
        assertTrue(PinHashUtil.verifyPin("2468", hash2))
        assertFalse(PinHashUtil.verifyPin("1357", hash1))
        assertFalse(PinHashUtil.verifyPin("2468", "malformed_hash"))
    }

    @Test
    fun lrcLyricsFetcher_cleansQueryAndParsesSyncedTimestamps() {
        val (cleanTitle, cleanArtist) = LrcLyricsFetcher.cleanTrackQuery(
            "Barış Manço - Gülpembe (Official Video) [4K] - YouTube",
            "Barış Manço - Topic"
        )
        assertFalse(cleanTitle.contains("Official Video", ignoreCase = true))
        assertFalse(cleanTitle.contains("4K", ignoreCase = true))
        assertNotNull(cleanArtist)

        val lrc = """
            [00:12.50] Sen gülünce güller açar
            [01:05.250] Gülpembe
        """.trimIndent()
        val lines = LrcLyricsFetcher.parseLrcTimestamps(lrc)
        assertEquals(2, lines.size)
        assertEquals(12_500L, lines[0].timeMs)
        assertEquals("Sen gülünce güller açar", lines[0].text)
        assertEquals(65_250L, lines[1].timeMs)
        assertEquals("Gülpembe", lines[1].text)
    }

    @Test
    fun scheduleWindow_handlesSameDayOvernightAndEqualHours() {
        // Aynı gün penceresi: 01:00 - 07:00
        assertTrue(DownloadForegroundService.isWithinScheduleWindow(3, 1, 7))
        assertFalse(DownloadForegroundService.isWithinScheduleWindow(12, 1, 7))
        assertFalse(DownloadForegroundService.isWithinScheduleWindow(7, 1, 7))

        // Gece yarısını aşan pencere: 23:00 - 06:00
        assertTrue(DownloadForegroundService.isWithinScheduleWindow(23, 23, 6))
        assertTrue(DownloadForegroundService.isWithinScheduleWindow(2, 23, 6))
        assertFalse(DownloadForegroundService.isWithinScheduleWindow(15, 23, 6))

        // Başlangıç == Bitiş -> her zaman açık
        assertTrue(DownloadForegroundService.isWithinScheduleWindow(14, 5, 5))
    }

    @Test
    fun instagramStoryOrProfileDetector_distinguishesStoriesAndProfilesFromPosts() {
        assertTrue(SocialImageExtractor.isInstagramStoryOrProfileUrl("https://www.instagram.com/stories/testuser/123456789/"))
        assertTrue(SocialImageExtractor.isInstagramStoryOrProfileUrl("https://www.instagram.com/stories/highlights/17999999/"))
        assertTrue(SocialImageExtractor.isInstagramStoryOrProfileUrl("https://www.instagram.com/testuser/"))
        assertFalse(SocialImageExtractor.isInstagramStoryOrProfileUrl("https://www.instagram.com/p/C9abcDEF/"))
        assertFalse(SocialImageExtractor.isInstagramStoryOrProfileUrl("https://www.instagram.com/reel/C9abcDEF/"))
    }

    @Test
    fun ffmpegAtempoAndCropFilters_produceValidChains() {
        val fast4x = LocalFfmpegProcessor.buildAtempoFilterChain(4.0f)
        assertTrue("4x atempo zinciri birden fazla atempo içermeli: $fast4x", fast4x.contains(","))
        val half = LocalFfmpegProcessor.buildAtempoFilterChain(0.5f)
        assertTrue(half.startsWith("atempo="))

        val crop916 = LocalFfmpegProcessor.buildCropFilter(StudioAspectRatio.RATIO_9_16)
        val crop11 = LocalFfmpegProcessor.buildCropFilter(StudioAspectRatio.RATIO_1_1)
        val crop169 = LocalFfmpegProcessor.buildCropFilter(StudioAspectRatio.RATIO_16_9)
        assertTrue(crop916.contains("crop="))
        assertTrue(crop11.contains("crop="))
        assertTrue(crop169.contains("crop="))
    }

    @Test
    fun channelFeedAndPoTokenHtmlParsers_extractExpectedValues() {
        val atomXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <yt:channelId>UC_x5XG1OV2P6uZZ5FSM9Ttw</yt:channelId>
              <title>Google Developers</title>
              <entry>
                <yt:videoId>dQw4w9WgXcQ</yt:videoId>
                <title>Yeni Android Özellikleri</title>
                <link rel="alternate" href="https://www.youtube.com/watch?v=dQw4w9WgXcQ"/>
              </entry>
            </feed>
        """.trimIndent()

        val snapshot = ChannelFeedChecker.parseYouTubeAtomFeed(atomXml)
        assertNotNull(snapshot)
        assertEquals("UC_x5XG1OV2P6uZZ5FSM9Ttw", snapshot!!.channelId)
        assertEquals("Google Developers", snapshot.title)
        assertEquals("dQw4w9WgXcQ", snapshot.latestVideoId)
        assertEquals("Yeni Android Özellikleri", snapshot.latestVideoTitle)

        val html = """<script>ytcfg.set({"VISITOR_DATA":"Cgt1bGdlblR1YmUxMjM%3D","INNERTUBE_API_KEY":"abc"});</script>"""
        val visitor = YouTubePoTokenProvider.parseVisitorDataFromHtml(html)
        assertEquals("Cgt1bGdlblR1YmUxMjM%3D", visitor)
    }

    @Test
    fun redditParsers_extractPostIdAndParseApiInfoAndListingJson() {
        assertEquals("1abc2de", SocialImageExtractor.extractRedditPostId("https://www.reddit.com/r/Nortya/comments/1abc2de/sample_post/"))
        assertEquals("1abc2de", SocialImageExtractor.extractRedditPostId("https://redd.it/1abc2de"))
        assertEquals(
            com.ulgentube.app.domain.model.PlatformType.REDDIT,
            com.ulgentube.app.util.UrlSanitizer.detectPlatform("https://www.rxddit.com/r/Nortya/comments/1abc2de/test/")
        )
        assertEquals(
            com.ulgentube.app.domain.model.PlatformType.REDDIT,
            com.ulgentube.app.util.UrlSanitizer.detectPlatform("https://v.redd.it/xyz123abc")
        )

        val apiInfoJson = """{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"Nortya Art","author":"artist1"}}]}}"""
        val parsedObj = SocialImageExtractor.parseRedditPostObject(apiInfoJson)
        assertNotNull(parsedObj)
        assertEquals("Nortya Art", parsedObj!!.optString("title"))
    }
}
