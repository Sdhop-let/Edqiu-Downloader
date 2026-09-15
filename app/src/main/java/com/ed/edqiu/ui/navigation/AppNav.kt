package com.ed.edqiu.ui.navigation

import android.app.Application
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.ed.edqiu.navigation.AppNavigation
import com.ed.edqiu.navigation.MineNav
import com.ed.edqiu.di.AppContainer
import com.ed.edqiu.di.EdqiuViewModelFactory
import com.ed.edqiu.ui.components.CapsuleFeedbackController
import com.ed.edqiu.ui.components.CapsuleFeedbackHost
import com.ed.edqiu.ui.components.EdqiuSnackbarHost
import com.ed.edqiu.ui.components.GlassBackground
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
import com.ed.edqiu.ui.theme.MonetSpec
import com.ed.edqiu.ui.theme.ThemeMode
import com.ed.edqiu.ui.theme.ThemeEffects
import com.ed.edqiu.ui.theme.TonalStyle
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
private const val NAV_TRANSITION_MS = 280
private const val NAV_TRANSITION_EXIT_MS = 200

@Composable
fun EdqiuApp(container: AppContainer) {
    val dynamicColor by container.settingsRepository.dynamicColorFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val themeMode by container.settingsRepository.themeModeFlow
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val accentColor by container.settingsRepository.accentColorFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_ACCENT_COLOR)
    val tonalStyle by container.settingsRepository.tonalStyleFlow
        .collectAsStateWithLifecycle(initialValue = TonalStyle.TONAL_SPOT)
    val monetSpec by container.settingsRepository.monetSpecFlow
        .collectAsStateWithLifecycle(initialValue = MonetSpec.SPEC_2021)
    val blurEnabled by container.settingsRepository.blurEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val blurIntensity by container.settingsRepository.blurIntensityFlow
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
    // 模糊强度：开关关闭时归零；开启时按 0..1 映射到背景/磨砂的软化程度
    val blurStrength = if (blurEnabled) blurIntensity else 0f

    // 界面缩放：通过 LocalDensity 注入 fontScale（不影响系统字体，只缩放 App 内 Compose UI）
    val baseDensity = LocalDensity.current
    val scaledDensity = androidx.compose.runtime.remember(displayScale, baseDensity) {
        androidx.compose.ui.unit.Density(baseDensity.density, fontScale = displayScale)
    }

    // 主题效果：通过 CompositionLocal 注入全局模糊强度与液态玻璃开关，
    // 使任意层级的玻璃组件统一实时跟随主题设置
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        ThemeEffects.BlurStrength provides blurStrength,
        ThemeEffects.LiquidGlassEnabled provides liquidGlass,
        com.ed.edqiu.ui.util.LocalHapticStrength provides hapticStrength
    ) {
    EdqiuTheme(
        themeMode = themeMode,
        keyColor = keyColor,
        dynamicColor = dynamicColor,
        tonalStyle = tonalStyle,
        monetSpec = monetSpec
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

            // 预测性返回：Android 14+ 系统预测性返回动画（跟随手指 + 可预测），受主题设置开关控制
            PredictiveBackHandler(enabled = predictiveBack && canGoBack) {
                nav.popBackStack()
            }

            CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
                GlassBackground(
                    seed = keyColor,
                    // 2026-09-14 模糊强度区分度修复：线性 40dp 在渐变背景上肉眼几乎无差异，
                    // 改非线性二次映射（0→0 / 0.33→7dp / 0.67→29dp / 1→64dp），低中高段拉开档位
                    blurRadius = if (blurEnabled) (blurIntensity * blurIntensity * 64f).dp else 0.dp
                ) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        // 让内容延伸到状态栏后（每个页面的 HeaderPanel 自己加 statusBarsPadding）
                        contentWindowInsets = WindowInsets(0),
                        snackbarHost = {
                            // 双宿主叠加：传统 Snackbar（带操作按钮）+ 底部玻璃胶囊（状态反馈，浮于其上）
                            Box {
                                EdqiuSnackbarHost(hostState = snackbarHostState)
                                CapsuleFeedbackHost(controller = capsuleController)
                            }
                        }
                    ) { innerPadding ->
                        NavHost(
                            navController = nav,
                            startDestination = Routes.DOWNLOAD_SHELL,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            // 转场（2026-09-14）：默认生硬切换 → 柔和淡入 + 微缩放（iOS 呼吸感）
                            // 新页淡入放大 0.96→1（视觉"靠近"），旧页纯淡出让位；返回反向
                            enterTransition = {
                                fadeIn(tween(NAV_TRANSITION_MS, easing = FastOutSlowInEasing)) +
                                    scaleIn(
                                        initialScale = 0.96f,
                                        animationSpec = tween(NAV_TRANSITION_MS, easing = FastOutSlowInEasing)
                                    )
                            },
                            exitTransition = {
                                fadeOut(tween(NAV_TRANSITION_EXIT_MS, easing = FastOutSlowInEasing))
                            },
                            popEnterTransition = {
                                // 返回时上一界面带轻微"回位"缩放（0.98→1），与 push 的 0.96→1 呼应
                                fadeIn(tween(NAV_TRANSITION_MS, easing = FastOutSlowInEasing)) +
                                    scaleIn(
                                        initialScale = 0.98f,
                                        animationSpec = tween(NAV_TRANSITION_MS, easing = FastOutSlowInEasing)
                                    )
                            },
                            popExitTransition = {
                                fadeOut(tween(NAV_TRANSITION_EXIT_MS, easing = FastOutSlowInEasing)) +
                                    scaleOut(
                                        targetScale = 0.97f,
                                        animationSpec = tween(NAV_TRANSITION_EXIT_MS, easing = FastOutSlowInEasing)
                                    )
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
        }
    }
    }
}
