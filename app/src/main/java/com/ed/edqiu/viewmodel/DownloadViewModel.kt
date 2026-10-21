package com.ed.edqiu.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.repository.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadUiState(
    val tasks: List<DownloadTask> = emptyList(),
    val isDownloading: Boolean = false
)

/**
 * 下载中心 ViewModel（2026-10-21 清理：旧「解析→选格式→下载」入口随 Home 屏删除；
 * 现只负责下载任务列表展示与取消/移除/清理操作）。
 */
class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadRepository = DownloadRepository(application)

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            downloadRepository.downloadTasks.collect { tasks ->
                _uiState.update {
                    it.copy(
                        tasks = tasks,
                        isDownloading = tasks.any { task -> task.isActive }
                    )
                }
            }
        }
    }

    fun cancelDownload(taskId: String) {
        downloadRepository.cancelDownload(taskId)
    }

    fun removeTask(taskId: String) {
        downloadRepository.removeTask(taskId)
    }

    fun clearCompleted() {
        downloadRepository.clearCompleted()
    }
}
