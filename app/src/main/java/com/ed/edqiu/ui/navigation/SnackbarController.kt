package com.ed.edqiu.ui.navigation

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Snackbar 消息数据。
 */
data class SnackbarMessage(
    val message: String,
    val actionLabel: String? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val onAction: (() -> Unit)? = null
)

/**
 * 全局 Snackbar 控制器。
 * ViewModel 通过 [show] 发送消息，顶层 Scaffold 通过 [observe] 收集并显示。
 */
class SnackbarController {
    private val channel = Channel<SnackbarMessage>(Channel.BUFFERED)
    val messages = channel.receiveAsFlow()

    fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onAction: (() -> Unit)? = null
    ) {
        channel.trySend(
            SnackbarMessage(message, actionLabel, duration, onAction)
        )
    }

    /**
     * 在顶层 Scaffold 中调用，收集消息并显示 Snackbar。
     */
    fun observe(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState
    ) {
        scope.launch {
            messages.collect { msg ->
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