package com.ed.edqiu.ui.components

import com.ed.edqiu.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskPresentationTest {
    @Test
    fun failedCancelledAndPausedHaveDistinctStatusText() {
        assertEquals("下载失败", downloadStatusText(DownloadStatus.FAILED, "视频", 0))
        assertEquals("已取消", downloadStatusText(DownloadStatus.CANCELLED, "视频", 0))
        assertEquals("已暂停", downloadStatusText(DownloadStatus.PAUSED, "视频", 0))
    }

    @Test
    fun failedCancelledAndPausedCanBeResumed() {
        assertTrue(DownloadStatus.FAILED.canResumeDownload())
        assertTrue(DownloadStatus.CANCELLED.canResumeDownload())
        assertTrue(DownloadStatus.PAUSED.canResumeDownload())
    }
}
