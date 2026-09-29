package com.ed.edqiu.backup.data

import androidx.room.Entity
import androidx.room.Index

/**
 * 备份账本（本地增量同步主数据源）。
 *
 * 记录每个 provider 每个远程路径的备份结果（size / mtime / 云端 file_id / 状态），
 * 使备份引擎能「跳过未变化文件」而非每次全量重扫 + 重复 exists 网络调用。
 *
 * 主键 `(providerId, remotePath)`：同一目标同一远程路径只保留一条最新记录。
 *
 * @property localHash   轻量指纹（预留，P2 大文件优化用），当前可为 null
 * @property cloudFileId 云端文件 id（provider 返回时记录，用于精确 exists / 续传兜底）
 * @property state       备份状态，见 [STATE_DONE] 等常量
 */
@Entity(
    tableName = "backup_ledger",
    primaryKeys = ["providerId", "remotePath"],
    indices = [Index("providerId"), Index("state")],
)
data class BackupLedgerEntity(
    val providerId: String,
    val remotePath: String,
    val localPath: String,
    val size: Long,
    val mtime: Long,
    val localHash: String? = null,
    val cloudFileId: String? = null,
    val state: String = STATE_DONE,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATE_DONE = "DONE"
        const val STATE_PENDING = "PENDING"
        const val STATE_FAILED = "FAILED"
    }
}
