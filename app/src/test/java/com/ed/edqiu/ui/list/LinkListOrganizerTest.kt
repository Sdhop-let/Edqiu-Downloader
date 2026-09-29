package com.ed.edqiu.ui.list

import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkListOrganizerTest {

    private val links = listOf(
        SavedLink(
            tweetId = "111111111111",
            rawUrl = "https://x.com/i/status/111111111111",
            authorId = "@beta",
            authorName = "Beta",
            caption = "second item",
            savedAt = 200L,
            status = LinkStatus.PENDING
        ),
        SavedLink(
            tweetId = "222222222222",
            rawUrl = "https://x.com/i/status/222222222222",
            authorId = "@alpha",
            authorName = "Alpha",
            caption = "first item",
            savedAt = 100L,
            status = LinkStatus.FAILED,
            lastError = "not installed"
        )
    )

    @Test
    fun searchesAcrossMetadataAndErrors() {
        assertEquals(
            listOf("222222222222"),
            LinkListOrganizer.organize(
                links,
                query = "not installed",
                sortOrder = LinkSortOrder.NEWEST
            ).map(SavedLink::tweetId)
        )
    }

    @Test
    fun sortsByAuthorAndStatus() {
        assertEquals(
            listOf("222222222222", "111111111111"),
            LinkListOrganizer.organize(
                links,
                query = "",
                sortOrder = LinkSortOrder.AUTHOR
            ).map(SavedLink::tweetId)
        )
        assertEquals(
            listOf("222222222222", "111111111111"),
            LinkListOrganizer.organize(
                links,
                query = "",
                sortOrder = LinkSortOrder.STATUS
            ).map(SavedLink::tweetId)
        )
    }
}
