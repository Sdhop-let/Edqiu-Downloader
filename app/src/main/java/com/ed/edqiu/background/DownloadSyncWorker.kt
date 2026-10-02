package com.ed.edqiu.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ed.edqiu.EdqiuApplication
import kotlinx.coroutines.flow.first

class DownloadSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? EdqiuApplication
            ?: return Result.retry()
        val container = application.container
        if (!container.settingsRepository.backgroundSyncFlow.first()) {
            return Result.success()
        }

        return runCatching {
            val monitorUri = container.settingsRepository.monitorDirUriFlow.first()
            val autoRetry = container.settingsRepository.autoRetryFlow.first()
            if (autoRetry) {
                container.savedLinkRepository.retryDueDownloads()
            }
            container.savedLinkRepository.refreshStatuses(monitorUri)
            container.savedLinkRepository.importScannedDownloads(monitorUri)
            container.savedLinkRepository.retryMissingMetadata()
            // 2026-09-30 v1.6.8：存量条目封面补落盘（每轮 ≤8 条），
            // 旧记录从远程 URL 升级为本地路径，避免下次进入再联网同步预览图
            container.savedLinkRepository.backfillLocalCovers()
            // 2026-10 P1 整改：移除 recoverInterruptedTasks——它无法区分"上次进程遗留"与
            // "本进程正在下载"，15 分钟周期任务会把超过 15 分钟的活跃下载改判为"已中断"，
            // 内存任务被覆盖、用户重下产生双份。进程级恢复只保留在 EdqiuApplication.onCreate。
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
