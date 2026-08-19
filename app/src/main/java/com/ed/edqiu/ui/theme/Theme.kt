package com.ed.edqiu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DefaultSeed = Color(0xFF2F4C8F)

/**
 * Edqiu 主题入口。
 *
 * 配色解析规则（2026-08-15 强化强调色 / 色彩标准全局生效）：
 * 1. [themeMode] 决定明暗；
 * 2. [dynamicColor] 开启且 API 31+ 时，中性面（background/surface/outline）跟随系统壁纸；
 * 3. **强调色 [keyColor] 始终生效**——无论是否启用动态取色，primary 及容器色族一律
 *    由 keyColor + [tonalStyle] + [monetSpec] 派生，确保按钮/链接/选中态等全部实时跟随；
 * 4. [monetSpec]（SPEC_2021 / CAM16）真实改变色度分布，[tonalStyle] 微调 tone 色位。
 */
@Composable
fun EdqiuTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    seed: Color = DefaultSeed,
    keyColor: Color = seed,
    dynamicColor: Boolean = true,
    tonalStyle: TonalStyle = TonalStyle.TONAL_SPOT,
    monetSpec: MonetSpec = MonetSpec.SPEC_2021,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> darkTheme || systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    // 基础方案：动态取色走系统引擎（提供中性面），否则由强调色种子生成全套
    val base: ColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        colorSchemeFromSeed(keyColor, dark, tonalStyle, monetSpec)
    }
    // 强调色族：始终由 keyColor 派生（保证强调色全局即时生效，不受动态取色覆盖）
    val accent = colorSchemeFromSeed(keyColor, dark, tonalStyle, monetSpec)

    val colorScheme = base.copy(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = accent.onPrimaryContainer,
        secondary = accent.secondary,
        onSecondary = accent.onSecondary,
        secondaryContainer = accent.secondaryContainer,
        onSecondaryContainer = accent.onSecondaryContainer,
        tertiary = accent.tertiary,
        onTertiary = accent.onTertiary,
        tertiaryContainer = accent.tertiaryContainer,
        onTertiaryContainer = accent.onTertiaryContainer
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

/** 根据当前主题返回状态色。 */
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
    }
}
