package com.ed.edqiu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * 扁平化液态胶囊底部导航。
 *
 * 扁平化原则（2026-08-01 调整）：
 * - **去除外壳玻璃容器**：背景透明，Tab 栏直接贴合在内容上（无悬浮外壳）
 * - **高度 64dp → 56dp**：更精致
 * - **保留选中态胶囊**：纯 primaryContainer 实色，无描边、无渐变
 * - **iOS Tab Bar 视觉密度**：图标 24dp + 选中文字 11sp
 *
 * iOS 式交互不变：
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
    badgeCount: Int = 0
) {
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val barWidth = maxWidth
        val itemWidth = barWidth / tabs.size

        // iOS 式交互：记录"当前按住的 tab"
        var pressedIndex by remember { mutableStateOf(-1) }

        val capsuleTargetIndex = if (pressedIndex >= 0) pressedIndex else selectedIndex
        val capsuleTargetX = itemWidth * capsuleTargetIndex

        val capsuleX by animateFloatAsState(
            targetValue = capsuleTargetX.value,
            animationSpec = if (pressedIndex >= 0) {
                tween(durationMillis = 50)
            } else {
                spring(dampingRatio = 0.6f, stiffness = 480f)
            },
            label = "capsuleX"
        )

        // 选中态胶囊：扁平化（无描边、无渐变，纯 primaryContainer 实色）
        Box(
            modifier = Modifier
                .offset(x = capsuleX.dp)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(primaryContainer)
                .zIndex(1f)
        )

        // Tab 项：垂直布局（图标上 + 文字下），iOS TabBar 标准
        // 水平排列在 48dp 高度内会拥挤，垂直堆叠后每个 tab 内容区约 35dp，舒适居中
        Row(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.06f else 1f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
                    label = "iconScale$index"
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
                            modifier = Modifier.size(22.dp)
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                                slideInVertically(tween(180)) { it / 3 },
                            exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 3 }
                        ) {
                            Text(
                                text = tab.label,
                                color = onPrimaryContainer,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 11.sp,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // 角标
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFF3B30))
                    .zIndex(3f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeCount.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}