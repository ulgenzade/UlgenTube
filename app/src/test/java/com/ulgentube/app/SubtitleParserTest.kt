package com.ulgentube.app

import com.ulgentube.app.player.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun parseSrt_handlesMultilineAndStripsHtmlTags() {
        val srt = """
            1
            00:00:01,500 --> 00:00:04,250
            <i>Merhaba</i> dünya!
            İkinci satır burada.

            2
            00:00:05,000 --> 00:00:07,000
            <b>Test</b> altyazısı
        """.trimIndent()

        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1_500L, cues[0].startMs)
        assertEquals(4_250L, cues[0].endMs)
        assertEquals("Merhaba dünya!\nİkinci satır burada.", cues[0].text)
        assertEquals("Test altyazısı", cues[1].text)
    }

    @Test
    fun parseVtt_handlesShortTimestampsAndWebVttHeader() {
        val vtt = """
            WEBVTT

            01:02.500 --> 01:05.000 align:center
            <c.colorE5E5E5>VTT satırı</c>

            00:02:10.100 --> 00:02:12.900
            Saatli format
        """.trimIndent()

        val cues = SubtitleParser.parse(vtt)
        assertEquals(2, cues.size)
        assertEquals(62_500L, cues[0].startMs)
        assertEquals(65_000L, cues[0].endMs)
        assertEquals("VTT satırı", cues[0].text)
        assertEquals(130_100L, cues[1].startMs)
        assertEquals(132_900L, cues[1].endMs)
    }

    @Test
    fun activeCueAt_returnsMatchingCueOrNull() {
        val srt = """
            1
            00:00:02,000 --> 00:00:05,000
            İlk cümle
        """.trimIndent()

        val cues = SubtitleParser.parse(srt)
        assertNull(SubtitleParser.activeCueAt(cues, 1_999L))
        assertEquals("İlk cümle", SubtitleParser.activeCueAt(cues, 2_000L))
        assertEquals("İlk cümle", SubtitleParser.activeCueAt(cues, 3_500L))
        assertNull(SubtitleParser.activeCueAt(cues, 5_001L))
    }
}
