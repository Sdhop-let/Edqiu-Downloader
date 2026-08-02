package com.ed.edqiu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ed.edqiu.data.model.DeletedLinkHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface DeletedLinkHistoryDao {

    @Query("SELECT * FROM deleted_link_history ORDER BY deleted_at DESC")
    fun observeAll(): Flow<List<DeletedLinkHistory>>

    @Query("SELECT COUNT(*) FROM deleted_link_history")
    fun count(): Flow<Int>

    @Query("SELECT * FROM deleted_link_history ORDER BY deleted_at DESC")
    suspend fun getAllSnapshot(): List<DeletedLinkHistory>

    @Query("SELECT * FROM deleted_link_history WHERE archive_id IN (:archiveIds)")
    suspend fun getByArchiveIds(archiveIds: List<String>): List<DeletedLinkHistory>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<DeletedLinkHistory>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(entries: List<DeletedLinkHistory>): List<Long>

    @Query("DELETE FROM deleted_link_history WHERE archive_id IN (:archiveIds)")
    suspend fun deleteByArchiveIds(archiveIds: List<String>)

    @Query("DELETE FROM deleted_link_history")
    suspend fun clearAll()
}
