package com.ed.edqiu.background

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.model.MediaType
import com.ed.edqiu.data.repository.HistoryRepository
import kotlinx.coroutines.delay
import java.io.File

/**
 * 存量媒体宽高回填（2026-10-02 批次B）：为 download_history 中宽高缺失（mediaWidth IS NULL）
 * 的记录做纯本地探测——视频用 MediaMetadataRetriever（rotation 90/270 时交换宽高，
 * 保证是显示方向尺寸），图片用 BitmapFactory inJustDecodeBounds 只读边界不解码。
 * 结果供播放器「滑动切条比例变动过渡动画」取比例，UI 层无需再读文件探测。
 *
 * 幂等：只处理 mediaWidth IS NULL 的记录，成功写库后不再出现；文件不存在/读取失败
 * 保持 null，下次启动自动再补。调度用 KEEP 防重复排队（见 [schedule]）。
 */
class MediaDimensionsBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 整体 try/catch：回填是纯增益任务，任何异常不影响主流程；
        // 恒返回 success（部分失败/中断的记录下次启动会再补）
        return try {
            val repository = HistoryRepository(applicationContext)
            val pending = repository.historyNeedingDimensions()
            pending.forEach { entry ->
                backfillEntry(repository, entry)
                delay(THROTTLE_MS)
            }
            if (pending.isNotEmpty()) {
                Log.i(TAG, "宽高回填完成：候选=${pending.size}")
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "宽高回填中断（下次启动继续）", e)
            Result.success()
        }
    }

    /** 单条回填：任何失败就地吞掉，不影响其余记录。 */
    private suspend fun backfillEntry(repository: HistoryRepository, entry: DownloadHistoryEntity) {
        runCatching {
            val file = File(entry.filePath)
            if (!file.exists()) return  // 文件不存在：保持 null，下次再补
            val dims = when (entry.mediaType) {
                MediaType.IMAGE -> readImageDimensions(file)
                MediaType.VIDEO -> readVideoDimensions(file)
            }
            // 宽或高 ≤0 视为失败跳过（保持 null）
            if (dims != null) {
                repository.updateMediaDimensions(entry.id, dims.first, dims.second)
            }
        }
    }

    /** 视频显示宽高：编码宽高 + 旋转角，rotation 90/270 时交换得到显示方向尺寸；读不到返回 null。 */
    private fun readVideoDimensions(file: File): Pair<Int, Int>? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) {
                null
            } else if (rotation == 90 || rotation == 270) {
                height to width
            } else {
                width to height
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    /** 图片宽高：inJustDecodeBounds 只读边界不解码位图；读不到（≤0）返回 null。 */
    private fun readImageDimensions(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    companion object {
        private const val TAG = "MediaDimensionsBackfill"
        private const val UNIQUE_WORK_NAME = "media_dimensions_backfill"

        /** 逐条节流间隔：批量探测是纯本地 IO，小延时避免挤占磁盘与 DB。 */
        private const val THROTTLE_MS = 50L

        /**
         * Application 启动时调度的一次性回填任务。
         * 幂等：Worker 只处理 mediaWidth IS NULL 的记录，成功写库后不再出现；
         * ExistingWorkPolicy.KEEP 防重复（已排队/运行中则本次跳过）。
         * 纯本地探测不依赖网络，无须设置约束。
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MediaDimensionsBackfillWorker>().build()
            )
        }
    }
}
