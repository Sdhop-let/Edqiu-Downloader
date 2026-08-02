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
            container.savedLinkRepository.retryMissingMetadata()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
