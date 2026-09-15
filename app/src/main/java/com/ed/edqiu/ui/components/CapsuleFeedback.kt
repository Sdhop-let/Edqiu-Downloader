package com.ed.edqiu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 底部胶囊提醒（2026-09-14）。
 *
 * 设计动机：下载/保存等操作结果原先用居中 AlertDialog（打断操作流）或 M3 Snackbar
 * （矩形块、观感重）。改为贴近手机底部的玻璃胶囊：图标 + 单行文本，弹性出入场，
 * 自动消失 —— 对齐 iOS 底部 capsule HUD 的轻量反馈语言。
 *
 * 视觉：
 * - 深色玻璃底（#1A1D21 @ 92%），浅/暗主题下都保持清晰对比（沿用 Snackbar 固定深色决策）
 * - 顶部窄镜面高光 + 内描边（Liquid 玻璃语言，与 GlassSurface 同源但为深色变体）
 * - 图标圈：SUCCESS = iOS 绿 / ERROR = iOS 红 / NEUTRAL = 玻璃白
 *
 * 动画：
 * - 入场：底部滑入 + 缩放 0.85→1（spring 弹性）+ 淡入
 * - 出场：下沉 + 缩放 1→0.9 + 淡出（tween，不弹，避免"弹出又弹回"的廉价感）
 */
enum class FeedbackKind { SUCCESS, ERROR, NEUTRAL }

data class CapsuleMessage(
    val id: Long,
    val kind: FeedbackKind,
    val text: String,
    /** true = 常驻胶囊（如「正在保存…」），不自动消失，直到新消息覆盖或 [CapsuleFeedbackController.clear]。 */
    val sticky: Boolean = false
)

/**
 * 胶囊提醒控制器：任何层级通过 [show] 发送，顶层 [CapsuleFeedbackHost] 收集渲染。
 * 同一时间只显示一条；后一条覆盖前一条（操作反馈无需排队，最新状态最重要）。
 */
class CapsuleFeedbackController {
    private val current = MutableStateFlow<CapsuleMessage?>(null)
    val message: StateFlow<CapsuleMessage?> = current.asStateFlow()

    private var seq = 0L

    fun show(kind: FeedbackKind, text: String, sticky: Boolean = false) {
        current.value = CapsuleMessage(id = ++seq, kind = kind, text = text, sticky = sticky)
    }

    fun clear() {
        current.value = null
    }
}

/** 胶囊自动消失时长：成功/中性 2.4s，失败 3.2s（失败信息需要更长阅读时间）。 */
private fun autoDismissMillis(kind: FeedbackKind): Long = when (kind) {
    FeedbackKind.ERROR -> 3200L
    else -> 2400L
}

@Composable
fun CapsuleFeedbackHost(
    controller: CapsuleFeedbackController,
    modifier: Modifier = Modifier
) {
    val message by controller.message.collectAsState()
    var visible by remember { mutableStateOf(false) }
    var displayed by remember { mutableStateOf<CapsuleMessage?>(null) }

    // 新消息到达：立即换内容并入场；sticky 常驻不自动收起，其余停留 autoDismiss 后收起
    LaunchedEffect(message) {
        when {
            message != null -> {
                displayed = message
                visible = true
                if (!message!!.sticky) {
                    delay(autoDismissMillis(message!!.kind))
                    visible = false
                }
            }
            else -> visible = false
        }
    }

    // 出场动画播完再真正清内容（避免文字在退场中突然消失）
    LaunchedEffect(visible) {
        if (!visible) {
            delay(260)
            if (!visible) displayed = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 20.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = visible && displayed != null,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
                initialOffsetY = { it / 2 }
            ) + scaleIn(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 520f),
                initialScale = 0.85f
            ) + fadeIn(tween(120)),
            exit = slideOutVertically(
                animationSpec = tween(200),
                targetOffsetY = { it / 3 }
            ) + scaleOut(tween(200), targetScale = 0.9f) + fadeOut(tween(160))
        ) {
            displayed?.let { msg ->
                CapsulePill(kind = msg.kind, text = msg.text)
            }
        }
    }
}

@Composable
private fun CapsulePill(
    kind: FeedbackKind,
    text: String
) {
    val accent = when (kind) {
        FeedbackKind.SUCCESS -> Color(0xFF34C759)
        FeedbackKind.ERROR -> Color(0xFFFF453A)
        FeedbackKind.NEUTRAL -> Color.White.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(50),
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.28f)
            )
            .clip(RoundedCornerShape(50))
            .background(Color(0xF01A1D21))
    ) {
        // 顶部窄镜面高光（Liquid 玻璃灵魂，深色变体）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.03f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 46f
                    )
                )
        )
        // 内描边（顶部亮、底部更弱的玻璃边缘光）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.White.copy(alpha = 0.04f)
                        ),
                        startY = 0f,
                        endY = 120f
                    )
                )
        )
        Row(
            modifier = Modifier
                .padding(start = 10.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标圈
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (kind) {
                        FeedbackKind.SUCCESS -> Icons.Rounded.Check
                        FeedbackKind.ERROR -> Icons.Rounded.Close
                        FeedbackKind.NEUTRAL -> Icons.Rounded.Info
                    },
                    contentDescription = null,
                    tint = when (kind) {
                        FeedbackKind.NEUTRAL -> Color.White.copy(alpha = 0.85f)
                        else -> accent
                    },
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.size(9.dp))
            Text(
                text = text,
                color = Color(0xFFEDEEF1),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 300.dp)
            )
        }
    }
}
