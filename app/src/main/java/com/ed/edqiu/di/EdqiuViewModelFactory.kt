package com.ed.edqiu.di

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ed.edqiu.capture.LinkCaptureCoordinator
import com.ed.edqiu.data.backup.HistoryBackupRepository
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.LinkHistoryRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.ui.backup.MediaBackupViewModel
import com.ed.edqiu.ui.authors.AuthorsViewModel
import com.ed.edqiu.ui.detail.DetailViewModel
import com.ed.edqiu.ui.history.HistoryViewModel
import com.ed.edqiu.ui.list.ListViewModel
import com.ed.edqiu.ui.settings.BackupViewModel
import com.ed.edqiu.ui.subscription.SubscriptionViewModel

/** 手动 DI 工厂：按类返回对应 ViewModel。 */
class EdqiuViewModelFactory(
    private val savedLinkRepository: SavedLinkRepository,
    private val settingsRepository: SettingsRepository,
    private val linkCaptureCoordinator: LinkCaptureCoordinator,
    private val linkHistoryRepository: LinkHistoryRepository,
    private val historyBackupRepository: HistoryBackupRepository,
    private val cloudSyncPreferences: CloudSyncPreferences,
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
                    downloadScope
                ) as T
            modelClass.isAssignableFrom(DetailViewModel::class.java) ->
                DetailViewModel(application, savedLinkRepository, settingsRepository, downloadScope) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(linkHistoryRepository) as T
            modelClass.isAssignableFrom(BackupViewModel::class.java) ->
                BackupViewModel(
                    backupRepository = historyBackupRepository,
                    settingsRepository = settingsRepository,
                    historyRepository = linkHistoryRepository,
                    savedLinkRepository = savedLinkRepository
                ) as T
            // 2026-10 轻量对账版：仅依赖每网盘独立配置（PROPFIND 实时对账，无引擎/账本）
            modelClass.isAssignableFrom(MediaBackupViewModel::class.java) ->
                MediaBackupViewModel(
                    application = application,
                    cloudSyncPreferences = cloudSyncPreferences
                ) as T
            // 2026-10 作品订阅页（多平台检测中枢 UI）
            modelClass.isAssignableFrom(SubscriptionViewModel::class.java) ->
                SubscriptionViewModel(application) as T
            modelClass.isAssignableFrom(AuthorsViewModel::class.java) ->
                AuthorsViewModel(savedLinkRepository, settingsRepository) as T
            else -> throw IllegalArgumentException("未知的 ViewModel: $modelClass")
        }
    }
}
