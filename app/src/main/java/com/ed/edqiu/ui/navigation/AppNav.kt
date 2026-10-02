package com.ed.edqiu.ui.navigation

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ed.edqiu.navigation.AppNavigation
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
import com.ed.edqiu.ui.theme.EdqiuTheme
import com.ed.edqiu.ui.theme.ThemeMode
import com.ed.edqiu.ui.theme.ThemeEffects
import com.ed.edqiu.data.preferences.SettingsRepository
import kotlin.math.abs
import kotlinx.coroutines.launch

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未提供")
}

val LocalSnackbarController = staticCompositionLocalOf<SnackbarController> {
    error("SnackbarController 未提供")
}

/** 浮层路由（2026-09-29 自绘跟手返回版）：主界面常驻底层，二级页为浮层 */
private const val OVERLAY_DETAIL = "detail"
private const val OVERLAY_BACKUP = "backup"
private const val OVERLAY_MEDIA_BACKUP = "media_backup"

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
    val keyColor = Color(accentColor)
    // 玻璃三参数（2026-09-28 拆分独立滑块）：透明度 / 磨砂 / 折射各自独立调节
    val frostStrength = blurIntensity

    // 界面缩放（2026-09-28 真全局缩放）：displayScale 乘入 density（dp 基准），
    // fontScale 保持系统设置。sp 字号 px = sp × fontScale × density，dp 卡片/间距/排版
    // 与字号同比缩放
    val baseDensity = LocalDensity.current
    val scaledDensity = androidx.compose.runtime.remember(displayScale, baseDensity) {
        androidx.compose.ui.unit.Density(baseDensity.density * displayScale, fontScale = baseDensity.fontScale)
    }

    // 主题效果：通过 CompositionLocal 注入全局模糊强度与液态玻璃开关
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

                // ===== 浮层导航状态（2026-09-29 自绘跟手返回 / 2026-09-30 弹性滑入）=====
                // 主界面常驻底层；详情/网盘备份/媒体备份为浮层。
                // 打开：浮层自右弹性滑入（spring 0.8/300，带回弹），主界面 1/3 速左滑并淡出至 0.3；
                // 关闭/右缘拖拽：浮层跟手右移，主界面同步滑回。
                var overlayRoute by remember { mutableStateOf<String?>(null) }
                var overlayTweetId by remember { mutableStateOf("") }
                // 0f = 浮层全屏显示（打开态）；1f = 浮层完全屏外（关闭态）
                var closeProgress by remember { mutableFloatStateOf(1f) }
                var overlayDragging by remember { mutableStateOf(false) }

                fun openOverlay(route: String, tweetId: String = "") {
                    if (overlayRoute != null) return
                    overlayTweetId = tweetId
                    overlayRoute = route
                    closeProgress = 1f
                    scope.launch {
                        // 弹性滑入（收件箱帖子详细页过渡规格）：spring 回弹即
                        // 「新页面滑入时带有弹性回弹」，感知时长 ≈ 350ms
                        animate(
                            initialValue = 1f,
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                        ) { v, _ -> closeProgress = v }
                    }
                }

                fun closeOverlay() {
                    if (overlayRoute == null || overlayDragging) return
                    scope.launch {
                        animate(
                            initialValue = closeProgress,
                            targetValue = 1f,
                            animationSpec = tween(400, easing = EaseOutCubic)
                        ) { v, _ -> closeProgress = v }
                        overlayRoute = null
                    }
                }

                // BACK 键：浮层显示时动画关闭（拖拽中不响应）
                BackHandler(enabled = overlayRoute != null && !overlayDragging) {
                    closeOverlay()
                }

                CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
                    val backdropBase = MaterialTheme.colorScheme.background
                    val appBackdrop = rememberLayerBackdrop {
                        drawRect(backdropBase)
                        drawContent()
                    }
                    CompositionLocalProvider(LocalAppBackdrop provides appBackdrop) {
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .layerBackdrop(appBackdrop)
                            ) {
                                GlassBackground(
                                    blurRadius = (frostStrength * frostStrength * 64f).dp
                                ) {
                                    val p = closeProgress
                                    // ===== 常驻主界面：浮层打开时按过渡规格以 1/3 速向左
                                    // 滑出并淡出（alpha 1→0.3），随右缘拖拽跟手可逆 =====
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                translationX = -(1f - p) * size.width / 3f
                                                alpha = (0.3f + 0.7f * p).coerceIn(0f, 1f)
                                            }
                                    ) {
                                        AppNavigation(
                                            // 2026-10：播放器操作反馈接入全局胶囊控制器
                                            onFeedback = { kind, text -> capsuleController.show(kind, text) },
                                            inboxContent = {
                                                val vm = viewModel<ListViewModel>(factory = factory)
                                                ListScreen(
                                                    vm = vm,
                                                    onOpenDetail = { openOverlay(OVERLAY_DETAIL, it) }
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
                                            openCloudBackup = { openOverlay(OVERLAY_BACKUP) },
                                            onOpenMediaBackup = { openOverlay(OVERLAY_MEDIA_BACKUP) },
                                            authorsContent = { onBack ->
                                                val vm = viewModel<AuthorsViewModel>(factory = factory)
                                                AuthorsScreen(vm = vm, onBack = onBack)
                                            },
                                            floatingTabBarEnabled = floatingTabBar,
                                            liquidGlassEnabled = liquidGlass,
                                            predictiveBackEnabled = false
                                        )
                                    }
                                    // ===== 浮层：详情 / 网盘备份 / 媒体备份 =====
                                    overlayRoute?.let { route ->
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .graphicsLayer { translationX = p * size.width }
                                                .pointerInput(route) {
                                                        while (true) {
                                                            var gestureWasBack = false
                                                            var released = false
                                                            val edge = with(density) { 30.dp.toPx() }
                                                            val slop = with(density) { 12.dp.toPx() }
                                                            awaitEachGesture {
                                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                                if (down.position.x < size.width - edge) {
                                                                    // 非右缘启动：放行给子级（列表/视频滑动）
                                                                    while (true) {
                                                                        val e = awaitPointerEvent(PointerEventPass.Main)
                                                                        if (e.changes.all { !it.pressed }) break
                                                                    }
                                                                    return@awaitEachGesture
                                                                }
                                                                overlayDragging = true
                                                                val downId = down.id
                                                                val startClose = closeProgress
                                                                val w = size.width.toFloat()
                                                                var dirDecided = false
                                                                // 注意：这里读写的是外层 while 块的 gestureWasBack/released，
                                                                // 2026-10 修复：此前在内层重复声明遮蔽了外层变量，
                                                                // 324 行结算永远读到 false，跟手拖拽关闭手势完全失效
                                                                while (true) {
                                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                                    val change = event.changes.firstOrNull { it.id == downId }
                                                                    if (change == null) continue
                                                                    if (!change.pressed) {
                                                                        change.consume()
                                                                        released = true
                                                                        break
                                                                    }
                                                                    val dx = change.position.x - down.position.x
                                                                    val dy = change.position.y - down.position.y
                                                                    if (!dirDecided) {
                                                                        if (abs(dx) < slop && abs(dy) < slop) continue
                                                                        dirDecided = true
                                                                        if (abs(dy) >= abs(dx)) break
                                                                        gestureWasBack = true
                                                                    }
                                                                    if (!gestureWasBack) break
                                                                    // 横向返回：跟手驱动（向左拖 = 关闭度增加）
                                                                    closeProgress =
                                                                        (startClose - dx / (w * 0.62f)).coerceIn(0f, 1f)
                                                                    change.consume()
                                                                }
                                                                overlayDragging = false
                                                            }
                                                        // 受限块外结算动画（此处可调任意 suspend）
                                                        if (gestureWasBack && released && overlayRoute != null) {
                                                            val commit = closeProgress > 0.35f
                                                            animate(
                                                                initialValue = closeProgress,
                                                                targetValue = if (commit) 1f else 0f,
                                                                animationSpec = tween(280, easing = EaseOutCubic)
                                                            ) { v, _ -> closeProgress = v }
                                                            if (commit) overlayRoute = null
                                                        }
                                                    }
                                                }
                                        ) {
                                            when (route) {
                                                OVERLAY_BACKUP -> {
                                                    val vm = viewModel<CloudBackupViewModel>(factory = factory)
                                                    CloudBackupScreen(vm = vm, onBack = { closeOverlay() })
                                                }
                                                OVERLAY_MEDIA_BACKUP -> {
                                                    val vm = viewModel<MediaBackupViewModel>(factory = factory)
                                                    MediaBackupScreen(vm = vm, onBack = { closeOverlay() })
                                                }
                                                else -> {
                                                    val vm = viewModel<DetailViewModel>(factory = factory)
                                                    DetailScreen(
                                                        vm = vm,
                                                        tweetId = overlayTweetId,
                                                        onBack = { closeOverlay() }
                                                    )
                                                }
                                            }
                                            // 右缘手势排除条（系统限 200dp 高）：让自绘跟手拖拽
                                            // 能收到边缘触摸，不被系统返回手势消费
                                            Box(
                                                Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .width(24.dp)
                                                    .height(200.dp)
                                                    .systemGestureExclusion()
                                            )
                                        }
                                    }
                                }
                            }
                            // Snackbar（原 Scaffold slot 上移至浮层之上）
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                EdqiuSnackbarHost(hostState = snackbarHostState)
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
