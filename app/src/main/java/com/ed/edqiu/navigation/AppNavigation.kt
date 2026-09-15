package com.ed.edqiu.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ed.edqiu.ExternalDownloadRequest
import com.ed.edqiu.ui.screens.DownloadScreen
import com.ed.edqiu.ui.screens.HomeScreen
import com.ed.edqiu.ui.screens.MediaLibraryScreen
import com.ed.edqiu.ui.screens.PlayerScreen
import com.ed.edqiu.ui.screens.SettingsScreen
import com.ed.edqiu.viewmodel.DownloadViewModel
import com.ed.edqiu.viewmodel.HistoryViewModel
import com.ed.edqiu.viewmodel.PlayerViewModel
import com.ed.edqiu.ui.components.EdqiuIcons
import com.ed.edqiu.ui.components.LiquidTab
import com.ed.edqiu.ui.components.LiquidTabBar
import com.ed.edqiu.ui.player.MiniPlayerBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "下载器", Icons.Outlined.Home)
    data object Download : Screen("download", "下载", Icons.Outlined.TaskAlt)
    data object Library : Screen("library", "媒体库", EdqiuIcons.Media)
    data object Inbox : Screen("edqiu_inbox", "收件箱", EdqiuIcons.Inbox)
    data object Trash : Screen("edqiu_trash", "回收站", Icons.Outlined.Delete)
    data object Settings : Screen("settings", "我的", EdqiuIcons.Profile)
    data object Player : Screen("player", "播放", Icons.Outlined.Download)
    /** 二级页：下载器设置（分组过滤） */
    data object DownloaderSettings : Screen("downloader_settings", "下载器设置", Icons.Outlined.Tune)
    /** 二级页：收件箱设置（分组过滤） */
    data object EdqiuSettings : Screen("edqiu_settings", "收件箱设置", Icons.Outlined.Settings)
    /** 二级页：作者作品 */
    data object Authors : Screen("edqiu_authors", "作者作品", EdqiuIcons.Profile)
}

/**
 * 「我的」页二级导航能力（由 AppNavigation 构造，注入给 settingsContent）。
 */
class MineNav(
    val openDownloadCenter: () -> Unit,
    val openTrash: () -> Unit,
    val openDownloaderSection: (String) -> Unit,
    val openEdqiuSection: (String) -> Unit,
    val openBackupCenter: () -> Unit,
    val openAuthors: () -> Unit
)

