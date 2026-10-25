package com.ed.edqiu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 固定 iOS 式配色体系（2026-10-10 全量重构：Monet 壁纸动态取色移除；
 * 2026-10-21 强调色功能移除——全局固定单一 tint，不再可选）。
 *
 * 设计原则（iOS HIG）：
 * 1. 中性面完全固定——背景/卡片/分隔线不随任何外部因素变化，
 *    浅色 = systemBackground/systemGroupedBackground 族，深色 = 纯黑 + 石墨层级；
 * 2. 强调色（tint）全局固定为 iOS 绿（[FIXED_ACCENT]）——按钮、开关、选中态、玻璃染色，
 *    永远不影响背景与文字层次（2026-10-21 起「强调色」设置项与 tint 派生参数一并移除）；
 * 3. 语义色（错误/成功/警告/状态）固定为 iOS 系统色，不随强调色与壁纸变化；
 * 4. tint 派生用固定映射规则（lerp），零色彩引擎依赖，效果可预测：
 *    - 浅色容器 = accent 混白 84%，深色容器 = accent 混黑 62%；
 *    - 深色模式下 tint 统一提亮 10%（对应 iOS dark mode tint 惯例）；
 *    - onPrimary 按亮度自动取黑/白（绿 #34C759 亮度 < 0.55 → 白字，iOS 绿按钮惯例）。
 */

// ===== 固定强调色（2026-10-21 定案：全局唯一，不可配置） =====

/**
 * 全局固定强调色：iOS 系统绿 #34C759。
 * 选型依据：实机长期使用该强调色（按钮/开关/选中态/玻璃 seed 描边/背景色场）观感已验证——
 * 浅色绿底白字对比达标、玻璃边缘光透绿、与 tertiary 青（#30B0C7）构成绿青色场。
 */
val FIXED_ACCENT = Color(0xFF34C759)

// ===== tint 派生（固定映射表，无引擎） =====

/** 单个强调色的四个 tint 角色（primary 族）。 */
data class TintRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color
)

/**
 * 从固定强调色派生 tint 角色（[FIXED_ACCENT] 唯一事实来源，深浅两态各自固定）。
 * 规则见类文档；[androidx.compose.ui.graphics.lerp] 线性混合在 sRGB 空间
 * 对本用例（容器/文字级中低饱和色）足够，无需 HCT 引擎。
 */
fun tintRoles(dark: Boolean): TintRoles {
    val accent = FIXED_ACCENT
    val primary = if (dark) lerp(accent, Color.White, 0.10f) else accent
    val onPrimary = if (primary.luminance() > 0.55f) Color.Black else Color.White
    val container = if (dark) lerp(accent, Color.Black, 0.62f) else lerp(accent, Color.White, 0.84f)
    val onContainer = if (dark) lerp(accent, Color.White, 0.72f) else lerp(accent, Color.Black, 0.62f)
    return TintRoles(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = if (dark) accent else lerp(accent, Color.White, 0.10f)
    )
}

// ===== 固定中性面（不随强调色变化） =====

/** 浅色固定 ColorScheme：iOS systemBackground 族 + 固定绿 tint 注入。 */
fun fixedLightColorScheme(): ColorScheme {
    val t = tintRoles(dark = false)
    return lightColorScheme(
        primary = t.primary,
        onPrimary = t.onPrimary,
        primaryContainer = t.primaryContainer,
        onPrimaryContainer = t.onPrimaryContainer,
        inversePrimary = t.inversePrimary,
        // 次级 = iOS systemGray（次要按钮/占位），容器用 iOS fill 四级灰
        secondary = Color(0xFF8E8E93),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE5E5EA),
        onSecondaryContainer = Color(0xFF000000),
        // 三级 = iOS teal（GlassBackground 冷色场），固定不随强调色
        tertiary = Color(0xFF30B0C7),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFD3F2F7),
        onTertiaryContainer = Color(0xFF084A56),
        // 中性面
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE5E5EA),
        // 2026-10-25 壁纸舞台对比度加固：原 #8A8A8E 在亮色壁纸照片上可读性差（用户实报
        // 「灰色文字可观度不高」），加深到 iOS secondaryLabel 加浓档
        onSurfaceVariant = Color(0xFF5C5C61),
        surfaceTint = t.primary,
        outline = Color(0xFFC6C6C8),
        outlineVariant = Color(0xFFE5E5EA),
        // 语义色 = iOS 系统色，固定
        error = Color(0xFFFF3B30),
        onError = Color.White,
        errorContainer = Color(0xFFFFE5E3),
        onErrorContainer = Color(0xFF8B0006),
        inverseSurface = Color(0xFF1C1C1E),
        inverseOnSurface = Color(0xFFF2F2F7),
        scrim = Color.Black,
        surfaceDim = Color(0xFFDEDEE3),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F7FA),
        surfaceContainer = Color(0xFFF2F2F7),
        surfaceContainerHigh = Color(0xFFE9E9EE),
        surfaceContainerHighest = Color(0xFFE2E2E7)
    )
}

/** 深色固定 ColorScheme：iOS 纯黑基底 + 石墨层级 + 固定绿 tint 注入。 */
fun fixedDarkColorScheme(): ColorScheme {
    val t = tintRoles(dark = true)
    return darkColorScheme(
        primary = t.primary,
        onPrimary = t.onPrimary,
        primaryContainer = t.primaryContainer,
        onPrimaryContainer = t.onPrimaryContainer,
        inversePrimary = t.inversePrimary,
        secondary = Color(0xFF8E8E93),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF2C2C2E),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF64D2FF),
        onTertiary = Color(0xFF00313F),
        tertiaryContainer = Color(0xFF0A4A56),
        onTertiaryContainer = Color(0xFFCFF4FB),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF2C2C2E),
        // 2026-10-25 壁纸舞台对比度加固：深色主题黑 scrim 压暗壁纸后，灰字提亮一档
        onSurfaceVariant = Color(0xFFB0B0B8),
        surfaceTint = t.primary,
        outline = Color(0xFF545458),
        outlineVariant = Color(0xFF38383A),
        error = Color(0xFFFF453A),
        onError = Color.White,
        errorContainer = Color(0xFF5C0A08),
        onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = Color(0xFFF2F2F7),
        inverseOnSurface = Color(0xFF1C1C1E),
        scrim = Color.Black,
        surfaceDim = Color(0xFF000000),
        surfaceBright = Color(0xFF2C2C2E),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF131315),
        surfaceContainer = Color(0xFF1C1C1E),
        surfaceContainerHigh = Color(0xFF242427),
        surfaceContainerHighest = Color(0xFF2C2C2E)
    )
}

/**
 * 主题模式（自 MonetColor.kt 迁入，语义不变）。
 */
enum class ThemeMode(internal val storageValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromStorage(v: String?): ThemeMode = entries.firstOrNull { it.storageValue == v } ?: SYSTEM
    }
}
