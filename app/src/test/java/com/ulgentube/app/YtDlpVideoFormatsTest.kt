package com.ulgentube.app

import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.engine.YtDlpEngine
import com.yausername.youtubedl_android.mapper.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpVideoFormatsTest {

    private val engine = YtDlpEngine()

    @Test
    fun detectEnhancedBitrate_separatesPremiumAndItag356FromHdr() {
        val premium356 = createVideoFormat(
            formatId = "356",
            format = "356 - 1920x1080 (1080p Premium)",
            formatNote = "1080p Premium",
            vcodec = "vp09.00.40.08",
            acodec = "none",
            ext = "webm",
            width = 1920,
            height = 1080,
            fps = 30,
            tbr = 6800.0
        )
        val hls616 = createVideoFormat(
            formatId = "616",
            format = "616 - 1920x1080 (1080p Premium)",
            formatNote = "Enhanced bitrate",
            vcodec = "vp09.00.40.08",
            acodec = "none",
            ext = "mp4",
            width = 1920,
            height = 1080,
            fps = 30,
            tbr = 6400.0
        )
        val hdrStream = createVideoFormat(
            formatId = "335",
            format = "335 - 1920x1080 (1080p60 HDR)",
            formatNote = "1080p60 HDR",
            vcodec = "vp09.02.41.10",
            acodec = "none",
            ext = "webm",
            width = 1920,
            height = 1080,
            fps = 60,
            tbr = 5900.0
        )

        assertTrue(engine.detectEnhancedBitrate(premium356))
        assertFalse(engine.detectHdrOrHighBit(premium356))

        assertTrue(engine.detectEnhancedBitrate(hls616))
        assertFalse(engine.detectHdrOrHighBit(hls616))

        assertFalse(engine.detectEnhancedBitrate(hdrStream))
        assertTrue(engine.detectHdrOrHighBit(hdrStream))
    }

    @Test
    fun buildRealVideoFormats_assignsDistinctBadgesForEnhancedBitrateHighBitrateAndHdr() {
        val standard1080 = createVideoFormat(
            formatId = "137",
            format = "137 - 1920x1080 (1080p)",
            formatNote = "1080p",
            vcodec = "avc1.640028",
            acodec = "none",
            ext = "mp4",
            width = 1920,
            height = 1080,
            fps = 30,
            tbr = 2500.0
        )
        val premium356 = createVideoFormat(
            formatId = "356",
            format = "356 - 1920x1080 (1080p Premium)",
            formatNote = "1080p Premium",
            vcodec = "vp09.00.40.08",
            acodec = "none",
            ext = "webm",
            width = 1920,
            height = 1080,
            fps = 30,
            tbr = 6800.0
        )
        val hdr1080p60 = createVideoFormat(
            formatId = "335",
            format = "335 - 1920x1080 (1080p60 HDR)",
            formatNote = "1080p60 HDR",
            vcodec = "vp09.02.41.10",
            acodec = "none",
            ext = "webm",
            width = 1920,
            height = 1080,
            fps = 60,
            tbr = 5800.0
        )
        val standard720 = createVideoFormat(
            formatId = "136",
            format = "136 - 1280x720 (720p)",
            formatNote = "720p",
            vcodec = "avc1.4D401F",
            acodec = "none",
            ext = "mp4",
            width = 1280,
            height = 720,
            fps = 30,
            tbr = 1200.0
        )
        val audio140 = createVideoFormat(
            formatId = "140",
            format = "140 - audio only",
            formatNote = "medium",
            vcodec = "none",
            acodec = "mp4a.40.2",
            ext = "m4a",
            width = 0,
            height = 0,
            fps = 0,
            tbr = 128.0
        )

        val options = engine.buildRealVideoFormats(
            formats = listOf(standard1080, premium356, hdr1080p60, standard720, audio140),
            platform = PlatformType.YOUTUBE
        )

        val premiumOpt = options.firstOrNull { it.ytDlpFormatSelector?.startsWith("356+") == true }
        val stdOpt = options.firstOrNull { it.ytDlpFormatSelector?.startsWith("137+") == true }
        val hdrOpt = options.firstOrNull { it.ytDlpFormatSelector?.startsWith("335+") == true }

        assertEquals(4, options.size)
        assertTrue(options.all { it.transcodeTargetHeight == null })
        assertTrue(premiumOpt != null)
        assertTrue(stdOpt != null)
        assertTrue(hdrOpt != null)

        assertTrue(premiumOpt!!.badgeTags.contains("Gelişmiş Bit Hızı"))
        assertFalse(premiumOpt.badgeTags.contains("HDR / 10-bit"))
        assertTrue(premiumOpt.isHdrOrHighBit)

        assertFalse(stdOpt!!.badgeTags.contains("Gelişmiş Bit Hızı"))
        assertFalse(stdOpt.badgeTags.contains("HDR / 10-bit"))

        assertTrue(hdrOpt!!.badgeTags.contains("HDR / 10-bit"))
        assertTrue(hdrOpt.badgeTags.contains("60 FPS"))
    }

    private fun createVideoFormat(
        formatId: String,
        format: String,
        formatNote: String,
        vcodec: String,
        acodec: String,
        ext: String,
        width: Int,
        height: Int,
        fps: Int,
        tbr: Double
    ): VideoFormat {
        val instance = VideoFormat()
        setField(instance, "formatId", formatId)
        setField(instance, "format", format)
        setField(instance, "formatNote", formatNote)
        setField(instance, "vcodec", vcodec)
        setField(instance, "acodec", acodec)
        setField(instance, "ext", ext)
        setField(instance, "width", width)
        setField(instance, "height", height)
        setField(instance, "fps", fps)
        setField(instance, "tbr", tbr)
        return instance
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        val field = VideoFormat::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        when (field.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> field.set(target, (value as Number).toInt())
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> field.set(target, (value as Number).toLong())
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> field.set(target, (value as Number).toFloat())
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> field.set(target, (value as Number).toDouble())
            else -> field.set(target, value)
        }
    }
}
