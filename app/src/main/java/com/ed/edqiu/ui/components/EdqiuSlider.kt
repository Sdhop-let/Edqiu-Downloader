package com.ed.edqiu.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一 iOS 风格滑杆（2026-10-10 全量重构，总指令 §6.4）。
 *
 * 视觉：4dp 圆角细轨道（active = primary tint，inactive = 中性面）+ 白色圆形 thumb（带阴影）；
 * 交互：按下/拖动时轨道轻微增粗 + thumb 放大（连续增强、松手平滑回落），
 * 拖动零延迟跟手（纯状态传递，动画只作用于视觉层，不拦截 onValueChange）。
 *
 * 用于外观/玻璃参数、界面缩放等全部标量设置；播放器进度条有专属增强形态
 * （GlassPlayerControls 内联实现），不经过本组件。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EdqiuSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(
        targetValue = if (pressed) 1.25f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "edqiu_slider_thumb"
    )
    val trackGrowth by animateFloatAsState(
        targetValue = if (pressed) 1.75f else 1f,
        animationSpec = tween(150),
        label = "edqiu_slider_track"
    )
    val trackHeight: Dp = 4.dp

    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                        shadowElevation = if (pressed) 12f else 6f
                        shape = CircleShape
                    }
                    .clip(CircleShape)
                    .background(Color.White)
            )
        },
        track = { state ->
            val fraction = state.coercedValueAsFraction
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight * trackGrowth)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(trackHeight * trackGrowth)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    )
}
