package com.ed.edqiu.ui.components

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
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
            // 2026-10-17：退场改并行单段——旧实现 scale 160ms 播完再淡出 160ms
            // （顺序双段共 320ms，中段肉眼可感的停顿=卡顿感）；现在缩放与淡出同一
            // tween 并行，180ms 一次完成，跟手且利落
            launch { scale.animateTo(0.96f, tween(180, easing = FastOutSlowInEasing)) }
            launch { alpha.animateTo(0f, tween(180, easing = FastOutSlowInEasing)) }
            delay(180)
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
        // 2026-10-17：关掉平台默认弹窗宽度钳制——它会把窗口先钳到 ~70% 屏宽，
        // 上层的 fillMaxWidth 比例全变成"钳后窗口的比例"。关掉后窗口=全屏约束，
        // SmoothAlertDialog 的 86% 比例才是真正的屏宽比例
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = properties.decorFitsSystemWindows,
        )
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
        // 2026-10-21 液态玻璃化：弹窗此前是 M3 Surface 实底（surfaceContainerHigh），
        // 与全局玻璃语言断裂——用户在暗背景上看到的是一块平涂色板。改为 GlassSurface
        // L3（最高实底档，保证正文可读）：四层白渐变 + 主色 tint + 内描边边缘光 + 顶光，
        // 与卡片/底栏同一套材质语言。
        // 真折射（drawBackdrop 采样 backdrop）在 Dialog 独立窗口里做不到——窗口之间
        // 无法共享 layerBackdrop，故此处走 GlassSurface 的程序化玻璃路径。
        GlassSurface(
            modifier = modifier
                // 2026-10-17：大屏适配——旧实现只有 widthIn(max) 无填充比例，
                // 内容多窄弹窗多宽，1272px 大屏上就是个迷你块。默认 86% 屏宽、
                // 上限 560dp，短文案也有合格的存在感
                .fillMaxWidth(DIALOG_WIDTH_FRACTION)
                .widthIn(max = 560.dp),
            tier = GlassTier.L3,
            shape = shape
        ) {
            // 可读性衬底（2026-10-21 实机回调）：纯玻璃 L3 在浅色主题下 alpha 不足，
            // 正文（onSurfaceVariant）与透过来的卡片文字混在一起读不清。垫一层 78%
            // 表面色——弹窗中心趋于实底保证可读，边缘的玻璃高光/内描边/主色 tint 仍保留，
            // 观感上依旧是"一块浮起来的玻璃"而不是平涂色板。
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
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
                    // 2026-10-17：无次要按钮时单按钮居中（iOS 单动作弹窗规范）——
                    // 宽弹窗里右对齐的孤按钮会让右侧大片留白，失衡
                    Modifier.align(
                        if (dismissButton == null) Alignment.CenterHorizontally else Alignment.End
                    ),
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

/** 弹窗宽度占屏比例（2026-10-17）：86% 屏宽 + 560dp 上限，兼顾大屏存在感与平板可读性。 */
private const val DIALOG_WIDTH_FRACTION = 0.86f

/**
 * 操作结果反馈的统一弹窗（2026-09-28）：替代卡片内嵌的 InlineFeedbackBar 通知条。
 * 按 [FeedbackKind] 定标题（操作成功/操作失败/提示），正文为完整消息文本，
 * 单按钮「知道了」——用户此前明确偏好弹窗形式的操作结果反馈。
 *
 * 2026-10-17：弹窗加宽至 86% 屏宽（见 [SmoothAlertDialog]），正文字号同步放大到
 * bodyLarge、按钮 labelLarge——大弹窗配小字会更显空。
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
        text = {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}


/**
 * 操作结果消息（2026-09-28 起由 [FeedbackDialog] 弹窗承载）。
 *
 * 2026-10-21：原宿主 InlineFeedbackBar.kt（卡片内嵌通知条，已无调用点、且与
 * 「反馈一律弹窗」约定冲突）整体删除，数据类迁移到这里——弹窗是它唯一消费者。
 */
data class FeedbackMessage(
    val text: String,
    val kind: FeedbackKind = FeedbackKind.SUCCESS
)
