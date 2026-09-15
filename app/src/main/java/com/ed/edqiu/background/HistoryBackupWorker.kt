package com.ed.edqiu.background

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ed.edqiu.EdqiuApplication
import kotlinx.coroutines.flow.first

class HistoryBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? EdqiuApplication
            ?: return Result.failure()
        val settings = application.container.settingsRepository
        val directory = settings.backupDirUriFlow.first()
        if (directory.isNullOrBlank()) {
            settings.recordBackupError("未设置自动备份目录")
            return Result.failure()
        }

        val repository = application.container.historyBackupRepository

        // 2026-09-14 节流：启动即备份（IMMEDIATE_WORK）与 24h 周期任务、手动"立即备份"
        // 可能同日多次触发，距上次成功备份不足 12 小时则跳过 —— 避免"为什么会有两份备份"
        val lastAt = settings.lastBackupAtFlow.first()
        if (lastAt != null && System.currentTimeMillis() - lastAt < 12 * 60 * 60 * 1000L) {
            return Result.success()
        }

        // 收件箱/回收站没有任何记录时无需备份，直接跳过，
        // 避免开发调试反复安装时在备份目录堆积空备份文件
        if (!repository.hasBackupContent()) {
            return Result.success()
        }

        return runCatching {
            repository.backupToDirectory(Uri.parse(directory))
            settings.recordBackupSuccess(System.currentTimeMillis())
            Result.success()
        }.getOrElse { error ->
            settings.recordBackupError(error.message ?: "自动备份失败")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
