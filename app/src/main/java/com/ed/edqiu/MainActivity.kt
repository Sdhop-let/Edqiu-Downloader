package com.ed.edqiu

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.ed.edqiu.data.preferences.FirstLaunchManager
import com.ed.edqiu.ui.navigation.EdqiuApp
import com.ed.edqiu.ui.onboarding.OnboardingScreen
import com.ed.edqiu.ui.splash.LaunchSplash

class MainActivity : ComponentActivity() {

    // 2026-10 合规补齐：POST_NOTIFICATIONS（API 33+）此前只声明未申请——备份进度通知被系统静默丢弃
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝即无通知，备份照常 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2026-09-29 跟手返回：覆盖系统 predictive 返回的窗口转场为"无动画"——
        // ColorOS（Android 17）在 ANIMATION 回调激活时仍会在窗口层叠加缩放预览，
        // 与 App 内 NavHost 的 seek 转场叠加成双重动画；此调用将其清零
        // （Android 14+ API；0 = 无动画。预览阶段若系统仍强制播放则属 ROM 行为）
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 2026-10 清理：移除已废弃的 window.statusBarColor/navigationBarColor 直接赋值——
        // 上方 enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)) 已在全 API 级别覆盖

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
            // 2026-10 合规补齐：进入主界面时申请通知权限（一次性，拒绝后不再骚扰）
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            // 2026-10 UX 短板补齐：首次安装引导门控——onboarding_completed 未落库时
            // 显示三步引导（欢迎/Cookie/代理，皆可跳过），完成或跳过后永久进入主界面。
            // initial 取 !showSplash：首装立即出引导；老用户不等 DataStore 首读、不闪引导页。
            val onboardingDone by container.settingsRepository.onboardingCompletedFlow
                .collectAsState(initial = !showSplash)
            // 引导页本地兜底关闭标记：即使 DataStore 写入失败（editSafe 吞异常）也能进入主界面
            var onboardingDismissed by remember { mutableStateOf(false) }
            // 2026-10 产品决策：老用户（升级安装、已有 Cookie 或代理配置）自动跳过引导并落库完成标记，
            // 只引导真正的新用户（全新安装且无任何配置）
            var legacyUserSkipped by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val done = container.settingsRepository.onboardingCompletedFlow.first()
                if (!done) {
                    val appContext = applicationContext
                    val hasSetup = com.ed.edqiu.data.preferences.CookiePreferences(appContext).hasCookies() ||
                        com.ed.edqiu.data.preferences.ProxyPreferences(appContext).getProxySettings().enabled
                    if (hasSetup) {
                        container.settingsRepository.setOnboardingCompleted(true)
                        legacyUserSkipped = true
                    }
                }
            }
            val showOnboarding = !onboardingDone && !onboardingDismissed && !legacyUserSkipped

            if (showSplash) {
                // remember 必须！否则每次重组 splashDone 重置为 false → 永远停在开屏
                var splashDone by remember { mutableStateOf(false) }
                // 开屏 → （引导）→ 主界面：淡入交接（2026-09-28 统一过渡语言），替代硬切
                Crossfade(
                    targetState = splashDone,
                    animationSpec = tween(320),
                    label = "splashHandoff"
                ) { done ->
                    if (!done) {
                        LaunchSplash {
                            splashDone = true
                            FirstLaunchManager.markShown(this@MainActivity)
                        }
                    } else if (showOnboarding) {
                        OnboardingScreen(
                            settingsRepository = container.settingsRepository,
                            onFinished = { onboardingDismissed = true }
                        )
                    } else {
                        EdqiuApp(container = container)
                    }
                }
            } else if (showOnboarding) {
                OnboardingScreen(
                    settingsRepository = container.settingsRepository,
                    onFinished = { onboardingDismissed = true }
                )
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
