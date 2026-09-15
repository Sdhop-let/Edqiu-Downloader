package com.ed.edqiu.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadHistoryDao {

    // 2026-09-15 排序准则：有发布时间的按「推文发布时间」倒序在前（同作者连续发布的
    // 帖子自然相邻，修复按下载完成时间排序导致的「同一天同作者被拆散」）；
    // 无发布时间（旧记录/yt-dlp 回退/推文不存在）垫底，按完成时间倒序。
    @Query(
        """
        SELECT * FROM download_history
        ORDER BY (publishedAt IS NULL) ASC,
                 COALESCE(publishedAt, completedAt) DESC
        """
    )
    fun getAllHistory(): Flow<List<DownloadHistoryEntity>>

    @Query("SELECT filePath FROM download_history WHERE publishedAt IS NULL")
    suspend fun getFilePathsMissingPublishedAt(): List<String>

    /** 发布时间补拉候选：无发布时间且能从 url 提取 tweetId 的记录（每轮限量，避免首轮网络风暴）。 */
    @Query(
        """
        SELECT * FROM download_history
        WHERE publishedAt IS NULL AND url LIKE '%/status/%'
        ORDER BY completedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getMissingPublishedWithUrl(limit: Int): List<DownloadHistoryEntity>

    @Query(
        "UPDATE download_history SET publishedAt = :publishedAt " +
            "WHERE filePath = :filePath AND publishedAt IS NULL"
    )
    suspend fun backfillPublishedAt(filePath: String, publishedAt: Long)

    /** 画质升级：本地文件被更高画质版本替换（路径/大小/画质标注同步更新）。 */
    @Query(
        """
        UPDATE download_history
        SET filePath = :newPath, fileSize = :fileSize, quality = :quality
        WHERE filePath = :oldPath
        """
    )
    suspend fun updateMediaFile(oldPath: String, newPath: String, fileSize: Long, quality: String)

    /** 画质升级候选（视频+图片，url 可提取 tweetId 的记录）。 */
    @Query(
        """
        SELECT * FROM download_history
        WHERE url LIKE '%/status/%'
        ORDER BY completedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getQualityUpgradeCandidates(limit: Int): List<DownloadHistoryEntity>

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

    /** pHash 补算候选（2026-09-15 批次3 重复检测）：phash 未计算的记录，最新优先。 */
    @Query("SELECT * FROM download_history WHERE phash IS NULL ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getMissingPhash(limit: Int): List<DownloadHistoryEntity>

    @Query("UPDATE download_history SET phash = :phash WHERE filePath = :filePath")
    suspend fun setPhash(filePath: String, phash: Long)
}

