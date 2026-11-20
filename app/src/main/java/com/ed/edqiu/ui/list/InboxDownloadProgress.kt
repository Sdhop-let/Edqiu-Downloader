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
        val pending = group.filter { it.id.startsWith("pending_") && it.isActive }.maxByOrNull { it.createdAt }
        val current = group.filter { !it.id.startsWith("pending_") &&
            (pending == null || it.createdAt >= pending.createdAt) }
        // 换源表示新尝试，不能把失败源的进度计入新源，也不能重复计算历史成品。
        val active = current.filter { it.isActive }
        if (active.isEmpty()) {
            if (pending == null) return null
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
