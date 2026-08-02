package com.ed.edqiu.ui.list

import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import java.util.Locale

enum class LinkSortOrder(val label: String) {
    NEWEST("最新捕获"),
    OLDEST("最早捕获"),
    STATUS("下载状态"),
    AUTHOR("作者名称")
}

object LinkListOrganizer {
    fun organize(
        links: List<SavedLink>,
        query: String,
        sortOrder: LinkSortOrder
    ): List<SavedLink> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val filtered = if (normalizedQuery.isBlank()) {
            links
        } else {
            links.filter { link ->
                listOf(
                    link.tweetId,
                    link.rawUrl,
                    link.authorId,
                    link.authorName,
                    link.caption,
                    link.lastError
                ).any { value -> value?.lowercase(Locale.ROOT)?.contains(normalizedQuery) == true }
            }
        }

        return when (sortOrder) {
            LinkSortOrder.NEWEST -> filtered.sortedByDescending(SavedLink::savedAt)
            LinkSortOrder.OLDEST -> filtered.sortedBy(SavedLink::savedAt)
            LinkSortOrder.STATUS -> filtered.sortedWith(
                compareBy<SavedLink> { statusOrder(it.status) }
                    .thenByDescending { it.savedAt }
            )
            LinkSortOrder.AUTHOR -> filtered.sortedWith(
                compareBy<SavedLink> {
                    (it.authorName ?: it.authorId ?: "").lowercase(Locale.ROOT)
                }.thenByDescending { it.savedAt }
            )
        }
    }

    private fun statusOrder(status: LinkStatus): Int = when (status) {
        LinkStatus.FAILED -> 0
        LinkStatus.PENDING -> 1
        LinkStatus.DOWNLOADED -> 2
    }
}
