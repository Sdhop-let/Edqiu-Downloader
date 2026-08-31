package com.ed.edqiu.data.repository

import android.content.Context
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.database.DownloadTaskDao
import com.ed.edqiu.data.database.DownloadTaskEntity
import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 活跃下载任务的持久化封装（`download_tasks`）。
 *
 * 职责：把 [DownloadTask] 落盘/更新/删除到 Room，并支持把上次进程被杀后遗留的中断任务
 * 恢复为「可重新下载」的 [DownloadStatus.FAILED] 状态，供 UI 与后台 Worker 触达。
 *
 * 说明：路径 A（DownloadRepository 的 Direct / yt-dlp 下载）通过本仓库持久化；
 * 收件箱（SavedLink）下载状态本就持久化在 SavedLinkDao，无需重复落盘。
 */
class DownloadTaskRepo(context: Context) {

    private val dao: DownloadTaskDao =
        AppDatabase.getInstance(context.applicationContext).downloadTaskDao()

    suspend fun addTask(task: DownloadTask, downloaderType: String = "DIRECT") = withContext(Dispatchers.IO) {
        dao.upsert(task.toEntity().copy(downloaderType = downloaderType))
    }

    suspend fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) =
        withContext(Dispatchers.IO) {
            val entity = dao.getById(taskId) ?: return@withContext
            val updated = transform(entity.toDomain())
            dao.upsert(updated.toEntity().copy(downloaderType = entity.downloaderType))
        }

    suspend fun removeTask(taskId: String) = withContext(Dispatchers.IO) {
        dao.deleteById(taskId)
    }

    suspend fun removeCompleted() = withContext(Dispatchers.IO) {
        dao.deleteByStatuses(
            listOf(DownloadStatus.COMPLETED.name, DownloadStatus.FAILED.name, DownloadStatus.CANCELLED.name)
        )
    }

    /**
     * 把上次进程结束后仍处于“进行中/待下载”的任务标记为中断（FAILED），
     * 并返回恢复后的任务列表，供写回内存总线（DownloadTaskBus）让下载中心可见。
     *
     * 现有下载器（Direct/yt-dlp）不支持断点续传，因此恢复为「可重新下载」而非自动续传，
     * 避免重复或半文件被误当完整文件。
     */
    suspend fun recoverInterruptedTasks(): List<DownloadTask> = withContext(Dispatchers.IO) {
        val interruptible = listOf(
            DownloadStatus.PENDING,
            DownloadStatus.RESOLVING,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
        ).map { it.name }
        dao.loadByStatuses(interruptible).map { entity ->
            val recovered = entity.toDomain().copy(
                status = DownloadStatus.FAILED,
                progress = 0f,
                errorMessage = "上次下载被中断，可重新下载",
            )
            dao.upsert(recovered.toEntity().copy(downloaderType = entity.downloaderType))
            recovered
        }
    }

    private fun DownloadTask.toEntity(): DownloadTaskEntity = DownloadTaskEntity(
        id = id,
        url = url,
        title = title,
        thumbnail = thumbnail,
        uploader = uploader,
        formatId = formatId,
        quality = quality,
        ext = ext,
        mediaType = mediaType.name,
        mediaIndex = mediaIndex,
        progress = progress,
        etaSeconds = etaSeconds,
        status = status.name,
        outputPath = outputPath,
        errorMessage = errorMessage,
        createdAt = createdAt,
        completedAt = completedAt,
        isCancelled = status == DownloadStatus.CANCELLED,
    )

    private fun DownloadTaskEntity.toDomain(): DownloadTask = DownloadTask(
        id = id,
        url = url,
        title = title,
        thumbnail = thumbnail,
        uploader = uploader,
        formatId = formatId,
        quality = quality,
        ext = ext,
        mediaType = runCatching { MediaType.valueOf(mediaType) }.getOrDefault(MediaType.VIDEO),
        mediaIndex = mediaIndex,
        progress = progress,
        etaSeconds = etaSeconds,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.PENDING),
        outputPath = outputPath,
        errorMessage = errorMessage,
        createdAt = createdAt,
        completedAt = completedAt,
    )
}