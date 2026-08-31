package com.ed.edqiu.di

import android.content.Context
import com.ed.edqiu.backup.data.BackupLedgerRepository
import com.ed.edqiu.backup.data.BackupTaskStore
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.data.EncryptedCredentialStore
import com.ed.edqiu.backup.data.JsonBackupTaskStore
import com.ed.edqiu.backup.engine.BackupEngine
import com.ed.edqiu.backup.provider.AliPanTarget
import com.ed.edqiu.backup.provider.BaiduPanTarget
import com.ed.edqiu.backup.provider.Pan123OpenTarget
import com.ed.edqiu.backup.provider.ProviderRegistry
import com.ed.edqiu.capture.LinkCaptureCoordinator
import com.ed.edqiu.data.backup.HistoryBackupRepository
import com.ed.edqiu.data.db.EdqiuDatabase
import com.ed.edqiu.data.metadata.MetadataFetcher
import com.ed.edqiu.data.preferences.PreDownloadPreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.DownloadMonitor
import com.ed.edqiu.data.repository.DownloadTaskRepo
import com.ed.edqiu.data.repository.DownloaderClient
import com.ed.edqiu.data.repository.LinkHistoryRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.predownload.PreDownloadManager
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
            metadataScope = globalIoScope
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

    // ================= 网盘直连备份（T05 UI 集成） =================
    // 凭证加密存储 / 任务持久化 / 适配器注册表 / 队列引擎：
    // CloudBackupViewModel 与 BackupWorker 共享同一实例，队列互斥串行、状态互通。
    val backupCredentialStore: CredentialStore = EncryptedCredentialStore(context.applicationContext)
    val backupTaskStore: BackupTaskStore = JsonBackupTaskStore(context.applicationContext)
    val backupLedgerRepository: BackupLedgerRepository =
        BackupLedgerRepository(database.backupLedgerDao())
    val backupProviderRegistry: ProviderRegistry = ProviderRegistry(context.applicationContext, backupCredentialStore)
        .registerWebDavFamily() // 自定义 WebDAV + 123网盘(WebDAV) + CloudDrive2
        .register(BaiduPanTarget(context.applicationContext, backupCredentialStore))
        .register(AliPanTarget(context.applicationContext, backupCredentialStore))
        .register(Pan123OpenTarget(context.applicationContext, backupCredentialStore)) // 123网盘官方 OAuth 登录
    val backupEngine: BackupEngine = BackupEngine(
        taskStore = backupTaskStore,
        registry = backupProviderRegistry,
        credentialStore = backupCredentialStore,
        ledgerRepository = backupLedgerRepository,
        ioScope = globalIoScope,
    )

    // ================= 预下载（自动攒批 + 代理通路检测 + 网盘联动） =================
    // 2026-08-31 18:22 恢复：此前误判为干扰收件箱下载而停用，真凶是 InternalMediaDownloader
    // openConnection(null) 抛 "proxy can not be null"（已修）。手动批量下载保持直接链路，
    // 自动预下载（onCaptured 攒批）独立走本协调器，互不阻塞。
    val preDownloadPreferences: PreDownloadPreferences =
        PreDownloadPreferences(context.applicationContext)
    val proxyPreferences: ProxyPreferences =
        ProxyPreferences(context.applicationContext)
    val preDownloadManager: PreDownloadManager = PreDownloadManager(
        context = context.applicationContext,
        savedLinkRepository = savedLinkRepository,
        preDownloadPreferences = preDownloadPreferences,
        proxyPreferences = proxyPreferences,
        backupEngine = backupEngine,
        backupProviderRegistry = backupProviderRegistry,
        backupTaskStore = backupTaskStore,
        backupLedgerRepository = backupLedgerRepository,
        settingsRepository = settingsRepository,
        ioScope = globalIoScope,
    )
    init {
        // 捕获挂载：收件箱保存链接成功后 → 攒批自动预下载（受开关控制）
        savedLinkRepository.onCaptured = preDownloadManager::onLinkCaptured
    }
}
