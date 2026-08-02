package com.ed.edqiu.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.backup.HistoryBackupRepository
import com.ed.edqiu.data.backup.RestoreMode
import com.ed.edqiu.data.backup.ValidatedBackup
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.LinkHistoryRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class BackupViewModel(
    private val backupRepository: HistoryBackupRepository,
    private val settingsRepository: SettingsRepository,
    historyRepository: LinkHistoryRepository,
    private val savedLinkRepository: SavedLinkRepository
) : ViewModel() {
    val backupDirUri = settingsRepository.backupDirUriFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )
    val automaticBackup = settingsRepository.automaticBackupFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false
    )
    val lastBackupAt = settingsRepository.lastBackupAtFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )
    val lastBackupError = settingsRepository.lastBackupErrorFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )
    val historyCount = historyRepository.count().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        0
    )

    private val pendingImportMutable = MutableStateFlow<ValidatedBackup?>(null)
    val pendingImport: StateFlow<ValidatedBackup?> = pendingImportMutable.asStateFlow()

    private val busyMutable = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyMutable.asStateFlow()

    private val busyMutex = Mutex()

    private val feedbackMutable = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = feedbackMutable.asStateFlow()

    fun setBackupDirectory(uri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setBackupDirUri(uri?.toString())
            feedbackMutable.value = if (uri == null) "已清除自动备份目录" else "已设置自动备份目录"
        }
    }

    fun setBackupDirectoryPath(path: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalized = path?.trim()?.takeIf { it.isNotBlank() }
            settingsRepository.setBackupDirUri(normalized)
            feedbackMutable.value = if (normalized == null) "已清除自动备份目录" else "已设置自动备份目录"
        }
    }

    fun setAutomaticBackup(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled && backupDirUri.value.isNullOrBlank()) {
                feedbackMutable.value = "请先选择自动备份目录"
                return@launch
            }
            settingsRepository.setAutomaticBackup(enabled)
        }
    }

    fun exportTo(uri: Uri) = runBusy {
        backupRepository.exportToUri(uri)
        feedbackMutable.value = "备份导出成功"
    }

    fun prepareImport(uri: Uri) = runBusy {
        pendingImportMutable.value = backupRepository.readAndValidate(uri)
    }

    fun cancelImport() {
        pendingImportMutable.value = null
    }

    fun restorePending(mode: RestoreMode) = runBusy {
        val pending = pendingImportMutable.value ?: return@runBusy
        val result = backupRepository.restore(pending, mode)
        pendingImportMutable.value = null
        val monitorUri = settingsRepository.monitorDirUriFlow.first()
        savedLinkRepository.refreshStatuses(monitorUri)
        feedbackMutable.value =
            "已导入 ${result.activeImported} 条收件箱记录、${result.historyImported} 条回收站记录；" +
                "跳过 ${result.activeSkipped + result.historySkipped} 条"
    }

    fun backupNow() = runBusy {
        val uri = backupDirUri.value?.let(Uri::parse)
            ?: error("请先选择自动备份目录")
        backupRepository.backupToDirectory(uri)
        settingsRepository.recordBackupSuccess(System.currentTimeMillis())
        feedbackMutable.value = "自动备份目录已写入新备份"
    }

    fun clearFeedback() {
        feedbackMutable.value = null
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!busyMutex.tryLock()) return@launch
            try {
                busyMutable.value = true
                runCatching { block() }
                    .onFailure { error ->
                        val message = error.message ?: "操作失败"
                        settingsRepository.recordBackupError(message)
                        feedbackMutable.value = message
                    }
            } finally {
                busyMutable.value = false
                busyMutex.unlock()
            }
        }
    }
}
