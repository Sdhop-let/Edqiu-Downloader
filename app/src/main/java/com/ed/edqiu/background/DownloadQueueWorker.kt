package com.ed.edqiu.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ed.edqiu.EdqiuApplication
import com.ed.edqiu.data.model.LinkStatus
import java.util.concurrent.TimeUnit

/**
 * 下载任务持久队列（2026-09-15 v2 批次2：P0-4 进程被杀下载不丢）。
 *
 * 语义：只承接「用户/订阅已请求下载」的任务——**不**自动下载所有 PENDING
 * （收件箱是手动模式语义）。每次 `requestDownload` 时由
 * [com.ed.edqiu.data.repository.SavedLinkRepository.onDownloadRequested] 钩子
 * 登记一个唯一名 Worker（KEEP 策略）；正常路径下 Worker 起来时任务已完成，
 * 幂等跳过；若进程在下载中途被杀，WorkManager 在进程恢复后自动重跑，
 * 检查状态未完成 → 续跑三层引擎链（yt-dlp 自带断点续传 / 内部引擎 Range 续传）。
 */
object DownloadQueue {

    const val KEY_TWEET_ID = "tweetId"

    /** 把任务登记进 WorkManager 持久队列（幂等：同 tweetId 用 KEEP 去重）。 */
    fun enqueue(context: Context, tweetIds: List<String>) {
        val wm = WorkManager.getInstance(context)
        tweetIds.filter { it.isNotBlank() }.forEach { id ->
            val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                .setInputData(workDataOf(KEY_TWEET_ID to id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30_000L, TimeUnit.MILLISECONDS)
                .addTag("download_queue")
                .build()
            wm.enqueueUniqueWork("edqiu_dl_$id", ExistingWorkPolicy.KEEP, request)
        }
    }
}

class DownloadQueueWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tweetId = inputData.getString(DownloadQueue.KEY_TWEET_ID)
            ?: return Result.success()
        val container = (applicationContext as? EdqiuApplication)?.container
            ?: return Result.retry()

        return runCatching {
            val link = container.savedLinkRepository.getByTweetId(tweetId)
                ?: return@runCatching Result.success()
            if (link.status == LinkStatus.DOWNLOADED || link.status == LinkStatus.DELETED) {
                return@runCatching Result.success()
            }
            // viaQueue = true：不再次触发登记钩子，避免 Worker 自我递归
            container.savedLinkRepository.requestDownload(tweetId, viaQueue = true)
            Result.success()
        }.getOrElse {
            // 网络类失败交给 WorkManager 退避重试（指数/线性 backoff）
            Result.retry()
        }
    }
}
