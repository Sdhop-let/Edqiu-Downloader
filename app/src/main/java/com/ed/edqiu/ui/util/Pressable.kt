package com.ed.edqiu.ui.util

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView

/**
 * 按压震动档位（2026-09-14 可调）：
 * 0 = 关闭；1 = 轻（CLOCK_TICK）；2 = 中（VIRTUAL_KEY）；3 = 明确（CONFIRM，API 30+，低版本退 VIRTUAL_KEY）。
 * 由 AppNav 顶层从 SettingsRepository 收集后经 [LocalHapticStrength] 注入。
 */
val LocalHapticStrength = staticCompositionLocalOf { 2 }

/** 档位 → HapticFeedbackConstants；CONFIRM 需 API 30+，低版本回退 VIRTUAL_KEY。 */
private fun hapticConstant(level: Int): Int = when (level) {
    1 -> android.view.HapticFeedbackConstants.CLOCK_TICK
    3 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        android.view.HapticFeedbackConstants.CONFIRM
    else android.view.HapticFeedbackConstants.VIRTUAL_KEY
    else -> android.view.HapticFeedbackConstants.VIRTUAL_KEY
}

/**
 * 无感按压（2026-09-14）：去掉默认 ripple（未裁剪元素上会显示黑色透明长方形框），
 * 点击时按 [LocalHapticStrength] 档位给一次震动 —— "看不到但摸得到"的按压确认，iOS 手感。
 *
 * 适用：设置行、菜单行、筛选 chip、卡片等高频点击的行级元素。
 * 需要 ripple 的按钮类组件（Button/TextButton）不使用此修饰符。
 */
fun Modifier.pressableNoRipple(
    enabled: Boolean = true,
    haptic: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val view = LocalView.current
    val strength = LocalHapticStrength.current
    val interactionSource = remember { MutableInteractionSource() }
    clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled
    ) {
        if (haptic && strength > 0) {
            view.performHapticFeedback(hapticConstant(strength))
        }
        onClick()
    }
}
