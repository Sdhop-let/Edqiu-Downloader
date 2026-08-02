package com.ed.edqiu.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TwitterIntentTest {

    @Test
    fun buildsWebStatusUriAsFallback() {
        assertEquals(
            "https://x.com/i/status/1234567890123456789",
            twitterStatusWebUri("1234567890123456789")
        )
    }
}
