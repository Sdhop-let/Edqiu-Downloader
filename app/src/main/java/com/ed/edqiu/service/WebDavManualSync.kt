package com.ed.edqiu.service

import android.content.Context
import com.ed.edqiu.EdqiuApplication
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「立即同步」的应用级运行器（2026-10）。
 *
 * 背景：手动同步此前挂在设置页的 rememberCoroutineScope 上——用户退出
 * WebDAV 同步页（返回/切 Tab）协程即被取消，上传中途静默终止且无任何提示。
 * 本对象把执行作用域提升到应用级（[EdqiuApplication.container.globalIoScope]，
 * 与 App 更新下载同款方案）：
 * - 退出页面同步继续，上传到哪算哪，不再半途而废；
 * - [state] 暴露进行中/结果状态：设置页前台时实时驱动按钮、进度条与结果反馈，
 *   页面不在前台完成时保留结果，下次进入该页补提示。
 * - 2026-10 统一 WebDAV 通道：Syncing 携带 [WebDavSyncProgress] 实时进度
 *   （第 i/N 个文件 + 单文件字节进度），驱动设置页进度条。
 */
object WebDavManualSync {

    /** 手动同步状态机：Idle ↔ Syncing → (Succeeded | Failed) → Idle（UI 消费后复位）。 */
    sealed interface State {
        data object Idle : State

        /** @property progress 实时进度快照；逐文件阶段前的准备动作（建目录等）为 null。 */
        data class Syncing(val progress: WebDavSyncProgress? = null) : State

        /** @property result 双向同步账面（上传/下载/跳过/冲突）。 */
        data class Succeeded(val result: WebDavSyncResult) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 防连点/重复入队：Syncing 期间再次调用直接忽略（UI 侧按钮同步禁用）。 */
    private val inFlight = AtomicBoolean(false)

    fun isSyncing(): Boolean = _state.value is State.Syncing

    /**
     * 发起一次手动同步（fire-and-forget，不挂调用方作用域）。
     * 读取持久化配置——调用方（设置页）需先把输入框当前值落盘。
     */
    fun sync(context: Context) {
        if (!inFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        _state.value = State.Syncing()
        val scope = (appContext as? EdqiuApplication)?.container?.globalIoScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val prefs = CloudSyncPreferences(appContext)
            val result = WebDavSyncService.syncDownloads(appContext, prefs) { progress ->
                _state.value = State.Syncing(progress)
            }
            result.fold(
                onSuccess = { syncResult ->
                    if (syncResult.hasChanges) {
                        prefs.syncedFileCount = prefs.syncedFileCount + syncResult.uploaded
                        prefs.lastSyncTime = System.currentTimeMillis()
                    }
                    _state.value = State.Succeeded(syncResult)
                },
                onFailure = { error ->
                    _state.value = State.Failed(error.message ?: "未知错误")
                },
            )
            inFlight.set(false)
        }
    }

    /** UI 展示过结果后复位到 Idle（Syncing 不可复位，避免按钮状态错乱）。 */
    fun consumeResult() {
        if (_state.value !is State.Syncing) _state.value = State.Idle
    }
}