@Composable
fun AppNavigation(
    externalDownloadRequest: ExternalDownloadRequest? = null,
    onExternalDownloadConsumed: (Long) -> Unit = {},
    inboxContent: (@Composable () -> Unit)? = null,
    trashContent: (@Composable () -> Unit)? = null,
    settingsContent: (@Composable (downloaderSettings: @Composable () -> Unit, mineNav: MineNav) -> Unit)? = null,
    edqiuSettingsContent: (@Composable (section: String?, onBack: () -> Unit) -> Unit)? = null,
    openCloudBackup: (() -> Unit)? = null,
    onOpenMediaBackup: (() -> Unit)? = null,
    authorsContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    floatingTabBarEnabled: Boolean = true,
    liquidGlassEnabled: Boolean = true,
    predictiveBackEnabled: Boolean = true
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 预测性返回：Android 14+ 系统预测性返回动画（跟随手指 + 可预测），受主题设置开关控制
    PredictiveBackHandler(enabled = predictiveBackEnabled && navController.previousBackStackEntry != null) {
        navController.popBackStack()
    }
    val edqiuMode = inboxContent != null
    val tabScreens = remember(edqiuMode) {
        if (edqiuMode) {
            // 「下载」已从 Tab 移除（功能保留在路由与我的页入口）
            listOf(Screen.Inbox, Screen.Library, Screen.Settings)
        } else {
            listOf(Screen.Home, Screen.Library, Screen.Settings)
        }
    }
    val startDestination = if (edqiuMode) Screen.Inbox.route else Screen.Home.route

    val downloadViewModel: DownloadViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val historyViewModel: HistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val playerViewModel: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    var playerFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var playerVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(externalDownloadRequest?.requestId) {
        if (externalDownloadRequest != null) {
            playerVisible = false
            playerFilePath = null
            navController.navigate(if (edqiuMode) Screen.Inbox.route else Screen.Home.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(playerVisible, playerFilePath) {
        if (!playerVisible && playerFilePath != null) {
            delay(280L) // ≥ 退场动画 240ms，动画播完再清路径，避免组合被硬移除
            playerFilePath = null
        }
    }

    val showBottomBar = currentDestination?.route in tabScreens.map { it.route } && playerFilePath == null
    val selectedTabIndex = tabScreens.indexOfFirst { currentDestination?.hierarchy?.any { h -> h.route == it.route } == true }
        .coerceAtLeast(0)

    // 悬浮 Tab 栏避让高度：内容延伸到屏底，仅列表尾部留出避让区
    // Tab 48dp (扁平化) + 底部间隙 28dp + 安全余量 8dp = 84dp
    val tabBarClearance = 84.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            // 内容延伸到状态栏后（每个页面 HeaderPanel 自己加 statusBarsPadding）
            contentWindowInsets = WindowInsets(0),
            // 不设 bottomBar：内容延伸到屏幕底部，悬浮 Tab 覆盖其上
            bottomBar = {}
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    // 仅保留系统 inset（状态栏/导航栏），不再为 Tab 额外占位
                    .padding(paddingValues),
                // Tab 切换转场（2026-09-14 v1.4.10）：纯淡入淡出 —— tab 间内容结构不同，
                // 缩放动画会让页面"忽大忽小"；fade 保持大小稳定，只做明度过渡
                enterTransition = {
                    fadeIn(tween(260, easing = FastOutSlowInEasing))
                },
                exitTransition = {
                    fadeOut(tween(180, easing = FastOutSlowInEasing))
                },
                popEnterTransition = {
                    fadeIn(tween(260, easing = FastOutSlowInEasing))
                },
                popExitTransition = {
                    fadeOut(tween(180, easing = FastOutSlowInEasing))
                }
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                        downloadViewModel = downloadViewModel,
                        externalDownloadRequest = externalDownloadRequest,
                        onExternalDownloadConsumed = onExternalDownloadConsumed
                    )
                }
                composable(Screen.Download.route) {
                    DownloadScreen(
                        downloadViewModel = downloadViewModel,
                        historyViewModel = historyViewModel,
                        onBack = { navController.popBackStack() },
                        onNavigateToPlayer = { filePath ->
                            // 2026-09-14 修复"立即返回后再点无法播放"：快速往返时 PlayerScreen
                            // 组合未销毁、LaunchedEffect(autoPlayFilePath) 不会重启，
                            // 必须在此显式触发 playVideo（幂等：播放中→早退，IDLE→重新 prepare）
                            playerViewModel.playVideo(filePath)
                            playerFilePath = filePath
                            playerVisible = true
                        }
                    )
                }
                composable(Screen.Library.route) {
                    MediaLibraryScreen(
                        historyViewModel = historyViewModel,
                        onNavigateToPlayer = { filePath ->
                            // 2026-09-14 修复"立即返回后再点无法播放"：快速往返时 PlayerScreen
                            // 组合未销毁、LaunchedEffect(autoPlayFilePath) 不会重启，
                            // 必须在此显式触发 playVideo（幂等：播放中→早退，IDLE→重新 prepare）
                            playerViewModel.playVideo(filePath)
                            playerFilePath = filePath
                            playerVisible = true
                        }
                    )
                }
                if (inboxContent != null) {
                    composable(Screen.Inbox.route) {
                        inboxContent()
                    }
                }
                if (trashContent != null) {
                    composable(Screen.Trash.route) {
                        trashContent()
                    }
                }
                if (authorsContent != null) {
                    composable(Screen.Authors.route) {
                        authorsContent({ navController.popBackStack() })
                    }
                }
                composable(Screen.Settings.route) {
                    val downloaderSettings: @Composable () -> Unit = {
                        SettingsScreen(
                            onBack = { navController.navigate(Screen.Home.route) },
                            onOpenMediaBackup = onOpenMediaBackup ?: {}
                        )
                    }
                    if (settingsContent != null) {
                        val mineNav = MineNav(
                            openDownloadCenter = {
                                navController.navigate(Screen.Download.route) { launchSingleTop = true }
                            },
                            openTrash = {
                                if (trashContent != null) {
                                    navController.navigate(Screen.Trash.route) { launchSingleTop = true }
                                }
                            },
                            openDownloaderSection = { section ->
                                navController.navigate(Screen.DownloaderSettings.route + "?section=$section") {
                                    launchSingleTop = true
                                }
                            },
                            openEdqiuSection = { section ->
                                navController.navigate(Screen.EdqiuSettings.route + "?section=$section") {
                                    launchSingleTop = true
                                }
                            },
                            openBackupCenter = openCloudBackup ?: {},
                            openAuthors = {
                                if (authorsContent != null) {
                                    navController.navigate(Screen.Authors.route) { launchSingleTop = true }
                                }
                            }
                        )
                        settingsContent(downloaderSettings, mineNav)
                    } else {
                        downloaderSettings()
                    }
                }

                // 二级页：下载器设置（分组过滤，返回箭头）
                composable(
                    route = Screen.DownloaderSettings.route + "?section={section}",
                    arguments = listOf(navArgument("section") { defaultValue = "" })
                ) { entry ->
                    val section = entry.arguments?.getString("section")?.takeIf { it.isNotBlank() }
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        showBack = true,
                        section = section,
                        onOpenMediaBackup = onOpenMediaBackup ?: {}
                    )
                }

                // 二级页：收件箱设置（分组过滤，返回箭头）
                composable(
                    route = Screen.EdqiuSettings.route + "?section={section}",
                    arguments = listOf(navArgument("section") { defaultValue = "" })
                ) { entry ->
                    val section = entry.arguments?.getString("section")?.takeIf { it.isNotBlank() }
                    if (edqiuSettingsContent != null) {
                        edqiuSettingsContent(section) { navController.popBackStack() }
                    }
                }
            }
        }

        // 悬浮液态胶囊 Tab 栏：navigationBarsPadding + 8dp 适配不同设备导航栏高度
        if (showBottomBar && floatingTabBarEnabled) {
            LiquidTabBar(
                tabs = tabScreens.map { LiquidTab(label = it.label, icon = it.icon) },
                selectedIndex = selectedTabIndex,
                onSelect = { index ->
                    val screen = tabScreens[index]
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
                liquidGlassEnabled = liquidGlassEnabled
            )
        }

        val activePlayerPath = playerFilePath
        if (activePlayerPath != null) {
            AnimatedVisibility(
                visible = playerVisible,
                // 播放器进出场（2026-09-14 v1.4.12 调优）：进场"沉入"保留（视频未加载，无 Surface 代价）；
                // 退场改纯透明度快速淡出 —— SurfaceView 是硬件合成层，scale/长 alpha 动画会撕裂掉帧，
                // 且 onBack 已 pause 冻结画面，短淡出配合冻结帧最顺滑
                enter = fadeIn(tween(200)) + scaleIn(initialScale = 1.06f, animationSpec = tween(200)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.fillMaxSize()
            ) {
                PlayerScreen(
                    playerViewModel = playerViewModel,
                    historyViewModel = historyViewModel,
                    autoPlayFilePath = activePlayerPath,
                    onBack = {
                        // 保持播放状态：返回列表后迷你播放条继续播放
                        playerVisible = false
                    }
                )
            }
        }

        // 迷你播放条：播放器退出后悬浮于底栏上方
        // 注意：collectAsState 收在 MiniPlayerHost 内部，避免高频 position 更新
        // （PlayerViewModel 每 350ms 同步一次）拖垮整个导航树的重组
        if (activePlayerPath != null) {
            MiniPlayerHost(
                playerViewModel = playerViewModel,
                visible = !playerVisible,
                onExpand = {
                    playerViewModel.playVideo(activePlayerPath)
                    playerFilePath = activePlayerPath
                    playerVisible = true
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showBottomBar) 116.dp else 24.dp)
            )
        }
    }
}

