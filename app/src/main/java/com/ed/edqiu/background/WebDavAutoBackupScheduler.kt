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
        val account = prefs.currentAccount()
        Log.i(TAG, "doWork() 被调用, autoBackup=${prefs.autoBackupEnabled}, verified=${account.verified}, hasConfig=${account.isComplete}")
        if (!prefs.autoBackupEnabled || !account.verified || !account.isComplete) {
            Log.i(TAG, "自动备份未开启或连接未验证，跳过")
            return Result.success()
        }
        Log.i(TAG, "开始自动双向同步，server=${account.serverUrl}, remotePath=${account.remotePath}")
        val result = WebDavSyncService.syncDownloads(applicationContext, prefs)
        return result.fold(
            onSuccess = { r ->
                Log.i(TAG, "自动备份完成：上传 ${r.uploaded}，下载 ${r.downloaded}，跳过 ${r.skippedExisting}，冲突 ${r.conflicts}")
                if (r.hasChanges) {
                    prefs.syncedFileCount = prefs.syncedFileCount + r.uploaded
                    prefs.lastSyncTime = System.currentTimeMillis()
                }
                Result.success()
            },
            onFailure = { e ->
                Log.e(TAG, "自动备份失败 (attempt=$runAttemptCount)", e)
                // 2026-10 整改：配置性失败（密码改了/服务器下线）旧实现无限退避重试——
                // 3 次后放弃本轮，等下一个调度周期（下轮配置若修复会自动恢复）
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            },
        )
    }
}
