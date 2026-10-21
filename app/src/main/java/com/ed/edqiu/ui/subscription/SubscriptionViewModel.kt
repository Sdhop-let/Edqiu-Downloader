package com.ed.edqiu.ui.subscription

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.database.SubscriptionEntity
import com.ed.edqiu.data.database.SubscriptionMessageEntity
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.service.SubscriptionManager
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.FeedbackMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 作品来源平台。 */
enum class SubPlatform(val key: String, val label: String) {
    TWITTER(SubscriptionEntity.PLATFORM_TWITTER, "X"),
    BLUESKY(SubscriptionEntity.PLATFORM_BLUESKY, "Bluesky"),
    INSTAGRAM(SubscriptionEntity.PLATFORM_INSTAGRAM, "Instagram"),
}

/** 订阅消息分区展示上限（与 DAO 查询 limit 对齐）。 */
private const val MESSAGE_LIMIT = 20

/** 「作品订阅」聚合 UI 状态。 */
data class SubscriptionUiState(
    /** 手动添加输入。 */
    val handleInput: String = "",
    val platform: SubPlatform = SubPlatform.TWITTER,
    val detecting: Boolean = false,
    val message: FeedbackMessage? = null,
)

/**
 * 作品订阅 ViewModel：订阅列表（Room Flow）+ 手动添加 + 立即检测 + 逐条开关/删除/检测。
 * 检测中枢 = [SubscriptionManager]（twitter=yt-dlp / bluesky=公共 AppView / instagram=yt-dlp 实验性）。
 */
