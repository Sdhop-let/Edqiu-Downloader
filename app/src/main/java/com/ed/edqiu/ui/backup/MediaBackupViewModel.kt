package com.ed.edqiu.ui.backup

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.backup.provider.WebDavCredential
import com.ed.edqiu.backup.provider.WebDavEngine
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.data.preferences.SyncHistoryRecord
import com.ed.edqiu.service.SyncTaskControl
import com.ed.edqiu.service.WebDavManualSync
import com.ed.edqiu.service.WebDavSyncProgress
import com.ed.edqiu.service.WebDavSyncService
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.FeedbackMessage
import com.ed.edqiu.ui.screens.webDavProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 单个文件的两端对账状态。 */
enum class SyncItemState {
    /** 两端一致（远端同名且尺寸相同）→ 已同步。 */
    SYNCED,

    /** 远端缺失 → 待上传。 */
    PENDING,

    /** 远端同名但尺寸不同 → 冲突（下一轮同步时本地版将上传、云端旧版改名保留）。 */
    CONFLICT,
}

/** 详情页分页：同步（未同步队列，可暂停/优先）| 已同步（只读账面）。 */
enum class SyncDetailTab(val label: String) {
    SYNCING("同步"),
    SYNCED("已同步"),
}

/** 「同步情况详情」列表条目。 */
data class SyncDetailItem(
    val file: File,
    /** 远端文件名（监控子目录文件带目录前缀，与同步服务的派生一致）。 */
    val remotePath: String,
    val size: Long,
    val modifiedAt: Long,
    val isVideo: Boolean,
    val state: SyncItemState,
)

/** 「同步情况详情」聚合 UI 状态。 */
data class SyncDetailUiState(
    val providerName: String = "WebDAV",
    val serverUrl: String = "",
    val configured: Boolean = false,
    val items: List<SyncDetailItem> = emptyList(),
    val tab: SyncDetailTab = SyncDetailTab.SYNCING,
    val loading: Boolean = false,
    val error: String? = null,
    val message: FeedbackMessage? = null,
    /** 当前选中网盘的验证结论（驱动「立即同步」可用性）。 */
    val verified: Boolean = false,
    /** 立即同步运行状态（来自应用级运行器）。 */
    val syncRunning: Boolean = false,
    val syncProgress: WebDavSyncProgress? = null,
    /** 暂停中的远端文件名集合（SyncTaskControl 实时镜像）。 */
    val pausedSet: Set<String> = emptySet(),
    /** 已置顶优先的远端文件名集合。 */
    val prioritizedSet: Set<String> = emptySet(),
    /** 上传队列「小文件优先」排序开关（持久化）。 */
    val smallFirst: Boolean = false,
    val lastRefreshAt: Long = 0,
    /** 备份中心「最近备份」历史（CloudSyncPreferences，新→旧）。 */
    val history: List<SyncHistoryRecord> = emptyList(),
    /** 上次成功同步时间（备份中心入口副标题用；0 = 从未同步）。 */
    val lastSyncTime: Long = 0,
) {
    val syncedCount: Int get() = items.count { it.state == SyncItemState.SYNCED }
    val pendingCount: Int get() = items.count { it.state == SyncItemState.PENDING }
    val conflictCount: Int get() = items.count { it.state == SyncItemState.CONFLICT }

    /** 已上传字节（对账为「已同步」条目尺寸和——备份中心「已上传 X」）。 */
    val uploadedBytes: Long get() = items.filter { it.state == SyncItemState.SYNCED }.sumOf { it.size }

    /** 本轮运行进度 0..1（null = 未在同步或进度未知）。 */
    val runProgress: Float?
        get() {
            val p = syncProgress?.takeIf { syncRunning } ?: return null
            if (p.totalFiles <= 0) return null
            return (((p.currentIndex - 1).coerceAtLeast(0)) + p.fileProgress) / p.totalFiles
        }

    /** 剩余待处理文件数（运行中=本阶段剩余；空闲=待上传+冲突）。 */
    val remainingFiles: Int
        get() = if (syncRunning && syncProgress != null) {
            (syncProgress.totalFiles - syncProgress.currentIndex).coerceAtLeast(0)
        } else {
            pendingCount + conflictCount
        }

    /** 正在同步的条目（远端名与进度流匹配；上传/下载均算）。 */
    val syncingItem: SyncDetailItem?
        get() = syncProgress?.currentFileName?.let { name -> items.firstOrNull { it.remotePath == name } }

    /** 当前 Tab 的列表。同步 Tab = 未同步（待上传 + 冲突，优先/正在同步置顶）；已同步 Tab = 已同步。 */
    val tabItems: List<SyncDetailItem>
        get() = when (tab) {
            SyncDetailTab.SYNCING -> items.filter { it.state != SyncItemState.SYNCED }
            SyncDetailTab.SYNCED -> items.filter { it.state == SyncItemState.SYNCED }
        }
}

