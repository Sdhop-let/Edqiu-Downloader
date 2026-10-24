package com.ed.edqiu.ui.navigation

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.ed.edqiu.ui.components.CapsuleFeedbackController
import com.ed.edqiu.ui.components.FeedbackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Snackbar 消息数据。
 *
 * 2026-10-24 起：**带 kind 的消息（含带操作按钮的「撤销」类）全部走底部玻璃胶囊**——
 * 胶囊已具备 action 渲染能力；无 kind 的调用仅作存量兜底走 M3 Snackbar。
 */
data class SnackbarMessage(
    val message: String,
    val actionLabel: String? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val onAction: (() -> Unit)? = null,
    val kind: FeedbackKind? = null
)

/**
 * 全局反馈控制器。
 * ViewModel 通过 [show] 发送消息，顶层 Scaffold 通过 [observe] 收集，
 * 按消息类型分流到胶囊（状态类）或 Snackbar（操作类）。
 */
class SnackbarController {
    private val channel = Channel<SnackbarMessage>(Channel.BUFFERED)
    val messages = channel.receiveAsFlow()

    fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onAction: (() -> Unit)? = null,
        kind: FeedbackKind? = null
    ) {
        channel.trySend(
            SnackbarMessage(message, actionLabel, duration, onAction, kind)
        )
    }

    /**
     * 在顶层 Scaffold 中调用，收集消息并按类型分流显示。
     *
     * 互斥规则（2026-09-15）：胶囊与 Snackbar 共存于屏幕底部同一区域，
     * 任一方出现前先收起另一方，保证同一时间只有一种底部提示，
     * 避免玻璃胶囊叠在 Snackbar 上方的视觉冲突。
     */
    fun observe(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        capsuleController: CapsuleFeedbackController
    ) {
        scope.launch {
            messages.collect { msg ->
                if (msg.kind != null) {
                    // 胶囊反馈（2026-10-24 起含带操作按钮的消息：「撤销」直接渲染在胶囊尾部）
                    snackbarHostState.currentSnackbarData?.dismiss()
                    capsuleController.show(
                        kind = msg.kind,
                        text = msg.message,
                        actionLabel = msg.actionLabel,
                        onAction = msg.onAction
                    )
                } else {
                    // 无 kind 兜底 → 传统 Snackbar（存量调用点未标注语义色时）
                    capsuleController.clear()
                    val result = snackbarHostState.showSnackbar(
                        message = msg.message,
                        actionLabel = msg.actionLabel,
                        duration = msg.duration
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        msg.onAction?.invoke()
                    }
                }
            }
        }
    }
}
