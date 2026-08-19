package com.ed.edqiu.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 主题效果全局状态（由 EdqiuApp 顶层从 SettingsRepository 注入）。
 *
 * 通过 CompositionLocal 而非层层传参，让任意层级的玻璃组件（GlassSurface、
 * LiquidTabBar、HeaderPanel、弹窗遮罩等）都能读取到统一的模糊强度与液态玻璃开关，
 * 保证「全局生效、实时跟随、彼此协同」。
 */
object ThemeEffects {
    /** 模糊强度 0.0-1.0（0 = 无模糊）。作用于背景莫奈色域与玻璃磨砂层。 */
    val BlurStrength = staticCompositionLocalOf { 0f }

    /** 液态玻璃全局开关。false 时 GlassSurface 退回扁平半透明。 */
    val LiquidGlassEnabled = staticCompositionLocalOf { true }
}
