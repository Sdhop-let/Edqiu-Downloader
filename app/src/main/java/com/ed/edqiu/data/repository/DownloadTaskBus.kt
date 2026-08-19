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
            current.filterNot { it.id == task.id } + task
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
}
