package com.ed.edqiu.backup.engine

import com.ed.edqiu.backup.data.BackupTaskStore
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.model.BackupException
import com.ed.edqiu.backup.model.BackupSummary
import com.ed.edqiu.backup.model.BackupTarget
import com.ed.edqiu.backup.model.BackupTask
import com.ed.edqiu.backup.model.BackupTaskStatus
import com.ed.edqiu.backup.model.UploadReceipt
import com.ed.edqiu.backup.provider.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 备份队列对外状态（UI 只读 [StateFlow]）。 */
data class BackupUiState(
    val tasks: List<BackupTask> = emptyList(),
    val running: Boolean = false,
    val lastSummary: BackupSummary? = null,
)

/**
 * 备份任务引擎：单队列串行（[Mutex]）+ 状态机 + 指数退避重试。
 *
 * - 状态机：`PENDING → UPLOADING → DONE | FAILED`；失败重试 `FAILED → PENDING(attempt+1)`；
 * - 重试策略：指数退避 30s / 2m / 10m，单任务最多 [BackupCapabilities.maxRetry] 次（默认 5），超限置 FAILED；
 * - 进度：`0..1`，经 [state] 实时暴露（内存更新）；任务状态转换时落盘（供重启恢复）；
 * - 与目标无关：目标从 [ProviderRegistry] 按 id 取出，执行逻辑对所有 provider 一致。
 *
 * @param credentialStore 凭证存储（T03/T04 百度/阿里 token 管理使用；WebDAV 族由目标自行持有）
 */
