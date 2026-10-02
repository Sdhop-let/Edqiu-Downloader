package com.ed.edqiu.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackgroundSyncScheduler {
    private const val PERIODIC_WORK = "xinvox_download_sync"
    private const val IMMEDIATE_WORK = "xinvox_download_sync_now"

    // 2026-10 整改：同步 Worker 全部是网络活（refreshStatuses/retryMissingMetadata/封面回填），
    // 旧实现无约束，离线时每 15 分钟照常唤醒→失败→retry，纯耗电。加网络约束后离线自动顺延。
    private val syncConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun setEnabled(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            workManager.cancelUniqueWork(IMMEDIATE_WORK)
            return
        }

        val periodic = PeriodicWorkRequestBuilder<DownloadSyncWorker>(
            15,
            TimeUnit.MINUTES
        ).setConstraints(syncConstraints).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DownloadSyncWorker>()
                .setConstraints(syncConstraints)
                .build()
        )
    }
}
