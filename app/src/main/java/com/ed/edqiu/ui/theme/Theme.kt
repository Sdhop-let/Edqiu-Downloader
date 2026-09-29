package com.ed.edqiu.ui.theme

import android.app.WallpaperManager
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
 * Edqiu 主题入口（2026-09-28 「单一色源」重构 —— 替代旧的强调色×风格×标准自定义引擎）。
 *
 * 旧方案（已废弃）：强调色种子 × 6 种 TonalStyle × 2 种 MonetSpec 自由组合派生全套配色，
 * 与壁纸 Monet、手绘莫奈背景三方互相打架——取色/强调色/风格/模糊怎么调都难以自洽，
 * 多轮修补无果后整体替换。
 *
 * 新方案 = 单一色源 + 固定派生：
 * 1. 明暗由 [themeMode] 决定；
 * 2. 壁纸取色开（[dynamicColor]）：
 *    - API 31+：全套 = 系统 Monet（dynamicXxxColorScheme）原样生效，不叠加任何自定义派生；
 *    - API 28-30：系统壁纸主色（WallpaperManager）作种子，按 Google 默认参数
 *      （TonalSpot + Spec 2021）派生全套；
 * 3. 壁纸取色关：强调色种子 + 同一组固定默认参数派生——强调色即时生效，
 *    但不再存在"风格/标准"两个互相干扰的旋钮；
 * 4. 背景不再手绘莫奈渐变：GlassBackground 直接取 ColorScheme 中性面 + 容器色光晕
 *    （见 GlassSurface.kt），背景/卡片/取色永远同源，结构上不可能互相割裂。
 */
@Composable
fun EdqiuTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    seed: Color = DefaultSeed,
    keyColor: Color = seed,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> darkTheme || systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    // 壁纸主色提取（API 28-30 取色路径）：一次 Binder 调用后缓存，壁纸更换后重启刷新
    val wallpaperSeed = remember {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                WallpaperManager.getInstance(context)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor
                    ?.toArgb()
            }.getOrNull()
        } else {
            null
        }
    }
    val colorScheme: ColorScheme = when {
        // 系统 Monet：唯一色源，完整生效（primary 不做任何覆盖）
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // 壁纸主色种子（Android 9~11）：固定默认派生参数
        dynamicColor && wallpaperSeed != null ->
            colorSchemeFromSeed(Color(wallpaperSeed), dark)
        // 强调色种子：同一组固定默认派生参数，效果可预测
        else -> colorSchemeFromSeed(keyColor, dark)
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
