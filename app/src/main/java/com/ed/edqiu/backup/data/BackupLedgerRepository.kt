package com.ed.edqiu.backup.data

import java.io.File

/**
 * 备份账本业务封装（增量同步语义）。
 *
 * - [recordSuccess]：上传成功 / 远端已存在跳过后写入一条 DONE 记录（含 size/mtime/云端 id）。
 * - [isUnchanged]：以 size + mtime 做轻量比对，判断本地文件相对账本是否未变化。
 * - [doneRemotePaths]：拉取某 provider 已备份的远程路径集合（用于增量跳过）。
 *
 * 引擎与收集器只依赖本类，不直接接触 [BackupLedgerDao]。
 */
class BackupLedgerRepository(
    private val dao: BackupLedgerDao,
) {

    /** 记录一次成功备份（上传完成 或 远端已存在跳过）。 */
    suspend fun recordSuccess(
        providerId: String,
        remotePath: String,
        localPath: String,
        size: Long,
        mtime: Long,
        cloudFileId: String? = null,
    ) {
        dao.upsert(
            BackupLedgerEntity(
                providerId = providerId,
                remotePath = remotePath,
                localPath = localPath,
                size = size,
                mtime = mtime,
                cloudFileId = cloudFileId,
                state = BackupLedgerEntity.STATE_DONE,
            ),
        )
    }

    /** 某 provider 已完成备份的远程路径集合。 */
    suspend fun doneRemotePaths(providerId: String): Set<String> =
        dao.remotePathsByState(providerId, BackupLedgerEntity.STATE_DONE).toSet()

    /**
     * 本地文件是否相对账本「未变化」→ 可跳过备份。
     * 仅当账本存在 DONE 记录、且 size 与 mtime 均一致时返回 true。
     */
    suspend fun isUnchanged(providerId: String, remotePath: String, file: File): Boolean {
        val entry = dao.get(providerId, remotePath) ?: return false
        return entry.state == BackupLedgerEntity.STATE_DONE &&
            entry.size == file.length() &&
            entry.mtime == file.lastModified()
    }

    /** 清除某 provider 的全部账本记录（清除登录态时调用）。 */
    suspend fun clear(providerId: String) {
        dao.clear(providerId)
    }
}
