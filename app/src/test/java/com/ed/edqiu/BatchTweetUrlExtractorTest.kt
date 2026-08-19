package com.ed.edqiu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTweetUrlExtractorTest {
    @Test
    fun extractsAndNormalizesMultipleTweetUrlsInOrder() {
        val text = """
            save these:
            https://twitter.com/alice/status/1234567890123?s=20
            https://x.com/bob/status/2234567890123
            https://example.com/nope
            https://mobile.twitter.com/alice/status/1234567890123
        """.trimIndent()

        assertEquals(
            listOf(
                "https://x.com/i/status/1234567890123",
                "https://x.com/i/status/2234567890123"
            ),
            BatchTweetUrlExtractor.extract(text)
        )
    }

    // === Inbox-specific tests (extractWithOriginals) ===

    @Test
    fun extractWithOriginals_preservesOriginalAndNormalizedUrls() {
        val text = "https://twitter.com/alice/status/1234567890123?s=20&t=xyz"
        val pairs = BatchTweetUrlExtractor.extractWithOriginals(text)

        assertEquals(1, pairs.size)
        assertEquals("https://x.com/i/status/1234567890123", pairs[0].normalizedUrl)
        assertEquals("1234567890123", pairs[0].tweetId)
        // Original URL should preserve the query params
        assertTrue(pairs[0].originalUrl.contains("twitter.com"))
    }

    @Test
    fun extractWithOriginals_dedupByTweetId() {
        val text = """
            https://twitter.com/alice/status/1234567890123
            https://x.com/alice/status/1234567890123?s=20
            https://mobile.twitter.com/alice/status/1234567890123
        """.trimIndent()

        val pairs = BatchTweetUrlExtractor.extractWithOriginals(text)
        assertEquals(1, pairs.size)
        assertEquals("1234567890123", pairs[0].tweetId)
    }

    @Test
    fun extractWithOriginals_handlesChinesePunctuationTrailing() {
        val text = "看这条推文 https://x.com/bob/status/9999999999999，还有这条 https://twitter.com/amy/status/8888888888888；"
        val pairs = BatchTweetUrlExtractor.extractWithOriginals(text)

        assertEquals(2, pairs.size)
        assertEquals("9999999999999", pairs[0].tweetId)
        assertEquals("8888888888888", pairs[1].tweetId)
    }

    @Test
    fun extractWithOriginals_handlesQueryParameters() {
        val text = "https://x.com/user/status/1111111111111?s=46&t=abc123"
        val pairs = BatchTweetUrlExtractor.extractWithOriginals(text)

        assertEquals(1, pairs.size)
        assertEquals("1111111111111", pairs[0].tweetId)
        assertEquals("https://x.com/i/status/1111111111111", pairs[0].normalizedUrl)
        assertTrue(pairs[0].originalUrl.contains("s=46"))
    }

    @Test
    fun extractWithOriginals_ignoresNonTwitterUrls() {
        val text = "https://example.com/nope https://youtube.com/watch?v=abc"
        val pairs = BatchTweetUrlExtractor.extractWithOriginals(text)
        assertEquals(0, pairs.size)
    }

    @Test
    fun extractWithOriginals_handlesMixedPlatforms() {
        val text = """
            https://x.com/bob/status/1234567890123
            https://mobile.twitter.com/amy/status/2234567890123
            https://twitter.com/carl/status/3234567890123
        """.trimIndent()

        val pairs = BatchTweetUrlExtractor.extractWithOriginals(text)
        assertEquals(3, pairs.size)
        // All normalized to x.com format
        pairs.forEach { pair ->
            assertTrue(pair.normalizedUrl.startsWith("https://x.com/i/status/"))
        }
    }

    @Test
    fun extractWithOriginals_emptyAndNullInput() {
        assertEquals(0, BatchTweetUrlExtractor.extractWithOriginals(null).size)
        assertEquals(0, BatchTweetUrlExtractor.extractWithOriginals("").size)
        assertEquals(0, BatchTweetUrlExtractor.extractWithOriginals("   ").size)
        assertEquals(0, BatchTweetUrlExtractor.extractWithOriginals("no urls here").size)
    }
}
