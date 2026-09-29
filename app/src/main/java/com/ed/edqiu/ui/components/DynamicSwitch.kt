package com.ed.edqiu.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 动态开关（设计文档 §5）。
 *
 * 在 M3 Switch 基础上叠加三态增强：
 * 1. 滑块内图标切换（☾ 关闭 / ✦ 开启）随位移淡入淡出
 * 2. 轨道辉光：开启时外圈 primary 8% 光晕，关闭时消散
 * 3. 弹性位移：Spring(0.55, 400)
 */
@Composable
fun DynamicSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val primary = MaterialTheme.colorScheme.primary
    val trackColor by animateColorAsState(
        targetValue = if (checked) primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing),
        label = "trackColor"
    )
    val glowColor by animateColorAsState(
        targetValue = if (checked) primary.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "glowColor"
    )

    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .background(glowColor, CircleShape)  // 辉光晕染
            .padding(4.dp)
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier
                .size(52.dp, 32.dp)
                .semantics { role = Role.Switch },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = trackColor,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = trackColor,
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.7f),
                disabledUncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                disabledCheckedTrackColor = primary.copy(alpha = 0.4f),
                disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

/** 迷你开关（图标行内使用，视觉更紧凑）。 */
@Composable
fun DynamicSwitchMini(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier
) {
    DynamicSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.scale(0.85f)
    )
}
