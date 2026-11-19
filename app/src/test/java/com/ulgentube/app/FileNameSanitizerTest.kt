package com.ulgentube.app

import com.ulgentube.app.util.FileNameSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameSanitizerTest {

    @Test
    fun sanitize_removesPathTraversalPatternsAndControlChars() {
        val unixTraversal = FileNameSanitizer.sanitize("../../etc/passwd")
        assertFalse(unixTraversal.contains(".."))
        assertFalse(unixTraversal.contains("/"))
        assertFalse(unixTraversal.contains("\\"))
        assertEquals("etc passwd", unixTraversal)

        val windowsTraversal = FileNameSanitizer.sanitize("..\\..\\Windows\\System32\\cmd.exe")
        assertFalse(windowsTraversal.contains(".."))
        assertFalse(windowsTraversal.contains("\\"))
        assertEquals("Windows System32 cmd.exe", windowsTraversal)

        val nestedDots = FileNameSanitizer.sanitize("....//....//secret.mp4")
        assertFalse(nestedDots.contains(".."))
        assertFalse(nestedDots.contains("/"))
        assertEquals("secret.mp4", nestedDots)

        val nullByte = FileNameSanitizer.sanitize("video\u0000.mp4\u0007\u001F")
        assertFalse(nullByte.contains('\u0000'))
        assertFalse(nullByte.contains('\u0007'))
        assertFalse(nullByte.contains('\u001F'))
        assertEquals("video .mp4", nullByte)
    }

    @Test
    fun sanitize_stripsWindowsForbiddenCharacters() {
        val raw = """Video <HD> : "Test" / \ | ? * Final"""
        val cleaned = FileNameSanitizer.sanitize(raw)
        val forbidden = listOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        forbidden.forEach { ch ->
            assertFalse("Yasak karakter '$ch' temizlenmeli", cleaned.contains(ch))
        }
        assertEquals("Video HD Test Final", cleaned)
    }

    @Test
    fun sanitize_preservesTurkishCharactersAndEmojis() {
        val raw = "İstanbul'da Gün Batımı 🌅🎬 - Şarkı Öğrenme Çılgınlığı"
        val cleaned = FileNameSanitizer.sanitize(raw)
        assertTrue(cleaned.contains("İstanbul"))
        assertTrue(cleaned.contains("Gün"))
        assertTrue(cleaned.contains("Şarkı"))
        assertTrue(cleaned.contains("Öğrenme"))
        assertTrue(cleaned.contains("Çılgınlığı"))
        assertTrue(cleaned.contains("🌅🎬"))
    }

    @Test
    fun sanitize_respectsMaxLengthTruncation() {
        val longTitle = "A".repeat(150)
        val defaultTrimmed = FileNameSanitizer.sanitize(longTitle)
        assertEquals(80, defaultTrimmed.length)

        val customTrimmed = FileNameSanitizer.sanitize(longTitle, maxLength = 25)
        assertEquals(25, customTrimmed.length)

        // Minimum güvenli sınır 16 karakterdir
        val minBounded = FileNameSanitizer.sanitize(longTitle, maxLength = 5)
        assertEquals(16, minBounded.length)
    }

    @Test
    fun sanitize_returnsFallbackForBlankOrOnlyInvalidCharacters() {
        assertEquals("ulgentube_media", FileNameSanitizer.sanitize(""))
        assertEquals("ulgentube_media", FileNameSanitizer.sanitize("     "))
        assertEquals("ulgentube_media", FileNameSanitizer.sanitize("../../../"))
        assertEquals("ulgentube_media", FileNameSanitizer.sanitize("""<>:"/\|?*...___---"""))
        assertEquals("ulgentube_media", FileNameSanitizer.sanitize("\u0000\u0001\u0002"))
    }

    @Test
    fun buildFileName_combinesSanitizedBaseSuffixAndExtension() {
        assertEquals(
            "Merhaba Dünya.mp4",
            FileNameSanitizer.buildFileName("Merhaba Dünya", ".MP4")
        )
        assertEquals(
            "Galeri Başlığı_1.jpg",
            FileNameSanitizer.buildFileName("Galeri Başlığı", "jpg", suffix = "1")
        )
        assertEquals(
            "ulgentube_media.mp4",
            FileNameSanitizer.buildFileName("///", "!!!")
        )
    }
}
