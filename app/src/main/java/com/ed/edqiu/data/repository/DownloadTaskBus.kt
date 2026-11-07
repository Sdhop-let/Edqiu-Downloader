package com.ed.edqiu.data.repository

import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object DownloadTaskBus {
    private val tasksMutable = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = tasksMutable.asStateFlow()

    fun add(task: DownloadTask) {
        tasksMutable.update { current ->
            val next = current.filterNot { it.id == task.id } + task
            // 2026-11-07 审计修复：终态任务自动裁剪——旧实现完成任务只进不出（仅 UI
            // 手动「清除已完成」），订阅批量下载一晚可积累几十个常驻 StateFlow
            val terminalCount = next.count {
                it.status == DownloadStatus.COMPLETED ||
                    it.status == DownloadStatus.FAILED ||
                    it.status == DownloadStatus.CANCELLED
            }
            if (terminalCount <= MAX_TERMINAL_TASKS) {
                next
            } else {
                val toDrop = terminalCount - MAX_TERMINAL_TASKS
                var dropped = 0
                next.filterNot { t ->
                    val terminal = t.status == DownloadStatus.COMPLETED ||
                        t.status == DownloadStatus.FAILED ||
                        t.status == DownloadStatus.CANCELLED
                    if (terminal && dropped < toDrop) {
                        dropped++
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        tasksMutable.update { tasks ->
            tasks.map { task -> if (task.id == taskId) transform(task) else task }
        }
    }

    fun cancel(taskId: String) {
        updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED) }
    }

    fun remove(taskId: String) {
        tasksMutable.update { tasks -> tasks.filterNot { it.id == taskId } }
    }

    fun clearCompleted() {
        tasksMutable.update { tasks ->
            tasks.filterNot {
                it.status == DownloadStatus.COMPLETED ||
                    it.status == DownloadStatus.FAILED ||
                    it.status == DownloadStatus.CANCELLED
            }
        }
    }

    /** 终态任务保留上限（2026-11-07 审计：防长会话无界累积）。 */
    private const val MAX_TERMINAL_TASKS = 100
}
