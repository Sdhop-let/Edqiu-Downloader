package com.ed.edqiu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * Monet 动态取色系统。
 *
 * - API 31+：跟随系统壁纸动态取色（莫奈引擎）
 * - API < 31 或取色不可用：降级为 [seedPresets] 中的种子色静态方案
 */
object Monet {
    /** 种子色预设（手动覆盖 / 低版本降级）。 */
    val seedPresets: List<SeedPreset> = listOf(
        SeedPreset("墨蓝", Color(0xFF2F4C8F), wall = listOf(Color(0xFFDCE6F5), Color(0xFFF6EDE4))),
        SeedPreset("曜石黑", Color(0xFF101417), wall = listOf(Color(0xFFDFE4E8), Color(0xFFEEF1F3))),
        SeedPreset("珊瑚橙", Color(0xFFE8583A), wall = listOf(Color(0xFFF8E3DA), Color(0xFFF3EEE2))),
        SeedPreset("抹茶绿", Color(0xFF3E7C4F), wall = listOf(Color(0xFFE2EFE2), Color(0xFFF0F3E6))),
        SeedPreset("薰衣草紫", Color(0xFF7C5CBF), wall = listOf(Color(0xFFE8E2F5), Color(0xFFF2EAEF))),
        SeedPreset("玫瑰粉", Color(0xFFC45B7E), wall = listOf(Color(0xFFF7E2E9), Color(0xFFF5EFE6)))
    )
}

data class SeedPreset(
    val name: String,
    val seed: Color,
    val wall: List<Color> = emptyList()
)

/** 根据种子色生成一套完整的 M3 ColorScheme（降级方案）。
 *  根据 seed 亮度自动判断 dark/light（不是系统设置）—— 曜石黑等深色 seed 自动走暗色主题。
 */
fun colorSchemeFromSeed(seed: Color, dark: Boolean = false): ColorScheme {
    // 深色 seed (luminance < 0.4) 自动走 darkColorScheme
    val dark = dark || seed.luminance() < 0.4f
    if (dark) {
        return darkColorScheme(
            primary = seed.copy(alpha = 0.92f).let { blend(it, Color.White, 0.16f) },
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = seed.copy(alpha = 0.28f).let { blend(it, Color(0xFF1A1D21), 0.55f) },
            onPrimaryContainer = Color(0xFFE6E8EB),
            secondaryContainer = seed.copy(alpha = 0.20f).let { blend(it, Color(0xFF1A1D21), 0.62f) },
            onSecondaryContainer = Color(0xFFE6E8EB),
            tertiaryContainer = seed.copy(alpha = 0.16f).let { blend(it, Color(0xFF23272C), 0.55f) },
            onTertiaryContainer = Color(0xFFE6E8EB),
            background = Color(0xFF121417),
            surface = Color(0xFF1A1D21),
            surfaceVariant = Color(0xFF23272C),
            onSurface = Color(0xFFE6E8EB),
            onSurfaceVariant = Color(0xFFA8AEB6),
            outline = Color(0xFF33383E),
            outlineVariant = Color(0xFF2A2F35),
            surfaceContainerLow = Color(0xFF171A1E),
            surfaceContainer = Color(0xFF1E2226),
            surfaceContainerHigh = Color(0xFF262B30)
        )
    }
    return lightColorScheme(
        primary = seed,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = seed.copy(alpha = 0.14f).let { blend(it, Color.White, 0.78f) },
        onPrimaryContainer = seed.copy(alpha = 0.92f),
        secondaryContainer = seed.copy(alpha = 0.10f).let { blend(it, Color.White, 0.85f) },
        onSecondaryContainer = seed.copy(alpha = 0.86f),
        tertiaryContainer = seed.copy(alpha = 0.08f).let { blend(it, Color.White, 0.88f) },
            onTertiaryContainer = seed.copy(alpha = 0.9f),
            // surface 改用 #F3F6FB 替代 #FFFFFF（避免 M3 Scaffold fallback 当背景遮挡 GlassBackground）
            background = Color(0xFFF1F5F9),
            surface = Color(0xFFF3F6FB),
        surfaceVariant = Color(0xFFEFF4F7),
        onSurface = Color(0xFF101417),
        onSurfaceVariant = Color(0xFF64748B),
        outline = Color(0xFFD9E3EA),
        outlineVariant = Color(0xFFE5EBF0),
        surfaceContainerLow = Color(0xFFF6F9FB),
        surfaceContainer = Color(0xFFEDF2F6),
        surfaceContainerHigh = Color(0xFFE6ECF2)
    )
}

/**
 * 读取当前系统的 ColorScheme：
 * API 31+ 且系统支持动态取色时使用莫奈引擎；否则用 [seed] 种子色降级。
 */
@Composable
fun monetColorScheme(seed: Color): ColorScheme {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        colorSchemeFromSeed(seed, dark)
    }
}

/** 把 [over] 按 [ratio] 混合到 [base] 上（ratio 0 = 纯 base，1 = 纯 over）。 */
private fun blend(base: Color, over: Color, ratio: Float): Color {
    val r = base.red * (1 - ratio) + over.red * ratio
    val g = base.green * (1 - ratio) + over.green * ratio
    val b = base.blue * (1 - ratio) + over.blue * ratio
    val a = base.alpha * (1 - ratio) + over.alpha * ratio
    return Color(r, g, b, a)
}
