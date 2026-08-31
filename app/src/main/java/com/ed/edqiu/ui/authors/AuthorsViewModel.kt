package com.ed.edqiu.ui.authors

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「作者作品」页数据源。
 *
 * - 数据源：`SavedLinkRepository.observeAll()`，筛选 `authorId != null` 的全部条目
 *   （覆盖已下载、已捕获、解析失败但已知作者的所有素材，按用户拍板 15:57 决策）。
 * - 分组：按 `@handle` 小写归一化，避免大小写或带/不带 `@` 导致同一作者分散。
 * - 组内聚合：取**时间最近**的首个非空 `avatarUrl` / `authorName`（最新非空值替换策略）；
 *   作品数与最近时间用于排序。
 *
 * ## 自动刷新机制
 *
 * 页面在 ON_RESUME 时调用 [refresh]（首次进入、从二级页返回、后台切回前台都会触发）。
 * 刷新三连（与详情页/后台 Worker 同款通道，幂等可重复执行）：
 * 1. `refreshStatuses` —— 扫描监控目录，把磁盘上已下载但 DB 仍 PENDING 的记录更新为
 *    DOWNLOADED 并补全 filePath/downloadedAt；
 * 2. `importScannedDownloads` —— 把磁盘上有、收件箱未收录的已下载媒体按 tweetId 导入
 *    （**新内容检测**的核心，导入后 Room Flow 自动推送 UI 更新）；
 * 3. `retryMissingMetadata` —— 对作者信息（昵称/头像/文案）缺失的记录重新拉取。
 *
 * 合并/替换语义：
 * - 作品条目：按 tweetId 主键**合并去重**（Room 天然保证），新作品追加、旧作品保留
 *   其下载状态与文件路径，不删除不覆盖；
 * - 作者信息：**最新非空值替换**，组内按时间倒序取首个非空 authorName/avatarUrl，
 *   作者改名/换头像后页面展示始终跟随最新记录。
 */
data class AuthorSummary(
    /** 规范化后的 handle（去 `@`、小写），用于去重与跳转推特 URL。 */
    val handle: String,
    /** 显示用的原始 handle（保留大小写与 `@`），来自组内首条。 */
    val displayHandle: String,
    /** 作者昵称（可能为空，UI 兜底"未知作者"）。 */
    val name: String?,
    /** 头像 URL（可能为空，UI 显示首字母占位）。 */
    val avatarUrl: String?,
    /** 该作者全部 SavedLink 数（含已下载和已捕获）。 */
    val workCount: Int,
    /** 最近一条素材时间戳（优先 downloadedAt，其次 savedAt），用于排序。 */
    val recentAt: Long,
    /** 该作者全部 SavedLink，按时间倒序。 */
    val works: List<SavedLink>
)

class AuthorsViewModel(
    private val savedLinkRepository: SavedLinkRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /** 是否正在执行刷新（防重入，ON_RESUME 与 LaunchedEffect 可能同帧触发）。 */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 最近一次刷新完成时间，供 UI 展示/日志。 */
    private val _lastRefreshAt = MutableStateFlow<Long?>(null)
    val lastRefreshAt: StateFlow<Long?> = _lastRefreshAt.asStateFlow()

    val authors: StateFlow<List<AuthorSummary>> =
        savedLinkRepository.observeAll()
            .map { all -> all.groupByHandle().sortedByDescending { it.recentAt } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 主动刷新：扫描磁盘导入新内容 + 状态对齐 + 作者元数据补齐。
     * 幂等：refreshStatuses/importScannedDownloads/retryMissingMetadata 均按 tweetId
     * 去重，重复执行无副作用；执行中的并发调用会被 [refreshing] 挡掉。
     */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val monitorUri = settingsRepository.monitorDirUriFlow.first()
                savedLinkRepository.refreshStatuses(monitorUri)
                val imported = savedLinkRepository.importScannedDownloads(monitorUri)
                savedLinkRepository.retryMissingMetadata()
                _lastRefreshAt.value = System.currentTimeMillis()
                if (imported > 0) {
                    Log.i("AuthorsViewModel", "Refresh imported $imported new works")
                }
            } catch (e: Exception) {
                Log.w("AuthorsViewModel", "Refresh failed: ${e.message}")
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun List<SavedLink>.groupByHandle(): List<AuthorSummary> {
        return asSequence()
            .filter { !it.authorId.isNullOrBlank() }
            .groupBy { normalizeHandle(it.authorId) }
            .mapNotNull { (_, links) ->
                // 组内按时间倒序：最新记录优先，作为「最新非空值替换」的取值顺序
                val newestFirst = links.sortedByDescending { it.downloadedAt ?: it.savedAt }
                val displayHandle = newestFirst.first().authorId?.trim().orEmpty()
                if (displayHandle.isEmpty()) return@mapNotNull null
                AuthorSummary(
                    handle = normalizeHandle(displayHandle),
                    displayHandle = displayHandle,
                    name = newestFirst.firstOrNull { !it.authorName.isNullOrBlank() }?.authorName,
                    avatarUrl = newestFirst.firstOrNull { !it.avatarUrl.isNullOrBlank() }?.avatarUrl,
                    workCount = links.size,
                    recentAt = newestFirst.first().downloadedAt ?: newestFirst.first().savedAt,
                    works = newestFirst
                )
            }
            .toList()
    }

    private fun normalizeHandle(id: String?): String =
        id?.trim()?.removePrefix("@")?.lowercase().orEmpty()
}