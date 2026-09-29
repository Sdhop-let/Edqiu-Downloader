package com.ed.edqiu.ui.navigation

import android.app.Application
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ed.edqiu.navigation.AppNavigation
import com.ed.edqiu.navigation.MineNav
import com.ed.edqiu.di.AppContainer
import com.ed.edqiu.di.EdqiuViewModelFactory
import com.ed.edqiu.ui.components.CapsuleFeedbackController
import com.ed.edqiu.ui.components.CapsuleFeedbackHost
import com.ed.edqiu.ui.components.EdqiuSnackbarHost
import com.ed.edqiu.ui.components.GlassBackground
import com.ed.edqiu.ui.components.LocalAppBackdrop
import com.ed.edqiu.ui.detail.DetailScreen
import com.ed.edqiu.ui.detail.DetailViewModel
import com.ed.edqiu.ui.history.HistoryScreen
import com.ed.edqiu.ui.history.HistoryViewModel
import com.ed.edqiu.ui.list.ListScreen
import com.ed.edqiu.ui.list.ListViewModel
import com.ed.edqiu.ui.authors.AuthorsScreen
import com.ed.edqiu.ui.authors.AuthorsViewModel
import com.ed.edqiu.ui.backup.CloudBackupScreen
import com.ed.edqiu.ui.backup.CloudBackupViewModel
import com.ed.edqiu.ui.backup.MediaBackupScreen
import com.ed.edqiu.ui.backup.MediaBackupViewModel
import com.ed.edqiu.ui.screens.MineScreen
import com.ed.edqiu.ui.settings.BackupViewModel
import com.ed.edqiu.ui.settings.SettingsScreen as EdqiuSettingsScreen
import com.ed.edqiu.ui.settings.XSection
import com.ed.edqiu.ui.theme.EdqiuTheme
import com.ed.edqiu.ui.theme.ThemeMode
import com.ed.edqiu.ui.theme.ThemeEffects
import com.ed.edqiu.data.preferences.SettingsRepository

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未提供")
}

val LocalSnackbarController = staticCompositionLocalOf<SnackbarController> {
    error("SnackbarController 未提供")
}

private object Routes {
    const val DOWNLOAD_SHELL = "download_shell"
    const val DETAIL = "detail/{tweetId}"
    const val BACKUP = "backup_center"
    const val MEDIA_BACKUP = "media_backup"
    fun detail(tweetId: String) = "detail/$tweetId"
}

/** 页面转场时长：iOS 风格柔和淡入淡出 + 微缩放，280ms 是「顺滑不拖沓」的平衡点 */

