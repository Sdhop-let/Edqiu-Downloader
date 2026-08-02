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
    private val captureCoordinator: LinkCaptureCoordinator
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

    data class ActionFeedback(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
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
                SavedLinkRepository.CaptureResult.Added -> CaptureFeedback.Added
                SavedLinkRepository.CaptureResult.Duplicate ->
                    if (showDuplicateFeedback) CaptureFeedback.Duplicate else null
                SavedLinkRepository.CaptureResult.Invalid ->
                    if (showDuplicateFeedback) CaptureFeedback.NotTwitter else null
            }
        }
    }

    fun requestDownload(tweetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.requestDownload(tweetId, manual = true)
            actionFeedback.value = ActionFeedback(downloadMessage(result))
            scheduleRetry(tweetId, result)
        }
    }

    fun downloadSelected() {
        val ids = selectedIdsMutable.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.requestDownloads(ids)
            actionFeedback.value = ActionFeedback(
                "已启动 ${result.launched} 条，失败 ${result.failed} 条，跳过 ${result.skipped} 条"
            )
            clearSelection()
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
            is SavedLinkRepository.DownloadRequestResult.Failed ->
                if (result.nextRetryAt != null) {
                    "${result.reason}，稍后自动重试"
                } else {
                    "${result.reason}，已停止自动重试"
                }
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
