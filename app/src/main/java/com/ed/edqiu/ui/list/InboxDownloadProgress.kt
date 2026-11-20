package com.ed.edqiu.ui.list

import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask

/** 收件箱按当前下载会话聚合全部媒体（包括排队项），终态后交回数据库状态。 */
object InboxDownloadProgress {
    private val TASK_ID = Regex("^(?:xinvox|tpapi)_(\\d+)_\\d+$|^(?:ytdlp|pending)_(\\d+)$")

    fun tweetIdOf(taskId: String): String? = TASK_ID.matchEntire(taskId)?.let { match ->
        match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    fun progressByTweet(tasks: List<DownloadTask>): Map<String, Int> = tasks
        .mapNotNull { task -> tweetIdOf(task.id)?.let { it to task } }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (tweetId, group) -> aggregate(group)?.let { tweetId to it } }.toMap()

    private fun aggregate(group: List<DownloadTask>): Int? {
        // P2 审计修复（2026-11-21）：会话分界改锚「最近一次占位任务时间」——旧实现锚在
        // 占位的 isActive 上，占位被用户取消（isActive=false）后过滤整体失效，Bus 里该
        // 推文全部历史会话任务泄入分母，进度被旧失败摊薄且长时间停在低位。
        // 占位任务在 launch finally 必然移除或置 CANCELLED，不存在跨会话残留，
        // 其 createdAt 恒等于「本次下载会话起点」。
        val boundary = group.filter { it.id.startsWith("pending_") }
            .maxByOrNull { it.createdAt }?.createdAt
            ?: group.maxOfOrNull { it.createdAt }
        val current = group.filter { !it.id.startsWith("pending_") &&
            (boundary == null || it.createdAt >= boundary) }
        // 换源表示新尝试，不能把失败源的进度计入新源，也不能重复计算历史成品。
        val active = current.filter { it.isActive }
        // 99 哨兵只在「占位仍活跃、引擎任务尚未产出终态」的会话内使用；
        // 占位已失活（取消/移除）交回 DB 状态（与旧语义一致）
        val activePending = group.any { it.id.startsWith("pending_") && it.isActive }
        if (active.isEmpty()) {
            if (!activePending) return null
            return if (current.any { it.status == DownloadStatus.COMPLETED }) 99 else 0
        }
        val engine = active.maxBy { it.createdAt }.id.substringBefore('_')
        val effective = current.filter { it.id.substringBefore('_') == engine }
        if (effective.isEmpty()) return 0
        val sum = effective.sumOf { task ->
            when (task.status) {
                DownloadStatus.COMPLETED -> 100.0
                DownloadStatus.DOWNLOADING -> task.progress.takeIf { it.isFinite() }?.coerceIn(0f, 99f)?.toDouble() ?: 0.0
                else -> 0.0
            }
        }
        return (sum / effective.size).toInt().coerceIn(0, 99)
    }
}
