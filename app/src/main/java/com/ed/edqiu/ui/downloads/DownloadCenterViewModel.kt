package com.ed.edqiu.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadCenterViewModel(
    private val repo: SavedLinkRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val busyMutable = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyMutable

    val state: StateFlow<DownloadCenterState> = combine(
        repo.observeAll(),
        settings.monitorDirUriFlow
    ) { links, monitorUri ->
        DownloadCenterState(
            monitorUri = monitorUri,
            total = links.size,
            downloaded = links.count { it.status == LinkStatus.DOWNLOADED },
            pending = links.count { it.status == LinkStatus.PENDING },
            failed = links.count { it.status == LinkStatus.FAILED },
            downloadedItems = links.filter { it.status == LinkStatus.DOWNLOADED },
            failedItems = links.filter { it.status == LinkStatus.FAILED }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadCenterState())

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            busyMutable.value = true
            runCatching {
                repo.refreshStatuses(state.value.monitorUri)
                repo.retryMissingMetadata(limit = 10)
            }
            busyMutable.value = false
        }
    }

    fun retry(tweetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            busyMutable.value = true
            runCatching { repo.requestDownload(tweetId, manual = true) }
            busyMutable.value = false
        }
    }
}

data class DownloadCenterState(
    val monitorUri: String? = null,
    val total: Int = 0,
    val downloaded: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val downloadedItems: List<SavedLink> = emptyList(),
    val failedItems: List<SavedLink> = emptyList()
)
