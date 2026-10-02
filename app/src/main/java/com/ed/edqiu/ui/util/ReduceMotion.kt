package com.ed.edqiu.ui.util

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 系统级「减少动画」检测（无障碍 → 移除动画 / 开发者选项动画时长缩放 = 0）。
 *
 * 过渡动画（页面进退、共享元素飞入等）在开启时降级为纯淡入淡出；
 * 触感反馈、进度指示等非装饰性动画不受影响。
 *
 * 静态读取一次即可：该开关变更会重启 Activity（uiMode 配置变更），无需监听。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            val resolver = context.contentResolver
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f ||
                Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
