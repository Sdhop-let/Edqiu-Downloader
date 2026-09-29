package com.ed.edqiu.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 主题效果全局状态（由 EdqiuApp 顶层从 SettingsRepository 注入）。
 *
 * 通过 CompositionLocal 而非层层传参，让任意层级的玻璃组件（GlassSurface、
 * LiquidTabBar、HeaderPanel、弹窗遮罩等）都能读取到统一的玻璃三参数，
 * 保证「全局生效、实时跟随、彼此协同」。
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
}
