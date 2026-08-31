package com.ed.edqiu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 设置页内联反馈条（2026-08-31 新增，替代全局顶部 Snackbar）。
 *
 * 背景：设置页此前通过全局 Snackbar 反馈操作结果，它显示在屏幕顶部且会把
 * 已有内容向下挤。本组件改为渲染在触发按钮所在的卡片/区块内部——消息固定
 * 出现在按钮下方，停留 5 秒后以展开/收起 + 淡入淡出的流畅动画消失。
 *
 * - 容器色/文字色与全局 Snackbar 一致（#1F232A / #EDEEF1），视觉语言统一
 * - `message` 置为 null 即触发退出动画；非 null 后自动计时 5s 调用 [onDismiss]
 */
@Composable
fun InlineFeedbackBar(
    message: String?,
    modifier: Modifier = Modifier,
    durationMillis: Long = 5000L,
    onDismiss: () -> Unit
) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(durationMillis)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically(animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = shrinkVertically(animationSpec = tween(260)) + fadeOut(animationSpec = tween(260)),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1F232A),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF7ED9A3),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message.orEmpty(),
                    color = Color(0xFFEDEEF1),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
