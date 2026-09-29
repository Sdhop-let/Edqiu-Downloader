package com.ed.edqiu.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 全局 Snackbar 容器。
 *
 * 设计动机（2026-08-16）：Edqiu 启用莫奈 dynamicColor 后，
 * Material 3 默认 Snackbar 取 [inverseSurface]/[inverseOnSurface] 这两个色作为容器/文字，
 * 而 inverseSurface 由系统壁纸经莫奈算法生成。在某些壁纸下（如本工程截图所示的暖色调），
 * inverseSurface 与 inverseOnSurface 演算出相近的色，导致 Snackbar 几乎不可见。
 *
 * 本组件绕过 dynamicColor 配色：
 * - **容器**：固定深色（#1F232A），保证与所有 dynamicColor 背景下都有清晰边缘
 * - **文字**：固定近白（#EDEEF1），与容器对比度 ≥ 13:1（WCAG AAA 远超阈值）
 * - **Action（2026-08-17 加固）**：不再直接使用 [primary]——primary 为 tone 40 派生色，
 *   在深色容器上对比度仅约 2.8:1（浅色强调色时更差）。改为「primary 向白色逐级混合，
 *   直到与容器对比度 ≥ 4.5:1」：保留品牌色相，同时保证 Action 文字在任何强调色下都清晰。
 *
 * 位置策略（关键避坑，2026-08-16 实测）：
 * 1. M3 Scaffold 的 `snackbarHost` slot 在我们这个 `contentWindowInsets=WindowInsets(0)` 的场景下
 *    **实际默认放在顶部 TopCenter**（不是 BottomCenter），所以 Snackbar 会贴着状态栏下方显示。
 * 2. 我们用 [Box.fillMaxWidth] + [Alignment.BottomCenter] **强制底部对齐**，
 *    但垂直方向 wrap content（不撑 fillMaxSize，否则会把 Scaffold content 区挤成 0）。
 * 3. 加 [navigationBarsPadding] 让 Snackbar 浮在底部导航栏之上而非被它盖住。
 * 4. Snackbar 自身的 horizontal padding = 12dp，vertical padding = 8dp 让它在容器内有呼吸感。
 */
@Composable
fun EdqiuSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val actionColor = rememberSnackbarActionColor()
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        SnackbarHost(
            hostState = hostState,
            modifier = modifier.navigationBarsPadding()
        ) { data ->
            Snackbar(
                snackbarData = data,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
                containerColor = Color(0xFF1F232A),
                contentColor = Color(0xFFEDEEF1),
                actionColor = actionColor,
                actionContentColor = actionColor
            )
        }
    }
}

/**
 * Action 按钮安全色（2026-08-17 加固）。
 *
 * 直接使用 primary（tone 40）在深色 #1F232A 容器上对比度仅约 2.8:1，
 * 用户选择浅色/低饱和强调色时更差。此处从 primary 出发逐级向白色混合，
 * 直到与固定容器色对比度 ≥ 4.5:1（WCAG AA），色相保持品牌一致。
 */
@Composable
private fun rememberSnackbarActionColor(): Color {
    val primary = MaterialTheme.colorScheme.primary
    val container = Color(0xFF1F232A)
    return remember(primary) {
        var c = primary
        repeat(14) {
            if (contrastRatio(c, container) >= 4.5f) return@remember c
            c = lerp(c, Color.White, 0.15f)
        }
        c
    }
}

private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}