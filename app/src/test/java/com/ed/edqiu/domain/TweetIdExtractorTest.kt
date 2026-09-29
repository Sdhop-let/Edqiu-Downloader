package com.ed.edqiu.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TweetIdExtractorTest {

    @Test
    fun canonicalizesSupportedTwitterUrls() {
        val text = "看看这个 https://twitter.com/example/status/1234567890123456789?s=20"

        assertEquals(
            "https://x.com/i/status/1234567890123456789",
            TweetIdExtractor.canonicalUrlFromText(text)
        )
    }

    @Test
    fun rejectsNonStatusUrls() {
        assertNull(TweetIdExtractor.canonicalUrlFromText("https://x.com/example"))
        assertNull(TweetIdExtractor.canonicalUrlFromText("普通文本 1234567890123456789"))
    }

    @Test
    fun extractsIdFromDownloaderFileName() {
        assertEquals(
            "1234567890123456789",
            TweetIdExtractor.fromFileName("author_1234567890123456789_1_video.mp4")
        )
        assertTrue(TweetIdExtractor.isTwitterUrl("https://x.com/i/status/1234567890123456789"))
    }
}
