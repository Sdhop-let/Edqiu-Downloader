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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.ColorSpec2021
import com.materialkolor.dynamiccolor.ColorSpec2025
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

/**
 * 主题模式（2026-08-15 主题设置页扩展）
 */
enum class ThemeMode(internal val storageValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromStorage(v: String?): ThemeMode = entries.firstOrNull { it.storageValue == v } ?: SYSTEM
    }
}

/**
 * Monet 色彩风格（Material 3 多风格）。
 *
 * 2026-09-14：接入真 HCT 引擎（com.materialkolor:material-color-utilities，
 * 与用户的 mcu-pipeline 取色管道同源），每个风格映射为对应的 DynamicScheme，
 * 不再是简化 tone 偏移。
 */
enum class TonalStyle(internal val storageValue: String, val label: String) {
    TONAL_SPOT("tonal_spot", "TonalSpot"),
    VIBRANT("vibrant", "Vibrant"),
    EXPRESSIVE("expressive", "Expressive"),
    FRUIT_SALAD("fruit_salad", "FruitSalad"),
    FIDELITY("fidelity", "Fidelity"),
    CONTENT("content", "Content");

    companion object {
        fun fromStorage(v: String?): TonalStyle = entries.firstOrNull { it.storageValue == v } ?: TONAL_SPOT
    }
}

/**
 * Monet 色彩标准（引擎 ColorSpec 版本）。
 *
 * 2026-09-14：SPEC_2021 = 经典 Monet 规范；CAM16 档位升级为
 * Material 3 Expressive（ColorSpec 2025）——mcu-pipeline 支持的两档规范。
 */
enum class MonetSpec(internal val storageValue: String, val label: String) {
    SPEC_2021("spec_2021", "SPEC_2021"),
    CAM16("cam16", "M3E_2025");

    companion object {
        fun fromStorage(v: String?): MonetSpec = entries.firstOrNull { it.storageValue == v } ?: SPEC_2021
    }
}

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

/**
 * 根据种子色生成一套完整的 M3 ColorScheme。
 *
 * 2026-09-14 **真引擎替换**：此前的 HSL 线性近似（色相恒定 + 明度线性 + 简化色度）
 * 与 Material You 实际渲染存在明显色偏（特别是高饱和种子色偏艳、低饱和偏灰）。
 * 现改用 mcu-pipeline 同源的 Material Color Utilities：
 * - [Hct.fromInt] 把种子色转 CAM16/HCT 感知空间；
 * - [TonalStyle] 一一映射到 DynamicScheme（TonalSpot/Vibrant/Expressive/FruitSalad/Fidelity/Content）；
 * - [MonetSpec] 映射 ColorSpec 2021 / 2025 两档规范；
 * - 角色色由 [ColorSpec] 求值（引擎内置对比度约束，onPrimary 不再需要手动保险逻辑）。
 */
fun colorSchemeFromSeed(
    seed: Color,
    dark: Boolean = false,
    tonalStyle: TonalStyle = TonalStyle.TONAL_SPOT,
    monetSpec: MonetSpec = MonetSpec.SPEC_2021
): ColorScheme {
    val hct = Hct.fromInt(seed.toArgb())
    val specVersion = when (monetSpec) {
        MonetSpec.SPEC_2021 -> ColorSpec.SpecVersion.SPEC_2021
        MonetSpec.CAM16 -> ColorSpec.SpecVersion.SPEC_2025
    }
    val scheme: DynamicScheme = when (tonalStyle) {
        TonalStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, 0.0, specVersion)
        TonalStyle.VIBRANT -> SchemeVibrant(hct, dark, 0.0, specVersion)
        TonalStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, 0.0, specVersion)
        TonalStyle.FRUIT_SALAD -> SchemeFruitSalad(hct, dark, 0.0, specVersion)
        TonalStyle.FIDELITY -> SchemeFidelity(hct, dark, 0.0, specVersion)
        TonalStyle.CONTENT -> SchemeContent(hct, dark, 0.0, specVersion)
    }
    return scheme.toComposeColorScheme()
}

/**
 * DynamicScheme → Compose M3 ColorScheme 全角色映射。
 * 角色清单与 mcu-pipeline SchemeMapper.mapRoles 对齐（2021/2025 规范通用）。
 */
