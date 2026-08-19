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

    suspend fun addToHistory(task: DownloadTask, filePath: String, duration: Long = 0) {
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
                completedAt = System.currentTimeMillis()
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

    suspend fun clearAll() = dao.clearAll()
}
