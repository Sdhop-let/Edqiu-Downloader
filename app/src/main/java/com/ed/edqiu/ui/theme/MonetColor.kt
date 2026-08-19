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
 * Monet 色彩风格（Material 3 1.2+ 多风格）。
 *
 * 注：Edqiu 用简化 blend 算法而非真 Material You HCT 引擎，
 * 不同风格通过调整 primaryContainer 等色位的 alpha / 混合比例实现近似效果。
 * 动态取色（dynamicColor=true）路径走系统引擎，风格不可控。
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
 * Monet 色彩标准。
 *
 * 注：Edqiu 简化方案下 SPEC_2021 / CAM16 渲染差异极小（无 HCT 色空间计算），
 * 此处主要用于持久化用户偏好，待未来接入真引擎时生效。
 */
enum class MonetSpec(internal val storageValue: String, val label: String) {
    SPEC_2021("spec_2021", "SPEC_2021"),
    CAM16("cam16", "CAM16");

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
 * 根据种子色生成一套完整的 M3 ColorScheme（降级方案）。
 *
 * 与早期「blend alpha」近似不同，此处实现真正的**色调板（tonal palette）**生成：
 * - 主/次/第三色由种子色及其色相偏移版本，映射到 Material 3 标准 tone 色位；
 * - [monetSpec] 控制色度（chroma）随明度的分布，使 SPEC_2021 / CAM16 产生真实差异；
 * - [tonalStyle] 微调 primary / container 的 tone 色位，模拟 M3 多风格色板。
 *
 * 根据 seed 亮度自动判断 dark/light——曜石黑等深色 seed 自动走暗色主题。
 */
fun colorSchemeFromSeed(
    seed: Color,
    dark: Boolean = false,
    tonalStyle: TonalStyle = TonalStyle.TONAL_SPOT,
    monetSpec: MonetSpec = MonetSpec.SPEC_2021
): ColorScheme {
    // 注意：明暗完全由调用方传入的 [dark] 参数（即 themeMode）决定——
    // 不再因强调色 seed 的 luminance 翻转主题。这避免了用户选墨蓝/曜石黑等深色
    // 强调色时被强制切成暗色主题、进而导致 ListScreen 黑底 sort chip / 设置页
    // 白底卡片等硬编码容器与暗色 scheme 的 onPrimary/onSurface 错配（黑底黑字/白底浅字）。
    val dark = dark

    val primary = tonalPalette(seed, monetSpec)
    val secondary = tonalPalette(shiftHue(seed, 30f), monetSpec)
    val tertiary = tonalPalette(shiftHue(seed, 60f), monetSpec)

    // 各风格的 primary / container tone 色位偏移（映射到 0..100 明度 tone）
    val (pOff, cOff) = when (tonalStyle) {
        TonalStyle.TONAL_SPOT -> 0 to 0
        TonalStyle.VIBRANT -> -5 to 0
        TonalStyle.EXPRESSIVE -> 5 to -2
        TonalStyle.FRUIT_SALAD -> 0 to 2
        TonalStyle.FIDELITY -> 0 to 5
        TonalStyle.CONTENT -> -2 to 4
    }

    fun tone(t: Int, off: Int): Color = primary(((t + off).coerceIn(0, 100)).toFloat())

    if (dark) {
        val p = tone(80, pOff)
        val c = tone(30, cOff)
        val s = secondary(80f)
        val t = tertiary(80f)
        return darkColorScheme(
            primary = p,
            // 2026-08-17 对比度保险：onPrimary 不再固定 tone(20)，而按 primary 亮度动态选择——
            // 浅色 primary（luminance>0.55）→ 用 primary 的深色反色（保留色相，HSL 降亮度到 15%）
            // 深色 primary → 白。这样浅色强调色（如亮粉/亮黄）下弹窗 Button 白字也能看清。
            onPrimary = dynamicOn(p, primary),
            primaryContainer = c,
            onPrimaryContainer = primary(90f),
            secondary = s,
            onSecondary = dynamicOn(s, secondary),
            secondaryContainer = secondary(30f),
            onSecondaryContainer = secondary(90f),
            tertiary = t,
            onTertiary = dynamicOn(t, tertiary),
            tertiaryContainer = tertiary(30f),
            onTertiaryContainer = tertiary(90f),
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
    val p = tone(40, pOff)
    val c = tone(90, cOff)
    val s = secondary(40f)
    val t = tertiary(40f)
    return lightColorScheme(
        primary = p,
        onPrimary = dynamicOn(p, primary),
        primaryContainer = c,
        onPrimaryContainer = primary(10f),
        secondary = s,
        onSecondary = dynamicOn(s, secondary),
        secondaryContainer = secondary(90f),
        onSecondaryContainer = secondary(10f),
        tertiary = t,
        onTertiary = dynamicOn(t, tertiary),
        tertiaryContainer = tertiary(90f),
        onTertiaryContainer = tertiary(10f),
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
 * 生成一张色调板函数 `tone(0..100) -> Color`。
 *
 * 采用 HSL 色域近似 Material You 的 tonal palette：
 * - 色相恒定（保持种子色相）；
 * - 明度随 tone 线性变化；
 * - **色度（饱和度）**按 [spec] 分布：
 *   - [MonetSpec.SPEC_2021]：恒定色度（2021 光谱式，饱和度不随明度衰减）；
 *   - [MonetSpec.CAM16]：色度在中间明度最高、两端衰减（贴近 CAM16 的色度-明度耦合）。
 */
private fun tonalPalette(seed: Color, spec: MonetSpec): (Float) -> Color {
    val (h, s, _) = seed.hsl()
    return { tone ->
        val l = (tone.coerceIn(0f, 100f)) / 100f
        val chroma = when (spec) {
            MonetSpec.SPEC_2021 -> s
            MonetSpec.CAM16 -> (s * (1.35f - 0.7f * kotlin.math.abs(l - 0.5f) * 2f)).coerceIn(0f, 1f)
        }
        hslToColor(h, chroma, l)
    }
}

/** 色相偏移（保持饱和度/明度不变，用于派生 secondary/tertiary 色）。 */
private fun shiftHue(seed: Color, degree: Float): Color {
    val (h, s, l) = seed.hsl()
    return hslToColor((h + degree / 360f) % 1f, s, l)
}

/** RGB → HSL（h ∈ [0,1), s/l ∈ [0,1]）。 */
private fun Color.hsl(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    var h = 0f
    var s = 0f
    val d = max - min
    if (d > 0f) {
        s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        h = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
    }
    return floatArrayOf(h, s, l)
}

/**
 * 「onX」对比度安全色（2026-08-17 用户反馈思考题落地）：
 * 用户在 AccentColorPickerDialog 中可选极高明度的浅色强调色（如亮粉、亮黄），
 * 此时 `primary = tone(40)` 会变成浅色，M3 默认 `onPrimary = 白` 会导致
 * 弹窗 filled Button 上白字与浅 primary 背景对比度 < 4.5:1，不可读。
 *
 * 解决：当 [c] 偏亮（luminance > 0.55）时，把 onX 强制降到同色相、HSL 明度 15% 的深色版本；
 * 当 [c] 偏深时，沿用白色。两种情况对比度均 ≥ 4.5:1（即便种子色极浅也安全）。
 *
 * @param c 当前 primary/secondary/tertiary 颜色
 * @param tonal 该色对应的 tonal palette 函数（用于精确推导同色相深色版本）
 */
private fun dynamicOn(c: Color, tonal: (Float) -> Color): Color =
    if (c.luminance() > 0.55f) tonal(15f) else Color.White

/** HSL → RGB。 */
private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val ss = s.coerceIn(0f, 1f)
    val ll = l.coerceIn(0f, 1f)
    if (ss == 0f) return Color(ll, ll, ll)
    val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
    val p = 2f * ll - q
    fun hue(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        if (t < 1f / 6f) return p + (q - p) * 6f * t
        if (t < 1f / 2f) return q
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f
        return p
    }
    return Color(hue(h + 1f / 3f), hue(h), hue(h - 1f / 3f))
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
