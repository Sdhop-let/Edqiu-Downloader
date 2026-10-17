package com.ed.edqiu.di

import android.content.Context
import com.ed.edqiu.capture.LinkCaptureCoordinator
import com.ed.edqiu.data.backup.HistoryBackupRepository
import com.ed.edqiu.data.db.EdqiuDatabase
import com.ed.edqiu.data.metadata.MetadataFetcher
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.DownloadMonitor
import com.ed.edqiu.data.repository.DownloadTaskRepo
import com.ed.edqiu.data.repository.DownloaderClient
import com.ed.edqiu.data.repository.LinkHistoryRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 轻量级依赖容器。集中构造数据库、仓库与单例服务，
 * 在 Application.onCreate 中实例化一次，全局复用。
 */
class AppContainer(context: Context) {

    /**
     * 应用级 IO 协程作用域（SupervisorJob，单点失败不拖垮整个 App）。
     * 供 [BackupEngine] 节流落盘 / 后台异步任务共用；生命周期与 Application 同步，不在 onTerminate 时取消（进程杀自然清理）。
     */
    val globalIoScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database: EdqiuDatabase = EdqiuDatabase.getDatabase(context)

    private val metadataFetcher: MetadataFetcher = MetadataFetcher()

    private val downloadMonitor: DownloadMonitor = DownloadMonitor(context)

    private val downloaderClient: DownloaderClient = DownloaderClient(context.applicationContext)

    val linkHistoryRepository: LinkHistoryRepository =
        LinkHistoryRepository(
            database = database,
            savedLinkDao = database.savedLinkDao(),
            historyDao = database.deletedLinkHistoryDao()
        )

    val savedLinkRepository: SavedLinkRepository =
        SavedLinkRepository(
            dao = database.savedLinkDao(),
            downloadMonitor = downloadMonitor,
            metadataFetcher = metadataFetcher,
            downloaderClient = downloaderClient,
            linkHistoryRepository = linkHistoryRepository,
            metadataScope = globalIoScope,
            // 2026-09-30 v1.6.8：启用封面本地落盘——分享保存当场存预览图，
            // 收件箱/媒体库免二次联网同步（离线可见封面）
            appContext = context.applicationContext
        )

    val historyBackupRepository: HistoryBackupRepository =
        HistoryBackupRepository(
            context = context.applicationContext,
            database = database,
            savedLinkDao = database.savedLinkDao(),
            historyDao = database.deletedLinkHistoryDao()
        )

    val linkCaptureCoordinator: LinkCaptureCoordinator =
        LinkCaptureCoordinator(savedLinkRepository)

    val settingsRepository: SettingsRepository = SettingsRepository(context)

    /** 独立下载器（Direct/yt-dlp）活跃任务的持久化仓库，供启动恢复与后台 Worker 使用。 */
    val downloadTaskRepo: DownloadTaskRepo = DownloadTaskRepo(context.applicationContext)

    // ================= 网盘备份 → 2026-10 整体移除 =================
    // 备份中心（BackupEngine 队列 / 多网盘适配器 / 账本 / 任务存储）随功能删除；
    // WebDAV 同步链路（WebDavSyncService + WebDavEngine + CloudSyncPreferences）
    // 直接对账远端，幂等不再依赖账本。
    //
    // 2026-10-17：预下载功能整体退役（自动攒批是订阅/手动之外的第三条下载路径，
    // 曾打满全局熔断器误伤手动下载；保留价值仅剩"省一次点击"）。下载后的网盘联动
    // 保留在 service/DownloadCloudSync。
    val proxyPreferences: ProxyPreferences =
        ProxyPreferences(context.applicationContext)
    val cloudSyncPreferences: CloudSyncPreferences =
        CloudSyncPreferences(context.applicationContext)
    init {
        // P0-4 持久队列挂载（2026-09-15）：每次请求下载 → 登记 WorkManager 唯一任务，
        // 下载中途进程被杀后由 Worker 在进程恢复时自动续跑（不改变收件箱手动模式语义）
        savedLinkRepository.onDownloadRequested = { tweetId ->
            com.ed.edqiu.background.DownloadQueue.enqueue(context.applicationContext, listOf(tweetId))
        }
        // P2-1 作者订阅轮询（2026-09-15 批次4）：挂载在应用级 IO 作用域，幂等启动
        com.ed.edqiu.service.SubscriptionManager.start(context.applicationContext, globalIoScope)
    }
}
