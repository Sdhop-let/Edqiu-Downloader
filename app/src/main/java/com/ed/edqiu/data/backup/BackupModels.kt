package com.ed.edqiu.data.backup

import com.ed.edqiu.data.model.DeletedLinkHistory
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import kotlinx.serialization.Serializable

@Serializable
data class BackupEnvelope(
    val formatVersion: Int,
    val appVersion: String,
    val databaseVersion: Int,
    val createdAt: Long,
    val payload: BackupPayload,
    val checksumSha256: String
)

@Serializable
data class BackupPayload(
    val activeLinks: List<BackupLink>,
    val deletedHistory: List<BackupHistoryEntry>
)

@Serializable
data class BackupLink(
    val tweetId: String,
    val rawUrl: String,
    val authorId: String? = null,
    val authorName: String? = null,
    val caption: String? = null,
    val thumbnailUrl: String? = null,
    val avatarUrl: String? = null,
    val savedAt: Long,
    val status: String = LinkStatus.PENDING.name,
    val filePath: String? = null,
    val downloadedAt: Long? = null,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val nextRetryAt: Long? = null
) {
    fun toNormalizedSavedLink(): SavedLink {
        val restoredStatus = runCatching { LinkStatus.valueOf(status) }
            .getOrDefault(LinkStatus.PENDING)
        return SavedLink(
            tweetId = tweetId,
            rawUrl = rawUrl,
            authorId = authorId,
            authorName = authorName,
            caption = caption,
            thumbnailUrl = thumbnailUrl,
            avatarUrl = avatarUrl,
            savedAt = savedAt,
            status = restoredStatus,
            filePath = filePath,
            downloadedAt = downloadedAt,
            attemptCount = attemptCount.coerceAtLeast(0),
            lastAttemptAt = lastAttemptAt,
            lastError = lastError,
            nextRetryAt = nextRetryAt
        )
    }

    companion object {
        fun from(link: SavedLink): BackupLink = BackupLink(
            tweetId = link.tweetId,
            rawUrl = link.rawUrl,
            authorId = link.authorId,
            authorName = link.authorName,
            caption = link.caption,
            thumbnailUrl = link.thumbnailUrl,
            avatarUrl = link.avatarUrl,
            savedAt = link.savedAt,
            status = link.status.name,
            filePath = link.filePath,
            downloadedAt = link.downloadedAt,
            attemptCount = link.attemptCount,
            lastAttemptAt = link.lastAttemptAt,
            lastError = link.lastError,
            nextRetryAt = link.nextRetryAt
        )
    }
}

@Serializable
data class BackupHistoryEntry(
    val archiveId: String,
    val link: BackupLink,
    val deletedAt: Long,
    val deletionReason: String
) {
    fun toHistory(): DeletedLinkHistory {
        val status = runCatching { LinkStatus.valueOf(link.status) }
            .getOrDefault(LinkStatus.PENDING)
        return DeletedLinkHistory(
            archiveId = archiveId,
            tweetId = link.tweetId,
            rawUrl = link.rawUrl,
            authorId = link.authorId,
            authorName = link.authorName,
            caption = link.caption,
            thumbnailUrl = link.thumbnailUrl,
            avatarUrl = link.avatarUrl,
            savedAt = link.savedAt,
            status = status,
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

    companion object {
        fun from(entry: DeletedLinkHistory): BackupHistoryEntry = BackupHistoryEntry(
            archiveId = entry.archiveId,
            link = BackupLink(
                tweetId = entry.tweetId,
                rawUrl = entry.rawUrl,
                authorId = entry.authorId,
                authorName = entry.authorName,
                caption = entry.caption,
                thumbnailUrl = entry.thumbnailUrl,
                avatarUrl = entry.avatarUrl,
                savedAt = entry.savedAt,
                status = entry.status.name,
                filePath = entry.filePath,
                downloadedAt = entry.downloadedAt,
                attemptCount = entry.attemptCount,
                lastAttemptAt = entry.lastAttemptAt,
                lastError = entry.lastError,
                nextRetryAt = entry.nextRetryAt
            ),
            deletedAt = entry.deletedAt,
            deletionReason = entry.deletionReason
        )
    }
}

enum class RestoreMode { MERGE, REPLACE }

data class BackupPreview(
    val createdAt: Long,
    val appVersion: String,
    val activeCount: Int,
    val historyCount: Int
)

data class ValidatedBackup(
    val envelope: BackupEnvelope,
    val preview: BackupPreview
)

data class RestoreResult(
    val activeImported: Int,
    val activeSkipped: Int,
    val historyImported: Int,
    val historySkipped: Int
)
