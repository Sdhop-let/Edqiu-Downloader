package com.ed.edqiu.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.db.EdqiuDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 「我的」页统计数据（2026-10 按设计规范 §06：三列统计卡全部真实计数，无写死值）。
 *
 * 数据源（三库各取所需，只读查询不动 schema）：
 * - 已下载：AppDatabase.download_history COUNT；
 * - 占用空间：AppDatabase.download_history SUM(fileSize)；
 * - 收件箱：EdqiuDatabase.saved_links 非 DELETED 计数；
 * - 订阅账号/新消息：AppDatabase.subscriptions enabled 计数 + subscription_messages 未读；
 * - 回收站：EdqiuDatabase.deleted_link_history 计数。
 */
class MineStatsViewModel(application: Application) : AndroidViewModel(application) {

    data class Stats(
        val downloadedCount: Int = 0,
        val storageBytes: Long = 0L,
        val inboxCount: Int = 0,
        val trashCount: Int = 0,
        val subscriptionAccounts: Int = 0,
        val unreadMessages: Int = 0,
    )

    private val appDatabase = AppDatabase.getInstance(application)
    private val edqiuDatabase = EdqiuDatabase.getDatabase(application)

    private val downloadHistoryDao = appDatabase.downloadHistoryDao()
    private val savedLinkDao = edqiuDatabase.savedLinkDao()
    private val deletedLinkHistoryDao = edqiuDatabase.deletedLinkHistoryDao()
    private val subscriptionDao = appDatabase.subscriptionDao()
    private val subscriptionMessageDao = appDatabase.subscriptionMessageDao()

    // combine 内建重载最多 5 路——分两级组合（核心四计数 + 订阅未读）
    private val coreStats: StateFlow<Stats> = combine(
        downloadHistoryDao.getCount(),
        downloadHistoryDao.observeTotalSize(),
        savedLinkDao.observeInboxCount(),
        deletedLinkHistoryDao.count(),
        subscriptionDao.observeAll(),
    ) { downloaded, storageBytes, inbox, trash, subscriptions ->
        Stats(
            downloadedCount = downloaded,
            storageBytes = storageBytes,
            inboxCount = inbox,
            trashCount = trash,
            subscriptionAccounts = subscriptions.count { it.enabled },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Stats(),
    )

    val stats: StateFlow<Stats> = combine(
        coreStats,
        subscriptionMessageDao.observeUnreadCount(),
    ) { core, unread ->
        core.copy(unreadMessages = unread)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Stats(),
    )
}
