package com.ed.edqiu.ui.screens

import com.ed.edqiu.data.database.DownloadHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 媒体库分组测试（分组单位已由「推文」升级为「作者 + 发布日」，见 MediaLibraryGrouping.kt）。
 */
class MediaLibraryGroupingTest {
    @Test
    fun groupsByAuthorAndDayKeepsOtherAuthorsSeparate() {
        val items = listOf(
            history("a", uploader = "u", completedAt = DAY1 + 30),
            history("b", uploader = "u", completedAt = DAY1 + 20),
            history("c", uploader = "v", completedAt = DAY1 + 10)
        )

        val groups = groupLibraryByAuthorDay(items)

        assertEquals(2, groups.size)
        // 组间按组内最新媒体时间倒序：u 组（最新 30）在前，组内保持传入顺序
        assertEquals(listOf("a", "b"), groups[0].items.map { it.id })
        assertEquals(listOf("c"), groups[1].items.map { it.id })
    }

    @Test
    fun sameAuthorDifferentDaysSplitIntoSeparateGroups() {
        val items = listOf(
            history("a", uploader = "u", completedAt = DAY2),
            history("b", uploader = "u", completedAt = DAY1)
        )

        val groups = groupLibraryByAuthorDay(items)

        assertEquals(2, groups.size)
        assertEquals(listOf("a"), groups[0].items.map { it.id })
        assertEquals(listOf("b"), groups[1].items.map { it.id })
    }

    @Test
    fun blankUploaderFallsBackToUnknownAuthor() {
        val groups = groupLibraryByAuthorDay(listOf(history("c", uploader = "", completedAt = DAY1)))

        assertEquals(1, groups.size)
        assertEquals("未知作者", groups.single().author)
    }

    private fun history(id: String, uploader: String, completedAt: Long): DownloadHistoryEntity =
        DownloadHistoryEntity(
            id = id,
            url = "",
            title = "title $id",
            thumbnail = "",
            uploader = uploader,
            quality = "720p",
            filePath = "/tmp/$id.mp4",
            createdAt = completedAt,
            completedAt = completedAt
        )

    private companion object {
        // 2026-01-01 与 2026-02-01 本地时区零点，保证落在不同「发布日」
        val DAY1: Long = java.util.Calendar.getInstance().apply {
            clear(); set(2026, 0, 1)
        }.timeInMillis
        val DAY2: Long = java.util.Calendar.getInstance().apply {
            clear(); set(2026, 1, 1)
        }.timeInMillis
    }
}
