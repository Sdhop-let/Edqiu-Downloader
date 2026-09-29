package com.ed.edqiu.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.ui.theme.statusColor

/**
 * 状态圆点：8dp 小圆点，颜色随状态变化，带 200ms 颜色过渡动画。
 */
@Composable
fun StatusDot(
    status: LinkStatus,
    modifier: Modifier = Modifier
) {
    val targetColor = statusColor(status)
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 200),
        label = "statusDotColor"
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}