package com.ed.edqiu

import com.ed.edqiu.background.BackgroundSyncScheduler
import com.ed.edqiu.background.HistoryBackupScheduler
import com.ed.edqiu.data.repository.DownloadTaskBus
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
        // 2026-10 整改：移除启动时的 importScannedDownloads——冷启动原本三重全盘扫描
        // （TwitterDownloaderApp 引擎初始化后 scanAndRebuildHistory + 此处 import +
        // BackgroundSyncScheduler 初始发射触发的 DownloadSyncWorker 内第三次 import），
        // 现保留「引擎初始化扫描 + Worker 同步」各一次，消除冷启动 IO/DB 尖峰。
        // 清理历史已修复 bug 残留的错误数据（旧 launchExternal 启动 MainActivity 失败留下的
        // ActivityNotFoundException 错误信息，会持续显示在详情页；幂等，无匹配也无副作用）
        applicationScope.launch {
            runCatching {
                val cleared = container.savedLinkRepository.clearKnownFixedErrors()
                if (cleared > 0) {
                    android.util.Log.i("EdqiuApp", "Cleared $cleared known-fixed error records")
                }
            }
        }
        // 2026-10 整改：清理上次会话残留的明文 cookie 文件（每次需要时都会重新写入）
        applicationScope.launch(Dispatchers.IO) {
            runCatching { com.ed.edqiu.service.YoutubeDLService.cleanupCookieFiles(this@EdqiuApplication) }
        }
        // 进程级恢复：把上次被杀后遗留的“进行中/待下载”任务标记为可重新下载，写回下载中心
        applicationScope.launch {
            runCatching {
                val recovered = container.downloadTaskRepo.recoverInterruptedTasks()
                recovered.forEach { DownloadTaskBus.add(it) }
            }
        }
    }
}
