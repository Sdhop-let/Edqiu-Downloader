package com.ed.edqiu.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主题效果全局状态（由 EdqiuApp 顶层从 SettingsRepository 注入）。
 *
 * 通过 CompositionLocal 而非层层传参，让任意层级的玻璃组件（GlassSurface、
 * LiquidTabBar、HeaderPanel、弹窗遮罩等）都能读取到统一的玻璃参数，
 * 保证「全局生效、实时跟随、彼此协同」。
 *
 * 2026-10-09 v1.8.0 扩展：在原有三参数（透明度 / 磨砂 / 折射）之上补齐
 * 「玻璃外观」四项——描边粗细 / 亮边强度 / 描边颜色 / 压暗程度。
 * 命名沿用「对象 × 属性」正交法（对象=玻璃，属性=粗细 / 强度 / 颜色 / 程度），
 * 与原有的三个「强度」参数同构，设置页按同一规则分组呈现。
 *
 * **默认值 = v1.7.0 既有行为**，即四项新参数的默认取值在渲染链上互为中性元，
 * 老用户升级后观感零变化；仅当用户主动拖动滑块 / 选色时才产生差异。
 */
object ThemeEffects {

    /** 玻璃透明度 0-1（0=实、1=透）：真玻璃表面色 alpha、卡片底色、普通面板 alpha。 */
    val GlassTransparency = staticCompositionLocalOf { 0.6f }

    /** 磨砂强度 0-1：真玻璃 blur（4-20dp）、卡片磨砂雾感、背景柔化半径。 */
    val GlassFrostStrength = staticCompositionLocalOf { 0.6f }

    /** 折射强度 0-1：真玻璃边缘 lens 位移 0.2x-1.6x（仅液态玻璃模式生效）。 */
    val GlassRefractionStrength = staticCompositionLocalOf { 0.6f }

    /** 液态玻璃全局开关。true = 真折射玻璃质感；false = 普通哑光面板（透明度/磨砂仍可调）。 */
    val LiquidGlassEnabled = staticCompositionLocalOf { true }

    // ── 玻璃外观（v1.8.0 新增；默认值均为中性元 = v1.7.0 观感） ──

    /**
     * 描边粗细（dp），0 = 不描边。
     * 作用于 [com.ed.edqiu.ui.components.glassEdgeLight] 的内描边线宽——
     * 该描边是 2026-09-21 投影移除后卡片「轮廓存在感」的唯一手段，此前硬编码 1.25dp。
     */
    val GlassEdgeWidth = staticCompositionLocalOf { 1.25f }

    /**
     * 亮边强度倍数 0-1.6，1 = 当前观感。
     * 等比缩放内描边白高光与顶部光带（[com.ed.edqiu.ui.components.GlassTier.topLight] 的乘数），
     * 0 = 完全熄灭高光（纯色磨砂面板），1.6 = 强反光。
     */
    val GlassEdgeLightStrength = staticCompositionLocalOf { 1f }

    /**
     * 描边 / 光边颜色；[Color.Unspecified] = 跟随主题色（默认，保持「单一色源」不变）。
     * 仅在用户显式选定颜色时覆盖内描边的 seed 层，属局部材质属性，
     * 不参与 ColorScheme 派生，因此不会破坏「壁纸取色 / 强调色」的单一色源结构。
     */
    val GlassEdgeColor = staticCompositionLocalOf { Color.Unspecified }

    /**
     * 压暗程度 0-1：在玻璃材质层叠加黑色，0 = 不压暗（默认）。
     * 深色模式下会自动 ×1.5 补偿（深色背景上同等压暗更难被感知），
     * 与「深色下需更强材质信号」的观感规律一致。
     */
    val GlassDimAmount = staticCompositionLocalOf { 0f }

    /** 深色模式下的压暗补偿系数（适配自 iOS 深色阴影加倍规律的材质等价实现）。 */
    const val DARK_DIM_COMPENSATION = 1.5f

    /**
     * 计算最终生效的压暗量：深色模式下自动放大，浅色模式原样。
     * 默认 dim=0 时两态均为 0，故未启用该参数的用户不受影响。
     */
    fun effectiveDim(dim: Float, dark: Boolean): Float {
        val v = dim.coerceIn(0f, 1f)
        return (if (dark) v * DARK_DIM_COMPENSATION else v).coerceIn(0f, 1f)
    }
}
