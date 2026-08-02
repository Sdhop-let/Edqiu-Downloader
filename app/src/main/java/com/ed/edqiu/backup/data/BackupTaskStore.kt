package com.ed.edqiu.backup.data

import android.content.Context
import com.ed.edqiu.backup.model.BackupTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 备份任务持久化接口。
 *
 * 任务队列是易失性中间数据（可重建），设计选 JSON 文件落盘（`filesDir/backup_tasks.json`），
 * 避免 Room schema 升级（架构文档 1.2 / 待明确事项 7）。
 */
interface BackupTaskStore {

    /** 从磁盘加载全部任务（同时刷新内存状态）。 */
    suspend fun load(): List<BackupTask>

    /** 写入或更新单个任务（按 taskId 幂等：已存在则覆盖，不存在则追加）。 */
    suspend fun upsert(task: BackupTask)

    /** 按 taskId 删除任务。 */
    suspend fun delete(taskId: String)

    /** 任务列表流（UI 只读）。 */
    fun observe(): Flow<List<BackupTask>>
}

/** 落盘文件结构。 */
@Serializable
private data class BackupTaskFile(
    val version: Int = 1,
    val tasks: List<BackupTask> = emptyList(),
    val updatedAt: Long = 0L,
)

/**
 * 基于 filesDir JSON 的任务存储实现。
 *
 * - 单文件 `filesDir/backup_tasks.json`（kotlinx-serialization 落盘）；
 * - [Mutex] 串行化所有读写，防止并发覆盖；
 * - [MutableStateFlow] 对外暴露最新任务列表；
 * - 解析失败/文件损坏时降级为空列表，不抛崩溃。
 */
class JsonBackupTaskStore(private val context: Context) : BackupTaskStore {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _tasks = MutableStateFlow<List<BackupTask>>(emptyList())
    override fun observe(): Flow<List<BackupTask>> = _tasks.asStateFlow()

    private var cache: List<BackupTask> = emptyList()
    private var loaded: Boolean = false

    override suspend fun load(): List<BackupTask> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            _tasks.value = cache
            cache
        }
    }

    override suspend fun upsert(task: BackupTask): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            val existingIndex = cache.indexOfFirst { it.taskId == task.taskId }
            cache = if (existingIndex >= 0) {
                cache.toMutableList().apply { set(existingIndex, task) }
            } else {
                cache + task
            }
            persistLocked()
        }
    }

    override suspend fun delete(taskId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            val updated = cache.filterNot { it.taskId == taskId }
            if (updated.size != cache.size) {
                cache = updated
                persistLocked()
            }
        }
    }

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val file = taskFile()
        cache = if (file.exists()) {
            runCatching {
                json.decodeFromString(BackupTaskFile.serializer(), file.readText()).tasks
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        loaded = true
    }

    private fun persistLocked() {
        val data = BackupTaskFile(
            version = 1,
            tasks = cache,
            updatedAt = System.currentTimeMillis(),
        )
        taskFile().writeText(json.encodeToString(BackupTaskFile.serializer(), data))
        _tasks.value = cache
    }

    private fun taskFile(): File = File(context.filesDir, "backup_tasks.json")
}
