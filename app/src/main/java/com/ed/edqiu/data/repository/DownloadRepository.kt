package com.ed.edqiu.data.repository

import android.content.Context
import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.DownloadPathPreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.service.YoutubeDLService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 下载任务仓库。
 *
 * 2026-10-21 清理：旧「解析 → 选格式 → 下载」管线（resolveVideoInfo / startDownload /
 * FXTwitterResolver / DirectDownloader / VideoInfo / VideoFormat）随旧「下载器」Home 屏
 * 一并删除——现行下载走 SavedLinkRepository.requestDownload → DownloaderClient 三层引擎链。
 * 本类保留下载中心所需的任务总线、持久化、取消与清理。
 */
class DownloadRepository(private val context: Context) {

    companion object {
        private const val TAG = "DownloadRepository"
    }

    val downloadTasks: StateFlow<List<DownloadTask>> = DownloadTaskBus.tasks

    private val proxyPreferences = ProxyPreferences(context)
    private val cookiePreferences = CookiePreferences(context)
    private val pathPreferences = DownloadPathPreferences(context)

    val outputDir: String
        get() = pathPreferences.resolveDownloadDir(context)

    private val taskRepo = DownloadTaskRepo(context)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cancelDownload(taskId: String) {
        // 先登记取消并销毁 yt-dlp 进程（若在跑），再更新状态——
        // 旧实现只改状态，下载引擎不受影响，取消完全无效（2026-10 修复）
        val processId = DownloadCancellation.cancel(taskId)
        YoutubeDLService.cancelDownload(processId)
        DownloadTaskBus.cancel(taskId)
        ioScope.launch {
            taskRepo.updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED) }
        }
    }

    fun removeTask(taskId: String) {
        DownloadTaskBus.remove(taskId)
        ioScope.launch { taskRepo.removeTask(taskId) }
    }

    fun clearCompleted() {
        DownloadTaskBus.clearCompleted()
        ioScope.launch { taskRepo.removeCompleted() }
    }
}
