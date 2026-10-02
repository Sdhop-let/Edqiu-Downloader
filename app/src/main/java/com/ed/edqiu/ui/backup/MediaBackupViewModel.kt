package com.ed.edqiu.ui.backup

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.backup.BackupFiles
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.FeedbackMessage
import com.ed.edqiu.backup.BackupScope
import com.ed.edqiu.backup.data.BackupLedgerRepository
import com.ed.edqiu.backup.data.BackupTaskStore
import com.ed.edqiu.backup.engine.BackupEngine
import com.ed.edqiu.backup.model.BackupTask
import com.ed.edqiu.backup.model.BackupTaskStatus
import com.ed.edqiu.backup.model.ProviderId
import com.ed.edqiu.backup.provider.ProviderRegistry
import com.ed.edqiu.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 单个媒体文件的上传状态（备份显示通道核心状态机）。 */
enum class MediaUploadState {
    /** 已上传（账本 DONE 记录 / 任务 DONE）。 */
    UPLOADED,
    /** 尚未上传（本地有文件，云端无记录）。 */
    NOT_UPLOADED,
    /** 排队中（队列 PENDING，尚未轮到上传；含失败退避重试等待期）。 */
    QUEUED,
    /** 上传中（正在传输，进度实时推进）。 */
    UPLOADING,
    /** 上传失败（可重试）。 */
    FAILED,
}

/** 备份状态页的单个条目。 */
data class MediaBackupItem(
    val file: File,
    val remotePath: String,
    val size: Long,
    val modifiedAt: Long,
    val isVideo: Boolean,
    val state: MediaUploadState,
    val taskId: String,
    val errorMessage: String? = null,
    val progress: Float = 0f,
)

/** 备份显示通道聚合 UI 状态。 */
data class MediaBackupUiState(
    val providerId: String? = null,
    val providerName: String? = null,
    val providerConfigured: Boolean = false,
    val items: List<MediaBackupItem> = emptyList(),
    val filter: BackupScope = BackupScope.ALL,
    val running: Boolean = false,
    val message: FeedbackMessage? = null,
    /** 本次会话内刚完成上传、待播放「左滑飞出」动画的条目（remotePath）。 */
    val justCompleted: Set<String> = emptySet(),
    /** 已完成飞出动画、从列表隐藏的条目（remotePath）。 */
    val dismissed: Set<String> = emptySet(),
) {
    /**
     * 当前 Tab 的全量条目（含已飞出隐藏的）——统计口径。
     * 计数跟随 Tab 过滤（与用户所见的列表范围一致，四个数之和 = Tab 内文件总数），
     * 但不随飞出隐藏而减少：飞出只腾列表位置，账面数字保持真实。
     */
    val scopeItems: List<MediaBackupItem>
        get() = when (filter) {
            BackupScope.ALL -> items
            BackupScope.VIDEO -> items.filter { it.isVideo }
            BackupScope.IMAGE -> items.filter { !it.isVideo }
        }

    val uploadedCount: Int get() = scopeItems.count { it.state == MediaUploadState.UPLOADED }
    val notUploadedCount: Int get() = scopeItems.count { it.state == MediaUploadState.NOT_UPLOADED }
    /** 上传中（含排队等待——队列进行中的总量）。 */
    val uploadingCount: Int
        get() = scopeItems.count {
            it.state == MediaUploadState.UPLOADING || it.state == MediaUploadState.QUEUED
        }
    val failedCount: Int get() = scopeItems.count { it.state == MediaUploadState.FAILED }

    /** 过滤后的展示列表（排除已飞出的完成条目，让未同步条目前移）。 */
    val filteredItems: List<MediaBackupItem>
        get() = scopeItems.filterNot { it.remotePath in dismissed }

    val canUploadAll: Boolean
        get() = filteredItems.any {
            it.state == MediaUploadState.NOT_UPLOADED || it.state == MediaUploadState.FAILED
        }
}

/**
 * 视频 / 图片备份显示通道 ViewModel。
 *
 * 职责：
 * - 扫描监控目录全部媒体文件，结合 [BackupTaskStore] 任务 + [BackupLedgerRepository] 账本
 *   判定每个文件的上传状态（已上传 / 未上传 / 上传中 / 失败）；
 * - 单个文件「上传到云盘」 / 「重试」 / 「全部上传」：复用 [BackupEngine] 串行队列，
 *   上传到备份中心当前选中网盘（[BackupSettings.selectedProvider]）；
 * - 任务流变化实时重建列表（上传进度 / 完成状态即时反映）。
 */
