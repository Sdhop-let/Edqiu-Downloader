package com.ed.edqiu.data.repository

import java.util.concurrent.ConcurrentHashMap

/**
 * 活跃下载任务的取消登记表（进程级，taskId → 取消标记）。
 *
 * - 下载开始前 [register] 登记（幂等，并清除旧的取消标记，支持同任务重新下载）；
 * - yt-dlp 引擎：taskId 直接作为 yt-dlp processId 使用，[cancel] 返回该 id 供调用方
 *   `YoutubeDLService.cancelDownload(processId)` 销毁进程（修复原 processId 为局部变量、
 *   取消链路不可达的问题）；
 * - HTTP 引擎（DirectDownloader / InternalMediaDownloader）：下载循环周期性查询
 *   [isCancelled]，命中即抛 [com.ed.edqiu.data.model.DownloadCancelledException] 中止；
 * - 引擎失败返回后调用方用 [isCancelled] 区分「用户取消」与「真实失败」：
 *   前者置 CANCELLED，后者置 FAILED（避免取消被记成失败并消耗重试次数）。
 */
object DownloadCancellation {

    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** 下载开始前登记任务（幂等；清除历史取消标记）。 */
    fun register(taskId: String) {
        cancelled.remove(taskId)
    }

    /** 下载进入终态后注销任务。 */
    fun unregister(taskId: String) {
        cancelled.remove(taskId)
    }

    /**
     * 取消任务：登记取消标记，并返回 yt-dlp processId（当前实现与 taskId 同值）
     * 供调用方销毁下载进程；无对应进程时销毁调用是安全的 no-op。
     */
    fun cancel(taskId: String): String {
        cancelled += taskId
        return taskId
    }

    fun isCancelled(taskId: String): Boolean = taskId in cancelled
}
