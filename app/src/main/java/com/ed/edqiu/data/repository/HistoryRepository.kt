package com.ed.edqiu.data.repository

import android.content.Context
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.model.DownloadTask
import kotlinx.coroutines.flow.Flow
import java.io.File

class HistoryRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).downloadHistoryDao()

    val allHistory: Flow<List<DownloadHistoryEntity>> = dao.getAllHistory()
    val historyCount: Flow<Int> = dao.getCount()

    suspend fun addToHistory(
        task: DownloadTask,
        filePath: String,
        duration: Long = 0,
        // 2026-10-02 批次B：下载入库即带宽高（解析期 FXTwitter 已给出的显示尺寸；
        // null=来源未提供，交由 MediaDimensionsBackfillWorker 本地回填）。
        mediaWidth: Int? = null,
        mediaHeight: Int? = null
    ) {
        val file = File(filePath)
        dao.insert(
            DownloadHistoryEntity(
                id = task.id,
                url = task.url,
                title = task.title,
                thumbnail = task.thumbnail,
                uploader = task.uploader,
                quality = task.quality,
                mediaIndex = task.mediaIndex,
                mediaType = task.mediaType,
                filePath = filePath,
                fileSize = file.takeIf { it.exists() }?.length() ?: 0L,
                duration = duration,
                createdAt = task.createdAt,
                completedAt = System.currentTimeMillis(),
                avatarUrl = task.avatarUrl.takeIf { it.isNotBlank() },
                authorName = task.authorName.takeIf { it.isNotBlank() },
                mediaWidth = mediaWidth,
                mediaHeight = mediaHeight
            )
        )
    }

    suspend fun delete(entity: DownloadHistoryEntity) = dao.delete(entity)

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun getByUrl(url: String) = dao.getByUrl(url)

    suspend fun getSingleByUrl(url: String) = dao.getSingleByUrl(url)

    suspend fun getRecentUploaders(limit: Int = 10) = dao.getRecentUploaders(limit)

    suspend fun getByUrlAndMediaIndex(url: String, mediaIndex: Int) =
        dao.getByUrlAndMediaIndex(url, mediaIndex)

    suspend fun getByTweetId(tweetId: String) = dao.getByTweetId(tweetId, tweetId)

    // 2026-10-02 批次B：存量媒体宽高回填（MediaDimensionsBackfillWorker 用）。
    /** 宽高缺失记录清单（mediaWidth IS NULL）。 */
    suspend fun historyNeedingDimensions(): List<DownloadHistoryEntity> =
        dao.historyNeedingDimensions()

    /** 回填单条记录的媒体显示宽高。 */
    suspend fun updateMediaDimensions(id: String, width: Int, height: Int) =
        dao.updateMediaDimensions(id, width, height)

    suspend fun clearAll() = dao.clearAll()
}
