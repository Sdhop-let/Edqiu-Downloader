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
 * - 占位（2026-10-22）：`pending_<tweetId>`——引擎链启动期（API 分析/yt-dlp 初始化）
 *   的「准备下载」占位任务，消除点下载后 5-10s 的反馈空窗
 * - 第三方 API 兜底（2026-10-24 审计补齐）：`tpapi_<tweetId>_<mediaIndex>`——此前不在
 *   归并正则内，兜底下载期间收件箱卡片全程无进度（下载中心却看得到任务）
 */
object InboxDownloadProgress {

    private val XINVOX_ID = Regex("""^xinvox_(\d+)_\d+$""")
    private val YTDLP_ID = Regex("""^ytdlp_(\d+)$""")
    private val PENDING_ID = Regex("""^pending_(\d+)$""")
    private val TPAPI_ID = Regex("""^tpapi_(\d+)_\d+$""")

    /** 从下载任务 ID 解析 tweetId；非收件箱任务（如手动 URL 下载）返回 null。 */
    fun tweetIdOf(taskId: String): String? =
        XINVOX_ID.find(taskId)?.groupValues?.get(1)
            ?: YTDLP_ID.find(taskId)?.groupValues?.get(1)
            ?: PENDING_ID.find(taskId)?.groupValues?.get(1)
            ?: TPAPI_ID.find(taskId)?.groupValues?.get(1)

    /**
     * 归并下载中任务：tweetId → 0..99 总进度百分比。
     *
     * 2026-11-11 回弹修复——聚合单位从「单任务百分比」改为「推文总进度」：
     * - 旧实现同推文取 max，但多媒体串行下载时媒体 1 完成即被滤掉（COMPLETED 不进表）、
     *   媒体 2 从 0% 起步 → 收件箱卡片 100%→0% 回弹；
     * - 现按 (已完成媒体数×100 + 下载中任务进度之和) / 动态总数 计算，媒体切换时
     *   进度平滑过半而非归零；
     * - 占位任务（pending_）在有真实任务时排除（0% 会拉低平均），仅占位期显示 0%
     *   （保留 2026-10-22 启动空窗修复的语义）；
     * - 引擎链切换（xinvox→ytdlp→回退）先置 FAILED 再起新任务，FAILED 同样不进表，
     *   新任务从 0 起步时由「已完成媒体数」托底，不再出现整段归零。
     */
    fun progressByTweet(tasks: List<DownloadTask>): Map<String, Int> =
        tasks.asSequence()
            .mapNotNull { task -> tweetIdOf(task.id)?.let { it to task } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (tweetId, group) -> aggregate(group)?.let { tweetId to it } }
            .toMap()

    /**
     * 2026-11-11 真机实测修复（收件箱永久卡「下载中 99%」）：全部任务已终态（无
     * DOWNLOADING）时**必须移出进度表**——终态任务常驻 Bus（100 上限裁剪），把
     * COMPLETED 纳入聚合后 (N×100)/N=100 → clamp 99 → 卡片永久显示 99%，而
     * LinkCard 的 isDownloading 只看进度 map 非空、不看 DB 状态，DOWNLOADED 也被
     * 盖住。返回 null = 卡片由 DB 状态接管（PENDING 显示待下载 / DOWNLOADED 显示已下载）。
     */
    private fun aggregate(group: List<DownloadTask>): Int? {
        val relevant = group.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.COMPLETED }
        // 占位任务有真实任务在场时排除；仅剩占位时保留（启动空窗显示 0% 占位语义）
        val real = relevant.filter { !it.id.startsWith("pending_") }
        val effective = real.ifEmpty { relevant }
        val active = effective.filter { it.status == DownloadStatus.DOWNLOADING }
        if (active.isEmpty()) return null
        val completed = effective.count { it.status == DownloadStatus.COMPLETED }
        val total = completed + active.size
        if (total == 0) return null
        val activeSum = active.fold(0f) { acc, task -> acc + task.progress }
        return ((completed * 100f + activeSum) / total).toInt().coerceIn(0, 99)
    }
}
