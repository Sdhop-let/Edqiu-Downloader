package com.ed.edqiu.data.repository

object DownloadRetryPolicy {
    const val MAX_ATTEMPTS = 3

    private val retryDelaysMs = longArrayOf(
        30_000L,
        2 * 60_000L
    )

    fun nextRetryAt(attemptNumber: Int, now: Long): Long? {
        if (attemptNumber <= 0 || attemptNumber >= MAX_ATTEMPTS) return null
        return now + retryDelaysMs[attemptNumber - 1]
    }
}
