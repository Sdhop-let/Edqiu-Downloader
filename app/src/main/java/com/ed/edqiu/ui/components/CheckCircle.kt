package com.ed.edqiu.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ed.edqiu.ui.util.rememberReduceMotion

/**
 * iOS 风多选勾选圆圈（设计系统组件，2026-10-13 取代行内 M3 Checkbox）。
 *
 * 视觉：未选中 = 中性描边空心圆；选中 = primary 实心圆 + 白勾弹性出现。
 * 语义：Role.Checkbox + toggleable，读屏行为与 M3 Checkbox 等价；
 *      列表行内使用时通常由外层行点击驱动，勾选圈自身保留独立 toggleable 语义。
 * 动画：填充/描边 150ms 颜色过渡（Motion.Control 语义）、勾 spring 弹入；
 *      ReduceMotion 时全部跳变，仅保留状态反馈。
 */
@Composable
fun CheckCircle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    diameter: Dp = 22.dp
) {
    val reduceMotion = rememberReduceMotion()
    val duration = if (reduceMotion) 0 else 150

    val fill by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(duration),
        label = "checkCircleFill"
    )
    val ring by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(duration),
        label = "checkCircleRing"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.4f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "checkCirclePop"
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 110),
        label = "checkCircleAlpha"
    )

    Box(
        modifier = modifier
            .size(diameter)
            .background(fill, CircleShape)
            .border(1.5.dp, ring, CircleShape)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        role = Role.Checkbox,
                        onValueChange = onCheckedChange
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checkAlpha > 0.01f) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(diameter * 0.62f)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                        alpha = checkAlpha
                    }
            )
        }
    }
}
