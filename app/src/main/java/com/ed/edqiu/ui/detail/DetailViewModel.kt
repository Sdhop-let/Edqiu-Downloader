package com.ed.edqiu.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(
    application: Application,
    private val repo: SavedLinkRepository,
    private val settings: SettingsRepository
) : AndroidViewModel(application) {

    private val linkMutable = MutableStateFlow<SavedLink?>(null)
    val link: StateFlow<SavedLink?> = linkMutable.asStateFlow()

    /** 下载结果弹窗：消息 + 成功/失败标志（true=成功 false=失败 null=中性）。 */
    data class DownloadFeedback(val message: String, val success: Boolean?)

    /** 下载进行中标志：true 时下载按钮转圈，给用户即时反馈。 */
    private val downloadingMutable = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = downloadingMutable

    private val downloadFeedbackMutable = MutableStateFlow<DownloadFeedback?>(null)
    val downloadFeedback: StateFlow<DownloadFeedback?> = downloadFeedbackMutable

    fun clearDownloadFeedback() {
        downloadFeedbackMutable.value = null
    }

    val monitorUri: StateFlow<String?> = settings.monitorDirUriFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun load(tweetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val link = repo.getByTweetId(tweetId)
            linkMutable.value = link
            // 作者信息缺失（保存时元数据抓取失败等）：进入详情页自动补抓一次并刷新
            if (link != null && (link.authorId == null || link.authorName == null || link.avatarUrl == null)) {
                if (repo.fetchMetadata(tweetId)) {
                    linkMutable.value = repo.getByTweetId(tweetId)
                }
            }
        }
    }

    fun requestDownload() {
        val id = linkMutable.value?.tweetId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // 先亮「下载中」即时反馈（下载耗时可能 10-60s，无中间反馈会误以为没反应）
            downloadingMutable.value = true
            val result = runCatching { repo.requestDownload(id, manual = true) }
                .getOrElse { error ->
                    SavedLinkRepository.DownloadRequestResult.Failed(
                        error.message ?: "下载异常",
                        nextRetryAt = null
                    )
                }
            downloadingMutable.value = false
            downloadFeedbackMutable.value = DownloadFeedback(
                message = downloadMessage(result),
                success = downloadSuccess(result)
            )
            linkMutable.value = repo.getByTweetId(id)
        }
    }

    private fun downloadMessage(result: SavedLinkRepository.DownloadRequestResult): String =
        when (result) {
            SavedLinkRepository.DownloadRequestResult.Launched ->
                "已交给 Edqiu 下载器"
            SavedLinkRepository.DownloadRequestResult.Downloaded ->
                "已下载到 Edqiu 下载中心"
            SavedLinkRepository.DownloadRequestResult.AlreadyDownloaded ->
                "该文件已经下载"
            SavedLinkRepository.DownloadRequestResult.Missing ->
                "记录不存在"
            SavedLinkRepository.DownloadRequestResult.Gone ->
                "推文不存在"
            is SavedLinkRepository.DownloadRequestResult.Failed ->
                result.reason
        }

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
            val id = linkMutable.value?.tweetId ?: return@launch
            repo.refreshStatuses(monitorUri.value)
            repo.importScannedDownloads(monitorUri.value)
            repo.retryMissingMetadata()
            linkMutable.value = repo.getByTweetId(id)
        }
    }
}