class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()

    /** 订阅列表（Room 实时流：新增/开关/删除/检测时间变化自动刷新）。 */
    val subscriptions: StateFlow<List<SubscriptionEntity>> =
        SubscriptionManager.observe(context).stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    /**
     * 订阅消息（2026-10-17 事件收纳箱）：新作品入库 / 检测失败 / 失败消失 / 降级提示。
     * 后台轮询与手动检测的事件都写这里，页面常驻展示；read 标记供后续入口角标复用。
     */
    val messages: StateFlow<List<SubscriptionMessageEntity>> =
        AppDatabase.getInstance(context).subscriptionMessageDao()
            .observeRecent(MESSAGE_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 页面展示时调用：消息标记已读（当前分区即开即读；保留状态供后续入口角标）。 */
    fun markMessagesRead() {
        viewModelScope.launch {
            runCatching { AppDatabase.getInstance(context).subscriptionMessageDao().markAllRead() }
        }
    }

    /** 清空订阅消息。 */
    fun clearMessages() {
        viewModelScope.launch {
            runCatching { AppDatabase.getInstance(context).subscriptionMessageDao().clearAll() }
            _state.update {
                it.copy(message = FeedbackMessage("已清空订阅消息", FeedbackKind.NEUTRAL))
            }
        }
    }

    // ==================== 订阅作品（2026-10-17 选择性下载）====================

    private val savedLinkRepository: com.ed.edqiu.data.repository.SavedLinkRepository? =
        (getApplication<Application>() as? com.ed.edqiu.EdqiuApplication)?.container?.savedLinkRepository

    /**
     * 订阅线作品（origin=subscription，最新在前）——订阅消息区底部帖子列表，
     * UI/功能与收件箱一致（LinkCard：详情跳转 + 待处理/失败可直接下载）。
     */
    val subscriptionPosts: StateFlow<List<SavedLink>> =
        savedLinkRepository?.observeAll()
            ?.map { all ->
                all.filter { it.origin == SavedLink.ORIGIN_SUBSCRIPTION }
                    .sortedByDescending { it.savedAt }
            }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
            ?: MutableStateFlow(emptyList())

    /** 订阅线待下载新作品数（消息区顶部「N 个新作品」角标）。 */
    val pendingNewCount: StateFlow<Int> = subscriptionPosts
        .map { posts -> posts.count { it.status == LinkStatus.PENDING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 用户在订阅消息区点按下载——**用户线**（manual=true）：与收件箱手动下载同语义，
     * 失败照实记全局熔断（这是用户主动行为，享有原手动下载保护）。
     */
    fun downloadPost(tweetId: String) {
        val repo = savedLinkRepository ?: return
        viewModelScope.launch {
            val result = runCatching { repo.requestDownload(tweetId, manual = true) }.getOrNull()
            _state.update {
                it.copy(
                    message = when (result) {
                        is SavedLinkRepository.DownloadRequestResult.Downloaded,
                        SavedLinkRepository.DownloadRequestResult.AlreadyDownloaded ->
                            FeedbackMessage("下载完成", FeedbackKind.SUCCESS)
                        // 2026-10-21 去重：点下载前已比对媒体库（download_history 同推文文件），
                        // 命中则回写 DOWNLOADED 并跳过引擎链——不重复下载收件箱/媒体库已有作品
                        is SavedLinkRepository.DownloadRequestResult.AlreadyInLibrary ->
                            FeedbackMessage("该作品已在媒体库，已跳过重复下载", FeedbackKind.SUCCESS)
                        SavedLinkRepository.DownloadRequestResult.Launched ->
                            FeedbackMessage("已开始下载", FeedbackKind.SUCCESS)
                        is SavedLinkRepository.DownloadRequestResult.Failed ->
                            FeedbackMessage("下载失败：${result.reason}", FeedbackKind.ERROR)
                        else -> FeedbackMessage("下载失败：链接不存在", FeedbackKind.ERROR)
                    }
                )
            }
        }
    }

    /** 设置订阅起始检测日期（只捞该日期后发布的推文）。null = 不限。 */
    fun setStartFromAt(sub: SubscriptionEntity, at: Long?) {
        viewModelScope.launch {
            runCatching {
                SubscriptionManager.setStartFromAt(context, sub.platform, sub.screenName, at)
            }
            _state.update {
                it.copy(message = FeedbackMessage(
                    if (at == null) "已设为不限日期" else "已设为只检测该日期后的新作品",
                    FeedbackKind.NEUTRAL,
                ))
            }
        }
    }

    fun setHandleInput(value: String) {
        _state.update { it.copy(handleInput = value) }
    }

    fun setPlatform(platform: SubPlatform) {
        _state.update { it.copy(platform = platform) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 手动添加订阅。 */
    fun addSubscription() {
        val handle = _state.value.handleInput.trim().removePrefix("@").trim()
        if (handle.isBlank()) {
            _state.update { it.copy(message = FeedbackMessage("请输入作者 handle（不含 @）", FeedbackKind.ERROR)) }
            return
        }
        val platform = _state.value.platform.key
        viewModelScope.launch {
            val already = SubscriptionManager.isSubscribed(context, platform, handle)
            if (already) {
                _state.update {
                    it.copy(
                        handleInput = "",
                        message = FeedbackMessage("该作者已在订阅中", FeedbackKind.NEUTRAL)
                    )
                }
                return@launch
            }
            SubscriptionManager.toggle(context, platform, handle)
            _state.update {
                it.copy(
                    handleInput = "",
                    message = FeedbackMessage("已订阅 @$handle（${platformLabel(platform)}），后台将自动检测新作品", FeedbackKind.SUCCESS)
                )
            }
        }
    }

    /** 立即检测一轮（全部 enabled 订阅）。 */
    fun detectNow() {
        if (_state.value.detecting) return
        _state.update { it.copy(detecting = true) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { SubscriptionManager.pollNow(context) }
            _state.update {
                it.copy(
                    detecting = false,
                    message = when {
                        outcome.newCount > 0 -> FeedbackMessage(
                            "检测完成：发现 ${outcome.newCount} 个新作品，已加入订阅作品，点按即可下载",
                            FeedbackKind.SUCCESS
                        )
                        // 2026-10-17：失败必须说清具体原因（未走代理/代理超时/账号被限/接口轮换失败）
                        !outcome.failure.isNullOrBlank() -> FeedbackMessage(
                            "检测失败：${outcome.failure}",
                            FeedbackKind.ERROR
                        )
                        !outcome.softNote.isNullOrBlank() -> FeedbackMessage(
                            "${outcome.softNote}（本轮未发现新作品）",
                            FeedbackKind.NEUTRAL
                        )
                        else -> FeedbackMessage("检测完成：暂无新作品", FeedbackKind.NEUTRAL)
                    }
                )
            }
        }
    }

    /** 检测单个订阅。 */
    fun detectOne(sub: SubscriptionEntity) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { SubscriptionManager.pollOne(context, sub) }
            _state.update {
                it.copy(
                    message = when {
                        outcome.newCount > 0 -> FeedbackMessage(
                            "@${sub.screenName}：发现 ${outcome.newCount} 个新作品，已加入订阅作品",
                            FeedbackKind.SUCCESS
                        )
                        !outcome.failure.isNullOrBlank() -> FeedbackMessage(
                            "@${sub.screenName}：${outcome.failure}",
                            FeedbackKind.ERROR
                        )
                        !outcome.softNote.isNullOrBlank() -> FeedbackMessage(
                            "@${sub.screenName}：${outcome.softNote}",
                            FeedbackKind.NEUTRAL
                        )
                        else -> FeedbackMessage("@${sub.screenName}：暂无新作品", FeedbackKind.NEUTRAL)
                    }
                )
            }
        }
    }

    fun toggleSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            val subscribed = SubscriptionManager.toggle(context, sub.platform, sub.screenName)
            _state.update {
                it.copy(
                    message = FeedbackMessage(
                        if (subscribed) "已恢复订阅 @${sub.screenName}" else "已暂停订阅 @${sub.screenName}（检测轮询跳过）",
                        FeedbackKind.NEUTRAL
                    )
                )
            }
        }
    }

    fun removeSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            SubscriptionManager.remove(context, sub.platform, sub.screenName)
            _state.update { it.copy(message = FeedbackMessage("已删除订阅 @${sub.screenName}", FeedbackKind.NEUTRAL)) }
        }
    }

    private fun platformLabel(platform: String): String =
        SubPlatform.entries.firstOrNull { it.key == platform }?.label ?: platform
}
