package com.ed.twitterdownloader.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadHistoryDao {

    @Query("SELECT * FROM download_history ORDER BY completedAt DESC")
    fun getAllHistory(): Flow<List<DownloadHistoryEntity>>

    @Query("SELECT * FROM download_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadHistoryEntity?

    @Query("SELECT * FROM download_history WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): DownloadHistoryEntity?

    @Query("SELECT * FROM download_history WHERE url = :url AND mediaIndex = :mediaIndex LIMIT 1")
    suspend fun getByUrlAndMediaIndex(url: String, mediaIndex: Int): DownloadHistoryEntity?

    @Query("SELECT * FROM download_history WHERE url = :url AND mediaIndex IS NULL LIMIT 1")
    suspend fun getSingleByUrl(url: String): DownloadHistoryEntity?
    @Query("SELECT DISTINCT uploader FROM download_history WHERE uploader IS NOT NULL AND uploader != '' ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getRecentUploaders(limit: Int): List<String>
    @Query("SELECT * FROM download_history WHERE id LIKE :tweetIdPrefix || '\\_%' OR url LIKE '%' || :tweetId LIMIT 1")
    suspend fun getByTweetId(tweetId: String, tweetIdPrefix: String): DownloadHistoryEntity?

    @Query("SELECT filePath FROM download_history")
    suspend fun getAllFilePaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadHistoryEntity)

    @Delete
    suspend fun delete(entity: DownloadHistoryEntity)

    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_history")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM download_history")
    fun getCount(): Flow<Int>
}

