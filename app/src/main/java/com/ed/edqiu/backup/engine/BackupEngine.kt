package com.ed.edqiu.backup.engine

import android.util.Log
import com.ed.edqiu.backup.data.BackupLedgerRepository
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * - 进度：`0..1`，经 [state] 实时暴露（内存更新）；任务状态转换与节流后的字节进度落盘
 *   （断点 / 重启恢复基础，详见 [runSingleAttempt] 中 `PROGRESS_PERSIST_BYTES`）；
 * - 与目标无关：目标从 [ProviderRegistry] 按 id 取出，执行逻辑对所有 provider 一致。
 *
 * ## 关键修复记录
 * - **P0-#1**：异常不再吞 `Throwable`，包装为 [TaskOutcome.Failed] 时附带 `cause`，
 *   把 `"${ex.javaClass.simpleName}: ${ex.message}"` 写入 `task.errorMessage`，并 `Log.w` 全堆栈。
 * - **P0-#2**：进度不再每 64KB 触发磁盘 IO；按 `PROGRESS_PERSIST_BYTES` 节流落盘。
 * - **P0-#3**：每个状态迁移（PENDING→UPLOADING/UPLOADING→DONE/UPLOADING→FAILED/retry/backoff）
 *   输出 `Log.i` 日志，troubleshooting 时能在 logcat 看到完整时间线。
 *
 * @param credentialStore 凭证存储（T03/T04 百度/阿里 token 管理使用；WebDAV 族由目标自行持有）
 * @param ledgerRepository 备份账本（上传成功/跳过时写 DONE 记录，供增量同步跳过未变化文件）；
 *         为 null 时禁用账本（测试场景），生产由 AppContainer 注入。
 * @param ioScope 节流落盘所用作用域，建议传 `AppContainer.globalIoScope`（与应用同生命周期）。
 *        为 null 时内部用 `SupervisorJob() + Dispatchers.IO` 自建一个；测试场景使用默认即可。
 */
