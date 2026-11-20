package com.ed.edqiu.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** 删除前保存的完整链接快照，可从回收站恢复。 */
@Entity(
    tableName = "deleted_link_history",
    indices = [
        Index(value = ["tweet_id"]),
        Index(value = ["deleted_at"])
    ]
)
data class DeletedLinkHistory(
    @PrimaryKey
    @ColumnInfo(name = "archive_id")
    val archiveId: String,

    @ColumnInfo(name = "tweet_id")
    val tweetId: String,

    @ColumnInfo(name = "raw_url")
    val rawUrl: String,

    @ColumnInfo(name = "author_id")
    val authorId: String? = null,

    @ColumnInfo(name = "author_name")
    val authorName: String? = null,

    val caption: String? = null,

    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,

    @ColumnInfo(name = "author_bio")
    val authorBio: String? = null,

    /** 推文发布时间（epoch ms，v8 起回收站保留，恢复后媒体库/作者页排序不退化）。 */
    @ColumnInfo(name = "published_at")
    val publishedAt: Long? = null,

    @ColumnInfo(name = "saved_at")
    val savedAt: Long,

    val status: LinkStatus,

    @ColumnInfo(name = "file_path")
    val filePath: String? = null,

    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long? = null,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long? = null,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long,

    @ColumnInfo(name = "deletion_reason")
    val deletionReason: String,

    /**
     * 来源线（P1 审计修复 2026-11-21）：user/subscription——旧快照无此列，订阅作品
     * 删除→恢复后错落用户收件箱（双线隔离被打破：进错页、重试风暴走错线、订阅页消失）。
     */
    @ColumnInfo(name = "origin", defaultValue = SavedLink.ORIGIN_USER)
    val origin: String = SavedLink.ORIGIN_USER
) {
    fun toRestoredLink(): SavedLink = SavedLink(
        tweetId = tweetId,
        rawUrl = rawUrl,
        authorId = authorId,
        authorName = authorName,
        caption = caption,
        thumbnailUrl = thumbnailUrl,
        avatarUrl = avatarUrl,
        authorBio = authorBio,
        publishedAt = publishedAt,
        savedAt = savedAt,
        status = status,
        filePath = filePath,
        downloadedAt = downloadedAt,
        attemptCount = attemptCount,
        lastAttemptAt = lastAttemptAt,
        lastError = lastError,
        nextRetryAt = nextRetryAt,
        origin = origin
    )

    companion object {
        fun from(
            link: SavedLink,
            deletedAt: Long,
            deletionReason: String,
            archiveId: String = UUID.randomUUID().toString()
        ): DeletedLinkHistory = DeletedLinkHistory(
            archiveId = archiveId,
            tweetId = link.tweetId,
            rawUrl = link.rawUrl,
            authorId = link.authorId,
            authorName = link.authorName,
            caption = link.caption,
            thumbnailUrl = link.thumbnailUrl,
            avatarUrl = link.avatarUrl,
            authorBio = link.authorBio,
            publishedAt = link.publishedAt,
            savedAt = link.savedAt,
            status = link.status,
            filePath = link.filePath,
            downloadedAt = link.downloadedAt,
            attemptCount = link.attemptCount,
            lastAttemptAt = link.lastAttemptAt,
            lastError = link.lastError,
            nextRetryAt = link.nextRetryAt,
            deletedAt = deletedAt,
            deletionReason = deletionReason,
            origin = link.origin
        )
    }
}
