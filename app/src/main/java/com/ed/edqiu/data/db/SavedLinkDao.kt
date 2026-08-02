package com.ed.edqiu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedLinkDao {

    /** 全量列表（按捕获时间倒序），以 Flow 暴露以支持实时刷新。 */
    @Query("SELECT * FROM saved_links ORDER BY saved_at DESC")
    fun observeAll(): Flow<List<SavedLink>>

    @Query("SELECT * FROM saved_links WHERE tweetId = :tweetId LIMIT 1")
    suspend fun getByTweetId(tweetId: String): SavedLink?

    @Query("SELECT * FROM saved_links WHERE tweetId IN (:tweetIds)")
    suspend fun getByTweetIds(tweetIds: List<String>): List<SavedLink>

    @Query("SELECT * FROM saved_links ORDER BY saved_at DESC")
    suspend fun getAllSnapshot(): List<SavedLink>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_links WHERE tweetId = :tweetId)")
    suspend fun exists(tweetId: String): Boolean

    @Query(
        """
        SELECT * FROM saved_links
        WHERE status = 'FAILED'
          AND next_retry_at IS NOT NULL
          AND next_retry_at <= :now
          AND attempt_count < :maxAttempts
        ORDER BY next_retry_at ASC
        """
    )
    suspend fun getRetryableFailures(now: Long, maxAttempts: Int): List<SavedLink>

    @Query(
        """
        SELECT * FROM saved_links
        WHERE author_id IS NULL
           OR caption IS NULL
           OR avatar_url IS NULL
           OR thumbnail_url IS NULL
           OR thumbnail_url = ''
        ORDER BY saved_at DESC
        LIMIT :limit
        """
    )
    suspend fun getMissingMetadata(limit: Int): List<SavedLink>

    /** 主键冲突时返回 -1，由调用方原子判断是否为重复捕获。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: SavedLink): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(links: List<SavedLink>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(links: List<SavedLink>)

    /** 刷新：用监控目录的扫描结果更新状态与命中文件。 */
    @Query(
        """
        UPDATE saved_links
        SET status = :status,
            file_path = :filePath,
            downloaded_at = :downloadedAt,
            attempt_count = CASE WHEN :status = 'DOWNLOADED' THEN 0 ELSE attempt_count END,
            last_error = NULL,
            next_retry_at = NULL
        WHERE tweetId = :tweetId
        """
    )
    suspend fun applyScanResult(
        tweetId: String,
        status: LinkStatus,
        filePath: String?,
        downloadedAt: Long?
    )

    /** 捕获阶段补全作者/文案/缩略图（仅当本地为空时由仓库层判断）。 */
    @Query(
        """
        UPDATE saved_links
        SET author_id = COALESCE(:authorId, author_id),
            author_name = COALESCE(:authorName, author_name),
            caption = COALESCE(:caption, caption),
            thumbnail_url = COALESCE(:thumbnailUrl, thumbnail_url),
            avatar_url = COALESCE(:avatarUrl, avatar_url)
        WHERE tweetId = :tweetId
        """
    )
    suspend fun applyMeta(
        tweetId: String,
        authorId: String?,
        authorName: String?,
        caption: String?,
        thumbnailUrl: String?,
        avatarUrl: String?
    )

    @Query(
        """
        UPDATE saved_links
        SET status = 'PENDING',
            attempt_count = attempt_count + 1,
            last_attempt_at = :attemptedAt,
            last_error = NULL,
            next_retry_at = NULL
        WHERE tweetId = :tweetId
        """
    )
    suspend fun markDownloadDispatched(tweetId: String, attemptedAt: Long)

    @Query(
        """
        UPDATE saved_links
        SET status = 'FAILED',
            attempt_count = :attemptCount,
            last_attempt_at = :attemptedAt,
            last_error = :error,
            next_retry_at = :nextRetryAt
        WHERE tweetId = :tweetId
        """
    )
    suspend fun markDownloadFailed(
        tweetId: String,
        attemptCount: Int,
        attemptedAt: Long,
        error: String,
        nextRetryAt: Long?
    )

    @Query(
        """
        UPDATE saved_links
        SET status = 'PENDING',
            attempt_count = 0,
            last_attempt_at = NULL,
            last_error = NULL,
            next_retry_at = NULL
        WHERE tweetId IN (:tweetIds)
        """
    )
    suspend fun resetFailures(tweetIds: List<String>)

    @Query("DELETE FROM saved_links WHERE tweetId = :tweetId")
    suspend fun delete(tweetId: String)

    @Query("DELETE FROM saved_links WHERE tweetId IN (:tweetIds)")
    suspend fun deleteMany(tweetIds: List<String>)

    @Query("DELETE FROM saved_links")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM saved_links WHERE status = :status")
    fun countByStatus(status: LinkStatus): Flow<Int>
}
