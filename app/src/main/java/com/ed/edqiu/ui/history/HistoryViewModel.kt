package com.ed.edqiu.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.repository.LinkHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: LinkHistoryRepository
) : ViewModel() {
    val entries = repository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val selectedIdsMutable = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = selectedIdsMutable.asStateFlow()

    private val feedbackMutable = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = feedbackMutable.asStateFlow()

    fun toggleSelected(archiveId: String) {
        selectedIdsMutable.value = selectedIdsMutable.value.toMutableSet().apply {
            if (!add(archiveId)) remove(archiveId)
        }
    }

    fun selectAll() {
        selectedIdsMutable.value = entries.value.map { it.archiveId }.toSet()
    }

    fun clearSelection() {
        selectedIdsMutable.value = emptySet()
    }

    fun restoreSelected() {
        val ids = selectedIdsMutable.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.restore(ids)
            feedbackMutable.value = "已恢复 ${result.restored} 条，跳过 ${result.skipped} 条"
            clearSelection()
        }
    }

    fun permanentlyDeleteSelected() {
        val ids = selectedIdsMutable.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = repository.permanentlyDelete(ids)
            feedbackMutable.value = "已永久删除 $deleted 条历史记录"
            clearSelection()
        }
    }

    fun clearFeedback() {
        feedbackMutable.value = null
    }
}
