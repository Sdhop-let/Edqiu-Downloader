package com.ed.edqiu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 列表边缘渐隐缓冲带（2026-10-22 方案A「软着陆」组件）。
 *
 * 解决「卡片撞顶/撞底硬切」：卡片滚过列表边缘时，被一层从页面基调色
 * 渐变到透明的色带温柔接住——内容「沉入」边缘而不是被切断。
 *
 * 2026-10-22 用户反馈「标题行底缘过于僵硬」：渐变改三段缓曲线
 * （0.9 → 0.45×中点 → 0），24dp 起步——线性两段渐变的「一步到位」
 * 观感改为先厚后薄的缓坡，交界处不再有可辨的直线。
 *
 * 使用约束：
 * - 叠在 LazyColumn 之上（Box 对齐上/下缘），**不参与布局、不挡点击**
 *   （Box 无 clickable，触摸穿透到下层列表）；
 * - **2026-10-25 白带修复**：color 早期传 scheme.background（浅色=白，
 *   maxAlpha 0.9 = 90% 不透明白带）——壁纸舞台时代 background 不再是页面
 *   背景（背景是壁纸照片），白渐变盖在壁纸上就是一条刺眼白色分界线
 *   （用户实报）。现统一传阴影色（Black 低 maxAlpha）：壁纸上读作
 *   「内容沉入阴影」，深浅主题皆自然。
 */
enum class EdgeFadeEdge { Top, Bottom }

@Composable
fun EdgeFadeScrim(
    edge: EdgeFadeEdge,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    maxAlpha: Float = 0.9f
) {
    val brush = when (edge) {
        // 三段缓曲线：中点保留 45% 强度，消掉线性渐变的可辨边界
        EdgeFadeEdge.Top -> Brush.verticalGradient(
            0f to color.copy(alpha = maxAlpha),
            0.45f to color.copy(alpha = maxAlpha * 0.45f),
            1f to Color.Transparent
        )
        EdgeFadeEdge.Bottom -> Brush.verticalGradient(
            0f to Color.Transparent,
            0.55f to color.copy(alpha = maxAlpha * 0.45f),
            1f to color.copy(alpha = maxAlpha)
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(brush)
    )
}
