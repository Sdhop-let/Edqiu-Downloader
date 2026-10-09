package com.ed.edqiu.ui.util

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
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
 * 无感按压（2026-09-14 建立，2026-10-10 增强触摸缩放）：
 * 1. 去掉默认 ripple（未裁剪元素上会显示黑色透明长方形框）；
 * 2. 点击时按 [LocalHapticStrength] 档位给一次震动 —— "摸得到"的按压确认，iOS 手感；
 * 3. **按压缩放反馈（2026-10-10 全局动画统一）**：按下时 1.0 → 0.97 轻缩放、
 *    松开平滑恢复——spring 低刚度无机械弹跳，动画期间可再次接收交互，
 *    符合总指令 §6.1（基础按压反馈 100-180ms 量级）。缩放纯 graphicsLayer，
 *    不触发重组；ReduceMotion 开启时自动退化为纯震动。
 *
 * 适用：设置行、菜单行、筛选 chip、卡片等高频点击的行级元素。
 * 需要 ripple 的按钮类组件（Button/TextButton）不使用此修饰符。
 */
fun Modifier.pressableNoRipple(
    enabled: Boolean = true,
    haptic: Boolean = true,
    scaleOnPress: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val view = LocalView.current
    val strength = LocalHapticStrength.current
    val interactionSource = remember { MutableInteractionSource() }
    val reduceMotion = rememberReduceMotion()

    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && scaleOnPress && !reduceMotion) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 600f),
        label = "pressable_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
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
