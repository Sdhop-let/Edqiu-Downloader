package com.ed.edqiu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Edqiu 主题入口（2026-10-10 固定配色重构）。
 *
 * 演进史：
 * - 旧方案（~v1.6.5）：强调色种子 × 6 种 TonalStyle × 2 种 MonetSpec 自由组合派生；
 * - 单一色源方案（v1.6.6~v1.8.0）：壁纸取色开 = 系统 Monet / 壁纸主色种子派生，
 *   关 = 强调色种子 materialkolor 派生全套；
 * - **本方案**：Monet 壁纸动态取色彻底移除（含 materialkolor 引擎与全部取色分支），
 *   配色 = 固定 iOS 式中性面（浅/深两套，见 [FixedScheme.kt]）+ 单一强调色 tint 注入。
 *   壁纸变化不影响任何界面颜色；强调色只作用于按钮/开关/选中态/玻璃染色。
 *
 * 背景与卡片同源结构不变：GlassBackground 直接取 ColorScheme 中性面，
 * 现在取到的是固定值，结构上不可能互相割裂。
 */
@Composable
fun EdqiuTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color = Color(DEFAULT_ACCENT),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> darkTheme || systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme: ColorScheme =
        if (dark) fixedDarkColorScheme(accent) else fixedLightColorScheme(accent)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

/**
 * 状态语义色（iOS 系统色，固定不随主题取色）：
 * 未下载 = iOS 橙 / 已下载 = iOS 绿 / 失败 = iOS 红 / 已删除 = iOS 灰。
 * 用法约定：彩色文本或圆点 + 同色 12% 底（StatusPill/StatusBadge），与 iOS 系统一致。
 */
@Composable
fun statusColor(status: com.ed.edqiu.data.model.LinkStatus): Color {
    val dark = isSystemInDarkTheme()
    return when (status) {
        com.ed.edqiu.data.model.LinkStatus.PENDING ->
            if (dark) StatusPendingDark else StatusPending
        com.ed.edqiu.data.model.LinkStatus.DOWNLOADED ->
            if (dark) StatusDownloadedDark else StatusDownloaded
        com.ed.edqiu.data.model.LinkStatus.FAILED ->
            if (dark) StatusFailedDark else StatusFailed
        com.ed.edqiu.data.model.LinkStatus.DELETED ->
            if (dark) StatusGoneDark else StatusGone
    }
}
