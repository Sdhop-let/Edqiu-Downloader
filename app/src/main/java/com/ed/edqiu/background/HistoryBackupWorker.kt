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
