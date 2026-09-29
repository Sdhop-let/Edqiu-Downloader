package com.ed.edqiu.background

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.service.WebDavSyncService
import java.util.concurrent.TimeUnit

object WebDavAutoBackupScheduler {

    private const val TAG = "WebDavAutoBackup"
    private const val WORK_NAME = "xinvox_webdav_auto_backup"

    fun schedule(context: Context, days: Int) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val period = days.toLong().coerceAtLeast(1L)
        val request = PeriodicWorkRequestBuilder<WebDavAutoBackupWorker>(period, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        Log.i(TAG, "自动备份已调度：每 ${period} 天执行一次")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "自动备份已取消")
    }
}

class WebDavAutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WebDavAutoBackupWorker"
    }

    override suspend fun doWork(): Result {
        val prefs = CloudSyncPreferences(applicationContext)
        Log.i(TAG, "doWork() 被调用, autoBackup=${prefs.autoBackupEnabled}, verified=${prefs.connectionVerified}, hasConfig=${prefs.hasWebDavConfig()}")
        if (!prefs.autoBackupEnabled || !prefs.connectionVerified) {
            Log.i(TAG, "自动备份未开启或连接未验证，跳过")
            return Result.success()
        }
        Log.i(TAG, "开始自动备份，server=${prefs.serverUrl}, remotePath=${prefs.remotePath}")
        val result = WebDavSyncService.syncDownloads(applicationContext, prefs)
        return result.fold(
            onSuccess = { (new, skip) ->
                Log.i(TAG, "自动备份完成：新增 $new，跳过 $skip")
                if (new > 0) {
                    prefs.syncedFileCount = prefs.syncedFileCount + new
                    prefs.lastSyncTime = System.currentTimeMillis()
                }
                Result.success()
            },
            onFailure = { e ->
                Log.e(TAG, "自动备份失败", e)
                Result.retry()
            },
        )
    }
}
