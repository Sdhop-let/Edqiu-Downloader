package com.ed.edqiu.background

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackgroundSyncScheduler {
    private const val PERIODIC_WORK = "xinvox_download_sync"
    private const val IMMEDIATE_WORK = "xinvox_download_sync_now"

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
        ).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DownloadSyncWorker>().build()
        )
    }
}
