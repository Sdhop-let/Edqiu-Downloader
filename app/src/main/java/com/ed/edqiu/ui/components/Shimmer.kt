package com.ed.edqiu.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Shimmer 效果 Modifier：在组件上叠加从左到右的渐变扫光动画。
 *
 * **限时 2.5s（2026-10-24 渲染冻结攻坚）**：无限 shimmer 是冷启动加载期「持续出帧
 * 不空闲」的帧源——系统因此推迟前一 Activity 的 STOP 提交（实测延迟可达 ~20s），
 * 迟到的生命周期批处理与主界面 RESUME 乱序后，ViewRoot 可被打进 mStopped=true
 * 拒绝遍历（渲染冻结触发链，见 FreezeProbe）。扫光限时 2.5s 后转静态
 * （transition 移出组合，帧回调真正停止）——加载提示语义不变，持续帧源被时限封顶。
 */
fun Modifier.shimmer(): Modifier = composed {
    var running by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(2500L)
        running = false
    }
    val translateAnim = if (running) {
        val transition = rememberInfiniteTransition(label = "shimmer")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmerTranslate"
        ).value
    } else {
        0.5f // 静态位：扫光停在中间，视觉近似暂停的加载提示
    }

    val baseColor = Color(0xFFF5F5F5)
    val highlightColor = Color(0xFFEEEEEE)

    clip(RoundedCornerShape(12.dp)).drawWithContent {
        drawContent()
        val width = size.width
        val brush = Brush.linearGradient(
            colors = listOf(baseColor, highlightColor, baseColor),
            start = Offset(translateAnim * width - width, 0f),
            end = Offset(translateAnim * width, size.height)
        )
        drawRect(brush = brush)
    }
}
