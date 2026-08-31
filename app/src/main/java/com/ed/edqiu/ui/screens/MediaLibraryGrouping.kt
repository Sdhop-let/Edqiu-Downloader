package com.ed.edqiu.ui.screens

import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.domain.TweetIdExtractor

/**
 * 媒体库分组：同一条推文的多个媒体文件归入同一组。
 *
 * - tweetId 从记录 URL 中提取（x.com / twitter.com 变体均归一）；
 * - 无 URL 或无法提取 tweetId 的记录各自单独成组（tweetId = null）；
 * - 组间顺序与组内顺序都保持传入列表的既有排序，不做二次排序。
 */
data class LibraryGroup(
    val tweetId: String?,
    val items: List<DownloadHistoryEntity>,
)

fun groupLibraryByTweet(items: List<DownloadHistoryEntity>): List<LibraryGroup> {
    val groups = mutableListOf<LibraryGroup>()
    val groupIndexByTweet = mutableMapOf<String, Int>()
    items.forEach { item ->
        val tweetId = TweetIdExtractor.fromUrl(item.url)
        if (tweetId == null) {
            groups += LibraryGroup(tweetId = null, items = listOf(item))
            return@forEach
        }
        val existingIndex = groupIndexByTweet[tweetId]
        if (existingIndex == null) {
            groupIndexByTweet[tweetId] = groups.size
            groups += LibraryGroup(tweetId = tweetId, items = listOf(item))
        } else {
            val group = groups[existingIndex]
            groups[existingIndex] = group.copy(items = group.items + item)
        }
    }
    return groups
}