class BackupEngine(
    private val taskStore: BackupTaskStore,
    private val registry: ProviderRegistry,
    private val credentialStore: CredentialStore,
) {

    private val queueMutex = Mutex()
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    companion object {
        /** 指数退避（毫秒）：第 1 次 30s、第 2 次 2m、之后 10m。 */
        private const val BACKOFF_30S = 30_000L
        private const val BACKOFF_2M = 120_000L
        private const val BACKOFF_10M = 600_000L
    }

    /**
     * 入队一批文件（幂等：taskId = sha256(targetId + "|" + remotePath)，同路径覆盖为新的 PENDING 任务）。
     *
     * @return 入队任务的 taskId 列表
     */
    suspend fun enqueue(targetId: String, files: List<File>): List<String> = withContext(Dispatchers.IO) {
        registry.get(targetId)
            ?: throw BackupException("未找到备份目标：$targetId")
        val now = System.currentTimeMillis()
        val ids = mutableListOf<String>()
        files.forEach { file ->
            if (!file.exists() || !file.isFile) return@forEach
            val remotePath = file.name
            val taskId = BackupTask.computeId(targetId, remotePath)
            val task = BackupTask(
                taskId = taskId,
                targetId = targetId,
                localPath = file.absolutePath,
                remotePath = remotePath,
                size = file.length(),
                createdAt = now,
                updatedAt = now,
            )
            taskStore.upsert(task)
            ids += taskId
        }
        refreshState()
        ids
    }

    /**
     * 执行队列：串行处理全部 PENDING 任务。
     *
     * 同一时刻只有一个 [runQueue] 在执行（[Mutex] 互斥）；失败按指数退避自动重试。
     *
     * @param onTaskUpdated 每个任务状态转换/完成时回调（非挂起，用于通知等副作用）
     */
    suspend fun runQueue(onTaskUpdated: ((BackupTask) -> Unit)? = null): Result<BackupSummary> =
        queueMutex.withLock {
            _state.update { it.copy(running = true) }
            try {
                val summary = executeQueue(onTaskUpdated)
                _state.update { it.copy(running = false, lastSummary = summary) }
                Result.success(summary)
            } catch (error: CancellationException) {
                _state.update { it.copy(running = false) }
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(running = false) }
                Result.failure(error)
            }
        }

    /** 取消任务（仅 PENDING / UPLOADING 可取消）。 */
    suspend fun cancel(taskId: String): Unit = withContext(Dispatchers.IO) {
        val task = taskStore.load().find { it.taskId == taskId } ?: return@withContext
        if (task.isActive) {
            taskStore.upsert(task.cancel())
            refreshState()
        }
    }

    /** 手动重试某目标的全部 FAILED 任务（重置 attempt 后回到 PENDING）。 */
    suspend fun retryFailed(targetId: String): Unit = withContext(Dispatchers.IO) {
        val tasks = taskStore.load()
            .filter { it.targetId == targetId && it.status == BackupTaskStatus.FAILED }
        tasks.forEach { task ->
            taskStore.upsert(
                task.copy(
                    status = BackupTaskStatus.PENDING,
                    attempt = 0,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
        refreshState()
    }

    private suspend fun executeQueue(onTaskUpdated: ((BackupTask) -> Unit)?): BackupSummary {
        var succeeded = 0
        var failed = 0
        var skipped = 0
        var totalBytes = 0L
        val startedAt = System.currentTimeMillis()

        val pending = taskStore.load().filter { it.status == BackupTaskStatus.PENDING }
        for (task in pending) {
            val target = registry.get(task.targetId)
            if (target == null) {
                failed++
                val failedTask = task.markFailed("未找到备份目标：${task.targetId}")
                taskStore.upsert(failedTask)
                onTaskUpdated?.invoke(failedTask)
                continue
            }
            when (val outcome = runSingleTaskWithRetry(task, target, onTaskUpdated)) {
                is TaskOutcome.Uploaded -> {
                    succeeded++
                    totalBytes += task.size
                }
                is TaskOutcome.Skipped -> skipped++
                is TaskOutcome.Failed -> failed++
            }
        }
        refreshState()
        return BackupSummary(
            succeeded = succeeded,
            failed = failed,
            skipped = skipped,
            totalBytes = totalBytes,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
        )
    }

    /** 单个任务执行 + 指数退避重试，直到 DONE / 跳过 / 重试耗尽 FAILED。 */
    private suspend fun runSingleTaskWithRetry(
        task: BackupTask,
        target: BackupTarget,
        onTaskUpdated: ((BackupTask) -> Unit)?,
    ): TaskOutcome {
        var current = task
        while (true) {
            val uploading = current.markUploading()
            taskStore.upsert(uploading)
            onTaskUpdated?.invoke(uploading)

            when (val result = runSingleAttempt(uploading, target, onTaskUpdated)) {
                is TaskOutcome.Uploaded, is TaskOutcome.Skipped -> {
                    val done = uploading.markDone()
                    taskStore.upsert(done)
                    onTaskUpdated?.invoke(done)
                    return result
                }
                is TaskOutcome.Failed -> {
                    val nextAttempt = current.attempt + 1
                    if (nextAttempt >= target.capabilities.maxRetry) {
                        val failedTask = uploading.markFailed(result.message)
                        taskStore.upsert(failedTask)
                        onTaskUpdated?.invoke(failedTask)
                        return result
                    } else {
                        val retried = uploading.markFailed(result.message).retry()
                        taskStore.upsert(retried)
                        onTaskUpdated?.invoke(retried)
                        delay(backoffDelayFor(nextAttempt))
                        current = retried
                    }
                }
            }
        }
    }

    /** 单次尝试：prepareRemote → exists 跳过 → uploadFile（进度实时刷新内存状态）。 */
    private suspend fun runSingleAttempt(
        task: BackupTask,
        target: BackupTarget,
        onTaskUpdated: ((BackupTask) -> Unit)?,
    ): TaskOutcome {
        return try {
            target.prepareRemote().getOrElse { error ->
                throw BackupException("准备远程目录失败：${error.message ?: "未知错误"}", error)
            }
            val exists = target.exists(task.remotePath).getOrElse { error ->
                throw BackupException("检查远程文件失败：${error.message ?: "未知错误"}", error)
            }
            if (exists) {
                TaskOutcome.Skipped(task.remotePath)
            } else {
                val local = File(task.localPath)
                if (!local.exists() || !local.isFile) {
                    throw BackupException("本地文件不存在：${task.localPath}")
                }
                val receipt = target.uploadFile(local, task.remotePath) { p ->
                    val bytes = (p * task.size).toLong().coerceIn(0L, task.size)
                    val updated = task.withProgress(bytes)
                    refreshTaskInState(updated)
                    onTaskUpdated?.invoke(updated)
                }.getOrElse { error ->
                    throw BackupException(error.message ?: "上传失败", error)
                }
                TaskOutcome.Uploaded(receipt)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            TaskOutcome.Failed(error.message ?: "上传失败")
        }
    }

    /** 进度回调（非挂起，只能做内存状态更新；磁盘在状态转换时落盘）。 */
    private fun refreshTaskInState(updated: BackupTask) {
        _state.update { state ->
            state.copy(tasks = state.tasks.map { if (it.taskId == updated.taskId) updated else it })
        }
    }

    private suspend fun refreshState() {
        val tasks = taskStore.load()
        _state.update { it.copy(tasks = tasks) }
    }

    private fun backoffDelayFor(attempt: Int): Long = when (attempt) {
        1 -> BACKOFF_30S
        2 -> BACKOFF_2M
        else -> BACKOFF_10M
    }

    /** 单任务最终结果。 */
    private sealed interface TaskOutcome {
        data class Uploaded(val receipt: UploadReceipt) : TaskOutcome
        data class Skipped(val remotePath: String) : TaskOutcome
        data class Failed(val message: String) : TaskOutcome
    }
}
