package com.ed.edqiu.ui.screens

import com.ed.edqiu.data.database.DownloadHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryGroupingTest {
    @Test
    fun groupsMediaByTweetIdAndKeepsUngroupedItemsSeparate() {
        val items = listOf(
            history("a", "https://x.com/i/status/1234567890123", completedAt = 30),
            history("b", "https://twitter.com/u/status/1234567890123", completedAt = 20),
            history("c", "", completedAt = 10)
        )

        val groups = groupLibraryByTweet(items)

        assertEquals(2, groups.size)
        assertEquals("1234567890123", groups[0].tweetId)
        assertEquals(2, groups[0].items.size)
        assertEquals("c", groups[1].items.single().id)
    }

    @Test
    fun preservesTheIncomingSortOrderAcrossGroups() {
        val items = listOf(
            history("c", "", completedAt = 10),
            history("a", "https://x.com/i/status/1234567890123", completedAt = 30),
            history("b", "https://twitter.com/u/status/1234567890123", completedAt = 20)
        )

        val groups = groupLibraryByTweet(items)

        assertEquals("c", groups[0].items.single().id)
        assertEquals("1234567890123", groups[1].tweetId)
    }

    private fun history(id: String, url: String, completedAt: Long): DownloadHistoryEntity =
        DownloadHistoryEntity(
            id = id,
            url = url,
            title = "title $id",
            thumbnail = "",
            uploader = "u",
            quality = "720p",
            filePath = "/tmp/$id.mp4",
            createdAt = completedAt,
            completedAt = completedAt
        )
}
