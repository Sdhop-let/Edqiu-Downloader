package com.ed.edqiu.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.capture.LinkCaptureCoordinator
import com.ed.edqiu.clipboard.ClipboardCapture
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.domain.TweetIdExtractor
import com.ed.edqiu.predownload.PreDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ListViewModel(
    application: Application,
    private val repo: SavedLinkRepository,
    private val settings: SettingsRepository,
    private val captureCoordinator: LinkCaptureCoordinator,
    private val preDownloadManager: PreDownloadManager
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

    /** 单条下载进行中标志：true 期间列表页给出「正在下载」即时反馈，避免用户以为没反应。 */
    private val downloadingMutable = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = downloadingMutable

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
        viewModelScope.launch(Dispatchers.IO) {
            // 先亮「下载中」即时反馈，再执行（下载耗时可能 10-60s，无中间反馈会误以为没反应）
            downloadingMutable.value = true
            val result = runCatching { repo.requestDownload(tweetId, manual = true) }
                .getOrElse { error ->
                    SavedLinkRepository.DownloadRequestResult.Failed(
                        error.message ?: "下载异常",
                        nextRetryAt = null
                    )
                }
            downloadingMutable.value = false
            actionFeedback.value = ActionFeedback(
                message = downloadMessage(result),
                asDialog = true,
                success = downloadSuccess(result)
            )
            scheduleRetry(tweetId, result)
        }
    }

    /** 批量下载弹窗状态：转圈（running）→ 打勾（success=true）/ 打叉（success=false）。 */
    data class BatchDownloadUiState(
        val running: Boolean,
        val success: Boolean? = null,
        val message: String = ""
    )

    private val batchDownloadStateMutable = MutableStateFlow<BatchDownloadUiState?>(null)
    val batchDownloadState: StateFlow<BatchDownloadUiState?> = batchDownloadStateMutable

    fun dismissBatchDownloadState() {
        batchDownloadStateMutable.value = null
    }

    fun downloadSelected() {
        val ids = selectedIdsMutable.value
        if (ids.isEmpty()) return
        batchDownloadStateMutable.value = BatchDownloadUiState(running = true)
        viewModelScope.launch(Dispatchers.IO) {
            // 直接走收件箱下载（DownloaderClient 双引擎回退），不排队不受后台预下载影响
            val result = repo.requestDownloads(ids)
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
        viewModelScope.launch(Dispatchers.IO) {
            val ids = repo.pendingTweetIds()
            if (ids.isEmpty()) {
                batchDownloadStateMutable.value = BatchDownloadUiState(
                    running = false,
                    success = false,
                    message = "没有待处理的链接"
                )
                return@launch
            }
            batchDownloadStateMutable.value = BatchDownloadUiState(running = true)
            val result = repo.requestDownloads(ids)
            batchDownloadStateMutable.value = BatchDownloadUiState(
                running = false,
                success = result.launched > 0,
                message = "成功 ${result.launched} 条，失败 ${result.failed} 条，跳过 ${result.skipped} 条"
            )
            if (result.launched > 0) preDownloadManager.syncAfterManualDownload()
        }
    }

    /** 预下载提示：批量派发指定 tweetId 的下载请求（直接走收件箱下载）。 */
    fun downloadPending(tweetIds: Collection<String>) {
        if (tweetIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch {
            delay((retryAt - System.currentTimeMillis()).coerceAtLeast(0L))
            if (!autoRetry.value) return@launch
            val retryResult = withContext(Dispatchers.IO) {
                repo.requestDownload(tweetId)
            }
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
