package com.ed.edqiu.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.ed.edqiu.BuildConfig
import com.ed.edqiu.data.db.DeletedLinkHistoryDao
import com.ed.edqiu.data.db.SavedLinkDao
import com.ed.edqiu.data.db.EdqiuDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HistoryBackupRepository(
    private val context: Context,
    private val database: EdqiuDatabase,
    private val savedLinkDao: SavedLinkDao,
    private val historyDao: DeletedLinkHistoryDao,
    private val codec: BackupCodec = BackupCodec()
) {
    private val operationMutex = Mutex()

    suspend fun exportToUri(uri: Uri) = operationMutex.withLock {
        val text = createBackupText()
        withContext(Dispatchers.IO) {
            if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
                uri.toFile().apply {
                    parentFile?.mkdirs()
                    writeText(text, Charsets.UTF_8)
                }
            } else {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(text) }
                    ?: error("无法写入所选文件")
            }
        }
    }

    suspend fun readAndValidate(uri: Uri): ValidatedBackup = withContext(Dispatchers.IO) {
        val text = if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
            uri.toFile().readText(Charsets.UTF_8)
        } else {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("无法读取所选文件")
        }
        codec.decodeAndValidate(text)
    }

    suspend fun restore(
        backup: ValidatedBackup,
        mode: RestoreMode
    ): RestoreResult = operationMutex.withLock {
        if (mode == RestoreMode.REPLACE) savePreRestoreSnapshot()

        database.withTransaction {
            if (mode == RestoreMode.REPLACE) {
                savedLinkDao.clearAll()
                historyDao.clearAll()
            }

            val active = backup.envelope.payload.activeLinks
                .map(BackupLink::toNormalizedSavedLink)
            val history = backup.envelope.payload.deletedHistory
                .map(BackupHistoryEntry::toHistory)
            val activeResults = savedLinkDao.insertAllIgnoringConflicts(active)
            val historyResults = historyDao.insertAllIgnoringConflicts(history)
            RestoreResult(
                activeImported = activeResults.count { it != -1L },
                activeSkipped = activeResults.count { it == -1L },
                historyImported = historyResults.count { it != -1L },
                historySkipped = historyResults.count { it == -1L }
            )
        }
    }

    /** 数据库中是否存在需要备份的收件箱/回收站记录。 */
    suspend fun hasBackupContent(): Boolean {
        val payload = createBackupPayload()
        return payload.activeLinks.isNotEmpty() || payload.deletedHistory.isNotEmpty()
    }

    /** 返回备份目录中最新一份备份文件；目录不存在或没有备份时返回 null。 */
    suspend fun latestBackupUri(treeUri: Uri): Uri? = withContext(Dispatchers.IO) {
        listBackupFiles(treeUri).firstOrNull()?.second
    }

    /** 删除备份目录中超过 maxAgeMillis 的过期备份，返回删除数量。 */
    suspend fun pruneBackupsOlderThan(
        treeUri: Uri,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS
    ): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        var deleted = 0
        if (treeUri.scheme == "file" || treeUri.scheme.isNullOrBlank()) {
            treeUri.toFile().listFiles().orEmpty()
                .filter { it.isFile && BACKUP_FILE_PATTERN.matches(it.name) && it.lastModified() < cutoff }
                .forEach { if (it.delete()) deleted++ }
        } else {
            DocumentFile.fromTreeUri(context, treeUri)
                ?.listFiles()
                ?.filter {
                    it.isFile && BACKUP_FILE_PATTERN.matches(it.name.orEmpty()) &&
                        it.lastModified() < cutoff
                }
                ?.forEach { if (it.delete()) deleted++ }
        }
        deleted
    }

    suspend fun backupToDirectory(
        treeUri: Uri,
        retentionCount: Int = DEFAULT_RETENTION
    ): Uri? = operationMutex.withLock {
        val payload = createBackupPayload()
        // 没有任何收件箱/回收站记录时不生成备份文件，避免调试/重装时堆积空备份
        if (payload.activeLinks.isEmpty() && payload.deletedHistory.isEmpty()) return@withLock null
        val text = codec.encode(
            payload = payload,
            appVersion = BuildConfig.VERSION_NAME,
            databaseVersion = DATABASE_VERSION,
            createdAt = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) {
            val existing = listBackupFiles(treeUri)
            // 内容与最新一份备份完全一致时跳过写入，避免重复备份文件
            val latest = existing.firstOrNull()
            if (latest != null && readBackupText(latest.second) == text) {
                DocumentFile.fromTreeUri(context, treeUri)?.let { pruneOldBackups(it, retentionCount) }
                return@withContext latest.second
            }

            if (treeUri.scheme == "file" || treeUri.scheme.isNullOrBlank()) {
                return@withContext backupToFileDirectory(treeUri.toFile(), text, retentionCount)
            }

            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("无法访问自动备份目录")
            if (!tree.isDirectory || !tree.canWrite()) error("自动备份目录不可写")

            val name = backupFileName(System.currentTimeMillis())
            val target = tree.createFile(MIME_TYPE, name)
                ?: error("无法创建自动备份文件")
            runCatching {
                context.contentResolver.openOutputStream(target.uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(text) }
                    ?: error("无法写入自动备份文件")
            }.onFailure {
                target.delete()
                throw it
            }
            pruneOldBackups(tree, retentionCount)
            target.uri
        }
    }

    private fun backupToFileDirectory(
        directory: File,
        text: String,
        retentionCount: Int
    ): Uri {
        check(directory.exists() || directory.mkdirs()) { "无法创建自动备份目录" }
        check(directory.isDirectory && directory.canWrite()) { "自动备份目录不可写" }
        val target = File(directory, backupFileName(System.currentTimeMillis()))
        target.writeText(text, Charsets.UTF_8)
        val cutoff = System.currentTimeMillis() - DEFAULT_MAX_AGE_MILLIS
        val kept = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && BACKUP_FILE_PATTERN.matches(it.name) }
            .filter {
                // 超过 7 天的备份直接删除，其余按份数保留
                if (it.lastModified() < cutoff) {
                    it.delete()
                    false
                } else {
                    true
                }
            }
            .sortedByDescending(File::lastModified)
            .drop(retentionCount.coerceAtLeast(1))
        kept.forEach(File::delete)
        return Uri.fromFile(target)
    }

    suspend fun createBackupText(): String {
        val payload = createBackupPayload()
        return codec.encode(
            payload = payload,
            appVersion = BuildConfig.VERSION_NAME,
            databaseVersion = DATABASE_VERSION,
            createdAt = System.currentTimeMillis()
        )
    }

    private suspend fun createBackupPayload(): BackupPayload = database.withTransaction {
        BackupPayload(
            activeLinks = savedLinkDao.getAllSnapshot().map(BackupLink::from),
            deletedHistory = historyDao.getAllSnapshot().map(BackupHistoryEntry::from)
        )
    }

    /** 备份文件列表（新→旧），元素为 (最后修改时间, 文件 Uri)。 */
    private fun listBackupFiles(treeUri: Uri): List<Pair<Long, Uri>> {
        val files = if (treeUri.scheme == "file" || treeUri.scheme.isNullOrBlank()) {
            treeUri.toFile().listFiles().orEmpty()
                .filter { it.isFile && BACKUP_FILE_PATTERN.matches(it.name) }
                .map { it.lastModified() to Uri.fromFile(it) }
        } else {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: return emptyList()
            tree.listFiles()
                .filter { it.isFile && BACKUP_FILE_PATTERN.matches(it.name.orEmpty()) }
                .map { it.lastModified() to it.uri }
        }
        return files.sortedWith(compareByDescending<Pair<Long, Uri>> { it.first }.thenByDescending { it.second })
    }

    private fun readBackupText(uri: Uri): String = if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
        uri.toFile().readText(Charsets.UTF_8)
    } else {
        context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("无法读取备份文件")
    }

    private suspend fun savePreRestoreSnapshot() {
        val text = createBackupText()
        withContext(Dispatchers.IO) {
            val directory = File(context.noBackupFilesDir, PRE_RESTORE_DIRECTORY)
            check(directory.exists() || directory.mkdirs()) { "无法创建恢复安全快照目录" }
            val target = File(directory, "pre-restore-${timestamp(System.currentTimeMillis())}.json")
            target.writeText(text, Charsets.UTF_8)
            directory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.startsWith("pre-restore-") }
                .sortedByDescending(File::lastModified)
                .drop(PRE_RESTORE_RETENTION)
                .forEach(File::delete)
        }
    }

    private fun pruneOldBackups(tree: DocumentFile, retentionCount: Int) {
        val cutoff = System.currentTimeMillis() - DEFAULT_MAX_AGE_MILLIS
        tree.listFiles()
            .filter { file -> file.isFile && BACKUP_FILE_PATTERN.matches(file.name.orEmpty()) }
            // 超过 7 天的过期备份直接删除
            .filter { it.lastModified() >= cutoff || !it.delete() }
            .sortedWith(
                compareByDescending<DocumentFile> { it.lastModified() }
                    .thenByDescending { it.name }
            )
            .drop(retentionCount.coerceAtLeast(1))
            .forEach(DocumentFile::delete)
    }

    private fun Uri.toFile(): File {
        val rawPath = when {
            scheme == "file" -> requireNotNull(path)
            scheme.isNullOrBlank() -> toString()
            else -> error("不支持的文件路径：$this")
        }
        return File(rawPath)
    }

    companion object {
        const val MIME_TYPE = "application/json"
        const val DEFAULT_RETENTION = 7
        const val DEFAULT_MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
        private const val DATABASE_VERSION = 3
        private const val PRE_RESTORE_DIRECTORY = "pre_restore_backups"
        private const val PRE_RESTORE_RETENTION = 3
        private val BACKUP_FILE_PATTERN = Regex("(?i)(?:xinvox|esqp|edqiu)-backup-\\d{8}-\\d{6}\\.json")

        fun backupFileName(now: Long): String = "Edqiu-backup-${timestamp(now)}.json"

        private fun timestamp(now: Long): String = SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            Locale.US
        ).format(Date(now))
    }
}
