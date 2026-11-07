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
import com.ed.edqiu.data.preferences.SyncHistoryRecord
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
        private const val CHANNEL_ID = "webdav_sync_progress"
        private const val NOTIFICATION_ID = 20261

        /**
         * 前台服务信息（2026-11-07 审计修复）：长传输（>2GB 视频断点续传）在
         * Android 14+ 无 FGS 保护会被系统冻结/杀掉——Manifest 的 dataSync 声明与
         * FOREGROUND_SERVICE_DATA_SYNC 权限早已就位，但 setForeground 调用随
         * BackupWorker(T05) 删除而丢失（死配置）。渠道惰性创建（幂等）。
         */
        fun syncForegroundInfo(context: Context): androidx.work.ForegroundInfo {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID, "网盘同步", android.app.NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "WebDAV 双向同步进行中" }
                )
            }
            val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("正在同步网盘")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
            return androidx.work.ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    override suspend fun doWork(): Result {
        // 2026-11-07 审计修复：挂前台服务防后台冻结；失败（权限/系统限制）降级普通后台
        runCatching { setForeground(syncForegroundInfo(applicationContext)) }
            .onFailure { Log.w(TAG, "setForeground 失败，降级后台执行", it) }
        val prefs = CloudSyncPreferences(applicationContext)
        val account = prefs.currentAccount()
        Log.i(TAG, "doWork() 被调用, autoBackup=${prefs.autoBackupEnabled}, verified=${account.verified}, hasConfig=${account.isComplete}")
        if (!prefs.autoBackupEnabled || !account.verified || !account.isComplete) {
            Log.i(TAG, "自动备份未开启或连接未验证，跳过")
            return Result.success()
        }
        Log.i(TAG, "开始自动双向同步，server=${account.serverUrl}, remotePath=${account.remotePath}")
        // 备份中心历史前置快照（2026-10）：同 WebDavManualSync 口径
        val firstEver = prefs.lastSyncTime == 0L
        val recovering = prefs.syncInProgress
        val result = WebDavSyncService.syncDownloads(applicationContext, prefs)
        return result.fold(
            onSuccess = { r ->
                Log.i(TAG, "自动备份完成：上传 ${r.uploaded}，下载 ${r.downloaded}，跳过 ${r.skippedExisting}，冲突 ${r.conflicts}")
                // 2026-11-07 审计修复：被「停止本轮」中断的轮（用户在详情页按停止，
                // 全局 abort 对 Worker 轮同样生效）不得记 success 历史——与手动链路
                // WebDavManualSync 的 stopped 口径对齐
                if (r.roundAborted) {
                    prefs.addSyncHistory(
                        SyncHistoryRecord(
                            timestamp = System.currentTimeMillis(),
                            mode = if (firstEver) "full" else "incremental",
                            files = r.uploaded + r.downloaded,
                            bytes = r.bytesTransferred,
                            status = "stopped",
                        )
                    )
                    return@fold Result.success()
                }
                if (r.hasChanges) {
                    prefs.syncedFileCount = prefs.syncedFileCount + r.uploaded
                    prefs.lastSyncTime = System.currentTimeMillis()
                }
                prefs.addSyncHistory(
                    SyncHistoryRecord(
                        timestamp = System.currentTimeMillis(),
                        mode = if (firstEver) "full" else "incremental",
                        files = r.uploaded + r.downloaded,
                        bytes = r.bytesTransferred,
                        status = if (recovering) "frozen_healed" else "success",
                    )
                )
                Result.success()
            },
            onFailure = { e ->
                Log.e(TAG, "自动备份失败 (attempt=$runAttemptCount)", e)
                prefs.addSyncHistory(
                    SyncHistoryRecord(
                        timestamp = System.currentTimeMillis(),
                        mode = if (firstEver) "full" else "incremental",
                        files = 0,
                        bytes = 0L,
                        status = "failed",
                    )
                )
                // 2026-10 整改：配置性失败（密码改了/服务器下线）旧实现无限退避重试——
                // 3 次后放弃本轮，等下一个调度周期（下轮配置若修复会自动恢复）
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            },
        )
    }
}
