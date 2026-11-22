package com.ulgentube.app

import com.ulgentube.app.engine.SocialImageExtractor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialImageExtractorInstagramTest {

    private val extractor = SocialImageExtractor(okHttpClient = OkHttpClient())

    @Test
    fun canonicalMediaKey_deduplicatesSameMediaWithDifferentTokenQueryParams() {
        val urlA = "https://scontent-ist1-1.cdninstagram.com/v/t51.2885-15/456789123_111_n.mp4?efg=aaa&ig_cache_key=MzQxMjM0NTY3ODkwMTIzNA%3D%3D.3-ccb7-5&oh=token1&oe=AAA"
        val urlB = "https://scontent-iad3-2.cdninstagram.com/v/t51.2885-15/987654321_222_n.mp4?efg=bbb&ig_cache_key=MzQxMjM0NTY3ODkwMTIzNA==.3-ccb7-5&oh=token2&oe=BBB"
        val urlOther = "https://scontent-ist1-1.cdninstagram.com/v/t51.2885-15/456789123_111_n.mp4?ig_cache_key=OTk5OTk5.3-ccb7-5"

        val keyA = extractor.canonicalMediaKey(urlA)
        val keyB = extractor.canonicalMediaKey(urlB)
        val keyOther = extractor.canonicalMediaKey(urlOther)

        assertEquals(keyA, keyB)
        assertNotEquals(keyA, keyOther)
    }

    @Test
    fun canonicalMediaKey_fallsBackToFilenameWhenNoCacheKey() {
        val url1 = "https://video.fbcdn.net/v/t50.2886-16/media_file_123_n.mp4?oe=111&oh=aaa"
        val url2 = "https://video-amt2-1.xx.fbcdn.net/v/t50.2886-16/media_file_123_n.mp4?oe=222&oh=bbb"

        assertEquals("media_file_123_n.mp4", extractor.canonicalMediaKey(url1))
        assertEquals(extractor.canonicalMediaKey(url1), extractor.canonicalMediaKey(url2))
    }

    @Test
    fun isGenericInstagramTitle_filtersProxyAndGenericPlaceholders() {
        assertTrue(extractor.isGenericInstagramTitle(null))
        assertTrue(extractor.isGenericInstagramTitle("   "))
        assertTrue(extractor.isGenericInstagramTitle("Instagram"))
        assertTrue(extractor.isGenericInstagramTitle("vxinstagram"))
        assertTrue(extractor.isGenericInstagramTitle("eeinstagram"))
        assertTrue(extractor.isGenericInstagramTitle("Instagram Gönderisi"))
        assertTrue(extractor.isGenericInstagramTitle("Log in • Instagram"))

        assertFalse(extractor.isGenericInstagramTitle("CAMPEONES DEL MUNDO"))
    }

    @Test
    fun parseInstagramTitleAndAuthor_extractsRealCaptionAndAuthorFromMeta() {
        val ogTitle = "Leo Messi on Instagram: \"CAMPEONES DEL MUNDO!!!!!!!\""
        val ogDesc = "74M likes, 2M comments - leomessi on December 18, 2022: \"CAMPEONES DEL MUNDO!!!!!!!\""

        val (title, author) = extractor.parseInstagramTitleAndAuthor(ogTitle, ogDesc)
        assertEquals("CAMPEONES DEL MUNDO!!!!!!!", title)
        assertEquals("Leo Messi", author)

        val (onlyDescTitle, onlyDescAuthor) = extractor.parseInstagramTitleAndAuthor(
            rawOgTitle = "vxinstagram",
            rawOgDescription = "12K likes, 45 comments - ulgendev on November 20, 2026: \"Yeni uygulama testi\""
        )
        assertEquals("Yeni uygulama testi", onlyDescTitle)
        assertEquals("ulgendev", onlyDescAuthor)

        val (emptyTitle, emptyAuthor) = extractor.parseInstagramTitleAndAuthor("Instagram", null)
        assertNull(emptyTitle)
        assertNull(emptyAuthor)
    }
}
