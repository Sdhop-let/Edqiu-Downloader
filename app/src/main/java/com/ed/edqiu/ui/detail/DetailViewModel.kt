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

    val actionFeedback = MutableStateFlow<String?>(null)

    val monitorUri: StateFlow<String?> = settings.monitorDirUriFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun load(tweetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            linkMutable.value = repo.getByTweetId(tweetId)
        }
    }

    fun requestDownload() {
        val id = linkMutable.value?.tweetId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.requestDownload(id, manual = true)
            actionFeedback.value = when (result) {
                SavedLinkRepository.DownloadRequestResult.Launched ->
                    "已交给 Edqiu 下载器"
                SavedLinkRepository.DownloadRequestResult.Downloaded ->
                    "已下载到 Edqiu 下载中心"
                SavedLinkRepository.DownloadRequestResult.AlreadyDownloaded ->
                    "该文件已经下载"
                SavedLinkRepository.DownloadRequestResult.Missing ->
                    "记录不存在"
                is SavedLinkRepository.DownloadRequestResult.Failed ->
                    result.reason
            }
            linkMutable.value = repo.getByTweetId(id)
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val id = linkMutable.value?.tweetId ?: return@launch
            repo.refreshStatuses(monitorUri.value)
            repo.retryMissingMetadata()
            linkMutable.value = repo.getByTweetId(id)
        }
    }

    fun clearActionFeedback() {
        actionFeedback.value = null
    }
}
