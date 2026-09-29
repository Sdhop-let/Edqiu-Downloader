package com.ed.edqiu.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.ed.edqiu.ui.components.LocalAppBackdrop
import com.ed.edqiu.ui.player.MiniPlayerBar
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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

    // 返回处理（2026-09-29 二分实验）：恢复普通 BackHandler——18:44 实证存在它时
    // mIsAnimationCallback=true（predictive progress 正常分发），删除后变 false。
    // 疑似其 enabled 状态变化触发 dispatcher 重新向平台注册 ANIMATION 回调
    BackHandler(enabled = navController.previousBackStackEntry != null) {
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

    // Tab 切换统一入口：悬浮底栏与标准底栏共用（popUpTo 起点 + saveState/restoreState）
    val selectTab: (Int) -> Unit = { index ->
        val screen = tabScreens[index]
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

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

    // ---- 液态玻璃（Backdrop 库）：内层 shell 捕获层 ----
    // shellBackdrop 只记录页面内容（NavHost）；底栏/迷你播放条位于捕获链外，
    // 真折射采样「正下方的列表内容」。此处覆盖外层 LocalAppBackdrop 值。
    val backdropBase = MaterialTheme.colorScheme.background
    val shellBackdrop = rememberLayerBackdrop {
        drawRect(backdropBase)
        drawContent()
    }
    CompositionLocalProvider(LocalAppBackdrop provides shellBackdrop) {
    // Tab 路由顺序索引：底栏转场的方向判断用（收件箱/首页 0 → 媒体库 1 → 我的 2）
    val tabRouteIndex = remember(tabScreens) {
        tabScreens.map { it.route }.withIndex().associate { (i, r) -> r to i }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            // 内容延伸到状态栏后（每个页面 HeaderPanel 自己加 statusBarsPadding）
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                // 2026-09-28 修复"关闭悬浮底栏后没有任何底栏、无法切换 Tab（卡死当前页）"：
                // 悬浮底栏关闭时回退标准 M3 底部导航栏（containerColor 取 surface，
                // Monet 取色时跟随壁纸色域），保证两种开关状态下 Tab 切换能力都不缺失
                if (showBottomBar && !floatingTabBarEnabled) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ) {
                        tabScreens.forEachIndexed { index, screen ->
                            NavigationBarItem(
                                selected = index == selectedTabIndex,
                                onClick = { selectTab(index) },
                                icon = { Icon(screen.icon, contentDescription = screen.label) },
                                label = { Text(screen.label) }
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    // 仅保留系统 inset（状态栏/导航栏），不再为 Tab 额外占位
                    .padding(paddingValues)
                    // 液态玻璃捕获：底栏/迷你条折射的采样源 = 此 NavHost 的页面内容
                    .layerBackdrop(shellBackdrop),
                // 转场（2026-09-29 修正）：Tab 间切换与进入二级页恢复原「方向感知滑动+淡变」方案；
                // 仅二级页返回（tab↔非tab 的 pop，如帖子详情/设置/播放页返回）采用视频复刻的
                // iOS 式纯位移视差——顶层整页右滑出 + 下层自左 1/5 视差滑回，无透明度变化，
                // 400ms EaseOutCubic 快出缓停（对齐 2026-09-29 微信视频逐帧实测）
                enterTransition = {
                    val from = tabRouteIndex[initialState.destination.route]
                    val to = tabRouteIndex[targetState.destination.route]
                    when {
                        from == null || to == null -> slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 3 } +
                            fadeIn(tween(300, easing = FastOutSlowInEasing))
                        to > from ->
                            slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 4 } +
                                fadeIn(tween(300))
                        else ->
                            slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 4 } +
                                fadeIn(tween(300))
                    }
                },
                exitTransition = {
                    val from = tabRouteIndex[initialState.destination.route]
                    val to = tabRouteIndex[targetState.destination.route]
                    when {
                        from == null || to == null -> slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 5 } +
                            fadeOut(tween(240, easing = FastOutSlowInEasing))
                        to > from ->
                            slideOutHorizontally(tween(220)) { -it / 5 } + fadeOut(tween(220))
                        else ->
                            slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(220))
                    }
                },
                popEnterTransition = {
                    val from = tabRouteIndex[initialState.destination.route]
                    val to = tabRouteIndex[targetState.destination.route]
                    when {
                        // 二级页返回：下层页 ColorOS 桌面卡片式放大展开（跟手 seek 驱动）
                        from == null || to == null ->
                            scaleIn(
                                initialScale = 0.88f,
                                animationSpec = tween(400, easing = EaseOutCubic)
                            )
                        to > from ->
                            slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 4 } +
                                fadeIn(tween(300))
                        else ->
                            slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 4 } +
                                fadeIn(tween(300))
                    }
                },
                popExitTransition = {
                    val from = tabRouteIndex[initialState.destination.route]
                    val to = tabRouteIndex[targetState.destination.route]
                    when {
                        from == null || to == null ->
                            slideOutHorizontally(tween(400, easing = EaseOutCubic)) { it }
                        to > from ->
                            slideOutHorizontally(tween(220)) { -it / 5 } + fadeOut(tween(220))
                        else ->
                            slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(220))
                    }
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
        // （blurRadius 不再写死 8dp——GlassOverlaySurface 默认值会按全局「模糊强度」滑块取值）
        if (showBottomBar && floatingTabBarEnabled) {
            LiquidTabBar(
                tabs = tabScreens.map { LiquidTab(label = it.label, icon = it.icon) },
                selectedIndex = selectedTabIndex,
                onSelect = selectTab,
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
                // 播放器进出场（2026-09-29 修正）：返回退出对齐视频复刻规格——整页右滑出
                // 400ms EaseOutCubic 快出缓停；SurfaceView 约束保留短淡出 160ms
                // （长 alpha 会撕裂掉帧，onBack 已 pause 冻结画面，淡出配合冻结帧最顺滑）；
                // 进入保持原自右滑入+淡入不动
                enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } +
                    fadeIn(tween(200)),
                exit = slideOutHorizontally(tween(400, easing = EaseOutCubic)) { it } +
                    fadeOut(tween(160)),
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
                    .then(
                        // 标准底栏模式：迷你条抬到 NavigationBar（80dp）上方；
                        // 悬浮底栏模式：抬到 50dp 胶囊 + 间隙上方；无底栏：贴底
                        when {
                            showBottomBar && !floatingTabBarEnabled ->
                                Modifier.navigationBarsPadding().padding(bottom = 88.dp)
                            showBottomBar -> Modifier.padding(bottom = 116.dp)
                            else -> Modifier.padding(bottom = 24.dp)
                        }
                    )
            )
        }
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



