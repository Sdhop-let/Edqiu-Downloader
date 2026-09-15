package com.ed.edqiu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    // 引擎结果缓存：HCT/DynamicScheme 求值虽是纯数学，但每次重组重算浪费
    val accent = remember(keyColor, dark, tonalStyle, monetSpec) {
        colorSchemeFromSeed(keyColor, dark, tonalStyle, monetSpec)
    }
    // 2026-09-14 Monet 冲突修复：
    // 旧逻辑无论 Monet 开关，secondary/tertiary 一律被 keyColor 引擎覆盖 ——
    // Monet 开启时壁纸 hue 的中性面 + keyColor hue 的 secondary/tertiary（±30/60° 派生）
    // 两套色相并排打架（切强调色时辅色容器跟着跳，与背景色域脱节）。
    // 新语义：
    // - Monet 开：primary 族（强调色链路）= keyColor 引擎；secondary/tertiary/中性面 = 系统壁纸
    //   Monet 同源派生 —— 辅色与背景协调，强调色只作用于按钮/链接/选中态；
    // - Monet 关：全套角色色 = keyColor 引擎（mcu-pipeline 同源种子 → 全套，自洽无冲突）。
    val useSystemMonet = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base: ColorScheme = if (useSystemMonet) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        accent
    }
    val colorScheme = if (useSystemMonet) {
        base.copy(
            primary = accent.primary,
            onPrimary = accent.onPrimary,
            primaryContainer = accent.primaryContainer,
            onPrimaryContainer = accent.onPrimaryContainer
        )
    } else {
        base
    }

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
        com.ed.edqiu.data.model.LinkStatus.DELETED ->
            if (dark) StatusGoneDark else StatusGone
    }
}
