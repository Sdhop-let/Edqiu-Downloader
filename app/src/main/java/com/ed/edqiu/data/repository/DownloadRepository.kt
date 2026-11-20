package com.ed.edqiu.data.repository

import android.content.Context
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.DownloadPathPreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.service.YoutubeDLService
import kotlinx.coroutines.flow.StateFlow

/**
 * 下载任务仓库。
 *
 * 2026-10-21 清理：旧「解析 → 选格式 → 下载」管线（resolveVideoInfo / startDownload /
 * FXTwitterResolver / DirectDownloader / VideoInfo / VideoFormat）随旧「下载器」Home 屏
 * 一并删除——现行下载走 SavedLinkRepository.requestDownload → DownloaderClient 三层引擎链。
 * 本类保留下载中心所需的任务总线、取消与清理。
 *
 * 2026-11-21（DB v17）：DownloadTaskRepo/download_tasks 死层已删除——updateTask 等全部
 * 依赖已有行（DAO upsert 无调用方），recoverInterruptedTasks 零调用，进程被杀恢复实际由
 * WorkManager 队列（DownloadQueue/Worker）承担，任务状态展示走内存 TaskBus。
 */
class DownloadRepository(private val context: Context) {

    companion object {
        /** 任务 id → 推文 id（与 InboxDownloadProgress 同口径，额外含 bgimg 补拉前缀）。 */
        private val TASK_TWEET_ID = Regex("^(?:xinvox|tpapi|bgimg)_(\\d+)_\\d+$|^(?:ytdlp|pending)_(\\d+)$")

        fun tweetIdOf(taskId: String): String? = TASK_TWEET_ID.matchEntire(taskId)?.let { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
    }

    val downloadTasks: StateFlow<List<DownloadTask>> = DownloadTaskBus.tasks

    private val proxyPreferences = ProxyPreferences(context)
    private val cookiePreferences = CookiePreferences(context)
    private val pathPreferences = DownloadPathPreferences(context)

    val outputDir: String
        get() = pathPreferences.resolveDownloadDir(context)

    fun cancelDownload(taskId: String) {
        // 先登记取消并销毁 yt-dlp 进程（若在跑），再更新状态——
        // 旧实现只改状态，下载引擎不受影响，取消完全无效（2026-10 修复）
        val processId = DownloadCancellation.cancel(taskId)
        YoutubeDLService.cancelDownload(processId)
        DownloadTaskBus.cancel(taskId)
        // P1 审计修复（2026-11-21）：取消升级为推文级。旧实现只写单个 taskId 标记——
        // 「准备中」占位（pending_ 前缀）从未传给任何引擎，取消完全无效（下载照常完成）；
        // 且推文内其余任务各自为政。现统一处理：总线内该推文全部活跃任务逐个
        // 取消+杀进程+置 CANCELLED，另写推文级会话信号（session_）兜底
        // 「引擎链启动期任务尚未创建」的取消窗口（引擎首个检查点即命中）。
        val tweetId = tweetIdOf(taskId) ?: return
        DownloadTaskBus.tasks.value.filter { task ->
            task.isActive && task.id != taskId && tweetIdOf(task.id) == tweetId
        }.forEach { task ->
            DownloadCancellation.cancel(task.id)
            YoutubeDLService.cancelDownload(task.id)
            DownloadTaskBus.cancel(task.id)
        }
        DownloadCancellation.cancel("session_$tweetId")
    }

    fun removeTask(taskId: String) {
        DownloadTaskBus.remove(taskId)
    }

    fun clearCompleted() {
        DownloadTaskBus.clearCompleted()
    }
}
