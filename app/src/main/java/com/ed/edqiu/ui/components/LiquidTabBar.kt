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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
            // 2026-09-16 手感对齐 liquid-glass-android demo：spring 更柔弹（0.8/260，原 0.6/480 偏硬快），
            // 按压仍 tween 50ms 立即跟手
            animationSpec = if (pressedIndex >= 0) tween(50) else spring(dampingRatio = 0.8f, stiffness = 260f),
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
            // 2026-09-16 液态玻璃升级：Backdrop 真折射（vibrancy+blur+lens，Android 13+），
            // 透出并折射正下方滚动内容；低版本/关闭时自动回退渐变模拟玻璃。
            // 折射带 14dp ≤ 最小圆角 25dp，符合 lens 参数约束
            // 2026-09-28：blurRadius 不再写死 8dp，默认按全局「模糊强度」滑块取值（4-20dp）
            // 2026-10-22「底部透字」定版：底栏显式固定 8dp 轻磨砂——全局滑块 0.6 档
            // 13.6dp 会把滚入底栏的文字糊成色块（透色不透字）；网易云级透字需要
            // 文字轮廓可辨，8dp 是「磨砂质感 + 字可辨」的平衡点。播放器控制层等
            // 其余表面仍跟随全局滑块不受影响。
            GlassOverlaySurface(
                shape = RoundedCornerShape(25.dp),
                style = OverlayGlassStyle.Light,
                blurRadius = 8.dp,
                lensHeight = 14.dp,
                // 2026-10-24 液态强化：折射位移 14→16dp，边缘弯折更明显（≤ 最小圆角 25dp 约束）
                lensAmount = 16.dp,
                // 2026-10-24 透光强化：0.35→0.5（t 封顶 1.0，表面 alpha 下探 0.16）——
                // 透光底部：内容从底栏下方穿透可辨
                transparencyBoost = 0.5f,
                // 2026-10-22 用户反馈：底栏外圈不要描边——干净玻璃壳
                showEdgeAccent = false,
                // 2026-10-24 用户反馈：tab 栏边缘取消白线描边——跳过顶光白洗整层
                showSheen = false,
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

        // 选中胶囊（2026-10-24 按钮透明可观重做）：primary 低透明色洗替代
        // primaryContainer 0.94 实底——内容从胶囊下穿透可辨，选中态由
        // 主题色 tint（图标/文字转 primary，Apple tab 选中语言）承担；
        // 圆角随位置动态贴合外壳内壁
        if (liquidGlass) {
            Box(
                modifier = Modifier
                    .offset(x = capsuleX.dp)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 3.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(capsuleRadius.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (dark) 0.38f else 0.28f
                        )
                    )
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
                // ── 2026-10-25 Tab 项重设计 v2（用户要求重设计图标/文字/布局）──
                // 布局语言从「三列同构（图标+文字）」改为「动态胶囊导航」：
                // - 选中项：滑动胶囊内 **实底图标 + 文字横排**（iOS 动态岛式聚合，
                //   选中态的信息密度与视觉重量显著高于未选中态）；
                // - 未选项：纯 24dp 大图标居中（轮廓版图形，退为环境元素）；
                // - 图形全部换新：轮廓版（未选）/ 实底版（选中）成对切换，
                //   实底图形是「已进入」的强语义，替代旧版的颜色+缩放微调。
                // 尺寸不变：外壳胶囊 50dp、三等分、点击区全宽。
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
                    if (selected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.alpha(1f)
                        ) {
                            Icon(
                                imageVector = tab.selectedIcon,
                                contentDescription = null,
                                tint = if (liquidGlass) MaterialTheme.colorScheme.primary
                                else onPrimaryContainer,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                text = tab.label,
                                color = if (liquidGlass) MaterialTheme.colorScheme.primary
                                else onPrimaryContainer,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp,
                                maxLines = 1
                            )
                        }
                    } else {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = onSurfaceVariant.copy(alpha = 0.72f),
                            modifier = Modifier.size(24.dp)
                        )
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
