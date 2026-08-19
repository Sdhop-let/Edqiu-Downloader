package com.ed.edqiu.backup.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 备份账本 DAO。
 *
 * 读写均由 [BackupLedgerRepository] 编排，UI / 引擎不直接接触本接口。
 */
@Dao
interface BackupLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BackupLedgerEntity)

    @Query("SELECT * FROM backup_ledger WHERE providerId = :providerId AND remotePath = :remotePath LIMIT 1")
    suspend fun get(providerId: String, remotePath: String): BackupLedgerEntity?

    @Query("SELECT remotePath FROM backup_ledger WHERE providerId = :providerId AND state = :state")
    suspend fun remotePathsByState(providerId: String, state: String): List<String>

    @Query("SELECT * FROM backup_ledger WHERE providerId = :providerId")
    suspend fun all(providerId: String): List<BackupLedgerEntity>

    @Query("DELETE FROM backup_ledger WHERE providerId = :providerId AND remotePath = :remotePath")
    suspend fun delete(providerId: String, remotePath: String)

    @Query("DELETE FROM backup_ledger WHERE providerId = :providerId")
    suspend fun clear(providerId: String)
}
