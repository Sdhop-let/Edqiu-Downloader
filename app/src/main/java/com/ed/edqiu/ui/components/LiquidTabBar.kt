package com.ed.edqiu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 液态玻璃（Liquid Glass）底部导航 — 悬浮胶囊样式。
 *
 * - Tab Bar 外壳完全透明，仅选中胶囊有玻璃背景
 * - 胶囊尺寸与单个 Tab 按钮完全贴合
 * - 高度 50dp，圆角 16dp
 * - 按压：胶囊 tween 50ms 立即到位
 * - 松手：spring 弹性回弹落定
 */
data class LiquidTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
)

@Composable
fun LiquidTabBar(
    tabs: List<LiquidTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    liquidGlassEnabled: Boolean = true
) {
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val dark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    val liquidGlass = liquidGlassEnabled && com.ed.edqiu.ui.theme.ThemeEffects.LiquidGlassEnabled.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 16.dp)
    ) {
        val barWidth = maxWidth
        val itemWidth = barWidth / tabs.size

        var pressedIndex by remember { mutableStateOf(-1) }
        val capsuleIndex = if (pressedIndex >= 0) pressedIndex else selectedIndex
        val capsuleTargetX = itemWidth * capsuleIndex
        val capsuleX by animateFloatAsState(
            targetValue = capsuleTargetX.value,
            animationSpec = if (pressedIndex >= 0) tween(50) else spring(dampingRatio = 0.6f, stiffness = 480f),
            label = "capsuleX"
        )

        // 胶囊圆角动态贴合（2026-09-14，Apple Liquid Glass 细节）：
        // 外壳是 stadium（高 50dp 圆角 25dp），内壁半径 = 25-3(内边距) = 22dp。
        // 选中胶囊滑向两端时圆角从 16dp 渐变到 22dp（stadium），弧线与外壳内壁同心对齐；
        // 居中时保持 16dp 的小圆角观感。随 capsuleX 同步动画，滑动全程圆角连续变化。
        val tabCount = tabs.size
        val barCenterX = itemWidth.value * tabCount / 2f
        val capsuleCenterX = capsuleX + itemWidth.value / 2f
        val halfSpan = (barCenterX - itemWidth.value / 2f).coerceAtLeast(1f)
        val edgeT = kotlin.math.abs(capsuleCenterX - barCenterX) / halfSpan
        val capsuleRadius by animateFloatAsState(
            targetValue = 16f + edgeT.coerceIn(0f, 1f) * 6f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 600f),
            label = "capsuleRadius"
        )

        // 外壳（2026-09-14：不再是"完全透明"，整体液态玻璃胶囊底 —— 对齐 iOS 底栏预期）
        if (liquidGlass) {
            GlassSurface(
                tier = GlassTier.L2,
                shape = RoundedCornerShape(25.dp),
                elevated = true,
                modifier = Modifier.fillMaxSize()
            ) { }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(25.dp))
                    .background(primaryContainer.copy(alpha = 0.55f))
            )
        }

        // 选中胶囊：玻璃模式下用 primaryContainer 实底突出选中态（叠在外壳玻璃上），
        // 圆角随位置动态贴合外壳内壁
        if (liquidGlass) {
            Box(
                modifier = Modifier
                    .offset(x = capsuleX.dp)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 3.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(capsuleRadius.dp))
                    .background(primaryContainer.copy(alpha = 0.94f))
            )
        } else {
            Box(
                modifier = Modifier
                    .offset(x = capsuleX.dp)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 3.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(capsuleRadius.dp))
                    .background(primaryContainer)
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.12f else 1f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
                    label = "scale$index"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .semantics { role = Role.Tab }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(index) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.scale(iconScale)
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) onPrimaryContainer else onSurfaceVariant,
                            modifier = Modifier.size(21.dp)
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
                            exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 3 }
                        ) {
                            Text(
                                text = tab.label,
                                color = onPrimaryContainer,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 11.sp,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFFF3B30)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeCount.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
