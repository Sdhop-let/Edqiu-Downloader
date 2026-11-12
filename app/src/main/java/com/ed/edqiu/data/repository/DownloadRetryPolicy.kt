package com.ed.edqiu.data.repository

object DownloadRetryPolicy {
    /**
     * 2026-11-11 断网场景放宽：旧值 MAX=3（30s/2min）在断网超过 2.5 分钟时即烧光
     * 自动重试额度，网络恢复后自动线停摆只剩手动。改为 4 次额度 + 长尾退避
     * （30s/2min/15min/1h），自动重试在约 78 分钟断网窗口内保持活性。
     */
    const val MAX_ATTEMPTS = 5

    private val retryDelaysMs = longArrayOf(
        30_000L,        // 第 1 次失败 → 30s
        2 * 60_000L,    // 第 2 次失败 → 2min
        15 * 60_000L,   // 第 3 次失败 → 15min
        60 * 60_000L    // 第 4 次失败 → 1h
    )

    fun nextRetryAt(attemptNumber: Int, now: Long): Long? {
        if (attemptNumber <= 0 || attemptNumber >= MAX_ATTEMPTS) return null
        return now + retryDelaysMs[attemptNumber - 1]
    }
}