/**
 * 同步情况详情 ViewModel（2026-10 重设计：双分页 + 逐文件暂停/优先）。
 *
 * 数据流：
 * - [WebDavSyncService.scanLocalMediaFiles] 本地扫描 × [WebDavEngine.listDirectory]
 *   PROPFIND 远端清单 → 逐文件对账（同名同尺寸=已同步 / 缺失=待上传 / 尺寸不同=冲突）；
 * - 同步执行仍走应用级运行器 [WebDavManualSync]（退出页面继续），进度中的
 *   currentFileName 与列表条目 remotePath 匹配 → 「正在同步」高亮与单项进度条；
 * - [SyncTaskControl] 提供逐文件暂停/恢复/优先，暂停对传输中的文件立即生效
 *   （进度回调内抛 WebDavSyncPausedSignal，.part 保留，恢复后断点续传）。
 */
class MediaBackupViewModel(
    application: Application,
    private val cloudSyncPreferences: CloudSyncPreferences,
) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _state = MutableStateFlow(SyncDetailUiState())
    val uiState: StateFlow<SyncDetailUiState> = _state.asStateFlow()

    private val engine = WebDavEngine(
        allowInsecureTls = { cloudSyncPreferences.allowInsecureTls }
    )

    /** 对账互斥：刷新与同步完成回调并发触发时防旧快照覆盖新快照。 */
    private val refreshMutex = Mutex()

    init {
        viewModelScope.launch { refresh() }
        reloadHistory()
        // 同步运行状态透传 + 完成后自动重新对账（上传/下载结束立即刷新两端状态）
        viewModelScope.launch {
            WebDavManualSync.state.collect { st ->
                _state.update { it.copy(syncRunning = st is WebDavManualSync.State.Syncing) }
                when (st) {
                    is WebDavManualSync.State.Syncing ->
                        _state.update { it.copy(syncProgress = st.progress) }
                    is WebDavManualSync.State.Succeeded -> {
                        val r = st.result
                        val note = buildString {
                            append("双向同步完成：上传 ${r.uploaded}，下载 ${r.downloaded}，一致跳过 ${r.skippedExisting}，冲突 ${r.conflicts}")
                            if (r.pausedCount > 0) append("，暂停 ${r.pausedCount} 个")
                        }
                        _state.update {
                            it.copy(
                                syncProgress = null,
                                message = FeedbackMessage(note, if (r.conflicts > 0) FeedbackKind.NEUTRAL else FeedbackKind.SUCCESS),
                            )
                        }
                        WebDavManualSync.consumeResult()
                        reloadHistory()
                        refresh()
                    }
                    is WebDavManualSync.State.Failed -> {
                        _state.update {
                            it.copy(
                                syncProgress = null,
                                message = FeedbackMessage("同步失败：${st.message}", FeedbackKind.ERROR)
                            )
                        }
                        WebDavManualSync.consumeResult()
                        reloadHistory()
                    }
                    is WebDavManualSync.State.Skipped -> {
                        // 互斥跳过：中性提示，不刷新对账（本轮什么都没发生）
                        _state.update {
                            it.copy(
                                syncProgress = null,
                                message = FeedbackMessage(st.message, FeedbackKind.NEUTRAL)
                            )
                        }
                        WebDavManualSync.consumeResult()
                    }
                    WebDavManualSync.State.Idle -> Unit
                }
            }
        }
        // 暂停/优先集合镜像（SyncTaskControl 单例 → UI 状态）
        viewModelScope.launch {
            SyncTaskControl.paused.collect { set -> _state.update { it.copy(pausedSet = set) } }
        }
        viewModelScope.launch {
            SyncTaskControl.prioritized.collect { set -> _state.update { it.copy(prioritizedSet = set) } }
        }
        // 实时账面（2026-10）：本轮完成的文件立即把条目翻成「已同步」并刷新计数——
        // 不必等整轮结束再 PROPFIND 重对账，Tab 计数随传输实时变化
        viewModelScope.launch {
            SyncTaskControl.completed.collect { done ->
                if (done.isEmpty()) return@collect
                _state.update { st ->
                    st.copy(items = st.items.map { item ->
                        if (item.state != SyncItemState.SYNCED &&
                            (item.remotePath in done || item.file.name in done)
                        ) item.copy(state = SyncItemState.SYNCED) else item
                    })
                }
            }
        }
    }

    // ================= 上传排序偏好（小文件优先） =================

    /** 小文件优先（持久化）：改变对本轮队列不生效，下一轮同步按新排序构建。 */
    val smallFirst: StateFlow<Boolean> get() = _smallFirst
    private val _smallFirst = MutableStateFlow(cloudSyncPreferences.syncSmallFirst)

    fun setSmallFirst(enabled: Boolean) {
        cloudSyncPreferences.syncSmallFirst = enabled
        _smallFirst.value = enabled
    }

    fun setTab(tab: SyncDetailTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 手动刷新：重新扫描本地 + PROPFIND 远端对账。 */
    fun refresh() {
        viewModelScope.launch { doRefresh() }
    }

    /** 重新读取同步历史与上次同步时间（同步结束/页面初始化时调用）。 */
    private fun reloadHistory() {
        _state.update {
            it.copy(
                history = cloudSyncPreferences.syncHistory(),
                lastSyncTime = cloudSyncPreferences.lastSyncTime,
            )
        }
    }

    /** 触发一轮双向同步（应用级运行器，退出页面继续）。 */
    fun syncNow() {
        if (_state.value.syncRunning) return
        WebDavManualSync.sync(context)
    }

    // ================= 逐文件控制（SyncTaskControl 透传） =================

    fun pauseItem(item: SyncDetailItem) = SyncTaskControl.pause(item.remotePath)

    fun resumeItem(item: SyncDetailItem) = SyncTaskControl.resume(item.remotePath)

    /** 优先同步：插队 + 置顶；若该文件处于暂停则同时恢复（优先即要传）。 */
    fun prioritizeItem(item: SyncDetailItem) {
        SyncTaskControl.prioritize(item.remotePath)
        if (SyncTaskControl.isPaused(item.remotePath)) SyncTaskControl.resume(item.remotePath)
    }

    private suspend fun doRefresh() = refreshMutex.withLock {
        _state.update { it.copy(loading = true, error = null) }
        withContext(Dispatchers.IO) {
            val account = cloudSyncPreferences.currentAccount()
            val providerName = providerDisplayName(cloudSyncPreferences.providerId)
            if (!account.isComplete) {
                _state.update {
                    it.copy(
                        loading = false,
                        providerName = providerName,
                        serverUrl = account.serverUrl,
                        configured = false,
                        verified = account.verified,
                        error = "当前网盘尚未配置完成，请先到「WebDAV 同步」填写并测试连接",
                        items = emptyList(),
                    )
                }
                return@withContext
            }
            val credential = WebDavCredential(
                serverUrl = account.serverUrl,
                username = account.username,
                password = account.password,
                remotePath = account.remotePath.ifBlank { "Edqiu" },
            )
            // 远端清单（PROPFIND Depth:1，忽略 .edqiu-part 暂存）
            val remoteFiles = engine.listDirectory(
                WebDavSyncService.rootUrlOf(engine, credential), credential
            ).getOrElse { error ->
                Log.w(TAG, "PROPFIND 对账失败：${error.message}")
                _state.update {
                    it.copy(
                        loading = false,
                        providerName = providerName,
                        serverUrl = account.serverUrl,
                        configured = true,
                        verified = account.verified,
                        error = error.message ?: "远端对账失败",
                    )
                }
                return@withContext
            }.filter { !it.isDir && !it.name.endsWith(WebDavEngine.PART_SUFFIX) }
                .associateBy { it.name }

            val localEntries = WebDavSyncService.scanLocalMediaFiles(context)
            val prioritizedNow = SyncTaskControl.prioritized.value
            val items = localEntries.map { (file, remotePath) ->
                // 双名兼容（2026-10 修复）：现行平坦化名 miss 时按早期版本纯文件名兜底——
                // 网盘上历史存量（纯名）不再误判「未同步」
                val remote = remoteFiles[remotePath] ?: remoteFiles[file.name]
                val st = when {
                    remote == null -> SyncItemState.PENDING
                    remote.size == file.length() -> SyncItemState.SYNCED
                    else -> SyncItemState.CONFLICT
                }
                SyncDetailItem(
                    file = file,
                    remotePath = remotePath,
                    size = file.length(),
                    modifiedAt = file.lastModified(),
                    isVideo = file.extension.lowercase() in VIDEO_EXTS,
                    state = st,
                )
            }.sortedWith(
                // 优先置顶 → 待上传 → 冲突 → 已同步 → 新的在前
                compareBy<SyncDetailItem> { it.remotePath !in prioritizedNow }
                    .thenBy { it.state != SyncItemState.PENDING }
                    .thenBy { it.state != SyncItemState.CONFLICT }
                    .thenBy { it.state != SyncItemState.SYNCED }
                    .thenByDescending { it.modifiedAt }
            )
            _state.update {
                it.copy(
                    loading = false,
                    providerName = providerName,
                    serverUrl = account.serverUrl,
                    configured = true,
                    verified = account.verified,
                    items = items,
                    error = null,
                    smallFirst = cloudSyncPreferences.syncSmallFirst,
                    lastRefreshAt = System.currentTimeMillis(),
                    history = cloudSyncPreferences.syncHistory(),
                    lastSyncTime = cloudSyncPreferences.lastSyncTime,
                )
            }
            Log.i(TAG, "对账完成：本地 ${items.size} 个，已同步 ${items.count { it.state == SyncItemState.SYNCED }}，" +
                "待上传 ${items.count { it.state == SyncItemState.PENDING }}，冲突 ${items.count { it.state == SyncItemState.CONFLICT }}")
        }
    }

    private fun providerDisplayName(pid: String): String =
        webDavProviders.firstOrNull { it.id == pid }?.name ?: "自定义 WebDAV"

    companion object {
        private const val TAG = "SyncDetailVM"
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v")
    }
}
