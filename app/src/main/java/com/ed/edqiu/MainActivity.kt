package com.ed.edqiu

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.ed.edqiu.data.preferences.FirstLaunchManager
import com.ed.edqiu.ui.navigation.EdqiuApp
import com.ed.edqiu.ui.splash.LaunchSplash

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val container = (application as EdqiuApplication).container

        // 强制高刷（2026-09-14 v1.4.15 改为设置开关，默认开）：
        // 收集开关实时生效 —— 开=锁定最高刷新率模式，关=清除锁定回系统动态刷新
        lifecycleScope.launch {
            container.settingsRepository.highRefreshRateFlow.collect { enabled ->
                applyHighRefreshRate(enabled)
            }
        }

        val showSplash = FirstLaunchManager.isFirstLaunch(this)

        setContent {
            if (showSplash) {
                // remember 必须！否则每次重组 splashDone 重置为 false → 永远停在开屏
                var splashDone by remember { mutableStateOf(false) }
                if (!splashDone) {
                    LaunchSplash {
                        splashDone = true
                        FirstLaunchManager.markShown(this@MainActivity)
                    }
                } else {
                    EdqiuApp(container = container)
                }
            } else {
                EdqiuApp(container = container)
            }
        }
    }

    /**
     * 应用强制高刷（2026-09-14 v1.4.15 可开关）：
     * enabled=true 时从设备支持的显示模式中选同分辨率+最高刷新率的 mode，
     * 用 preferredDisplayModeId 锁定（比 preferredRefreshRate 约束强，
     * ColorOS 动态降频不易插手，PLK110 上即 120Hz）；false 时清除锁定回系统动态刷新。
     */
    private fun applyHighRefreshRate(enabled: Boolean) {
        runCatching {
            if (!enabled) {
                // modeId = 0 表示不指定，交还系统动态刷新策略
                window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
                return
            }
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return
            val current = display.mode
            // 只在同分辨率的模式里选（避免选中降分辨率的高刷模式）
            val best = display.supportedModes
                .filter {
                    it.physicalWidth == current.physicalWidth &&
                        it.physicalHeight == current.physicalHeight
                }
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = best.modeId
            }
            android.util.Log.i(
                "EdqiuHRR",
                "high refresh rate locked: ${best.refreshRate}Hz (mode ${best.modeId})"
            )
        }
    }
}