private fun DynamicScheme.toComposeColorScheme(): ColorScheme {
    val spec = when (specVersion) {
        ColorSpec.SpecVersion.SPEC_2021 -> ColorSpec2021()
        ColorSpec.SpecVersion.SPEC_2025 -> ColorSpec2025()
    }
    fun argbOf(dc: DynamicColor?): Color =
        dc?.getArgb(this)?.let { Color(it) } ?: Color.Unspecified

    return if (isDark) {
        darkColorScheme(
            primary = argbOf(spec.primary()),
            onPrimary = argbOf(spec.onPrimary()),
            primaryContainer = argbOf(spec.primaryContainer()),
            onPrimaryContainer = argbOf(spec.onPrimaryContainer()),
            inversePrimary = argbOf(spec.inversePrimary()),
            secondary = argbOf(spec.secondary()),
            onSecondary = argbOf(spec.onSecondary()),
            secondaryContainer = argbOf(spec.secondaryContainer()),
            onSecondaryContainer = argbOf(spec.onSecondaryContainer()),
            tertiary = argbOf(spec.tertiary()),
            onTertiary = argbOf(spec.onTertiary()),
            tertiaryContainer = argbOf(spec.tertiaryContainer()),
            onTertiaryContainer = argbOf(spec.onTertiaryContainer()),
            background = argbOf(spec.background()),
            onBackground = argbOf(spec.onBackground()),
            surface = argbOf(spec.surface()),
            onSurface = argbOf(spec.onSurface()),
            surfaceVariant = argbOf(spec.surfaceVariant()),
            onSurfaceVariant = argbOf(spec.onSurfaceVariant()),
            surfaceTint = argbOf(spec.primary()),
            outline = argbOf(spec.outline()),
            outlineVariant = argbOf(spec.outlineVariant()),
            error = argbOf(spec.error()),
            onError = argbOf(spec.onError()),
            errorContainer = argbOf(spec.errorContainer()),
            onErrorContainer = argbOf(spec.onErrorContainer()),
            inverseSurface = argbOf(spec.inverseSurface()),
            inverseOnSurface = argbOf(spec.inverseOnSurface()),
            scrim = Color.Black,
            surfaceDim = argbOf(spec.surfaceDim()),
            surfaceBright = argbOf(spec.surfaceBright()),
            surfaceContainerLowest = argbOf(spec.surfaceContainerLowest()),
            surfaceContainerLow = argbOf(spec.surfaceContainerLow()),
            surfaceContainer = argbOf(spec.surfaceContainer()),
            surfaceContainerHigh = argbOf(spec.surfaceContainerHigh()),
            surfaceContainerHighest = argbOf(spec.surfaceContainerHighest())
        )
    } else {
        lightColorScheme(
            primary = argbOf(spec.primary()),
            onPrimary = argbOf(spec.onPrimary()),
            primaryContainer = argbOf(spec.primaryContainer()),
            onPrimaryContainer = argbOf(spec.onPrimaryContainer()),
            inversePrimary = argbOf(spec.inversePrimary()),
            secondary = argbOf(spec.secondary()),
            onSecondary = argbOf(spec.onSecondary()),
            secondaryContainer = argbOf(spec.secondaryContainer()),
            onSecondaryContainer = argbOf(spec.onSecondaryContainer()),
            tertiary = argbOf(spec.tertiary()),
            onTertiary = argbOf(spec.onTertiary()),
            tertiaryContainer = argbOf(spec.tertiaryContainer()),
            onTertiaryContainer = argbOf(spec.onTertiaryContainer()),
            background = argbOf(spec.background()),
            onBackground = argbOf(spec.onBackground()),
            surface = argbOf(spec.surface()),
            onSurface = argbOf(spec.onSurface()),
            surfaceVariant = argbOf(spec.surfaceVariant()),
            onSurfaceVariant = argbOf(spec.onSurfaceVariant()),
            surfaceTint = argbOf(spec.primary()),
            outline = argbOf(spec.outline()),
            outlineVariant = argbOf(spec.outlineVariant()),
            error = argbOf(spec.error()),
            onError = argbOf(spec.onError()),
            errorContainer = argbOf(spec.errorContainer()),
            onErrorContainer = argbOf(spec.onErrorContainer()),
            inverseSurface = argbOf(spec.inverseSurface()),
            inverseOnSurface = argbOf(spec.inverseOnSurface()),
            scrim = Color.Black,
            surfaceDim = argbOf(spec.surfaceDim()),
            surfaceBright = argbOf(spec.surfaceBright()),
            surfaceContainerLowest = argbOf(spec.surfaceContainerLowest()),
            surfaceContainerLow = argbOf(spec.surfaceContainerLow()),
            surfaceContainer = argbOf(spec.surfaceContainer()),
            surfaceContainerHigh = argbOf(spec.surfaceContainerHigh()),
            surfaceContainerHighest = argbOf(spec.surfaceContainerHighest())
        )
    }
}

/**
 * 读取当前系统的 ColorScheme：
 * API 31+ 且系统支持动态取色时使用莫奈引擎；否则用 [seed] 种子色降级。
 */
@Composable
fun monetColorScheme(seed: Color, monetSpec: MonetSpec = MonetSpec.SPEC_2021): ColorScheme {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        colorSchemeFromSeed(seed, dark, monetSpec = monetSpec)
    }
}
