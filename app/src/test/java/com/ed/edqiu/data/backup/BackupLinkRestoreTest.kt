package com.ed.edqiu.data.backup

import com.ed.edqiu.data.model.LinkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupLinkRestoreTest {

    @Test
    fun restoresDownloadedFieldsFromBackupLink() {
        val restored = BackupLink(
            tweetId = "1234567890123456789",
            rawUrl = "https://x.com/i/status/1234567890123456789",
            authorId = "@author",
            authorName = "Author",
            caption = "Downloaded item",
            thumbnailUrl = "https://example.com/thumb.jpg",
            avatarUrl = "https://example.com/avatar.jpg",
            savedAt = 1_000L,
            status = LinkStatus.DOWNLOADED.name,
            filePath = "/storage/emulated/0/Android/data/com.ed.twitterdownload/files/Download/video.mp4",
            downloadedAt = 2_000L,
            attemptCount = 3,
            lastAttemptAt = 1_900L,
            lastError = "previous transient error",
            nextRetryAt = 2_500L
        ).toNormalizedSavedLink()

        assertEquals(LinkStatus.DOWNLOADED, restored.status)
        assertEquals(
            "/storage/emulated/0/Android/data/com.ed.twitterdownload/files/Download/video.mp4",
            restored.filePath
        )
        assertEquals(2_000L, restored.downloadedAt)
        assertEquals(3, restored.attemptCount)
        assertEquals(1_900L, restored.lastAttemptAt)
        assertEquals("previous transient error", restored.lastError)
        assertEquals(2_500L, restored.nextRetryAt)
    }
}
