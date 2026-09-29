package com.ed.edqiu.di

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ed.edqiu.backup.data.BackupLedgerRepository
import com.ed.edqiu.backup.data.BackupTaskStore
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.engine.BackupEngine
import com.ed.edqiu.backup.provider.ProviderRegistry
import com.ed.edqiu.capture.LinkCaptureCoordinator
import com.ed.edqiu.data.backup.HistoryBackupRepository
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.LinkHistoryRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.predownload.PreDownloadManager
import com.ed.edqiu.ui.backup.CloudBackupViewModel
import com.ed.edqiu.ui.backup.MediaBackupViewModel
import com.ed.edqiu.ui.authors.AuthorsViewModel
import com.ed.edqiu.ui.detail.DetailViewModel
import com.ed.edqiu.ui.downloads.DownloadCenterViewModel
import com.ed.edqiu.ui.history.HistoryViewModel
import com.ed.edqiu.ui.list.ListViewModel
import com.ed.edqiu.ui.settings.BackupViewModel

/** 手动 DI 工厂：按类返回对应 ViewModel。 */
class EdqiuViewModelFactory(
    private val savedLinkRepository: SavedLinkRepository,
    private val settingsRepository: SettingsRepository,
    private val linkCaptureCoordinator: LinkCaptureCoordinator,
    private val linkHistoryRepository: LinkHistoryRepository,
    private val historyBackupRepository: HistoryBackupRepository,
    private val backupProviderRegistry: ProviderRegistry,
    private val backupTaskStore: BackupTaskStore,
    private val backupEngine: BackupEngine,
    private val backupCredentialStore: CredentialStore,
    private val backupLedgerRepository: BackupLedgerRepository,
    private val preDownloadManager: PreDownloadManager,
    // 应用级下载作用域：收件箱/详情页下载执行不随页面销毁中断（退后台继续下载）
    private val downloadScope: kotlinx.coroutines.CoroutineScope,
    private val application: Application
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ListViewModel::class.java) ->
                ListViewModel(
                    application,
                    savedLinkRepository,
                    settingsRepository,
                    linkCaptureCoordinator,
                    preDownloadManager,
                    downloadScope
                ) as T
            modelClass.isAssignableFrom(DetailViewModel::class.java) ->
                DetailViewModel(application, savedLinkRepository, settingsRepository, downloadScope) as T
            modelClass.isAssignableFrom(DownloadCenterViewModel::class.java) ->
                DownloadCenterViewModel(savedLinkRepository, settingsRepository) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(linkHistoryRepository) as T
            modelClass.isAssignableFrom(BackupViewModel::class.java) ->
                BackupViewModel(
                    backupRepository = historyBackupRepository,
                    settingsRepository = settingsRepository,
                    historyRepository = linkHistoryRepository,
                    savedLinkRepository = savedLinkRepository
                ) as T
            modelClass.isAssignableFrom(CloudBackupViewModel::class.java) ->
                CloudBackupViewModel(
                    application = application,
                    registry = backupProviderRegistry,
                    engine = backupEngine,
                    taskStore = backupTaskStore,
                    credentialStore = backupCredentialStore,
                    ledgerRepository = backupLedgerRepository,
                    settingsRepository = settingsRepository
                ) as T
            modelClass.isAssignableFrom(MediaBackupViewModel::class.java) ->
                MediaBackupViewModel(
                    application = application,
                    registry = backupProviderRegistry,
                    engine = backupEngine,
                    taskStore = backupTaskStore,
                    ledgerRepository = backupLedgerRepository,
                    settingsRepository = settingsRepository
                ) as T
            modelClass.isAssignableFrom(AuthorsViewModel::class.java) ->
                AuthorsViewModel(savedLinkRepository, settingsRepository) as T
            else -> throw IllegalArgumentException("未知的 ViewModel: $modelClass")
        }
    }
}
