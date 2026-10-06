package com.ed.edqiu.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.model.VideoFormat
import com.ed.edqiu.data.model.VideoInfo
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.repository.DownloadRepository
import com.ed.edqiu.data.repository.HistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadUiState(
    val tasks: List<DownloadTask> = emptyList(),
    val isDownloading: Boolean = false
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadRepository = DownloadRepository(application)
    private val historyRepository = HistoryRepository(application)
    private val proxyPreferences = ProxyPreferences(application)

    /** Current proxy URL derived from ProxyPreferences settings. */
    fun getProxyUrl(): String? = proxyPreferences.getProxySettings().toProxyUrl()

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            downloadRepository.downloadTasks.collect { tasks ->
                _uiState.update {
                    it.copy(
                        tasks = tasks,
                        isDownloading = tasks.any { task -> task.isActive }
                    )
                }
            }
        }
    }

    fun startDownload(videoInfo: VideoInfo, format: VideoFormat) {
        val proxyUrl = getProxyUrl()
        viewModelScope.launch {
            downloadRepository.startDownload(
                url = videoInfo.url,
                videoInfo = videoInfo,
                format = format,
                proxyUrl = proxyUrl
            ).onSuccess { filePath ->
                // Save to history with duration
                val task = downloadRepository.downloadTasks.value
                    .lastOrNull { it.url == videoInfo.url && it.formatId == format.formatId && it.mediaIndex == format.mediaIndex }
                task?.let {
                    // 2026-10-02 批次B：入库即带媒体显示宽高（比例动画前提）
                    val (mediaWidth, mediaHeight) = resolveMediaDimensions(format, videoInfo)
                    historyRepository.addToHistory(
                        it, filePath, videoInfo.duration,
                        mediaWidth = mediaWidth, mediaHeight = mediaHeight
                    )
                }
            }
        }
    }

    fun startDownloadAll(videoInfo: VideoInfo) {
        val proxyUrl = getProxyUrl()
        viewModelScope.launch {
            startDownloadsSequentially(videoInfo, videoInfo.formats, proxyUrl)
        }
    }

    /**
     * Rule-based download entry used by manual selection and clipboard auto-download.
     *
     * Rules:
     * 1. Same-link multi-video posts download every media item automatically unless the user explicitly chose one.
     * 2. Same-author sequel/series posts are downloaded one-by-one in paste/order sequence.
     * 3. Other posts fall back to the normal channel: selected format, best format, or all media items.
     */
    fun startRuleBasedDownload(videoInfo: VideoInfo, selectedFormat: VideoFormat? = null) {
        val proxyUrl = getProxyUrl()
        viewModelScope.launch {
            if (downloadRepository.downloadTasks.value.any { it.url == videoInfo.url && it.isActive }) {
                return@launch
            }
            val explicitSelection = selectedFormat != null
            val formats = when {
                videoInfo.hasMultipleMediaItems && !explicitSelection -> videoInfo.formats
                isSameAuthorContext(videoInfo) && looksLikeSeriesWork(videoInfo.title) -> listOfNotNull(selectedFormat ?: videoInfo.bestFormat)
                selectedFormat != null -> listOf(selectedFormat)
                videoInfo.hasMultipleMediaItems -> videoInfo.formats
                else -> listOfNotNull(videoInfo.bestFormat)
            }

            startDownloadsSequentially(videoInfo, formats, proxyUrl)
        }
    }

    private suspend fun startDownloadsSequentially(
        videoInfo: VideoInfo,
        formats: List<VideoFormat>,
        proxyUrl: String?
    ) {
        formats.forEach { format ->
            downloadRepository.startDownload(
                url = videoInfo.url,
                videoInfo = videoInfo,
                format = format,
                proxyUrl = proxyUrl
            ).onSuccess { filePath ->
                val task = downloadRepository.downloadTasks.value
                    .lastOrNull { it.url == videoInfo.url && it.formatId == format.formatId && it.mediaIndex == format.mediaIndex }
                task?.let {
                    // 2026-10-02 批次B：入库即带媒体显示宽高（比例动画前提）
                    val (mediaWidth, mediaHeight) = resolveMediaDimensions(format, videoInfo)
                    historyRepository.addToHistory(
                        it, filePath, videoInfo.duration,
                        mediaWidth = mediaWidth, mediaHeight = mediaHeight
                    )
                }
            }
        }
    }

    /**
     * 2026-10-02 批次B：入库宽高取值——与下载目标一致的媒体条目（format）优先，
     * 缺失时用 VideoInfo 主媒体兜底。本链路没有已取到的 TweetMeta（不为此追加网络请求），
     * 仍缺失则留 null，由 MediaDimensionsBackfillWorker 本地探测回填。
     */
    private fun resolveMediaDimensions(format: VideoFormat, videoInfo: VideoInfo): Pair<Int?, Int?> =
        (format.width ?: videoInfo.width) to (format.height ?: videoInfo.height)

    private suspend fun isSameAuthorContext(videoInfo: VideoInfo): Boolean {
        val currentAuthor = normalizeAuthor(videoInfo.uploader)
        val currentHandle = extractAuthorHandle(videoInfo.url)
        if (currentAuthor == null && currentHandle == null) return false

        val taskMatch = downloadRepository.downloadTasks.value.any { task ->
            task.url != videoInfo.url && authorsMatch(currentAuthor, currentHandle, task.uploader, task.url)
        }
        if (taskMatch) return true

        return historyRepository.allHistory.first().any { history ->
            history.url != videoInfo.url && authorsMatch(currentAuthor, currentHandle, history.uploader, history.url)
        }
    }

    private fun authorsMatch(
        currentAuthor: String?,
        currentHandle: String?,
        candidateUploader: String,
        candidateUrl: String
    ): Boolean {
        val candidateAuthor = normalizeAuthor(candidateUploader)
        val candidateHandle = extractAuthorHandle(candidateUrl)
        return (currentAuthor != null && currentAuthor == candidateAuthor) ||
            (currentHandle != null && currentHandle == candidateHandle)
    }

    private fun normalizeAuthor(author: String): String? =
        author.trim().removePrefix("@").lowercase().takeIf { it.isNotBlank() }

    private fun extractAuthorHandle(url: String): String? =
        TWITTER_AUTHOR_REGEX.find(url)?.groupValues?.getOrNull(1)?.lowercase()

    private fun looksLikeSeriesWork(title: String): Boolean {
        val normalized = title.lowercase()
        if (SERIES_KEYWORDS.any { normalized.contains(it) }) return true
        return SERIES_PATTERNS.any { it.containsMatchIn(title) }
    }

    fun cancelDownload(taskId: String) {
        downloadRepository.cancelDownload(taskId)
    }

    fun removeTask(taskId: String) {
        downloadRepository.removeTask(taskId)
    }

    fun clearCompleted() {
        downloadRepository.clearCompleted()
    }

    fun getActiveCount(): Int =
        _uiState.value.tasks.count { it.isActive }

    companion object {
        private val TWITTER_AUTHOR_REGEX = Regex(
            "https?://(?:x|twitter)\\.com/([^/]+)/status/\\d+",
            RegexOption.IGNORE_CASE
        )
        private val SERIES_KEYWORDS = listOf(
            "续集", "后续", "下集", "上集", "系列", "合集", "番外", "第二部", "第三部",
            "part", "episode", "chapter"
        )
        private val SERIES_PATTERNS = listOf(
            Regex("第\\s*[0-9一二三四五六七八九十百]+\\s*(集|部|章|篇|话)"),
            Regex("(?i)\\b(?:part|pt|ep|episode|chapter)\\s*[0-9一二三四五六七八九十百]+\\b"),
            Regex("(?i)\\b[pP][0-9]{1,3}\\b")
        )    }
}

