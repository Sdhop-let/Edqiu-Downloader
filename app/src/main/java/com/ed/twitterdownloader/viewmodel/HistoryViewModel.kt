package com.ed.twitterdownloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.twitterdownloader.data.database.DownloadHistoryEntity
import com.ed.twitterdownloader.data.preferences.HiddenHistoryPreferences
import com.ed.twitterdownloader.data.repository.HistoryRepository
import com.ed.twitterdownloader.service.DirectoryScanner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = HistoryRepository(application)
    private val hiddenHistoryPreferences = HiddenHistoryPreferences(application)

    val historyList: StateFlow<List<DownloadHistoryEntity>> =
        repository.allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val historyCount: StateFlow<Int> =
        repository.historyCount.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun deleteHistory(entity: DownloadHistoryEntity, deleteLocalFile: Boolean = false) {
        viewModelScope.launch {
            if (deleteLocalFile) {
                val deleted = deleteLocalMediaFiles(entity.filePath)
                if (deleted) {
                    hiddenHistoryPreferences.unhide(entity.filePath)
                } else {
                    hiddenHistoryPreferences.hide(entity.filePath)
                }
            } else {
                hiddenHistoryPreferences.hide(entity.filePath)
            }
            repository.delete(entity)
        }
    }

    fun scanLocalVideos(onComplete: (String) -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                DirectoryScanner.scanAndRebuildHistory(getApplication())
            }
            onComplete("宸叉绱㈠綋鍓嶅瓨鍌ㄧ洰褰曚笅鐨勮棰戝拰鍥剧墖鏂囦欢")
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch { repository.clearAll() }
    }

    private fun deleteLocalMediaFiles(filePath: String): Boolean {
        if (filePath.isBlank()) return true
        return runCatching {
            val mediaFile = File(filePath)
            val mediaDeleted = !mediaFile.exists() || mediaFile.delete()
            val metaFile = File("$filePath.meta.json")
            if (metaFile.exists()) metaFile.delete()
            mediaDeleted
        }.getOrDefault(false)
    }
}
