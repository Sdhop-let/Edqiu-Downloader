package com.ed.twitterdownloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.twitterdownloader.TwitterDownloaderApp
import com.ed.twitterdownloader.data.model.VideoInfo
import com.ed.twitterdownloader.data.preferences.ProxyPreferences
import com.ed.twitterdownloader.data.repository.DownloadRepository
import com.ed.twitterdownloader.data.repository.HistoryRepository
import com.ed.twitterdownloader.service.DirectoryScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val urlInput: String = "",
    val isResolving: Boolean = false,
    val videoInfo: VideoInfo? = null,
    val error: String? = null,
    val clipboardUrl: String? = null,
    val autoDownloadMessage: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TwitterDownloaderApp
    private val downloadRepository = DownloadRepository(application)
    private val historyRepository = HistoryRepository(application)
    private val proxyPreferences = ProxyPreferences(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val autoResolvingUrls = mutableSetOf<String>()

    /** Dynamic proxy URL - re-reads from preferences each time. */
    fun getProxyUrl(): String? = proxyPreferences.getProxySettings().toProxyUrl()

    /** Expose the app-level engine initialization state for UI observation. */
    val isEngineInitialized: StateFlow<Boolean> = app.isInitialized
    val engineInitError: StateFlow<String?> = app.initError

    companion object {
        /** Regex supporting both standard and /i/status/ Twitter/X URL formats. */
        private val TWITTER_URL_REGEX = Regex(
            "https?://(?:www\\.|mobile\\.)?(?:x|twitter)\\.com/(?:[A-Za-z0-9_]+/)?status/\\d+",
            RegexOption.IGNORE_CASE
        )

        /** Process-level flag: clipboard auto-download runs only once per app process. */
        @Volatile
        var clipboardAutoProcessed: Boolean = false
    }

    /** Retry yt-dlp engine initialization after a failure. */
    fun retryEngineInit() {
        app.retryInit()
    }

    fun updateUrl(url: String) {
        _uiState.update { it.copy(urlInput = url, error = null) }
    }

    fun isValidTwitterUrl(url: String): Boolean {
        return TWITTER_URL_REGEX.containsMatchIn(url)
    }

    fun resolveUrl() {
        val url = _uiState.value.urlInput.trim()
        if (!isValidTwitterUrl(url)) {
            _uiState.update { it.copy(error = "璇疯緭鍏ユ湁鏁堢殑鎺ㄦ枃閾炬帴") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isResolving = true, error = null, videoInfo = null) }

            downloadRepository.resolveVideoInfo(url, getProxyUrl())
                .onSuccess { info ->
                    if (isFullyDownloaded(info)) {
                        _uiState.update {
                            it.copy(
                                isResolving = false,
                                error = "璇ラ摼鎺ュ凡涓嬭浇杩囷紝璇峰嬁閲嶅涓嬭浇",
                                urlInput = ""
                            )
                        }
                        return@onSuccess
                    }
                    _uiState.update { it.copy(isResolving = false, videoInfo = info) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isResolving = false, error = e.message ?: "瑙ｆ瀽澶辫触")
                    }
                }
        }
    }

    fun clearVideoInfo() {
        _uiState.update { it.copy(videoInfo = null, urlInput = "") }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun detectClipboardUrl(text: String?) {
        if (text != null && isValidTwitterUrl(text)) {
            _uiState.update { it.copy(clipboardUrl = text) }
        } else {
            _uiState.update { it.copy(clipboardUrl = null) }
        }
    }

    fun useClipboardUrl() {
        _uiState.value.clipboardUrl?.let { url ->
            updateUrl(url)
            _uiState.update { it.copy(clipboardUrl = null) }
        }
    }

    fun dismissClipboardPrompt() {
        _uiState.update { it.copy(clipboardUrl = null) }
    }

    fun getDownloadRepository(): DownloadRepository = downloadRepository

    /**
     * Auto-download flow: resolve the URL and then start downloading with the best format.
     * Called when clipboard auto-detection triggers.
     *
     * @param url           The Twitter/X URL detected from clipboard.
     * @param downloadVm   The DownloadViewModel instance used to trigger the download.
     */
    fun autoResolveAndDownload(url: String, downloadVm: DownloadViewModel) {
        val normalizedUrl = url.trim()
        if (!autoResolvingUrls.add(normalizedUrl)) {
            _uiState.update { it.copy(autoDownloadMessage = "璇ラ摼鎺ユ鍦ㄥ鐞嗕腑") }
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        urlInput = normalizedUrl,
                        isResolving = true,
                        error = null,
                        autoDownloadMessage = "姝ｅ湪鑷姩涓嬭浇妫€娴嬪埌鐨勬帹鏂囧獟浣?.."
                    )
                }

                downloadRepository.resolveVideoInfo(normalizedUrl, getProxyUrl())
                    .onSuccess { info ->
                        if (isFullyDownloaded(info)) {
                            _uiState.update {
                                it.copy(
                                    isResolving = false,
                                    autoDownloadMessage = "璇ラ摼鎺ュ凡涓嬭浇杩囷紝璺宠繃鑷姩涓嬭浇"
                                )
                            }
                            return@onSuccess
                        }

                        _uiState.update { it.copy(isResolving = false, videoInfo = info) }
                        downloadVm.startRuleBasedDownload(info)
                    }
                    .onFailure { e ->
                        _uiState.update {
                            it.copy(
                                isResolving = false,
                                error = e.message ?: "鑷姩瑙ｆ瀽澶辫触",
                                autoDownloadMessage = null
                            )
                        }
                    }
            } finally {
                autoResolvingUrls.remove(normalizedUrl)
            }
        }
    }

    fun clearAutoDownloadMessage() {
        _uiState.update { it.copy(autoDownloadMessage = null) }
    }

    private suspend fun isFullyDownloaded(info: VideoInfo): Boolean {
        return if (info.hasMultipleMediaItems) {
            info.formats.all { format ->
                format.mediaIndex?.let {
                    historyRepository.getByUrlAndMediaIndex(info.url, it) != null
                } == true
            }
        } else {
            historyRepository.getSingleByUrl(info.url) != null ||
                DirectoryScanner.isUrlAlreadyDownloaded(app, info.url)
        }
    }
}

