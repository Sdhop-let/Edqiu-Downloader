package com.ed.edqiu.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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
import com.ed.edqiu.ui.util.rememberReduceMotion
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.map

/** 二级页 Shared Axis 过渡曲线（2026-09-30 规格）：spring 回弹 */
private fun secondarySpringFloat() = spring<Float>(dampingRatio = 0.85f, stiffness = 380f)

/**
 * 把缩放坐标系（Scaffold graphicsLayer 中心锚点缩放）中的矩形换算回原尺寸坐标。
 * 2026-09-30 v1.6.9 退场定位用：媒体库卡片在收尾动画期间上报的窗口矩形
 * 处于 0.92~1 的实时缩放空间内，必须除以当时缩放比展开回 reveal=1 的位置，
 * 播放层才能精确缩小落到卡片封面上（否则落点整体向屏幕中心偏移最多 ~8%）。
 */
private fun Rect.scaleBackAroundScreenCenter(center: Offset, scale: Float): Rect {
    if (scale <= 0.001f) return this
    val inv = 1f / scale
    return Rect(
        center.x + (left - center.x) * inv,
        center.y + (top - center.y) * inv,
        center.x + (right - center.x) * inv,
        center.y + (bottom - center.y) * inv
    )
}

private fun secondarySpringOffset() = spring<IntOffset>(
    dampingRatio = 0.85f,
    stiffness = 380f,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

/** 二级页前进：详情页自右滑入整屏 + 淡入 */
private fun secondaryEnter() = slideInHorizontally(secondarySpringOffset()) { it } +
    fadeIn(secondarySpringFloat())

/** 二级页前进时下层列表页：向左滑出 1/4 屏 + 淡出 */
private fun secondaryExit() = slideOutHorizontally(secondarySpringOffset()) { -it / 4 } +
    fadeOut(secondarySpringFloat())

/** 二级页返回（自动反向）：列表页自左 1/4 滑回 + 淡入 */
private fun secondaryPopEnter() = slideInHorizontally(secondarySpringOffset()) { -it / 4 } +
    fadeIn(secondarySpringFloat())

/** 二级页返回（自动反向）：详情页向右滑出整屏 + 淡出 */
private fun secondaryPopExit() = slideOutHorizontally(secondarySpringOffset()) { it } +
    fadeOut(secondarySpringFloat())

/** Tab 切换进入：水平位移 + 轻微放大（0.95→1）+ 淡入，新页延迟 50ms 与旧页重叠 */
private fun tabSwitchEnter(fromLeft: Boolean) =
    slideInHorizontally(tween(300, delayMillis = 50, easing = FastOutSlowInEasing)) {
        if (fromLeft) -it else it
    } +
    scaleIn(tween(300, delayMillis = 50, easing = FastOutSlowInEasing), initialScale = 0.95f) +
    fadeIn(tween(300, delayMillis = 50, easing = FastOutSlowInEasing))

/** Tab 切换退出：向左/右滑出 + 轻微缩小（1→0.95）+ 淡出 */
private fun tabSwitchExit(toLeft: Boolean) =
    slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) {
        if (toLeft) -it else it
    } +
    scaleOut(tween(300, easing = FastOutSlowInEasing), targetScale = 0.95f) +
    fadeOut(tween(300, easing = FastOutSlowInEasing))

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
    // 2026-10：胶囊反馈回调（宿主全局 CapsuleFeedbackController），接线到 PlayerScreen
    onFeedback: ((com.ed.edqiu.ui.components.FeedbackKind, String) -> Unit)? = null,
    floatingTabBarEnabled: Boolean = true,
    liquidGlassEnabled: Boolean = true,
    predictiveBackEnabled: Boolean = true,
    // 2026-10 修复：浮层（详情/网盘备份/媒体备份）打开时须屏蔽 NavHost 的返回处理——
    // BackHandler 后注册者优先，本文件的返回 Handler 注册晚于 AppNav 的浮层返回，
    // 若不屏蔽，浮层开着按返回会先弹走底下的导航栈（肉眼不可见），连按数次后
    // 浮层关闭时露出的已是起点页（如收件箱），用户视角=「返回后页面不对/返回无效」
    navBackGated: Boolean = false
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 返回处理（2026-09-29 二分实验）：恢复普通 BackHandler——18:44 实证存在它时
    // mIsAnimationCallback=true（predictive progress 正常分发），删除后变 false。
    // 疑似其 enabled 状态变化触发 dispatcher 重新向平台注册 ANIMATION 回调
    BackHandler(enabled = !navBackGated && navController.previousBackStackEntry != null) {
        android.util.Log.e("BackDispatch", "NAVHOST BH fired gated=$navBackGated")
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
    // 屏幕中心（Scaffold 缩放锚点）：退场定位矩形的缩放换算基准
    val locateConfiguration = LocalConfiguration.current
    val locateDensity = LocalDensity.current
    val screenCenter = remember(locateConfiguration, locateDensity) {
        with(locateDensity) {
            Offset(
                locateConfiguration.screenWidthDp.dp.toPx() / 2f,
                locateConfiguration.screenHeightDp.dp.toPx() / 2f
            )
        }
    }

    var playerFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var playerVisible by rememberSaveable { mutableStateOf(false) }
    // 2026-09-30 v1.6.8 播放返回定位：退场时 PlayerScreen 回调当前视频路径，
    // 媒体库滚动定位该视频卡片（滑动切过视频后返回定位切到的那条）。
    // 打开播放器时必须清空，避免沿用上一次的定位目标
    var playerLocatePath by remember { mutableStateOf<String?>(null) }
    // Slidr 背景（2026-09-30）：背景还原进度 0=播放层盖住（页面缩至 0.92+压暗），
    // 1=播放层完全滑出（页面复原）。由 PlayerScreen 拖拽/滑入滑出实时上报
    var playerBgReveal by remember { mutableStateOf(1f) }
    // 容器变形转场（2026-09-30 第二版）：
    // - originBounds = 进场几何起点（媒体库点击卡片回传的封面矩形）
    // - targetBounds = 退场几何终点（媒体库定位滚动后回传的目标卡片封面矩形）
    // - infoHiddenFor/infoRevealed = 退场期间隐藏目标卡片信息区（作者/文案/下载），
    //   播放层缩小落定后放行 → 卡片信息淡入
    var playerOriginBounds by remember { mutableStateOf<Rect?>(null) }
    var playerTargetBounds by remember { mutableStateOf<Rect?>(null) }
    var infoHiddenFor by remember { mutableStateOf<String?>(null) }
    var infoRevealed by remember { mutableStateOf(false) }
    // 2026-09-30 v1.6.9：移除退场后的迷你播放条胶囊（用户反馈不符合预期：
    // 退出到媒体库后底部多出一条显示标题的胶囊，且仅短暂闪现 620ms 即消失）。
    // 播放器退场即完全收起，媒体库恢复原样。

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

    val showBottomBar = currentDestination?.route in tabScreens.map { it.route } && playerFilePath == null
    val selectedTabIndex = tabScreens.indexOfFirst { currentDestination?.hierarchy?.any { h -> h.route == it.route } == true }
        .coerceAtLeast(0)

    // 悬浮 Tab 栏避让高度：内容延伸到屏底，仅列表尾部留出避让区
    // Tab 48dp (扁平化) + 底部间隙 28dp + 安全余量 8dp = 84dp
    val tabBarClearance = 84.dp

    // ---- 液态玻璃（Backdrop 库）：内层 shell 捕获层 ----
    // shellBackdrop 只记录页面内容（NavHost）；底栏位于捕获链外，
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
    // 系统「减少动画」（无障碍移除动画/动画时长缩放=0）：全部转场降级为纯淡变
    val reduceMotion = rememberReduceMotion()
    val activePlayerPath = playerFilePath
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                // 容器变形底衬（2026-09-30 第二版）：播放层盖住时页面缩至 0.92，
                // 拖拽/缩小回卡片时随 playerBgReveal（0→1）复原；Scaffold 铺满全屏，
                // graphicsLayer 缩放锚点即屏幕中心。块内读状态 = 逐帧更新层参数不触发重组
                .graphicsLayer {
                    val scale = androidx.compose.ui.util.lerp(0.92f, 1f, playerBgReveal)
                    scaleX = scale
                    scaleY = scale
                },
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
                    // 液态玻璃捕获：底栏折射的采样源 = 此 NavHost 的页面内容
                    .layerBackdrop(shellBackdrop),
                // 转场（2026-09-30 统一动画规格；系统「减少动画」全部降级为纯淡变）：
                // - Tab↔Tab：水平位移 + 轻微缩放（0.95↔1）+ 淡变，300ms FastOutSlowIn，
                //   新页延迟 50ms 起步形成 ~50ms 轻微重叠；
                // - Tab↔二级页（Shared Axis X，spring 0.85/380）：前进 = 详情自右滑入+淡入、
                //   列表左滑 1/4 屏+淡出；返回自动反向。
                enterTransition = {
                    val from = tabRouteIndex[initialState.destination.route]
                    val to = tabRouteIndex[targetState.destination.route]
                    when {
                        reduceMotion -> fadeIn(tween(150))
                        from == null || to == null -> secondaryEnter()
                        to > from -> tabSwitchEnter(fromLeft = false)
                        else -> tabSwitchEnter(fromLeft = true)
                    }
                },
                exitTransition = {
                    val from = tabRouteIndex[initialState.destination.route]
                    val to = tabRouteIndex[targetState.destination.route]
                    when {
                        reduceMotion -> fadeOut(tween(150))
                        from == null || to == null -> secondaryExit()
                        to > from -> tabSwitchExit(toLeft = true)
                        else -> tabSwitchExit(toLeft = false)
                    }
                },
                popEnterTransition = {
                    val from = tabRouteIndex[initialState.destination.route]
                    val to = tabRouteIndex[targetState.destination.route]
                    when {
                        reduceMotion -> fadeIn(tween(150))
                        // 二级页返回：下层页随 Shared Axis 反向滑回（1/4 视差 + 淡入）
                        from == null || to == null -> secondaryPopEnter()
                        to > from -> tabSwitchEnter(fromLeft = false)
                        else -> tabSwitchEnter(fromLeft = true)
                    }
                },
                popExitTransition = {
                    val from = tabRouteIndex[initialState.destination.route]
                    val to = tabRouteIndex[targetState.destination.route]
                    when {
                        reduceMotion -> fadeOut(tween(150))
                        // 二级页返回：详情页整屏右滑出 + 淡出（前进的自动反向）
                        from == null || to == null -> secondaryPopExit()
                        to > from -> tabSwitchExit(toLeft = true)
                        else -> tabSwitchExit(toLeft = false)
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
                            // 2026-09-30 容器变形：入口只定位/预载不起播（autoStart=false），
                            // 真正 play() 由 PlayerScreen 展开落定后触发，进场期间无声；
                            // 2026-09-30 v1.6.9：openPlayer 建立类型分流的会话列表
                            // （视频会话只翻视频 / 图片会话只翻图片）
                            playerViewModel.openPlayer(filePath)
                            playerLocatePath = null
                            playerOriginBounds = null
                            playerTargetBounds = null
                            playerFilePath = filePath
                            playerVisible = true
                        }
                    )
                }
                composable(Screen.Library.route) {
                    MediaLibraryScreen(
                        historyViewModel = historyViewModel,
                        onNavigateToPlayer = { filePath, coverBounds ->
                            // 2026-09-30 容器变形：卡片封面矩形作为进场几何起点；
                            // 只预载不起播，play() 在展开落定后触发。
                            // 2026-09-30 v1.6.9：openPlayer 建立类型分流的会话列表
                            playerViewModel.openPlayer(filePath)
                            playerLocatePath = null
                            playerOriginBounds = coverBounds
                            playerTargetBounds = null
                            playerFilePath = filePath
                            playerVisible = true
                        },
                        locateFilePath = playerLocatePath,
                        // 定位滚动落定后回传目标卡片封面矩形（退场缩小终点）
                        // 2026-09-30 v1.6.9：上报矩形处于 Scaffold 实时缩放坐标系
                        //（0.92~1 随背景还原变化），按上报时的缩放比换算回原尺寸坐标，
                        // 播放层收尾才能精确落在卡片封面上（换算基准=上报时刻的 reveal）
                        onLocateBounds = { rect ->
                            val scale = androidx.compose.ui.util.lerp(0.92f, 1f, playerBgReveal)
                            playerTargetBounds = rect.scaleBackAroundScreenCenter(screenCenter, scale)
                        },
                        // 退场期间隐藏目标卡片信息区，落定后淡入（见 PlayerScreen onBack）
                        infoHiddenFor = infoHiddenFor,
                        infoRevealed = infoRevealed
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
                            onOpenMediaBackup = onOpenMediaBackup ?: {},
                            onOpenCloudBackup = openCloudBackup ?: {}
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
                        onOpenMediaBackup = onOpenMediaBackup ?: {},
                        onOpenCloudBackup = openCloudBackup ?: {}
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

        // 背景压暗（容器变形）：播放层盖住时 45% 黑，随缩小回卡片/展开实时还原到全透明
        if (activePlayerPath != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (1f - playerBgReveal).coerceIn(0f, 1f) * 0.45f }
                    .background(Color.Black)
            )
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

        if (activePlayerPath != null) {
            AnimatedVisibility(
                visible = playerVisible,
                // 2026-09-30 第二版：浮层进出场为 None，进场展开/拖拽跟手/
                // 缩小回卡片全部由 PlayerScreen 内部 geoT 容器变形引擎驱动
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                modifier = Modifier.fillMaxSize()
            ) {
                PlayerScreen(
                    playerViewModel = playerViewModel,
                    historyViewModel = historyViewModel,
                    autoPlayFilePath = activePlayerPath,
                    // 2026-10：宿主全局胶囊反馈（未接线时为空回调，行为不变）
                    onFeedback = onFeedback ?: { _, _ -> },
                    // 进场几何起点（媒体库卡片封面矩形；下载页入口为 null 走中央降级矩形）
                    originBounds = playerOriginBounds,
                    // 退场几何终点（媒体库定位滚动回传，实时跟随更新）
                    targetBounds = playerTargetBounds,
                    // 2026-10-05 定位失效修复：实时取值器供播放器退场等待定位回传
                    targetBoundsNow = { playerTargetBounds },
                    // 拖拽/缩小进度 → 背景缩放+压暗实时还原
                    onDragProgress = { playerBgReveal = it },
                    // 退场定位链路：记录当前视频 → 媒体库滚动定位；
                    // 同帧隐藏目标卡片信息区（等播放层缩小落定后再淡入）
                    onRequestExitLocate = { path ->
                        playerLocatePath = path
                        infoHiddenFor = path
                        infoRevealed = false
                        playerTargetBounds = null
                    },
                    onBack = {
                        // PlayerScreen 缩小落定后才回调：同帧收起浮层与全部会话状态。
                        // 2026-09-30 v1.6.9：迷你条已移除，退场即完全收起，不留任何悬浮件；
                        // 背景复原全尺寸、不压暗；放行卡片信息区淡入
                        playerVisible = false
                        playerFilePath = null
                        playerBgReveal = 1f
                        playerLocatePath = null
                        playerOriginBounds = null
                        playerTargetBounds = null
                        infoHiddenFor = null
                        infoRevealed = true
                    }
                )
            }
        }
    }
    }
}



