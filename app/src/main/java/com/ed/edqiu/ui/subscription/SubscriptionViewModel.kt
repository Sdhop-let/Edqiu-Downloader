package com.ed.edqiu.ui.subscription

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.database.SubscriptionEntity
import com.ed.edqiu.service.SubscriptionManager
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.FeedbackMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            val count = withContext(Dispatchers.IO) { SubscriptionManager.pollNow(context) }
            _state.update {
                it.copy(
                    detecting = false,
                    message = FeedbackMessage(
                        if (count > 0) "检测完成：发现 $count 个新作品，已加入收件箱并自动下载"
                        else "检测完成：暂无新作品",
                        if (count > 0) FeedbackKind.SUCCESS else FeedbackKind.NEUTRAL
                    )
                )
            }
        }
    }

    /** 检测单个订阅。 */
    fun detectOne(sub: SubscriptionEntity) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { SubscriptionManager.pollOne(context, sub) }
            _state.update {
                it.copy(
                    message = FeedbackMessage(
                        if (count > 0) "@${sub.screenName}：发现 $count 个新作品，已加入收件箱"
                        else "@${sub.screenName}：暂无新作品",
                        if (count > 0) FeedbackKind.SUCCESS else FeedbackKind.NEUTRAL
                    )
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
