package com.ed.edqiu.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRetryPolicyTest {

    @Test
    fun appliesExpectedBackoff() {
        val now = 1_000L

        // 2026-11-11 断网场景放宽：30s / 2min / 15min / 1h 长尾退避
        assertEquals(31_000L, DownloadRetryPolicy.nextRetryAt(1, now))
        assertEquals(121_000L, DownloadRetryPolicy.nextRetryAt(2, now))
        assertEquals(901_000L, DownloadRetryPolicy.nextRetryAt(3, now))
        assertEquals(3_601_000L, DownloadRetryPolicy.nextRetryAt(4, now))
    }

    @Test
    fun stopsAfterMaximumAttempts() {
        assertNull(DownloadRetryPolicy.nextRetryAt(0, 1_000L))
        assertNull(
            DownloadRetryPolicy.nextRetryAt(
                DownloadRetryPolicy.MAX_ATTEMPTS,
                1_000L
            )
        )
    }
}
