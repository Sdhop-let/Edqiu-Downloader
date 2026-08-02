package com.ed.edqiu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DefaultSeed = Color(0xFF2F4C8F)

/**
 * Edqiu 主题入口。
 *
 * @param seed 种子色（仅低版本降级 / 手动覆盖时使用；API 31+ 默认走系统莫奈取色）
 * @param dynamicColor 是否启用系统动态取色（Android 12+）
 */
@Composable
fun EdqiuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seed: Color = DefaultSeed,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (dynamicColor) monetColorScheme(seed) else colorSchemeFromSeed(seed, darkTheme)
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
