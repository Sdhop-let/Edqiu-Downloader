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

    @Test fun cancelledPlaceholderStillAnchorsSessionBoundary() {
        // P2 审计修复（2026-11-21）：占位被取消（isActive=false）后，会话分界不再锚在
        // 占位活性上——历史会话任务（更早 createdAt）不得泄入当前会话分母。
        val oldFailed = task("xinvox_${tweet}_1", DownloadStatus.FAILED, 50f, 10L)
        val cancelledPending = task("pending_$tweet", DownloadStatus.CANCELLED, 0f, 100L)
        val fresh = task("xinvox_${tweet}_2", DownloadStatus.DOWNLOADING, 40f, 110L)
        // 占位失活前：分母 = 本次会话任务（fresh 单个），进度 40%（旧 FAILED 不计入）
        assertEquals(40, InboxDownloadProgress.progressByTweet(listOf(oldFailed, cancelledPending, fresh))[tweet])
    }

    @Test fun activeEmptyWithoutLivePlaceholderHandsBackToDb() {
        // 占位被取消且无活跃任务：不显示 99 哨兵，交回 DB 状态（与旧语义一致）
        val cancelledPending = task("pending_$tweet", DownloadStatus.CANCELLED, 0f, 100L)
        val failed = task("xinvox_${tweet}_1", DownloadStatus.FAILED, 50f, 110L)
        assertTrue(InboxDownloadProgress.progressByTweet(listOf(cancelledPending, failed)).isEmpty())
    }

    @Test fun noNinetyNineSentinelWhileSessionHasFailures() {
        // P3 审计修复（2026-11-21）：引擎空窗（会话内有失败任务，可能换引擎续跑）
        // 不亮 99 哨兵——避免「99→换引擎低位」锯齿；无失败的真实等待落库窗口才亮
        val pending = task("pending_$tweet", DownloadStatus.DOWNLOADING, 0f, 100L)
        val done = task("xinvox_${tweet}_1", DownloadStatus.COMPLETED, 100f, 110L)
        val failed = task("xinvox_${tweet}_2", DownloadStatus.FAILED, 30f, 120L)
        assertTrue(InboxDownloadProgress.progressByTweet(listOf(pending, done, failed)).isEmpty())
    }
}
