package com.ed.edqiu

import com.ed.edqiu.background.BackgroundSyncScheduler
import com.ed.edqiu.background.HistoryBackupScheduler
import com.ed.edqiu.di.AppContainer
import com.ed.edqiu.TwitterDownloaderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 应用入口。通过容器（AppContainer）集中持有数据库与仓库实例，
 * 供各 ViewModel 以手动 DI 方式获取，避免引入 Hilt 增加复杂度。
 */
class EdqiuApplication : TwitterDownloaderApp() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch {
            container.settingsRepository.backgroundSyncFlow
                .distinctUntilChanged()
                .collect { enabled ->
                    BackgroundSyncScheduler.setEnabled(this@EdqiuApplication, enabled)
                }
        }
        applicationScope.launch {
            container.settingsRepository.automaticBackupFlow
                .distinctUntilChanged()
                .collect { enabled ->
                    HistoryBackupScheduler.setEnabled(this@EdqiuApplication, enabled)
                }
        }
    }
}
