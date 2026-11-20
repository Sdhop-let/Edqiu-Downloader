package com.ed.edqiu.ui.list

import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import org.junit.Assert.*
import org.junit.Test

class InboxDownloadProgressTest {
    private val tweet = "1234567890123"
    private fun task(id: String, status: DownloadStatus, progress: Float = 0f, at: Long = 100L) =
        DownloadTask(id = id, url = "https://x.com/i/status/$tweet", status = status, progress = progress, createdAt = at)

    @Test fun queuedMediaCountBeforeFirstMediaFinishes() {
        val pending = task("pending_$tweet", DownloadStatus.DOWNLOADING)
        val first = task("xinvox_${tweet}_1", DownloadStatus.DOWNLOADING, 99f)
        val second = task("xinvox_${tweet}_2", DownloadStatus.PENDING)
        assertEquals(49, InboxDownloadProgress.progressByTweet(listOf(pending, first, second))[tweet])
        assertEquals(50, InboxDownloadProgress.progressByTweet(listOf(pending,
            first.copy(status = DownloadStatus.COMPLETED, progress = 100f),
            second.copy(status = DownloadStatus.DOWNLOADING)))[tweet])
    }

    @Test fun terminalTasksLeaveProgressWhenSessionEnds() {
        val done = task("xinvox_${tweet}_1", DownloadStatus.COMPLETED, 100f)
        assertTrue(InboxDownloadProgress.progressByTweet(listOf(done)).isEmpty())
        assertEquals(99, InboxDownloadProgress.progressByTweet(listOf(done,
            task("pending_$tweet", DownloadStatus.DOWNLOADING)))[tweet])
    }

    @Test fun oldCompletedTasksDoNotPolluteNewAttempt() {
        val old = task("xinvox_${tweet}_1", DownloadStatus.COMPLETED, 100f, 10L)
        val pending = task("pending_$tweet", DownloadStatus.DOWNLOADING, at = 100L)
        val fresh = task("ytdlp_$tweet", DownloadStatus.DOWNLOADING, 20f, 110L)
        assertEquals(20, InboxDownloadProgress.progressByTweet(listOf(old, pending, fresh))[tweet])
    }

    @Test fun helperAndBackgroundImagesAreExcluded() {
        assertNull(InboxDownloadProgress.tweetIdOf("bgimg_${tweet}_1"))
        assertEquals(tweet, InboxDownloadProgress.tweetIdOf("tpapi_${tweet}_1"))
    }
}
