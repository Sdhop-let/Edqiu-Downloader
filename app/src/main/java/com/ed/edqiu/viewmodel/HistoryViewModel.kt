package com.ed.edqiu.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.preferences.HiddenHistoryPreferences
import com.ed.edqiu.data.repository.HistoryRepository
import com.ed.edqiu.service.DirectoryScanner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = HistoryRepository(application)
    private val hiddenHistoryPreferences = HiddenHistoryPreferences(application)

    val historyList: StateFlow<List<DownloadHistoryEntity>> =
        repository.allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val historyCount: StateFlow<Int> =
        repository.historyCount.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun deleteHistory(entity: DownloadHistoryEntity, deleteLocalFile: Boolean = false) {
        viewModelScope.launch {
            if (deleteLocalFile) {
                val deleted = deleteLocalMediaFiles(entity.filePath)
                if (deleted) {
                    hiddenHistoryPreferences.unhide(entity.filePath)
                } else {
                    hiddenHistoryPreferences.hide(entity.filePath)
                }
            } else {
                hiddenHistoryPreferences.hide(entity.filePath)
            }
            repository.delete(entity)
        }
    }

    fun scanLocalVideos(onComplete: (String) -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                DirectoryScanner.scanAndRebuildHistory(getApplication())
                // 2026-09-15：旧记录发布时间网络补拉（FXTwitter by tweetId，内部 10 分钟节流，
                // 每轮最多 10 条）——存量旧文件 sidecar 无发布时间，必须联网补齐排序才归位
                runCatching {
                    com.ed.edqiu.service.PublishedAtBackfiller.backfillAfterScan(getApplication())
                }
            }
            onComplete("宸叉绱㈠綋鍓嶅瓨鍌ㄧ洰褰曚笅鐨勮棰戝拰鍥剧墖鏂囦欢")
        }
    }

    /** 进入媒体库时触发一轮发布时间补拉（内部节流，与扫描通道独立）。 */
    fun backfillPublishedTimes() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    com.ed.edqiu.service.PublishedAtBackfiller.backfillAfterScan(getApplication())
                }
            }
        }
    }

    /** 进入媒体库时触发一轮画质升级检测（内部档位节流；慢网自动暂停）。 */
    fun upgradeMediaQuality() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    com.ed.edqiu.service.MediaQualityUpgrader.upgradeAfterScan(getApplication())
                }.onFailure { Log.w("HistoryViewModel", "quality upgrade failed", it) }
            }
        }
    }

    /** 进入媒体库时触发一轮 pHash 补算（内部 10 分钟节流，每轮 ≤10 条；2026-09-15 批次3）。 */
    fun backfillPhashes() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    com.ed.edqiu.service.PhashService.backfillBatch(getApplication())
                }.onFailure { Log.w("HistoryViewModel", "phash backfill failed", it) }
            }
        }
    }

    /**
     * 手动「升级」（媒体库按钮）：立即执行一轮发布时间补拉 + 画质升级（无视节流）。
     * @return 摘要消息（补齐条数 + 升级条数），供 UI 提示。
     */
    suspend fun manualUpgradeNow(): String {
        var backfilled = 0
        var upgraded = 0
        withContext(Dispatchers.IO) {
            runCatching { backfilled = com.ed.edqiu.service.PublishedAtBackfiller.backfillNow(getApplication()) }
                .onFailure { Log.w("HistoryViewModel", "manual backfill failed", it) }
            runCatching { upgraded = com.ed.edqiu.service.MediaQualityUpgrader.upgradeNow(getApplication()) }
                .onFailure { Log.w("HistoryViewModel", "manual quality upgrade failed", it) }
        }
        return "时间排序补齐 $backfilled 条 · 画质升级 $upgraded 条（检测范围见后续轮次）"
    }

    fun clearAllHistory() {
        viewModelScope.launch { repository.clearAll() }
    }

    private fun deleteLocalMediaFiles(filePath: String): Boolean {
        if (filePath.isBlank()) return true
        return runCatching {
            val mediaFile = File(filePath)
            val mediaDeleted = !mediaFile.exists() || mediaFile.delete()
            val metaFile = File("$filePath.meta.json")
            if (metaFile.exists()) metaFile.delete()
            mediaDeleted
        }.getOrDefault(false)
    }
}
