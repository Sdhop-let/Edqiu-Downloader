package com.ed.edqiu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp

/**
 * iOS 大标题↔紧凑导航标题折叠体系（2026-10-10 全量重构）。
 *
 * 语义：页面顶部大标题随真实列表滚动平滑过渡为紧凑标题——
 * - progress = 0：完全展开（大字号标题 + 副标题）；
 * - progress = 1：完全折叠（紧凑字号，副标题淡出）。
 *
 * 实现原则（总指令 §6.7：滚动与布局变化统一规则）：
 * - 纯状态驱动（derivedStateOf 读 LazyListState），滚动多远折叠多少，
 *   无动画缓存、天然可中断可回弹，不存在"动画追赶滚动"的错位；
 * - 折叠行程统一 180px，所有接入页手感一致；
 * - 调用方用 [titleCollapseStyle]/[titleCollapseAlpha] 做插值，禁止各页自造曲线。
 */

/** 折叠行程：滚动 180px 完成大标题 → 紧凑标题的全过程。 */
private const val COLLAPSE_RANGE_PX = 180f

@Composable
fun rememberTitleCollapseProgress(listState: LazyListState): State<Float> =
    remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / COLLAPSE_RANGE_PX).coerceIn(0f, 1f)
            }
        }
    }

/** 大标题字号插值：展开 [expanded]，折叠 [compact]。 */
fun titleCollapseSize(progress: Float, expanded: TextUnit, compact: TextUnit): TextUnit =
    lerp(expanded, compact, progress)

/**
 * 大标题完整 TextStyle 插值（字号 + 字重）。
 * 展开态 = iOS largeTitle 语义（34sp 级加粗），折叠态 = iOS navTitle 语义（20sp 级加粗）。
 */
fun titleCollapseStyle(
    progress: Float,
    expandedSize: TextUnit,
    compactSize: TextUnit
): TextStyle = TextStyle(
    fontSize = titleCollapseSize(progress, expandedSize, compactSize),
    fontWeight = FontWeight.ExtraBold
)

/** 副标题/辅助元素淡出：折叠前 60% 行程完成淡出，避免折叠完成后内容突然消失。 */
fun titleCollapseAlpha(progress: Float): Float = (1f - progress / 0.6f).coerceIn(0f, 1f)

/**
 * 列表内大标题页的固定紧凑导航条。
 *
 * 适用场景：大标题放在 LazyColumn 内随内容滚走的页面（下载中心/回收站/网盘备份）。
 * 列表滚动时本条从状态栏下滑入（淡入 + 下移动画），接住导航职责——
 * 与 iOS「large title scrolls away, nav bar title fades in」行为一致。
 *
 * 仅在 progress > 0 时组合内容，避免未滚动时遮挡列表头部。
 */
@Composable
fun CollapsingNavTitleBar(
    title: String,
    progress: Float,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val visible = progress > 0.05f
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInVertically(tween(180)) { -it / 2 },
        exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { -it / 2 },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (onBack != null) {
                // 圆形返回钮（与各页 HeaderIconButton 同形态，此处内联避免反向依赖页面私有组件）
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            trailing()
        }
    }
}
