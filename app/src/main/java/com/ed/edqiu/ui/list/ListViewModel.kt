package com.ed.edqiu.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.capture.LinkCaptureCoordinator
import com.ed.edqiu.clipboard.ClipboardCapture
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.DownloadTaskBus
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.domain.TweetIdExtractor
import com.ed.edqiu.predownload.PreDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ListViewModel(
    application: Application,
    private val repo: SavedLinkRepository,
    private val settings: SettingsRepository,
    private val captureCoordinator: LinkCaptureCoordinator,
    private val preDownloadManager: PreDownloadManager,
    // 应用级下载作用域（AppContainer.globalIoScope，SupervisorJob）：下载执行不随
    // Activity/ViewModel 销毁中断——用户退后台后只要不划掉 App，下载持续进行。
    // 下载期间的 UI 状态写 StateFlow（线程安全），页面回来即可恢复订阅。
    private val downloadScope: CoroutineScope
) : AndroidViewModel(application) {

    private val searchQueryMutable = MutableStateFlow("")
    val searchQuery: StateFlow<String> = searchQueryMutable

    private val sortOrderMutable = MutableStateFlow(LinkSortOrder.NEWEST)
    val sortOrder: StateFlow<LinkSortOrder> = sortOrderMutable

    private val selectedIdsMutable = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = selectedIdsMutable

    private val selectionModeMutable = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = selectionModeMutable

    val links: StateFlow<List<SavedLink>> = combine(
        repo.observeAll(),
        searchQueryMutable,
        sortOrderMutable
    ) { links, query, sortOrder ->
        LinkListOrganizer.organize(links, query, sortOrder)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 收件箱实时下载进度（2026-09-15）：tweetId → 0..99 百分比。
     * 数据源 = DownloadTaskBus 内存任务总线（双引擎 300ms 节流回写），
     * 卡片据此显示「下载中 xx%」+ 进度条；进程结束下载任务随之消失，无需持久化。
     */
    val downloadProgress: StateFlow<Map<String, Int>> = DownloadTaskBus.tasks
        .map { tasks -> InboxDownloadProgress.progressByTweet(tasks) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val pendingCount: StateFlow<Int> = repo.countPending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val monitorUri: StateFlow<String?> = settings.monitorDirUriFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val autoCapture: StateFlow<Boolean> = settings.autoCaptureFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoRetry: StateFlow<Boolean> = settings.autoRetryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val captureFeedback = MutableStateFlow<CaptureFeedback?>(null)
    val actionFeedback = MutableStateFlow<ActionFeedback?>(null)
    private val lastDeletedArchiveIds = MutableStateFlow<List<String>>(emptyList())

    // 2026-09-15：原 downloading 布尔标志已删除——单条下载改走统一的进度胶囊
    // （batchDownloadState），携带实时剩余条数与当前条百分比，信息量更足。

    data class ActionFeedback(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
        // 下载类操作结果以弹窗形式展示成功/失败，其余仍走底部 Snackbar
        val asDialog: Boolean = false,
        // 弹窗标题用：true=下载成功 / false=下载失败 / null=中性（如"已交给下载器"）
        val success: Boolean? = null
    )

    fun captureFromClipboard(showNoopFeedback: Boolean = false) {
        val ctx = getApplication<Application>()
        val text = ClipboardCapture.readLatest(ctx)
        if (text.isNullOrBlank()) {
            if (showNoopFeedback) captureFeedback.value = CaptureFeedback.Empty
            return
        }
        if (!TweetIdExtractor.isTwitterUrl(text)) {
            if (showNoopFeedback) captureFeedback.value = CaptureFeedback.NotTwitter
            return
        }
        launchCapture(text, showDuplicateFeedback = showNoopFeedback)
    }

    fun captureText(input: String) {
        val text = input.trim()
        if (!TweetIdExtractor.isTwitterUrl(text)) {
            captureFeedback.value = CaptureFeedback.NotTwitter
            return
        }
        launchCapture(text)
    }

    private fun launchCapture(text: String, showDuplicateFeedback: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            captureFeedback.value = when (captureCoordinator.capture(text)) {
                is SavedLinkRepository.CaptureResult.Added -> CaptureFeedback.Added
                SavedLinkRepository.CaptureResult.Duplicate ->
                    if (showDuplicateFeedback) CaptureFeedback.Duplicate else null
                SavedLinkRepository.CaptureResult.Invalid ->
                    if (showDuplicateFeedback) CaptureFeedback.NotTwitter else null
            }
        }
    }

    fun requestDownload(tweetId: String) {
        // 下载执行放应用级 downloadScope：退后台/页面销毁不中断（不划掉 App 就继续下载）；
        // 进度走与批量统一的胶囊（剩余 N 条 + 当前条百分比），结果仍走弹窗反馈。
        val tracker = startProgressCapsule(listOf(tweetId))
        downloadScope.launch {
            val result = runCatching { repo.requestDownload(tweetId, manual = true) }
                .getOrElse { error ->
                    SavedLinkRepository.DownloadRequestResult.Failed(
                        error.message ?: "下载异常",
                        nextRetryAt = null
                    )
                }
            tracker.cancel()
            batchDownloadStateMutable.value = null
            actionFeedback.value = ActionFeedback(
                message = downloadMessage(result),
                asDialog = true,
                success = downloadSuccess(result)
            )
            scheduleRetry(tweetId, result)
        }
    }

    /** 批量/单条下载进度胶囊状态（2026-09-15 实时化）：remaining=实时待下载条数（随完成递减），currentProgress=当前条百分比。 */
    data class BatchDownloadUiState(
        val running: Boolean,
        val success: Boolean? = null,
        val message: String = "",
        val total: Int = 0,
        // 实时待下载数量：批内仍未变为 DOWNLOADED 的条数，随下载完成递减
        val remaining: Int = 0,
        // 当前活跃任务的实时百分比（0..99，串行下载同一时刻只有一条活跃）
        val currentProgress: Int? = null
    )

    private val batchDownloadStateMutable = MutableStateFlow<BatchDownloadUiState?>(null)
    val batchDownloadState: StateFlow<BatchDownloadUiState?> = batchDownloadStateMutable

    fun dismissBatchDownloadState() {
        batchDownloadStateMutable.value = null
    }

    /**
     * 下载期间实时跟踪进度（2026-09-15 实时化）：
     * - 实时待下载数量 = 批内 DB 状态仍非 DOWNLOADED 的条数（requestDownload 每条完成
     *   即落库 DOWNLOADED，Room Flow 实时发射）；
     * - 当前条百分比 = DownloadTaskBus 活跃 DOWNLOADING 任务（双引擎 300ms 节流回写）。
     * 在 [downloadScope]（应用级）收集：页面销毁不影响跟踪，回来恢复显示。
     */
    private fun trackDownloadProgress(
        batchIds: Set<String>,
        stateFlow: MutableStateFlow<BatchDownloadUiState?>
    ): Job = downloadScope.launch {
        combine(repo.observeAll(), DownloadTaskBus.tasks) { links, tasks ->
            val remaining = links.count { it.tweetId in batchIds && it.status != LinkStatus.DOWNLOADED }
            val pct = InboxDownloadProgress.progressByTweet(tasks).values.maxOrNull()
            remaining to pct
        }.collect { (remaining, pct) ->
            stateFlow.update { state ->
                state?.takeIf { it.running }?.copy(remaining = remaining, currentProgress = pct)
            }
        }
    }

    /** 启动进度胶囊（单条/批量共用）+ 返回跟踪协程，下载结束后由调用方取消。 */
    private fun startProgressCapsule(batchIds: Collection<String>): Job {
        val ids = batchIds.toSet()
        batchDownloadStateMutable.value = BatchDownloadUiState(running = true, total = ids.size, remaining = ids.size)
        return trackDownloadProgress(ids, batchDownloadStateMutable)
    }

    fun downloadSelected() {
        val ids = selectedIdsMutable.value
        if (ids.isEmpty()) return
        // 下载执行放应用级 downloadScope：退后台不中断；胶囊显示实时剩余条数 + 当前条百分比
        val tracker = startProgressCapsule(ids)
        downloadScope.launch {
            // 直接走收件箱下载（DownloaderClient 双引擎回退），不排队不受后台预下载影响
            val result = repo.requestDownloads(ids)
            tracker.cancel()
            batchDownloadStateMutable.value = BatchDownloadUiState(
                running = false,
                success = result.launched > 0,
                message = "成功 ${result.launched} 条，失败 ${result.failed} 条，跳过 ${result.skipped} 条"
            )
            clearSelection()
            // 手动下载完成 → 联动网盘同步（无论预下载开关状态）
            if (result.launched > 0) preDownloadManager.syncAfterManualDownload()
        }
    }

    /**
     * 下载全部待处理（收件箱右上角「下载」按钮）：
     * 批量派发所有 PENDING，弹窗反馈结果；无待处理时也给出明确提示（避免"点了没反应"）。
     */
    fun downloadAllPending() {
        downloadScope.launch {
            val ids = repo.pendingTweetIds()
            if (ids.isEmpty()) {
                batchDownloadStateMutable.value = BatchDownloadUiState(
                    running = false,
                    success = false,
                    message = "没有待处理的链接"
                )
                return@launch
            }
            val tracker = startProgressCapsule(ids)
            val result = repo.requestDownloads(ids)
            tracker.cancel()
            batchDownloadStateMutable.value = BatchDownloadUiState(
                running = false,
                success = result.launched > 0,
                message = "成功 ${result.launched} 条，失败 ${result.failed} 条，跳过 ${result.skipped} 条"
            )
            if (result.launched > 0) preDownloadManager.syncAfterManualDownload()
        }
    }

    /** 预下载提示：批量派发指定 tweetId 的下载请求（直接走收件箱下载，应用级 scope 后台不中断）。 */
    fun downloadPending(tweetIds: Collection<String>) {
        if (tweetIds.isEmpty()) return
        downloadScope.launch {
            val result = repo.requestDownloads(tweetIds)
            // 手动批量下载完成 → 联动网盘同步
            if (result.launched > 0) preDownloadManager.syncAfterManualDownload()
        }
    }

    fun delete(tweetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.delete(tweetId)
            lastDeletedArchiveIds.value = result.archiveIds
            actionFeedback.value = ActionFeedback(
                message = "已删除 1 条记录",
                actionLabel = "撤销",
                onAction = ::undoDelete
            )
        }
    }

    fun deleteSelected() {
        val ids = selectedIdsMutable.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.deleteMany(ids)
            lastDeletedArchiveIds.value = result.archiveIds
            actionFeedback.value = ActionFeedback(
                message = "已删除 ${result.archived} 条记录",
                actionLabel = "撤销",
                onAction = ::undoDelete
            )
            clearSelection()
        }
    }

    fun undoDelete() {
        val archiveIds = lastDeletedArchiveIds.value
        if (archiveIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.undoDelete(archiveIds)
            lastDeletedArchiveIds.value = emptyList()
            actionFeedback.value = ActionFeedback("已恢复 ${result.restored} 条记录")
        }
    }

    private fun scheduleRetry(
        tweetId: String,
        result: SavedLinkRepository.DownloadRequestResult
    ) {
        val failed = result as? SavedLinkRepository.DownloadRequestResult.Failed ?: return
        val retryAt = failed.nextRetryAt ?: return
        // 重试同样挂应用级 scope：退后台后到期照常自动重试
        downloadScope.launch {
            delay((retryAt - System.currentTimeMillis()).coerceAtLeast(0L))
            if (!autoRetry.value) return@launch
            val retryResult = repo.requestDownload(tweetId)
            scheduleRetry(tweetId, retryResult)
        }
    }

    private fun downloadMessage(result: SavedLinkRepository.DownloadRequestResult): String =
        when (result) {
            SavedLinkRepository.DownloadRequestResult.Launched -> "已交给 Edqiu 下载器"
            SavedLinkRepository.DownloadRequestResult.Downloaded -> "已下载到 Edqiu 下载中心"
            SavedLinkRepository.DownloadRequestResult.AlreadyDownloaded -> "该文件已经下载"
            SavedLinkRepository.DownloadRequestResult.Missing -> "记录不存在"
            SavedLinkRepository.DownloadRequestResult.Gone -> "推文不存在"
            is SavedLinkRepository.DownloadRequestResult.Failed ->
                if (result.nextRetryAt != null) {
                    "${result.reason}，稍后自动重试"
                } else {
                    "${result.reason}，已停止自动重试"
                }
        }

    /** 下载结果弹窗标题用：成功 / 失败 / 中性（交给下载器、记录缺失等）。 */
    private fun downloadSuccess(result: SavedLinkRepository.DownloadRequestResult): Boolean? =
        when (result) {
            SavedLinkRepository.DownloadRequestResult.Downloaded,
            SavedLinkRepository.DownloadRequestResult.AlreadyDownloaded -> true
            SavedLinkRepository.DownloadRequestResult.Gone,
            is SavedLinkRepository.DownloadRequestResult.Failed -> false
            SavedLinkRepository.DownloadRequestResult.Launched,
            SavedLinkRepository.DownloadRequestResult.Missing -> null
        }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            if (autoRetry.value) repo.retryDueDownloads()
            repo.refreshStatuses(monitorUri.value)
            repo.retryMissingMetadata()
        }
    }

    fun setSearchQuery(query: String) {
        searchQueryMutable.value = query
    }

    fun setSortOrder(sortOrder: LinkSortOrder) {
        sortOrderMutable.value = sortOrder
    }

    fun toggleSelectionMode() {
        selectionModeMutable.value = !selectionModeMutable.value
        if (!selectionModeMutable.value) selectedIdsMutable.value = emptySet()
    }

    fun toggleSelected(tweetId: String) {
        selectedIdsMutable.value = selectedIdsMutable.value.toMutableSet().apply {
            if (!add(tweetId)) remove(tweetId)
        }
    }

    fun selectAll(tweetIds: Collection<String>) {
        selectedIdsMutable.value = tweetIds.toSet()
    }

    /** 全选/取消全选切换：已全部选中则清空选择，否则全选；不退出批量模式。 */
    fun toggleSelectAll(tweetIds: Collection<String>) {
        val ids = tweetIds.toSet()
        selectedIdsMutable.value = if (ids.isNotEmpty() && selectedIdsMutable.value.containsAll(ids)) {
            emptySet()
        } else {
            ids
        }
    }

    fun clearSelection() {
        selectedIdsMutable.value = emptySet()
        selectionModeMutable.value = false
    }

    fun clearFeedback() {
        captureFeedback.value = null
    }

    fun clearActionFeedback() {
        actionFeedback.value = null
    }

    enum class CaptureFeedback {
        Added, Duplicate, NotTwitter, Empty
    }
}
