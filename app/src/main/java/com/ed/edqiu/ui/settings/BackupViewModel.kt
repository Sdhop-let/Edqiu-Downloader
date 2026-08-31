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
            if (uri == null) {
                feedbackMutable.value = "已清除自动备份目录"
            } else {
                scanImportAndPrune(uri)
            }
        }
    }

    fun setBackupDirectoryPath(path: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalized = path?.trim()?.takeIf { it.isNotBlank() }
            settingsRepository.setBackupDirUri(normalized)
            when (normalized) {
                null -> feedbackMutable.value = "已清除自动备份目录"
                else -> scanImportAndPrune(Uri.parse(normalized))
            }
        }
    }

    /**
     * 设置备份目录后先行扫描：目录中已有备份则导入最新一份，
     * 并清理超过 7 天的过期备份文件。
     */
    private suspend fun scanImportAndPrune(treeUri: Uri) {
        runCatching {
            var message = "已设置自动备份目录"
            val latest = backupRepository.latestBackupUri(treeUri)
            if (latest != null) {
                val backup = backupRepository.readAndValidate(latest)
                val result = backupRepository.restore(backup, RestoreMode.MERGE)
                message += "，已导入最新备份：" +
                    "${result.activeImported} 条收件箱、${result.historyImported} 条回收站记录" +
                    "（跳过 ${result.activeSkipped + result.historySkipped} 条）"
                val monitorUri = settingsRepository.monitorDirUriFlow.first()
                savedLinkRepository.refreshStatuses(monitorUri)
                savedLinkRepository.importScannedDownloads(monitorUri)
            }
            val pruned = backupRepository.pruneBackupsOlderThan(treeUri)
            if (pruned > 0) message += "，已清理 $pruned 份超过 7 天的过期备份"
            feedbackMutable.value = message
        }.onFailure { error ->
            feedbackMutable.value = error.message ?: "扫描备份目录失败"
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
        savedLinkRepository.importScannedDownloads(monitorUri)
        feedbackMutable.value =
            "已导入 ${result.activeImported} 条收件箱记录、${result.historyImported} 条回收站记录；" +
                "跳过 ${result.activeSkipped + result.historySkipped} 条"
    }

    fun backupNow() = runBusy {
        val uri = backupDirUri.value?.let(Uri::parse)
            ?: error("请先选择自动备份目录")
        if (backupRepository.backupToDirectory(uri) == null) {
            feedbackMutable.value = "没有需要备份的记录"
        } else {
            settingsRepository.recordBackupSuccess(System.currentTimeMillis())
            feedbackMutable.value = "自动备份目录已写入新备份"
        }
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
