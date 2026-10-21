package com.ed.edqiu.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 吸顶分组节头（2026-10-20 升级：媒体库分组模式接入）。
 *
 * 设计规范（edqiu-design-system.html §3 / edqiu-screens-content.html §媒体库）：
 * - 材质：**不用完整玻璃配方**——82% 表面色 + 12dp 圆角，避免滚动时与卡片「抢亮」；
 *   Compose 侧禁 Modifier.blur（ColorOS 离屏白框铁律），故以高 alpha 表面近似
 *   HTML 原型中「72% 白底 + blur(14px)」的观感意图；
 * - 结构：头像（可选）+ 标题（作者 · 发布日）+ 尾部计数（N 个媒体）；
 * - 吸顶由调用方在 LazyColumn 的 stickyHeader{} 中包裹实现，本组件只负责视觉。
 *
 * @param title 主标题，如「小秋 · 10 月 19 日」
 * @param avatarInitial 头像首字符；null = 不渲染头像
 * @param trailingText 尾部计数文本，如「3 个媒体」；null = 不渲染
 */
@Composable
fun StickySectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    avatarInitial: String? = null,
    trailingText: String? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!avatarInitial.isNullOrBlank()) {
                SectionAvatar(initial = avatarInitial.take(1))
            }
            Text(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (!trailingText.isNullOrBlank()) {
                Text(
                    text = trailingText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** 节头像：18dp 圆形渐变底 + 白色首字符。渐变组按字符哈希确定，同作者颜色稳定。 */
@Composable
private fun SectionAvatar(initial: String) {
    val gradients = listOf(
        listOf(Color(0xFF007AFF), Color(0xFF5AC8FA)),
        listOf(Color(0xFFFF9F0A), Color(0xFFFF375F)),
        listOf(Color(0xFF5E5CE6), Color(0xFFBF5AF2)),
        listOf(Color(0xFF34C759), Color(0xFF0A2540))
    )
    val pair = gradients[abs(initial.hashCode()) % gradients.size]
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(brush = Brush.linearGradient(colors = pair), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
    }
}
