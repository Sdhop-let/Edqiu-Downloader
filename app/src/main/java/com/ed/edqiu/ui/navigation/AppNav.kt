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
    // 玻璃外观四项（v1.8.0）：描边粗细 / 亮边强度 / 描边颜色 / 压暗程度
    val glassEdgeWidth by container.settingsRepository.glassEdgeWidthFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_GLASS_EDGE_WIDTH)
    val glassLightStrength by container.settingsRepository.glassEdgeLightStrengthFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_GLASS_LIGHT_STRENGTH)
    val glassEdgeColor by container.settingsRepository.glassEdgeColorFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_GLASS_EDGE_COLOR)
    val glassDimAmount by container.settingsRepository.glassDimAmountFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_GLASS_DIM_AMOUNT)
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
        ThemeEffects.GlassEdgeWidth provides glassEdgeWidth,
        ThemeEffects.GlassEdgeLightStrength provides glassLightStrength,
        ThemeEffects.GlassEdgeColor provides
            // 0 = 跟随主题色哨兵：映射为 Unspecified，避免 Color(0)（透明黑）被当成有效覆盖色
            if (glassEdgeColor == SettingsRepository.DEFAULT_GLASS_EDGE_COLOR) Color.Unspecified
            else Color(glassEdgeColor),
        ThemeEffects.GlassDimAmount provides glassDimAmount,
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

                // 临时诊断 + 自愈探针（2026-10）：三通道对比 + 冻结自愈。
                // 冻结签名：Snapshot apply 在走（重组/状态推进）而 OnPreDraw 长时间
                // 为零——ViewRoot 被平台生命周期批处理打进 mStopped=true 拒绝遍历
                //（view_not_visible），画面停在全量重组风暴前的旧帧。该状态只随
                // 窗口重建（recreate）恢复，故检测到即自愈重建；导航栈与 VM 均可恢复，
                // 仅浮层开合状态丢失
                val probeView = androidx.compose.ui.platform.LocalView.current
                val probeDraws = remember { java.util.concurrent.atomic.AtomicInteger(0) }
                val probeApplies = remember { java.util.concurrent.atomic.AtomicInteger(0) }
                androidx.compose.runtime.DisposableEffect(probeView) {
                    val l = android.view.ViewTreeObserver.OnPreDrawListener { probeDraws.incrementAndGet(); true }
                    probeView.viewTreeObserver.addOnPreDrawListener(l)
                    onDispose { probeView.viewTreeObserver.removeOnPreDrawListener(l) }
                }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    val obs = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { _, _ ->
                        probeApplies.incrementAndGet()
                    }
                    onDispose { obs.dispose() }
                }
                // 同一进程 30s 内只自愈一次，防连环重建
                val lastRecreateMs = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    var lastApply = -1
                    var lastDraw = -1
                    var stallRounds = 0
                    while (true) {
                        kotlinx.coroutines.delay(1200)
                        val a = probeApplies.get()
                        val d = probeDraws.get()
                        if (a != lastApply && d == lastDraw) stallRounds++ else stallRounds = 0
                        if (lastApply >= 0 && stallRounds >= 2 &&
                            System.currentTimeMillis() - lastRecreateMs.longValue > 30_000
                        ) {
                            lastRecreateMs.longValue = System.currentTimeMillis()
                            android.util.Log.e(
                                "FreezeProbe",
                                "render stall: apply=$a draws=$d 3.6s 无绘制 — recreate 自愈"
                            )
                            var ctx: android.content.Context? = probeView.context
                            while (ctx !is android.app.Activity && ctx is android.content.ContextWrapper) {
                                ctx = ctx.baseContext
                            }
                            (ctx as? android.app.Activity)?.recreate()
                            break
                        }
                        lastApply = a
                        lastDraw = d
                    }
                }
                LaunchedEffect(Unit) {
                    var ticks = 0
                    var lastLogMs = 0L
                    var lastCp = closeProgress
                    var lastRoute = overlayRoute
                    var lastStateChangeMs = 0L
                    while (true) {
                        androidx.compose.runtime.withFrameNanos { }
                        ticks++
                        val now = android.os.SystemClock.uptimeMillis()
                        if (ticks % 120 == 0 || now - lastLogMs > 3000) {
                            lastLogMs = now
                            android.util.Log.d(
                                "FreezeProbe",
                                "ticks=$ticks draws=${probeDraws.get()} shown=${probeView.isShown} " +
                                    "focus=${probeView.hasWindowFocus()} winVis=${probeView.windowVisibility} " +
                                    "attached=${probeView.isAttachedToWindow} cp=$closeProgress route=$overlayRoute " +
                                    "dragging=$overlayDragging"
                            )
                        }
                        lastCp = closeProgress
                        lastRoute = overlayRoute
                    }
                }

                fun openOverlay(route: String, tweetId: String = "") {
                    if (overlayRoute != null) return
                    overlayTweetId = tweetId
                    overlayRoute = route
                    closeProgress = 1f
                    overlayDragging = false
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
                    if (overlayRoute == null) return
                    if (overlayDragging) {
                        // 拖拽中按返回：直接收起浮层（拖拽协程随浮层离开组合而终止），
                        // 不再走动画结算，避免与进行中的拖拽协程互相干扰
                        overlayDragging = false
                        overlayRoute = null
                        return
                    }
                    scope.launch {
                        animate(
                            initialValue = closeProgress,
                            targetValue = 1f,
                            animationSpec = tween(400, easing = EaseOutCubic)
                        ) { v, _ -> closeProgress = v }
                        overlayRoute = null
                    }
                }

                // BACK 键（浮层）：见下方浮层内容内的注册点——2026-10 移入浮层内容，
                // 保证注册顺序晚于 AppNavigation 的 NavHost 返回 Handler（LIFO 优先），
                // 且只在浮层存在时注册/启用

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
                                    // ===== 常驻主界面：浮层打开时按过渡规格以 1/3 速向左
                                    // 滑出并淡出（alpha 1→0.3），随右缘拖拽跟手可逆。
                                    // 2026-10 冻结修复：closeProgress 改为在 graphicsLayer 块内
                                    // 延迟读取——旧实现 `val p = closeProgress` 在组合期读值，
                                    // 浮层动画期间整个 EdqiuApp 子树（含 NavHost/全部页面）每帧
                                    // 全量重组，与系统窗口可见性提交竞态后把 ViewRoot 打进
                                    // mStopped=true（view_not_visible），表现即"画面冻在半路"。
                                    // 层块内读状态 = 逐帧更新层参数且不触发重组
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                translationX = -(1f - closeProgress) * size.width / 3f
                                                alpha = (0.3f + 0.7f * closeProgress).coerceIn(0f, 1f)
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
                                            predictiveBackEnabled = false,
                                            navBackGated = overlayRoute != null
                                        )
                                    }
                                    // ===== 浮层：详情 / 网盘备份 / 媒体备份 =====
                                    overlayRoute?.let { route ->
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .graphicsLayer { translationX = closeProgress * size.width }
                                                .pointerInput(route) {
                                                    awaitEachGesture {
                                                        val edge = 30.dp.toPx()
                                                        val slop = 12.dp.toPx()
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
                                                        // 2026-10 修复：此前这两个变量声明在
                                                        // awaitEachGesture 外层的 while 里，而
                                                        // awaitEachGesture 自身永不返回，结算读到的
                                                        // 永远是初值 false——现随手势块声明
                                                        var gestureWasBack = false
                                                        var released = false
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
                                                        // 2026-10 关键修复：awaitEachGesture 把手势循环整个跑在
                                                        // awaitPointerEventScope 内、协程存活期间永不返回——
                                                        // 旧版"块外结算"（原 while(true) 后的提交/回弹动画）是
                                                        // 永远执行不到的死代码：拖拽跟手、松手后页面停在半开
                                                        // 位置，跟手关闭手势等于失效。结算必须经组合作用域
                                                        // scope.launch 在事件作用域外执行；仅在浮层仍是拖拽
                                                        // 发起的路由时才提交关闭
                                                        if (gestureWasBack && released && overlayRoute == route) {
                                                            val commit = closeProgress > 0.35f
                                                            scope.launch {
                                                                animate(
                                                                    initialValue = closeProgress,
                                                                    targetValue = if (commit) 1f else 0f,
                                                                    animationSpec = tween(280, easing = EaseOutCubic)
                                                                ) { v, _ -> closeProgress = v }
                                                                if (commit && overlayRoute == route) overlayRoute = null
                                                            }
                                                        }
                                                    }
                                                }
                                        ) {
                                            // BACK 键（浮层）：注册在浮层内容内——组合顺序晚于
                                            // AppNavigation 的 NavHost 返回 Handler，LIFO 优先级
                                            // 保证浮层打开时返回键必先关浮层，绝不穿透弹底层
                                            // 导航栈（2026-10 修复：此前注册早于 NavHost，真机上
                                            // 返回被 NavHost 抢走，浮层底下页面被逐层弹空）
                                            BackHandler(enabled = true) {
                                                android.util.Log.e("BackDispatch", "OVERLAY BH fired route=$route")
                                                closeOverlay()
                                            }
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
