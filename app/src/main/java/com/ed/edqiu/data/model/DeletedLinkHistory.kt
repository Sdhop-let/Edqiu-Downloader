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
    val deletionReason: String
) {
    fun toRestoredLink(): SavedLink = SavedLink(
        tweetId = tweetId,
        rawUrl = rawUrl,
        authorId = authorId,
        authorName = authorName,
        caption = caption,
        thumbnailUrl = thumbnailUrl,
        avatarUrl = avatarUrl,
        savedAt = savedAt,
        status = status,
        filePath = filePath,
        downloadedAt = downloadedAt,
        attemptCount = attemptCount,
        lastAttemptAt = lastAttemptAt,
        lastError = lastError,
        nextRetryAt = nextRetryAt
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
            savedAt = link.savedAt,
            status = link.status,
            filePath = link.filePath,
            downloadedAt = link.downloadedAt,
            attemptCount = link.attemptCount,
            lastAttemptAt = link.lastAttemptAt,
            lastError = link.lastError,
            nextRetryAt = link.nextRetryAt,
            deletedAt = deletedAt,
            deletionReason = deletionReason
        )
    }
}
