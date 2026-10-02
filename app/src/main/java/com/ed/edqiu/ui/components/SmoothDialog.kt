package com.ed.edqiu.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 统一弹窗动画容器。
 *
 * Compose 的 Dialog / M3 AlertDialog 默认无进出场动画（直接硬切出现/消失），
 * 本组件补齐「缩放 + 淡入」入场与「缩小 + 淡出」退场，全应用弹窗统一观感：
 * - 入场：alpha 0→1 + scale 0.92→1（220ms FastOutSlowIn，"靠近"呼吸感）；
 * - 退场：点遮罩 / 返回键触发后先播 160ms 缩小淡出，动画结束才真正回调 [onDismissRequest]
 *   （宿主状态翻转 → 弹窗离开组合），用户看到的是完整过渡而非瞬间消失。
 *
 * 弹窗内容在 [content] 里自行组织布局；[SmoothAlertDialog] 是 M3 AlertDialog 语义的
 * 预制封装，绝大多数确认类弹窗应直接用它。
 */
@Composable
fun AnimatedDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.92f) }
    var dismissing by remember { mutableStateOf(false) }
    var dismissRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(dismissing) {
        if (dismissing) {
            scale.animateTo(0.94f, tween(160, easing = FastOutSlowInEasing))
            alpha.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
            if (!dismissRequested) {
                dismissRequested = true
                onDismissRequest()
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (!dismissing) {
                dismissing = true
            } else if (dismissRequested) {
                // 2026-10 P1 整改：退场动画已播完但调用方吞掉了 dismiss（如提交中弹窗
                // onDismissRequest 被 !submitting 拦截）——旧实现 dismissing 恒 true，
                // 再次按返回永远无效，用户面对全透明拦截触摸的弹窗只能杀进程。
                // 允许在退场完成后重复派发，由调用方决定何时真正关闭。
                onDismissRequest()
            }
        },
        properties = properties
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
        ) {
            content()
        }
    }
}

/**
 * M3 [androidx.compose.material3.AlertDialog] 的丝滑动画版：API 与 M3 对齐
 * （title/text/icon/confirmButton/dismissButton/containerColor/shape 等），
 * 内部走 [AnimatedDialog] 的缩放淡入淡出，调用点只需替换函数名与导入即可。
 */
@Composable
fun SmoothAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    iconContentColor: Color = MaterialTheme.colorScheme.secondary,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    shape: Shape = AlertDialogDefaults.shape,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    AnimatedDialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            modifier = modifier.widthIn(max = 560.dp),
            shape = shape,
            color = containerColor,
            tonalElevation = tonalElevation
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                icon?.let {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 16.dp)
                    ) {
                        CompositionLocalProvider(LocalContentColor provides iconContentColor) { it() }
                    }
                }
                title?.let {
                    Box(
                        Modifier
                            .padding(bottom = 14.dp)
                            .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally)
                    ) {
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            // M3 语义：title 统一 headlineSmall 字体样式，内容由调用方组合
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall) { it() }
                        }
                    }
                }
                text?.let {
                    Box(Modifier.padding(bottom = 20.dp)) {
                        CompositionLocalProvider(LocalContentColor provides textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                        }
                    }
                }
                Row(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/**
 * 操作结果反馈的统一弹窗（2026-09-28）：替代卡片内嵌的 InlineFeedbackBar 通知条。
 * 按 [FeedbackKind] 定标题（操作成功/操作失败/提示），正文为完整消息文本，
 * 单按钮「知道了」——用户此前明确偏好弹窗形式的操作结果反馈。
 */
@Composable
fun FeedbackDialog(
    message: FeedbackMessage?,
    onDismiss: () -> Unit
) {
    message ?: return
    SmoothAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (message.kind) {
                    FeedbackKind.SUCCESS -> "操作成功"
                    FeedbackKind.ERROR -> "操作失败"
                    FeedbackKind.NEUTRAL -> "提示"
                }
            )
        },
        text = { Text(message.text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}