class MediaBackupViewModel(
    application: Application,
    private val registry: ProviderRegistry,
    private val engine: BackupEngine,
    private val taskStore: BackupTaskStore,
    private val ledgerRepository: BackupLedgerRepository,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _filter = MutableStateFlow(BackupScope.VIDEO)
    private val _message = MutableStateFlow<FeedbackMessage?>(null)
    private val _items = MutableStateFlow<List<MediaBackupItem>>(emptyList())
    private val _providerConfigured = MutableStateFlow(false)
    /** 本次会话内刚完成上传、待播放飞出动画的条目。 */
    private val _justCompleted = MutableStateFlow<Set<String>>(emptySet())
    /** 已飞出、从列表隐藏的条目。 */
    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    /** rebuildItems 串行化：collect 与 refresh() 并发触发时防止旧快照覆盖新快照。 */
    private val rebuildMutex = Mutex()

    private val engineState = engine.state
    private val tasksFlow = combine(engineState, taskStore.observe()) { es, stored ->
        // 引擎内存态（含实时进度）优先；空闲时用磁盘持久化任务（App 重启恢复）
        if (es.tasks.isNotEmpty()) es.tasks else stored
    }

    /** 引擎运行态 + 飞出动画簿记合并为一个流（uiState 的 combine 只有 5 槽位）。 */
    private data class UiEffects(val running: Boolean, val justCompleted: Set<String>, val dismissed: Set<String>)

    private val effects = combine(engineState, _justCompleted, _dismissed) { es, just, dismissed ->
        UiEffects(es.running, just, dismissed)
    }

    val uiState: StateFlow<MediaBackupUiState> = combine(
        _items,
        _filter,
        _message,
        _providerConfigured,
        effects,
    ) { items, filter, message, configured, eff ->
        val target = registry.get(ProviderId.WEBDAV)
        MediaBackupUiState(
            providerId = ProviderId.WEBDAV,
            providerName = target?.displayName ?: "WebDAV",
            providerConfigured = configured,
            items = items,
            filter = filter,
            running = eff.running,
            message = message,
            justCompleted = eff.justCompleted,
            dismissed = eff.dismissed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaBackupUiState())

    init {
        viewModelScope.launch {
            taskStore.load()
            tasksFlow
                // 2026-10 整改：上传期间每个进度 tick（bytesDone 变化）都会流到这里，旧实现
                // 每个 tick 都触发一次全目录重扫（扫描风暴）。改为只按「任务集 + 状态」签名
                // 去重 + 500ms 去抖，纯进度变化不再触发扫描；重建时取最新任务快照。
                .map { tasks -> tasks.joinToString("|") { "${it.taskId}:${it.status.name}" } }
                .distinctUntilChanged()
                .debounce(500)
                .collect {
                    rebuildItems(tasksFlow.first())
                }
        }
    }

    // ---------------- 展示 ----------------

    fun setFilter(scope: BackupScope) {
        _filter.value = scope
    }

    /** 手动刷新（扫描目录后重建列表）。 */
    fun refresh() {
        viewModelScope.launch {
            rebuildItems(tasksFlow.first())
        }
    }

    // ---------------- 上传操作 ----------------

    /** 单个文件「上传到云盘」（未上传 / 失败重试共用：enqueue 幂等覆盖为 PENDING）。 */
    fun uploadItem(item: MediaBackupItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val providerId = ensureProviderReady() ?: return@launch
            engine.enqueue(providerId, listOf(BackupFiles.BackupEntry(item.file, item.remotePath)))
            engine.runQueue(targetId = providerId).fold(
                onSuccess = { summary ->
                    postMessage("「${item.remotePath}」上传完成：成功 ${summary.succeeded}，失败 ${summary.failed}", FeedbackKind.SUCCESS)
                },
                onFailure = { error ->
                    postMessage("上传失败：${error.message ?: "未知错误"}", FeedbackKind.ERROR)
                },
            )
        }
    }

    /** 同步当前 Tab（视频/图片）下所有未上传 / 失败的文件到 WebDAV。 */
    fun uploadAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val providerId = ensureProviderReady() ?: return@launch
            val scope = _filter.value
            val targets = _items.value.filter { item ->
                (item.state == MediaUploadState.NOT_UPLOADED || item.state == MediaUploadState.FAILED) &&
                    when (scope) {
                        BackupScope.VIDEO -> item.isVideo
                        BackupScope.IMAGE -> !item.isVideo
                        BackupScope.ALL -> true
                    }
            }.map { BackupFiles.BackupEntry(it.file, it.remotePath) }
            if (targets.isEmpty()) {
                postMessage("没有需要上传的文件", FeedbackKind.NEUTRAL)
                return@launch
            }
            engine.enqueue(providerId, targets)
            engine.runQueue(targetId = providerId).fold(
                onSuccess = { summary ->
                    postMessage("同步完成：成功 ${summary.succeeded}，失败 ${summary.failed}，跳过 ${summary.skipped}", FeedbackKind.SUCCESS)
                },
                onFailure = { error ->
                    postMessage("同步失败：${error.message ?: "未知错误"}", FeedbackKind.ERROR)
                },
            )
        }
    }

    /** 消费一次性提示消息。 */
    fun consumeMessage() {
        _message.value = null
    }

    // ---------------- 内部 ----------------

    /** 校验 WebDAV 已配置；未配置时提示并返回 null（suspend：isConfigured 为挂起函数）。 */
    private suspend fun ensureProviderReady(): String? {
        val target = registry.get(ProviderId.WEBDAV)
        val configured = target != null && runCatching { target.isConfigured() }.getOrDefault(false)
        if (!configured) {
            postMessage("WebDAV 尚未配置，请先到「下载器设置 → WebDAV 同步」填写并测试连接", FeedbackKind.NEUTRAL)
            return null
        }
        return ProviderId.WEBDAV
    }

    /** 扫描目录 + 任务/账本比对，重建条目列表（IO 线程，目标固定 WebDAV）。 */
    private suspend fun rebuildItems(tasks: List<BackupTask>) = rebuildMutex.withLock {
        withContext(Dispatchers.IO) {
            val providerId = ProviderId.WEBDAV
            val target = registry.get(providerId)
            // 配置态在挂起上下文计算并缓存（isConfigured 为 suspend，不能在 combine 里直接调）
            _providerConfigured.value = target?.let { runCatching { it.isConfigured() }.getOrDefault(false) } ?: false
            val monitorUri = settingsRepository.monitorDirUriFlow.first()
            val files = BackupFiles.scanMediaFiles(context, monitorUri)
            val doneRemotePaths = runCatching { ledgerRepository.doneRemotePaths(providerId) }.getOrDefault(emptySet())

            val items = files.map { entry ->
                val file = entry.file
                val remotePath = entry.remotePath
                val taskId = BackupTask.computeId(providerId, remotePath)
                val task = tasks.find { it.targetId == providerId && it.taskId == taskId }
                val state = when {
                    // 任务未开始：账本已有 DONE 记录 → 已上传
                    task == null && doneRemotePaths.contains(remotePath) -> MediaUploadState.UPLOADED
                    task == null -> MediaUploadState.NOT_UPLOADED
                    task.status == BackupTaskStatus.DONE -> MediaUploadState.UPLOADED
                    task.status == BackupTaskStatus.UPLOADING -> MediaUploadState.UPLOADING
                    // PENDING 单列"排队中"：串行队列里只有 1 个真实上传中，
                    // 旧版把排队全算"上传中"导致统计失真
                    task.status == BackupTaskStatus.PENDING -> MediaUploadState.QUEUED
                    task.status == BackupTaskStatus.FAILED -> MediaUploadState.FAILED
                    else -> MediaUploadState.NOT_UPLOADED
                }
                MediaBackupItem(
                    file = file,
                    remotePath = remotePath,
                    size = file.length(),
                    modifiedAt = file.lastModified(),
                    isVideo = file.extension.lowercase() in VIDEO_EXTS,
                    state = state,
                    taskId = taskId,
                    errorMessage = task?.errorMessage,
                    progress = task?.progress ?: 0f,
                )
            }.sortedWith(
                compareByDescending<MediaBackupItem> { it.state == MediaUploadState.UPLOADING }
                    .thenByDescending { it.state == MediaUploadState.QUEUED }
                    .thenByDescending { it.modifiedAt }
            )

            // 飞出动画簿记：对比上一快照，非 UPLOADED → UPLOADED 的条目标记"刚完成"；
            // 首次重建（进入页面）不触发——历史已上传条目正常显示，不播动画也不隐藏
            val prevStates = _items.value.associate { it.remotePath to it.state }
            val just = _justCompleted.value.toMutableSet()
            val names = items.mapTo(mutableSetOf()) { it.remotePath }
            items.forEach { item ->
                val prev = prevStates[item.remotePath]
                if (item.state == MediaUploadState.UPLOADED && prev != null && prev != MediaUploadState.UPLOADED) {
                    just += item.remotePath
                }
            }
            just.retainAll(names)
            _justCompleted.value = just
            _dismissed.value = _dismissed.value intersect names

            _items.value = items
            Log.i(TAG, "rebuildItems() 扫描 ${files.size} 个文件，provider=$providerId")
        }
    }

    /**
     * 完成条目飞出动画结束的回执：从列表隐藏该条目（腾位给未同步条目）。
     * 账面计数不受影响（scopeItems 不排除 dismissed）。
     */
    fun dismissCompleted(remotePath: String) {
        _dismissed.update { it + remotePath }
        _justCompleted.update { it - remotePath }
    }

    private fun postMessage(message: String, kind: FeedbackKind = FeedbackKind.SUCCESS) {
        _message.value = FeedbackMessage(message, kind)
    }

    companion object {
        private const val TAG = "MediaBackupViewModel"
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v")
    }
}
