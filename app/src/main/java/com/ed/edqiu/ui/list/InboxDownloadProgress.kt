package com.ed.edqiu.ui.list

import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask

/**
 * 收件箱实时下载进度映射（2026-09-15）。
 *
 * 背景：收件箱 [com.ed.edqiu.data.model.SavedLink] 的状态由磁盘扫描派生，只有
 * PENDING/DOWNLOADED/FAILED/DELETED 四态，下载进行期间条目一直停在 PENDING，
 * 用户看不出「哪条正在下载、下到多少」。
 *
 * 而下载双引擎（FXTwitter 内部引擎 / yt-dlp 回退引擎）本来就把实时进度以 300ms
 * 节流回写到 [com.ed.edqiu.data.repository.DownloadTaskBus]（内存 StateFlow）。
 * 本映射器把 Bus 里的活跃任务按 tweetId 归并，供收件箱 UI 直接消费——
 * 零数据库迁移、零写放大；App 进程结束下载任务也随之消失，内存态与真实状态天然一致。
 *
 * 任务 ID 约定（与 [com.ed.edqiu.downloader.InternalMediaDownloader] /
 * [com.ed.edqiu.data.repository.DownloaderClient] 保持一致）：
 * - 引擎一：`xinvox_<tweetId>_<mediaIndex>`（一条推文多媒体时可有多个任务，串行下载）
 * - 引擎二：`ytdlp_<tweetId>`
 */
object InboxDownloadProgress {

    private val XINVOX_ID = Regex("""^xinvox_(\d+)_\d+$""")
    private val YTDLP_ID = Regex("""^ytdlp_(\d+)$""")

    /** 从下载任务 ID 解析 tweetId；非收件箱任务（如手动 URL 下载）返回 null。 */
    fun tweetIdOf(taskId: String): String? =
        XINVOX_ID.find(taskId)?.groupValues?.get(1)
            ?: YTDLP_ID.find(taskId)?.groupValues?.get(1)

    /**
     * 归并下载中任务：tweetId → 0..99 总进度百分比。
     * - 只统计 [DownloadStatus.DOWNLOADING]（COMPLETED 不进表：完成瞬间 DB 即转 DOWNLOADED，
     *   避免卡片闪「100% 下载中」）；
     * - 同一推文多媒体任务按均值合并（内部引擎为串行下载，通常只有一个活跃任务）；
     * - 返回值上限 99：100% 语义上属于「完成」，交给 DB 的 DOWNLOADED 状态表达。
     */
    fun progressByTweet(tasks: List<DownloadTask>): Map<String, Int> =
        tasks.asSequence()
            .filter { it.status == DownloadStatus.DOWNLOADING }
            .mapNotNull { task -> tweetIdOf(task.id)?.let { it to task.progress } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, progresses) ->
                (progresses.average().toInt()).coerceIn(0, 99)
            }
}
