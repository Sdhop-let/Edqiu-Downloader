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
            avatar_url = COALESCE(:avatarUrl, avatar_url),
            author_bio = COALESCE(:authorBio, author_bio)
        WHERE tweetId = :tweetId
        """
    )
    suspend fun applyMeta(
        tweetId: String,
        authorId: String?,
        authorName: String?,
        caption: String?,
        thumbnailUrl: String?,
        avatarUrl: String?,
        authorBio: String?
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

    /**
     * 推文已不存在（被删除/私密/未公开）：标记为终态 DELETED。
     * 不累计 attempt_count、不设 next_retry_at —— 永久失败，不再自动重试；
     * last_error 存面向用户的文案「推文不存在」。
     */
    @Query(
        """
        UPDATE saved_links
        SET status = 'DELETED',
            last_attempt_at = :attemptedAt,
            last_error = '推文不存在',
            next_retry_at = NULL
        WHERE tweetId = :tweetId
        """
    )
    suspend fun markTweetGone(tweetId: String, attemptedAt: Long)

    /**
     * 批量重置匹配 last_error 模式的历史失败记录。
     *
     * 用于清理**已修复 bug 期间**残留的错误数据：旧版本下载器曾通过
     * startActivity(自身 MainActivity) 触发下载，旧逻辑会抛
     * `Unable to find explicit activity class {com.ed.edqiu/com.ed.edqiu.MainActivity}`
     * 并写入 last_error；该 bug 已修复，但错误记录会持续显示在详情页。
     * 启动时调用一次即可把这些记录重置回 PENDING，让用户重新下载。
     *
     * @return 受影响的行数（用于日志确认）
     */
    @Query(
        """
        UPDATE saved_links
        SET status = 'PENDING',
            attempt_count = 0,
            last_attempt_at = NULL,
            last_error = NULL,
            next_retry_at = NULL
        WHERE last_error LIKE :pattern
        """
    )
    suspend fun resetFailuresByErrorPattern(pattern: String): Int

    @Query("DELETE FROM saved_links WHERE tweetId = :tweetId")
    suspend fun delete(tweetId: String)

    @Query("DELETE FROM saved_links WHERE tweetId IN (:tweetIds)")
    suspend fun deleteMany(tweetIds: List<String>)

    @Query("DELETE FROM saved_links")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM saved_links WHERE status = :status")
    fun countByStatus(status: LinkStatus): Flow<Int>

    /** 按状态取 tweetId 列表（自动预下载攒批用）。 */
    @Query("SELECT tweetId FROM saved_links WHERE status = :status ORDER BY saved_at DESC")
    suspend fun getTweetIdsByStatus(status: LinkStatus): List<String>
}
