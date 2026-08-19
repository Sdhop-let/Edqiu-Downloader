package com.ed.edqiu.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRuleStrategyTest {

    @Test
    fun sameAuthorMultiVideoPostUsesMultiChannelStrategy() {
        val context = DownloadRuleContext(
            uploader = "alice",
            title = "Part 1",
            isSameAuthor = true,
            hasMultipleMediaItems = true,
            formatCount = 2,
            recentAuthorNames = setOf("alice")
        )

        assertEquals(DownloadRuleStrategy.MULTI_CHANNEL, decideDownloadRule(context))
    }

    @Test
    fun sameAuthorContinuationUsesSequentialStrategy() {
        val context = DownloadRuleContext(
            uploader = "alice",
            title = "第二集 续作",
            isSameAuthor = true,
            hasMultipleMediaItems = false,
            formatCount = 1,
            recentAuthorNames = setOf("alice")
        )

        assertEquals(DownloadRuleStrategy.SEQUENTIAL, decideDownloadRule(context))
    }

    @Test
    fun sameAuthorRegularPostUsesMultiChannelStrategy() {
        val context = DownloadRuleContext(
            uploader = "alice",
            title = "普通视频",
            isSameAuthor = true,
            hasMultipleMediaItems = false,
            formatCount = 2,
            recentAuthorNames = setOf("alice")
        )

        assertEquals(DownloadRuleStrategy.MULTI_CHANNEL, decideDownloadRule(context))
    }
}