@Composable
fun EdqiuApp(container: AppContainer) {
    val dynamicColor by container.settingsRepository.dynamicColorFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val themeMode by container.settingsRepository.themeModeFlow
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val accentColor by container.settingsRepository.accentColorFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_ACCENT_COLOR)
    val blurIntensity by container.settingsRepository.blurIntensityFlow
        .collectAsStateWithLifecycle(initialValue = 0.6f)
    val glassTransparency by container.settingsRepository.glassTransparencyFlow
        .collectAsStateWithLifecycle(initialValue = 0.6f)
    val refractionIntensity by container.settingsRepository.refractionIntensityFlow
        .collectAsStateWithLifecycle(initialValue = 0.6f)
    val displayScale by container.settingsRepository.displayScaleFlow
        .collectAsStateWithLifecycle(initialValue = 0.8f)
    val floatingTabBar by container.settingsRepository.floatingTabBarFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val liquidGlass by container.settingsRepository.liquidGlassEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val hapticStrength by container.settingsRepository.hapticStrengthFlow
        .collectAsStateWithLifecycle(initialValue = 2)
    val predictiveBack by container.settingsRepository.predictiveBackFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val keyColor = Color(accentColor)
    // 玻璃三参数（2026-09-28 拆分独立滑块）：透明度 / 磨砂 / 折射各自独立调节，
    // 随液态玻璃开关联动作用对象（见 ThemeEffects）
    val frostStrength = blurIntensity

    // 界面缩放（2026-09-28 真全局缩放）：displayScale 乘入 density（dp 基准），
    // fontScale 保持系统设置。sp 字号 px = sp × fontScale × density，dp 卡片/间距/排版
    // 与字号同比缩放；旧版只改 fontScale（仅文字缩放），卡片排版纹丝不动
    val baseDensity = LocalDensity.current
    val scaledDensity = androidx.compose.runtime.remember(displayScale, baseDensity) {
        androidx.compose.ui.unit.Density(baseDensity.density * displayScale, fontScale = baseDensity.fontScale)
    }

    // 主题效果：通过 CompositionLocal 注入全局模糊强度与液态玻璃开关，
    // 使任意层级的玻璃组件统一实时跟随主题设置
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        ThemeEffects.GlassTransparency provides glassTransparency,
        ThemeEffects.GlassFrostStrength provides frostStrength,
        ThemeEffects.GlassRefractionStrength provides refractionIntensity,
        ThemeEffects.LiquidGlassEnabled provides liquidGlass,
        com.ed.edqiu.ui.util.LocalHapticStrength provides hapticStrength
    ) {
    EdqiuTheme(
        themeMode = themeMode,
        keyColor = keyColor,
        dynamicColor = dynamicColor
    ) {
        CompositionLocalProvider(LocalAppContainer provides container) {
            val nav = rememberNavController()
            val navBackStackEntry by nav.currentBackStackEntryAsState()
            val canGoBack = navBackStackEntry != null && nav.previousBackStackEntry != null
            val application = LocalContext.current.applicationContext as Application
            val factory = remember(container, application) {
                EdqiuViewModelFactory(
                    savedLinkRepository = container.savedLinkRepository,
                    settingsRepository = container.settingsRepository,
                    linkCaptureCoordinator = container.linkCaptureCoordinator,
                    linkHistoryRepository = container.linkHistoryRepository,
                    historyBackupRepository = container.historyBackupRepository,
                    backupProviderRegistry = container.backupProviderRegistry,
                    backupTaskStore = container.backupTaskStore,
                    backupEngine = container.backupEngine,
                    backupCredentialStore = container.backupCredentialStore,
                    backupLedgerRepository = container.backupLedgerRepository,
                    preDownloadManager = container.preDownloadManager,
                    downloadScope = container.globalIoScope,
                    application = application
                )
            }

            val snackbarHostState = remember { SnackbarHostState() }
            val snackbarController = remember { SnackbarController() }
            val capsuleController = remember { CapsuleFeedbackController() }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                snackbarController.observe(scope, snackbarHostState, capsuleController)
            }

            // 返回处理（2026-09-29 跟手版）：这里【不能】放普通 BackHandler——
            // 它注册的是非 ANIMATION 回调，会抢占 OnBackPressedDispatcher 栈顶，
            // 导致系统在手势期间播默认"缩放卡片"动画（NavHost 2.9.8+ 内置的
            // PredictiveBackHandler 收不到 progress，seekTo 转场无法驱动）。
            // 返回完全交给 NavHost 内置的 PredictiveBackHandler：
            // 手势 progress → SeekableTransitionState.seekTo(popExit/popEnter)，
            // 当前页跟手滑出、下层 -1/5 视差跟手滑入，松手完成/取消弹回

            CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
                // ---- 液态玻璃（Backdrop 库）：外层捕获层 ----
                // appBackdrop 记录 GlassBackground + 外层 NavHost 的全部绘制，
                // 供捕获链之外的悬浮玻璃（胶囊反馈等）做真折射采样。
                // 注意：玻璃组件必须位于捕获链之外，否则会把上一帧自己的绘制采样回来，
                // alpha 逐帧累积导致玻璃逐步变实色（Backdrop 库的硬性结构要求）。
                val backdropBase = MaterialTheme.colorScheme.background
                val appBackdrop = rememberLayerBackdrop {
                    drawRect(backdropBase)
                    drawContent()
                }
                CompositionLocalProvider(LocalAppBackdrop provides appBackdrop) {
                    Box(Modifier.fillMaxSize()) {
                        // 捕获链内容层：背景 + 外层导航内容
                        Box(
                            Modifier
                                .fillMaxSize()
                                .layerBackdrop(appBackdrop)
                        ) {
                            GlassBackground(
                                // 背景柔化跟随磨砂强度（光晕边缘随磨砂弥散）
                                blurRadius = (frostStrength * frostStrength * 64f).dp
                            ) {
                                Scaffold(
                                    containerColor = Color.Transparent,
                                    // 让内容延伸到状态栏后（每个页面的 HeaderPanel 自己加 statusBarsPadding）
                                    contentWindowInsets = WindowInsets(0),
                                    snackbarHost = {
                                        // 传统 Snackbar（带操作按钮）；玻璃胶囊反馈已上移至捕获链外
                                        EdqiuSnackbarHost(hostState = snackbarHostState)
                                    }
                                ) { innerPadding ->
                                    NavHost(
                                        navController = nav,
                                        startDestination = Routes.DOWNLOAD_SHELL,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(innerPadding),
                                        // 丝滑过渡（2026-09-29 修正）：进入(enter/exit)保持原「滑动+淡变」
                                        // 方案；仅返回(pop)采用视频复刻的 iOS 式纯位移视差——顶层页整页
                                        // 右滑出、下层自左侧 1/5 视差位滑回，全程无透明度变化，
                                        // 400ms EaseOutCubic（帖子详情/设置等二级页返回场景）
                                        enterTransition = {
                                            slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 3 } +
                                                fadeIn(tween(300, easing = FastOutSlowInEasing))
                                        },
                                        exitTransition = {
                                            slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 5 } +
                                                fadeOut(tween(240, easing = FastOutSlowInEasing))
                                        },
                                        popEnterTransition = {
                                            // 2026-09-29 卡片翻页版：下层页以 ColorOS 桌面卡片式
                                            // 缩小态（88%）呈现，随手势幅度放大展开到全屏
                                            // （seek 跟手驱动），配合上层整页右滑出
                                            scaleIn(
                                                initialScale = 0.88f,
                                                animationSpec = tween(400, easing = EaseOutCubic)
                                            )
                                        },
                                        popExitTransition = {
                                            slideOutHorizontally(tween(400, easing = EaseOutCubic)) { it }
                                        }
                                    ) {
                                        composable(Routes.DOWNLOAD_SHELL) {
                                            AppNavigation(
                                                inboxContent = {
                                                    val vm = viewModel<ListViewModel>(factory = factory)
                                                    ListScreen(
                                                        vm = vm,
                                                        onOpenDetail = { nav.navigate(Routes.detail(it)) }
                                                    )
                                                },
                                                trashContent = {
                                                    val vm = viewModel<HistoryViewModel>(factory = factory)
                                                    HistoryScreen(vm = vm)
                                                },
                                                settingsContent = { _, mineNav ->
                                                    MineScreen(mineNav = mineNav)
                                                },
                                                edqiuSettingsContent = { section, onBack ->
                                                    val backupVm = viewModel<BackupViewModel>(factory = factory)
                                                    EdqiuSettingsScreen(
                                                        settings = container.settingsRepository,
                                                        backupVm = backupVm,
                                                        onBack = onBack,
                                                        showBack = true,
                                                        section = section
                                                    )
                                                },
                                                openCloudBackup = { nav.navigate(Routes.BACKUP) },
                                                onOpenMediaBackup = { nav.navigate(Routes.MEDIA_BACKUP) },
                                                authorsContent = { onBack ->
                                                    val vm = viewModel<AuthorsViewModel>(factory = factory)
                                                    AuthorsScreen(
                                                        vm = vm,
                                                        onBack = onBack
                                                    )
                                                },
                                                floatingTabBarEnabled = floatingTabBar,
                                                liquidGlassEnabled = liquidGlass,
                                                predictiveBackEnabled = predictiveBack
                                            )
                                        }
                                        composable(
                                            route = Routes.DETAIL,
                                            arguments = listOf(navArgument("tweetId") { type = NavType.StringType })
                                        ) { backStack ->
                                            val tweetId = backStack.arguments?.getString("tweetId").orEmpty()
                                            val vm = viewModel<DetailViewModel>(factory = factory)
                                            DetailScreen(
                                                vm = vm,
                                                tweetId = tweetId,
                                                onBack = { nav.popBackStack() }
                                            )
                                        }
                                        composable(Routes.BACKUP) {
                                            val vm = viewModel<CloudBackupViewModel>(factory = factory)
                                            CloudBackupScreen(
                                                vm = vm,
                                                onBack = { nav.popBackStack() }
                                            )
                                        }
                                        composable(Routes.MEDIA_BACKUP) {
                                            val vm = viewModel<MediaBackupViewModel>(factory = factory)
                                            MediaBackupScreen(
                                                vm = vm,
                                                onBack = { nav.popBackStack() }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 捕获链外：底部玻璃胶囊反馈（真折射，采 appBackdrop）
                        CapsuleFeedbackHost(controller = capsuleController)
                    }
                }
            }
        }
    }
    }
}
