package com.ed.edqiu.data.repository

import androidx.room.withTransaction
import com.ed.edqiu.data.db.DeletedLinkHistoryDao
import com.ed.edqiu.data.db.SavedLinkDao
import com.ed.edqiu.data.db.EdqiuDatabase
import com.ed.edqiu.data.model.DeletedLinkHistory
import kotlinx.coroutines.flow.Flow

class LinkHistoryRepository(
    private val database: EdqiuDatabase,
    private val savedLinkDao: SavedLinkDao,
    private val historyDao: DeletedLinkHistoryDao
) {
    data class ArchiveResult(val archived: Int, val archiveIds: List<String>)
    data class RestoreResult(val restored: Int, val skipped: Int)

    fun observeAll(): Flow<List<DeletedLinkHistory>> = historyDao.observeAll()

    fun count(): Flow<Int> = historyDao.count()

    suspend fun archiveAndDelete(
        tweetIds: Collection<String>,
        reason: String
    ): ArchiveResult {
        val ids = tweetIds.distinct()
        if (ids.isEmpty()) return ArchiveResult(0, emptyList())

        return database.withTransaction {
            val links = savedLinkDao.getByTweetIds(ids)
            if (links.isEmpty()) return@withTransaction ArchiveResult(0, emptyList())
            val deletedAt = System.currentTimeMillis()
            val entries = links.map { link ->
                DeletedLinkHistory.from(
                    link = link,
                    deletedAt = deletedAt,
                    deletionReason = reason
                )
            }
            historyDao.insertAll(entries)
            savedLinkDao.deleteMany(links.map { it.tweetId })
            ArchiveResult(links.size, entries.map { it.archiveId })
        }
    }

    suspend fun restore(archiveIds: Collection<String>): RestoreResult {
        val ids = archiveIds.distinct()
        if (ids.isEmpty()) return RestoreResult(restored = 0, skipped = 0)

        return database.withTransaction {
            val entries = historyDao.getByArchiveIds(ids)
            val insertResults = savedLinkDao.insertAllIgnoringConflicts(
                entries.map(DeletedLinkHistory::toRestoredLink)
            )
            val restoredIds = entries.zip(insertResults)
                .filter { (_, rowId) -> rowId != -1L }
                .map { (entry, _) -> entry.archiveId }
            if (restoredIds.isNotEmpty()) historyDao.deleteByArchiveIds(restoredIds)
            RestoreResult(
                restored = restoredIds.size,
                skipped = entries.size - restoredIds.size
            )
        }
    }

    suspend fun permanentlyDelete(archiveIds: Collection<String>): Int {
        val ids = archiveIds.distinct()
        if (ids.isEmpty()) return 0
        return database.withTransaction {
            val existing = historyDao.getByArchiveIds(ids)
            if (existing.isNotEmpty()) historyDao.deleteByArchiveIds(existing.map { it.archiveId })
            existing.size
        }
    }
}
