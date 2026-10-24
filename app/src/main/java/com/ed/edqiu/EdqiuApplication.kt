package com.ed.edqiu

import com.ed.edqiu.background.BackgroundSyncScheduler
import com.ed.edqiu.background.HistoryBackupScheduler
import com.ed.edqiu.background.MediaDimensionsBackfillWorker
import com.ed.edqiu.data.db.EdqiuDatabase
import com.ed.edqiu.di.AppContainer
import com.ed.edqiu.TwitterDownloaderApp
import com.ed.edqiu.ui.components.WallpaperProvider
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
        // 临时诊断探针（2026-10）：生命周期事件日志，配合 FreezeProbe 定位窗口 mStopped 卡死。
        // 2026-10-24：日志输出门控 debug（正式包不再刷 Log.d）；探针注册本身开销为零保留，
        // 渲染冻结 WIP 关闭后再整体移除
        if (BuildConfig.DEBUG) {
            registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                private fun log(act: android.app.Activity, ev: String) {
                    android.util.Log.d("FreezeProbe", "LIFECYCLE ${act.javaClass.simpleName} $ev")
                }
                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) { log(a, "CREATE") }
                override fun onActivityStarted(a: android.app.Activity) { log(a, "START") }
                override fun onActivityResumed(a: android.app.Activity) { log(a, "RESUME") }
                override fun onActivityPaused(a: android.app.Activity) { log(a, "PAUSE") }
                override fun onActivityStopped(a: android.app.Activity) { log(a, "STOP") }
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) { log(a, "SAVE") }
                override fun onActivityDestroyed(a: android.app.Activity) { log(a, "DESTROY") }
            })
        }
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
        // 回收站 30 天保留期清理（2026-10-24 审计落地）：过期归档行删除——
        // 30 天粒度无需 WorkManager 精度，启动跑一次即可；媒体文件归媒体库管不动
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                val threshold = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                val purged = EdqiuDatabase.getDatabase(this@EdqiuApplication)
                    .deletedLinkHistoryDao().purgeOlderThan(threshold)
                if (purged > 0) {
                    android.util.Log.i("EdqiuApp", "回收站 30 天清理：删除 $purged 条过期归档")
                }
            }
        }
        // 壁纸热更新（2026-10-24）：监听系统壁纸变更 → 失效缓存 → GlassBackground 重载，
        // 换壁纸不再需要杀进程
        WallpaperProvider.registerChangeListener(this)
        // 2026-10-02 批次B：启动即调度存量媒体宽高回填（播放器比例动画前提）。
        // 幂等：Worker 只处理 mediaWidth IS NULL 的记录；KEEP 防重复排队（纯本地探测，无须网络约束）
        MediaDimensionsBackfillWorker.schedule(this)
    }
}
