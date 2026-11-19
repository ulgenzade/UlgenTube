package com.ulgentube.app

import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.engine.YtDlpEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatsTest {

    @Test
    fun buildDefaultAudioFormats_generatesAllSevenFormatsWithEstimatedSizesFor180Seconds() {
        val durationSec = 180L
        val formats = YtDlpEngine.buildDefaultAudioFormats(
            formats = emptyList(),
            durationSeconds = durationSec
        )

        assertEquals(7, formats.size)

        val expectedIds = listOf(
            "audio_mp3_320",
            "audio_mp3_192",
            "audio_mp3_128",
            "audio_m4a",
            "audio_opus",
            "audio_flac",
            "audio_wav"
        )
        assertEquals(expectedIds, formats.map { it.id })

        val expectedExtensions = listOf("mp3", "mp3", "mp3", "m4a", "opus", "flac", "wav")
        assertEquals(expectedExtensions, formats.map { it.extension })

        val expectedBitrates = listOf(320, 192, 128, 192, 160, 900, 1411)
        assertEquals(expectedBitrates, formats.map { it.bitrateKbps })

        // Tüm seçenekler AUDIO kategorisinde ve ses ayrıştırma bayrağı açık olmalı
        formats.forEach { option ->
            assertEquals(MediaCategory.AUDIO, option.category)
            assertTrue(option.isAudioExtraction)
            assertNull(option.directUrl)
            assertTrue(!option.ytDlpFormatSelector.isNullOrBlank())
        }

        // 180 saniye için beklenen tahmini boyutlar: (180 * kbps * 1000) / 8
        val byId = formats.associateBy { it.id }
        assertEquals(7_200_000L, byId.getValue("audio_mp3_320").fileSizeBytes)
        assertEquals(4_320_000L, byId.getValue("audio_mp3_192").fileSizeBytes)
        assertEquals(2_880_000L, byId.getValue("audio_mp3_128").fileSizeBytes)
        assertEquals(4_320_000L, byId.getValue("audio_m4a").fileSizeBytes)
        assertEquals(3_600_000L, byId.getValue("audio_opus").fileSizeBytes)
        assertEquals(20_250_000L, byId.getValue("audio_flac").fileSizeBytes)
        assertEquals(31_747_500L, byId.getValue("audio_wav").fileSizeBytes)
    }

    @Test
    fun buildDefaultAudioFormats_handlesNullDurationAndDirectSourceUrl() {
        val directUrl = "https://cdn.example.com/video.mp4"
        val formats = YtDlpEngine.buildDefaultAudioFormats(
            formats = emptyList(),
            durationSeconds = null,
            directSourceUrl = directUrl
        )

        assertEquals(7, formats.size)
        formats.forEach { option ->
            assertNull(option.fileSizeBytes)
            assertNull(option.ytDlpFormatSelector)
            assertEquals(directUrl, option.directUrl)
            assertTrue(option.isAudioExtraction)
        }
    }

    @Test
    fun buildDownscaleOptions_onlyGeneratesLowerResolutionsWithTranscodeTargetHeight() {
        val primary1080p = MediaFormatOption(
            id = "video_1080p",
            label = "1080p",
            sublabel = "MP4",
            category = MediaCategory.VIDEO,
            extension = "mp4",
            resolutionHeight = 1080,
            ytDlpFormatSelector = "bestvideo+bestaudio/best"
        )

        val downscaledFrom1080 = YtDlpEngine.buildDownscaleOptions(primary1080p, 1080)
        assertEquals(listOf(720, 480, 360), downscaledFrom1080.map { it.resolutionHeight })
        assertEquals(listOf(720, 480, 360), downscaledFrom1080.map { it.transcodeTargetHeight })

        val downscaledFrom720 = YtDlpEngine.buildDownscaleOptions(primary1080p, 720)
        assertEquals(listOf(480, 360), downscaledFrom720.map { it.resolutionHeight })
        assertEquals(listOf(480, 360), downscaledFrom720.map { it.transcodeTargetHeight })
    }
}
