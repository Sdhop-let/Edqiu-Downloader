package com.ed.edqiu.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRetryPolicyTest {

    @Test
    fun appliesExpectedBackoff() {
        val now = 1_000L

        assertEquals(31_000L, DownloadRetryPolicy.nextRetryAt(1, now))
        assertEquals(121_000L, DownloadRetryPolicy.nextRetryAt(2, now))
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
