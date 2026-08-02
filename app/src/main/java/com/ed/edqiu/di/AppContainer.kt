package com.ed.edqiu.di

import android.content.Context
import com.ed.edqiu.backup.data.BackupTaskStore
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.data.EncryptedCredentialStore
import com.ed.edqiu.backup.data.JsonBackupTaskStore
import com.ed.edqiu.backup.engine.BackupEngine
import com.ed.edqiu.backup.provider.AliPanTarget
import com.ed.edqiu.backup.provider.BaiduPanTarget
import com.ed.edqiu.backup.provider.ProviderRegistry
import com.ed.edqiu.capture.LinkCaptureCoordinator
import com.ed.edqiu.data.backup.HistoryBackupRepository
import com.ed.edqiu.data.db.EdqiuDatabase
import com.ed.edqiu.data.metadata.MetadataFetcher
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.DownloadMonitor
import com.ed.edqiu.data.repository.DownloaderClient
import com.ed.edqiu.data.repository.LinkHistoryRepository
import com.ed.edqiu.data.repository.SavedLinkRepository

/**
 * 轻量级依赖容器。集中构造数据库、仓库与单例服务，
 * 在 Application.onCreate 中实例化一次，全局复用。
 */
class AppContainer(context: Context) {

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
            linkHistoryRepository = linkHistoryRepository
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

    // ================= 网盘直连备份（T05 UI 集成） =================
    // 凭证加密存储 / 任务持久化 / 适配器注册表 / 队列引擎：
    // CloudBackupViewModel 与 BackupWorker 共享同一实例，队列互斥串行、状态互通。
    val backupCredentialStore: CredentialStore = EncryptedCredentialStore(context.applicationContext)
    val backupTaskStore: BackupTaskStore = JsonBackupTaskStore(context.applicationContext)
    val backupProviderRegistry: ProviderRegistry = ProviderRegistry(context.applicationContext, backupCredentialStore)
        .registerWebDavFamily() // 自定义 WebDAV + 123网盘 + CloudDrive2
        .register(BaiduPanTarget(context.applicationContext, backupCredentialStore))
        .register(AliPanTarget(context.applicationContext, backupCredentialStore))
    val backupEngine: BackupEngine = BackupEngine(
        taskStore = backupTaskStore,
        registry = backupProviderRegistry,
        credentialStore = backupCredentialStore,
    )
}