class BackupEngine(
    private val taskStore: BackupTaskStore,
    private val registry: ProviderRegistry,
    private val credentialStore: CredentialStore,
    private val ledgerRepository: BackupLedgerRepository? = null,
    ioScope: CoroutineScope? = null,
) {

    private val queueMutex = Mutex()

    private val ioScope: CoroutineScope = ioScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    companion object {
        private const val TAG = "BackupEngine"
        /** 指数退避（毫秒）：第 1 次 30s、第 2 次 2m、之后 10m。 */
        private const val BACKOFF_30S = 30_000L
        private const val BACKOFF_2M = 120_000L
        private const val BACKOFF_10M = 600_000L
        /** 进度节流：累计 ≥1MB 才落盘一次 taskStore（断点续传 + UI 恢复）。 */
        private const val PROGRESS_PERSIST_BYTES = 1_000_000L
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
        Log.i(TAG, "enqueue(target=$targetId) 入队 ${ids.size} 个文件（filter 掉不存在/非文件: ${files.size - ids.size}）")
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
            Log.i(TAG, "runQueue() 启动执行队列")
            try {
                val summary = executeQueue(onTaskUpdated)
                _state.update { it.copy(running = false, lastSummary = summary) }
                Log.i(TAG, "runQueue() 完成: 成功 ${summary.succeeded} 失败 ${summary.failed} 跳过 ${summary.skipped} " +
                    "字节 ${summary.totalBytes} 耗时 ${summary.durationMs}ms")
                Result.success(summary)
            } catch (error: CancellationException) {
                _state.update { it.copy(running = false) }
                Log.w(TAG, "runQueue() 被取消", error)
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(running = false) }
                Log.e(TAG, "runQueue() 未捕获异常", error)
                Result.failure(error)
            }
        }

    /** 取消任务（仅 PENDING / UPLOADING 可取消）。 */
    suspend fun cancel(taskId: String): Unit = withContext(Dispatchers.IO) {
        val task = taskStore.load().find { it.taskId == taskId } ?: return@withContext
        if (task.isActive) {
            Log.i(TAG, "取消任务 [${task.remotePath}] was=${task.status}")
            taskStore.upsert(task.cancel())
            refreshState()
        }
    }

    /** 手动重试某目标的全部 FAILED 任务（重置 attempt 后回到 PENDING）。 */
    suspend fun retryFailed(targetId: String): Unit = withContext(Dispatchers.IO) {
        val tasks = taskStore.load()
            .filter { it.targetId == targetId && it.status == BackupTaskStatus.FAILED }
        Log.i(TAG, "retryFailed(target=$targetId) 重置 ${tasks.size} 个 FAILED 任务到 PENDING")
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
        Log.i(TAG, "executeQueue() 装载 ${pending.size} 个 PENDING 任务")
        for (task in pending) {
            val target = registry.get(task.targetId)
            if (target == null) {
                failed++
                val failedTask = task.markFailed("未找到备份目标：${task.targetId}")
                taskStore.upsert(failedTask)
                onTaskUpdated?.invoke(failedTask)
                Log.w(TAG, "[${task.remotePath}] 找不到 target=${task.targetId}，置 FAILED")
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
        var lastFailedCause: String? = task.errorMessage
        while (true) {
            val uploading = current.markUploading()
            taskStore.upsert(uploading)
            onTaskUpdated?.invoke(uploading)
            Log.i(TAG, "[${task.remotePath}] ${current.status} → UPLOADING (attempt=${current.attempt}, " +
                "size=${task.size}B, lastError=$lastFailedCause)")

            when (val result = runSingleAttempt(uploading, target, onTaskUpdated)) {
                is TaskOutcome.Uploaded, is TaskOutcome.Skipped -> {
                    val done = uploading.markDone()
                    taskStore.upsert(done)
                    onTaskUpdated?.invoke(done)
                    recordLedger(done, result)
                    Log.i(TAG, "[${task.remotePath}] UPLOADING → DONE")
                    return result
                }
                is TaskOutcome.Failed -> {
                    lastFailedCause = result.message
                    val nextAttempt = current.attempt + 1
                    if (nextAttempt >= target.capabilities.maxRetry) {
                        // 重试耗尽：保持真实错误信息，置 FAILED 给 UI 显示
                        val failedTask = uploading.markFailed(result.message)
                        taskStore.upsert(failedTask)
                        onTaskUpdated?.invoke(failedTask)
                        Log.w(TAG, "[${task.remotePath}] 重试耗尽 maxRetry=${target.capabilities.maxRetry}，" +
                            "置 FAILED: ${result.message}")
                        return result
                    } else {
                        // 保留 errorMessage 给 UI（标红提示失败原因），但内部 status 回到 PENDING 准备重试
                        val retried = uploading.copy(
                            status = BackupTaskStatus.PENDING,
                            attempt = nextAttempt,
                            errorMessage = result.message,
                            bytesDone = 0L, // WebDAV 不支持断点续传时，归零重传
                            updatedAt = System.currentTimeMillis(),
                        )
                        taskStore.upsert(retried)
                        onTaskUpdated?.invoke(retried)
                        val backoff = backoffDelayFor(nextAttempt)
                        Log.i(TAG, "[${task.remotePath}] 第 $nextAttempt 次失败，将等 ${backoff / 1000}s 后重试: ${result.message}")
                        delay(backoff)
                        current = retried
                    }
                }
            }
        }
    }

    /** 单次尝试：prepareRemote → exists 跳过 → uploadFile（进度实时刷新内存 + 节流落盘）。 */
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
                Log.i(TAG, "[${task.remotePath}] 远程已存在，跳过上传")
                TaskOutcome.Skipped(task.remotePath)
            } else {
                val local = File(task.localPath)
                if (!local.exists() || !local.isFile) {
                    throw BackupException("本地文件不存在：${task.localPath}")
                }
                // 节流落盘：每累计 ≥1MB bytesDone 写一次 disk
                var lastPersistedBytes = task.bytesDone
                val sizeForLog = local.length()
                val receipt = target.uploadFile(local, task.remotePath) { p ->
                    val bytes = (p * task.size).toLong().coerceIn(0L, task.size)
                    val updated = task.withProgress(bytes)
                    refreshTaskInState(updated)
                    if (bytes - lastPersistedBytes >= PROGRESS_PERSIST_BYTES) {
                        lastPersistedBytes = bytes
                        ioScope.launch { taskStore.upsert(updated) }
                    }
                    onTaskUpdated?.invoke(updated)
                }.getOrElse { error ->
                    throw BackupException(error.message ?: "上传失败", error)
                }
                Log.i(TAG, "[${task.remotePath}] 上传完毕: size=${sizeForLog}B remotePath=${receipt.remotePath}")
                TaskOutcome.Uploaded(receipt)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val userMessage = formatFailureMessage(error)
            Log.w(TAG, "[${task.remotePath}] attempt=${task.attempt} 失败: $userMessage", error)
            TaskOutcome.Failed(userMessage)
        }
    }

    /** 把异常压成"类型 + message"格式（保底填空，保证非空）。 */
    private fun formatFailureMessage(error: Throwable): String {
        // 拆链式 cause：仅取第一级足以定位（更深层可在 Logcat 看到全堆栈）
        val root = generateSequence(error) { it.cause }.firstOrNull { it != error } ?: error
        val type = root.javaClass.simpleName
        val msg = root.message?.takeIf { it.isNotBlank() }
            ?: "(no message, see logcat for stack)"
        return "$type: $msg"
    }

    /** 进度回调（非挂起，只能做内存状态更新；disk 由 [runSingleAttempt] 节流写）。 */
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

    /**
     * 写备份账本（上传成功 / 远端已存在跳过）。
     *
     * best-effort：账本写入失败不影响备份主流程，仅记录日志。
     * mtime 取本地文件最后修改时间（文件已被删则用当前时间兜底）。
     */
    private suspend fun recordLedger(task: BackupTask, outcome: TaskOutcome) {
        val repo = ledgerRepository ?: return
        val cloudFileId = (outcome as? TaskOutcome.Uploaded)?.receipt?.cloudFileId
        runCatching {
            val file = File(task.localPath)
            repo.recordSuccess(
                providerId = task.targetId,
                remotePath = task.remotePath,
                localPath = task.localPath,
                size = task.size,
                mtime = if (file.exists()) file.lastModified() else System.currentTimeMillis(),
                cloudFileId = cloudFileId,
            )
        }.onFailure { error ->
            Log.w(TAG, "[${task.remotePath}] 写备份账本失败（忽略）：${error.message}")
        }
    }

    /** 单任务最终结果。 */
    private sealed interface TaskOutcome {
        data class Uploaded(val receipt: UploadReceipt) : TaskOutcome
        data class Skipped(val remotePath: String) : TaskOutcome
        data class Failed(val message: String) : TaskOutcome
    }
}
