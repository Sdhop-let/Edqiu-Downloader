package com.ed.edqiu.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ed.edqiu.service.YtdlpEngineUpdater
import com.ed.edqiu.service.YoutubeDLService
import java.util.concurrent.TimeUnit

/**
 * yt-dlp 引擎每日后台自更新（2026-11-21 轻云控）。
 *
 * 设计：
 * - **静默**：成功/失败只进日志，不发通知不打扰——引擎更新对用户是透明的基础设施维护；
 * - **幂等省流量**：已是最新时 [YtdlpEngineUpdater.update] 只做一次 API 检查即返回
 *   （不下载 ~3MB 二进制），失败不 Result.retry（下个 24h 周期自然再试）；
 * - **替换安全**：落位用同目录 rename（原子换 inode），正在运行的 yt-dlp 进程持有
 *   旧 inode 不受影响，新进程即用新版本；
 * - **开关在 Worker 内自查**（[YtdlpEngineUpdater.isAutoUpdateEnabled]），调度保持
 *   常驻（KEEP 策略防每次启动重置 24h 间隔导致永不执行）。
 */
class YtdlpEngineUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!YtdlpEngineUpdater.isAutoUpdateEnabled(applicationContext)) {
            return Result.success()
        }
        runCatching { YoutubeDLService.updateYoutubeDL(applicationContext) }
            .onSuccess { version ->
                android.util.Log.i(TAG, "yt-dlp 引擎后台自更新完成：$version")
            }
            .onFailure { e ->
                // 静默失败：网络/代理波动下个周期自然重试，不打扰用户
                android.util.Log.w(TAG, "yt-dlp 引擎后台自更新失败（下轮重试）：${e.message}")
            }
        return Result.success()
    }

    companion object {
        private const val TAG = "YtdlpEngineUpdateWorker"
        private const val PERIODIC_WORK = "edqiu_ytdlp_engine_update"

        /**
         * App 启动时调度（幂等）。**必须 KEEP**：UPDATE 策略会在每次冷启动重置
         * 24h 周期，用户每天开 App = 任务永不执行。开关关闭时 Worker 自查直接返回，
         * 保留调度零成本（每日一次的空跑）。
         */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<YtdlpEngineUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