/**
 * 迷你播放条宿主：内部收集 playerState。
 *
 * PlayerViewModel 的 position 每 350ms 更新一次，若在 AppNavigation 顶层 collect，
 * 会导致 NavHost / Scaffold / LiquidTabBar 全部随进度条高频重组 —— 点击不跟手的根因。
 * 这里把收集隔离在本组件内，重组只影响迷你条自身。
 */
@Composable
private fun MiniPlayerHost(
    playerViewModel: PlayerViewModel,
    visible: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playerStateValue by playerViewModel.playerState.collectAsState()
    val miniCurrent = playerStateValue.currentVideo
    if (!visible || miniCurrent == null) return

    MiniPlayerBar(
        title = miniCurrent.title?.takeIf { it.isNotBlank() }
            ?: miniCurrent.filePath.substringAfterLast('/'),
        subtitle = miniCurrent.uploader?.takeIf { it.isNotBlank() } ?: "播放中",
        progress = if (playerStateValue.duration > 0) {
            (playerStateValue.position.toFloat() / playerStateValue.duration).coerceIn(0f, 1f)
        } else 0f,
        isPlaying = playerStateValue.isPlaying,
        onClick = onExpand,
        onTogglePlay = { playerViewModel.togglePlayPause() },
        modifier = modifier
    )
}



