package com.ed.edqiu.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.ed.edqiu.R
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.domain.TweetIdExtractor
import com.ed.edqiu.service.SubscriptionManager
import com.ed.edqiu.ui.components.CapsuleFeedbackController
import com.ed.edqiu.ui.components.CapsuleFeedbackHost
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.SmoothAlertDialog
import com.ed.edqiu.ui.player.GlassActionsPanel
import com.ed.edqiu.ui.player.GlassPlayerControls
import com.ed.edqiu.ui.player.rememberAutoHide
import com.ed.edqiu.ui.util.copyToClipboard
import com.ed.edqiu.ui.util.openTwitterStatus
import com.ed.edqiu.viewmodel.HistoryViewModel
import com.ed.edqiu.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 播放器屏幕（容器变形转场 + 横向拖拽退场，2026-09-30 v1.6.8 第二版）。
 *
 * 转场语言（iOS 容器变形 / shared element）：
 * - 进场：播放层从「来源卡片封面矩形」展开至全屏（430ms EaseOutCubic），
 *   飞行中显示该条封面（与卡片同一张图），落定后才 play()（进场期间只预载不出声），
 *   随后控件淡入；
 * - 拖拽：横向跟手拖动播放层（竖向仍归 VerticalPager 切视频，互不冲突），
 *   背景随拖拽进度实时还原缩放/压暗（onDragProgress 上报）；
 * - 松手：位移 >42% 屏宽或快速甩动 → 位移回归与「全屏缩至目标封面矩形」
 *   同时进行，曲线落到媒体库卡片预览图上（末段 alpha 淡出交接给卡片真封面），
 *   落定后宿主让卡片信息区（作者/文案/下载信息）淡入；否则回弹归位；
 * - 返回键/←/删除 → 程序化走同一收尾路径（缩小至预览图）。
 * 退场开始即请求媒体库定位当前视频，滑出期间列表已滚到该视频卡片并回传
 * 最新封面矩形（targetBounds 实时生效，飞行终点跟随更新）。
 *
 * ⚠️ 渲染根因（2026-09-30 修复）：播放器视图必须 TextureView（见
 * res/layout/player_view_texture.xml）——SurfaceView 是独立合成层打洞渲染，
 * 不跟随 graphicsLayer 平移缩放，在 ColorOS 新机上转场期间表现为错位的
 * 小矩形、甚至 surface 销毁后只有声音没有画面。
 *
 * 铁律：功能零损失 —— 播放/暂停、seek、倍速、静音、全屏、播放列表、
 * 打开文件/原推文/复制/分享/删除 全部保留。
 */
@Composable
fun PlayerScreen(
    playerViewModel: PlayerViewModel,
    historyViewModel: HistoryViewModel,
    autoPlayFilePath: String? = null,
    // 进场几何起点：点击卡片时宿主回传的封面矩形（窗口坐标）；null 时降级为
    // 屏幕中央小矩形展开（下载页入口没有卡片坐标）
    originBounds: Rect? = null,
    // 退场几何终点：媒体库定位滚动后回传的目标卡片封面矩形（窗口坐标，
    // 定位滚动期间持续更新）。null 时退回 originBounds，再退回屏幕中央
    targetBounds: Rect? = null,
    // 拖拽/缩小进度（0=播放层盖住背景，1=背景完全还原）：宿主据此缩放+压暗背景
    onDragProgress: (Float) -> Unit = {},
    // 退场开始时回调当前视频路径：宿主据此驱动媒体库滚动定位（用户滑动切过
    // 视频后返回，定位的是切到的那条，不是进入时的卡片）
    onRequestExitLocate: (String) -> Unit = {},
    // 退场动画完成后回调（宿主同帧切 playerVisible=false 收起浮层、卡片信息淡入）
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val state by playerViewModel.playerState.collectAsState()
    // 2026-09-30 v1.6.9：翻页列表 = 播放会话列表（openPlayer 按入口类型分流的稳定快照）——
    // 视频会话只翻视频、图片会话只翻图片（优化1）；会话内冻结，后台库重排不再在手指下换页
    val playlist by playerViewModel.playerPlaylist.collectAsState()
    val rowState = rememberLazyListState()
    val current = state.currentVideo
    // 2026-09-16 修复「快速滑动回跳第一个视频」：current 不在列表时返回 null（保持 pager
    // 原地），不再兜 0——旧逻辑 ?: 0 会在 current 短暂失配（列表流刷新/画质升级换路径）
    // 时把 currentIndex 错误归 0，配合下方反向 animateScrollToPage 形成回跳环。
    val currentIndex = current?.filePath?.let { fp ->
        playlist.indexOfFirst { it.filePath == fp }.takeIf { it >= 0 }
    }
    // 会话类型（2026-09-30 v1.6.9）：整个会话要么全视频要么全图片（openPlayer 保证），
    // 图片会话不组合 ExoPlayer 的 PlayerView、每页直接展示图片——
    // 消除旧版视频/图片互切时 AndroidView 反复重建造成的闪屏与比例跳变
    val sessionIsImage = playlist.firstOrNull()?.let { MediaFileTypes.isImageFile(it.filePath) }
        ?: (current?.filePath?.let { MediaFileTypes.isImageFile(it) } ?: false)
    val backgroundInteraction = remember { MutableInteractionSource() }
    var pendingDelete by remember { mutableStateOf<DownloadHistoryEntity?>(null) }
    var showActions by remember { mutableStateOf(false) }

    // ===================== 容器变形引擎（2026-09-30 第二版） =====================
    // 全屏矩形（窗口坐标；LocalConfiguration 与 boundsInWindow 同一坐标系）
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val fullRect = remember(configuration, density) {
        with(density) {
            Rect(
                0f, 0f,
                configuration.screenWidthDp.dp.toPx(),
                configuration.screenHeightDp.dp.toPx()
            )
        }
    }
    val scope = rememberCoroutineScope()
    // 收尾进行中（缩小回卡片/程序化退出）：期间拒绝新的拖拽与重复退出
    var exiting by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // 几何进度：0 = 缩在缩略图矩形，1 = 全屏。进场 0→1，退场 1→0
    val geoT = remember { Animatable(0f) }
    // 横向拖拽位移（px）：拖拽跟手；松手提交退场时与 geoT 同时归零（曲线落点）
    val slideX = remember { Animatable(0f) }
    val velocityTracker = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }

    // 降级矩形：入口没有卡片坐标（下载页）时用屏幕中央 16:9 小矩形
    val fallbackThumb = remember(fullRect) {
        val w = fullRect.width * 0.55f
        val h = w * 9f / 16f
        Rect(
            fullRect.center.x - w / 2f,
            fullRect.center.y - h / 2f,
            fullRect.center.x + w / 2f,
            fullRect.center.y + h / 2f
        )
    }

    // 控件在进场落定后才淡入（见下方进场动画尾部），不与飞行重叠
    var controlsVisible by remember { mutableStateOf(false) }

    // 进度上报：geoT/slideX 任何变化（进场展开/跟手拖拽/缩小收尾/回弹）统一换算
    // 成背景还原进度 0→1 给宿主（宿主 graphicsLayer 内读取，不触发重组）
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(geoT.value, slideX.value, exiting) }.collect { (t, sx, _) ->
            val reveal = maxOf(1f - t, kotlin.math.abs(sx) / fullRect.width)
            onDragProgress(reveal.coerceIn(0f, 1f))
        }
    }

    // 进场（容器变形）：从来源封面矩形展开至全屏，到位后才淡入控件并 play()
    // （进场期间只预载不出声）。
    // 2026-09-30 v1.6.9 起播链路加固：play() 必须等「current 已定位 + 媒体 READY」，
    // 不再在半准备状态盲调——修复首次进入时进度条在走、声画却不动的错位：
    // openPlayer 等库首帧期间 current 可能尚未就位，等齐后再起播，声画同帧出现。
    LaunchedEffect(Unit) {
        geoT.animateTo(1f, tween(durationMillis = 430, easing = EaseOutCubic))
        controlsVisible = true
        withTimeoutOrNull(4000L) {
            snapshotFlow { playerViewModel.playerState.value.currentVideo?.filePath }
                .first { it != null }
        }
        val pathNow = playerViewModel.playerState.value.currentVideo?.filePath
        if (pathNow != null && !MediaFileTypes.isImageFile(pathNow)) {
            // 已在播放（如重开同一条）则不必再等 READY；否则最多等 3s 缓冲
            val alreadyPlaying = playerViewModel.playerState.value.isPlaying
            if (!alreadyPlaying) {
                withTimeoutOrNull(3000L) {
                    snapshotFlow { playerViewModel.playerState.value.isReady }.first { it }
                }
            }
            playerViewModel.play()
        }
    }

    // 拖拽回弹：中阻尼 spring 归位（不退场）
    fun startCancel() {
        scope.launch {
            slideX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 380f))
        }
    }

    // ⋮ 菜单（X 风）：跳转到该视频 / 跳转到该作者主页 / 订阅该作者（2026-09-15 批次4）
    var feedMenuOpen by remember { mutableStateOf(false) }
    val menuScope = rememberCoroutineScope()
    var authorSubscribed by remember { mutableStateOf(false) }
    LaunchedEffect(current?.uploader) {
        val handle = current?.uploader?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        authorSubscribed = runCatching { SubscriptionManager.isSubscribed(context, handle) }.getOrDefault(false)
    }

    // 统一退场（拖拽提交 / 返回键 / ← 按钮 / 删除后共用）：
    // ① 先暂停+复位横屏+请求媒体库定位（并行滚动到目标卡片）
    // ② 拖拽位移 spring 归零 与 全屏→目标封面矩形缩小 同时进行（曲线落点）
    // ③ 缩小落定（末段已淡出交接给卡片真封面）→ onBack，宿主让卡片信息区淡入
    fun startExit(velocityX: Float = 0f) {
        if (exiting) return
        exiting = true
        dragging = false
        controlsVisible = false
        showActions = false
        feedMenuOpen = false
        // 先暂停（否则画面缩回去声音还在播）；同步复位横屏
        playerViewModel.pauseVideo()
        playerViewModel.exitFullscreen()
        activity?.setPlayerFullscreen(false)
        // 请求宿主定位「当前视频」的媒体库卡片——滑动切过视频时定位的是
        // 切到的那条，缩小过程中列表已停在它的预览图上并回传最新矩形
        playerViewModel.playerState.value.currentVideo?.filePath?.let(onRequestExitLocate)
        scope.launch {
            launch {
                slideX.animateTo(
                    0f,
                    spring(dampingRatio = 1f, stiffness = 380f),
                    initialVelocity = velocityX
                )
            }
            geoT.animateTo(0f, tween(durationMillis = 460, easing = EaseOutCubic))
            onBack()
        }
    }

    LaunchedEffect(autoPlayFilePath) {
        // autoStart=false：只定位/预载不起播，真正起播在封面到位后（见进入动画）
        autoPlayFilePath?.let { playerViewModel.playVideo(it, autoStart = false) }
    }
    LaunchedEffect(state.isFullscreen) {
        activity?.setPlayerFullscreen(state.isFullscreen)
    }
    // 双保险：PlayerScreen 离开组合时停止 ExoPlayer（stop 不释放，回来还能播；release 仅在 ViewModel.onCleared）
    DisposableEffect(Unit) {
        onDispose { playerViewModel.stopPlayer() }
    }
    BackHandler {
        if (state.isFullscreen) {
            playerViewModel.exitFullscreen()
        } else {
            // 走统一退场动画（暂停 → 全屏缩小至预览图矩形 → 信息区淡入），完成后才切 playerVisible
            startExit()
        }
    }

    // 1.5s 自动隐藏控制层（正在播放且未展开操作面板时）
    rememberAutoHide(
        enabled = controlsVisible && state.isPlaying && !showActions,
        onHide = { controlsVisible = false },
        delayMs = 1500L  // 2026-09-15 对齐下载器项目：1.5s 沉浸
    )

    // 垂直连贯播放（2026-09-15，抖音式）：上下滑切换上下条视频——列表首页只能往下滑，
    // 末页只能往上滑，中间页双向可滑（Pager 边界天然行为）。
    // 页内容 = 该条封面；落定且媒体就绪后封面淡出露出下层 ExoPlayer 画面。
    val pagerState = rememberPagerState(initialPage = 0) { playlist.size.coerceAtLeast(0) }
    // 首次对齐标志：playlist 异步到达后需要瞬时定位到点击条目（分组视图展示顺序≠SQL
    // 播放列表顺序，若用动画滚动会路过一堆别的封面，而 ExoPlayer 已在播目标——
    // 表现为「卡在封面页但声音正常」的 bug，2026-09-15 用户反馈）
    var initialAligned by remember { mutableStateOf(false) }
    LaunchedEffect(currentIndex, playlist.size) {
        if (playlist.isEmpty() || currentIndex == null) return@LaunchedEffect
        // 2026-09-16 修复回跳环：只保留首次瞬时对齐；删除「currentIndex 变化 →
        // animateScrollToPage」反向驱动——它与 pager 落定→playVideo 正向链路互相打架
        // （快速滑动时 settledPage 领先 current 更新，程序把 pager 拉回旧位 = 回跳）。
        // 程序化翻页现在只由操作面板选片显式触发（见 GlassActionsPanel 的 onPlayVideo 包装）。
        if (!initialAligned) {
            val target = currentIndex.coerceIn(0, playlist.lastIndex)
            runCatching { pagerState.scrollToPage(target) }
            initialAligned = true
        }
    }
    // 2026-09-16 稳定性修复：pager 落定 → 切播放改 snapshotFlow + 180ms 去抖——
    // 快速连滑时 settledPage 会逐页跳变，旧逻辑每页都 playVideo（ExoPlayer 反复
    // prepare/seek = 掉帧 + 声音乱 + 界面抖动）；去抖后只处理停稳后的最终页。
    // currentVideo 也放进快照流（实时读取，避免长生命周期协程捕获旧值）。
    LaunchedEffect(playlist.size) {
        snapshotFlow {
            pagerState.settledPage to
                playerViewModel.playerState.value.currentVideo?.filePath
        }
            .distinctUntilChanged()
            .drop(1)
            .debounce(180L)
            .collect { pair ->
                val page = pair.first
                val currentPath = pair.second
                val entity = playlist.getOrNull(page) ?: return@collect
                if (entity.filePath != currentPath) {
                    playerViewModel.playVideo(entity)
                }
            }
    }

    // 播放器独立反馈胶囊（2026-09-15）：替代系统 Toast，样式与主 App 玻璃胶囊统一。
    // 播放器不在主 Scaffold 内，自持一套 controller。
    val capsule = remember { CapsuleFeedbackController() }
    val notify: (FeedbackKind, String) -> Unit = { kind, text -> capsule.show(kind, text) }

    val duration = state.duration.takeIf { it > 0 } ?: ((current?.duration ?: 0L) * 1000L)
    val position = state.position.coerceIn(0L, duration.coerceAtLeast(0L))
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 容器变形（2026-09-30 第二版）：整层按 geoT 在「缩略图矩形 ↔ 全屏」
            // 之间插值（translation + 非均匀 scale + 圆角 + 末段淡出），全部在
            // graphicsLayer 块内逐帧读取动画值，不触发重组。
            // - 进场：thumb=originBounds（点击卡片回传），0→1 展开；
            // - 退场：thumb 切换为 targetBounds（媒体库定位回传，实时跟随更新），1→0 缩小，
            //   t<0.25 时 alpha 线性淡出，交接给卡片真封面；
            // - 拖拽：slideX 叠加在几何位移上，松手提交后两者同时归零（曲线落点）。
            .graphicsLayer {
                val t = geoT.value
                val thumb = when {
                    exiting && targetBounds != null -> targetBounds
                    originBounds != null -> originBounds
                    else -> fallbackThumb
                }
                val left = lerp(thumb.left, 0f, t) + slideX.value
                val top = lerp(thumb.top, 0f, t)
                val w = lerp(thumb.width, fullRect.width, t)
                val h = lerp(thumb.height, fullRect.height, t)
                translationX = left
                translationY = top
                scaleX = (w / fullRect.width).coerceAtLeast(0.001f)
                scaleY = (h / fullRect.height).coerceAtLeast(0.001f)
                transformOrigin = TransformOrigin(0f, 0f)
                shape = RoundedCornerShape(with(density) { lerpDp(22.dp, 0.dp, t).toPx() })
                clip = true
                alpha = if (exiting) (t / 0.25f).coerceIn(0f, 1f) else 1f
            }
            // 横向拖拽跟手（竖向留给 VerticalPager 切视频；
            // Slider/LazyRow 等子组件自行消费的横向手势不会传到这里）
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { _ ->
                        // 进场飞行未落定（geoT<1）不允许拖拽，避免与展开动画打架
                        if (!exiting && geoT.value >= 1f) {
                            dragging = true
                            controlsVisible = false
                            showActions = false
                            velocityTracker.resetTracking()
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (dragging && !exiting) {
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch { slideX.snapTo(slideX.value + dragAmount) }
                        }
                    },
                    onDragEnd = {
                        if (dragging && !exiting) {
                            dragging = false
                            val velocityX = velocityTracker.calculateVelocity().x
                            val width = fullRect.width
                            val offset = slideX.value
                            val farEnough = kotlin.math.abs(offset) >= width * 0.42f
                            val fastFlick = kotlin.math.abs(velocityX) >= 2200f &&
                                kotlin.math.sign(velocityX) == kotlin.math.sign(offset) &&
                                kotlin.math.abs(offset) >= width * 0.1f
                            if (farEnough || fastFlick) startExit(velocityX) else startCancel()
                        }
                    },
                    onDragCancel = {
                        if (dragging && !exiting) {
                            dragging = false
                            startCancel()
                        }
                    }
                )
            }
    ) {
        // ============ 页内容层（飞行前景：整层随容器变形缩放，黑幕作层底色）============
        Box(modifier = Modifier.fillMaxSize()) {
            // 层底黑幕：飞行缩放期间的底色（TextureView 下无打洞问题，此层仅兜底
            // 视频 FIT 黑边与封面文字兜底的背景）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.onSurface)
            )
            // 沉浸式背景层：video 区域外的"画框氛围"
            // 在 SurfaceView 之后渲染（Compose 兄弟元素按声明顺序绘制），
            // 但通过 zIndex = -1 让它在视频下方，且用 primary 22% 微微染色
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                MaterialTheme.colorScheme.onSurface
                            ),
                            radius = 1800f
                        )
                    )
            )
            // 背景点击：唤出 / 隐藏控制层
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = backgroundInteraction,
                        indication = null,
                        onClick = { controlsVisible = !controlsVisible }
                    )
            )

            // ============ 页面媒体层（2026-09-30 v1.6.9 第三版：对齐 X/Twitter 信息流）============
            // 每页自带媒体面（Twitter 式过渡）：PlayerView 放进「当前条目所在页」内部，
            // 不再共享一个挂在 pager 底下的 PlayerView + 全局封面交叉淡变。
            // 滑动 = 页面整体平移；每页媒体区域/比例（FIT 按源比例）在页内恒定，
            // 结构上消灭「切条时共享画面与滑动页面的时间差闪屏」与「全局比例变化」。
            // 封面→视频的交接发生在页内：首帧已渲染（hasFirstFrame）才淡出封面，且
            // 视频面始终在封面之下、完整可见后才撤——任何时刻都不露黑底。
            if (playlist.isNotEmpty()) {
                VerticalPager(
                    state = pagerState,
                    // 2026-09-16 掉帧优化：预组合相邻页（0=滑动时现场组合封面 → 解码卡顿）
                    beyondViewportPageCount = 1,
                    // 标准抖音方向（2026-09-15 用户定案）：手指从底部向上滑 = 下一条，
                    // 向下滑 = 返回上一条；边界天然无反应（首页没有上一条、末页没有下一条）。
                    // 注意：勿加 reverseLayout（那会翻转成"下滑=下一条"，与用户习惯相反）。
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val entity = playlist.getOrNull(page)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { controlsVisible = !controlsVisible }
                            )
                    ) {
                        if (entity != null) {
                            if (sessionIsImage) {
                                // 图片会话页：完整图片直接铺在页内（Fit 全屏），邻页由
                                // beyondViewportPageCount 预组合，滑动无缝
                                coil.compose.AsyncImage(
                                    model = File(entity.filePath),
                                    contentDescription = entity.title,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    text = "${page + 1}/${playlist.size}",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .statusBarsPadding()
                                        .padding(top = 8.dp, end = 16.dp)
                                )
                            } else {
                                val isPlayerPage = entity.filePath == current?.filePath
                                if (isPlayerPage) {
                                    // 播放器视图：TextureView（见 layout/player_view_texture.xml 头注释——
                                    // SurfaceView 不跟随 graphicsLayer 变换，转场错位/丢画面）。
                                    // 固定 RESIZE_MODE_FIT：按下载时原始宽高比完整播放。
                                    // 播放器实例全局唯一（playerViewModel.exoPlayer），随当前页
                                    // 在页间迁移；离开组合时解绑，避免持已销毁的页面的 surface
                                    AndroidView(
                                        factory = { viewContext ->
                                            (android.view.LayoutInflater.from(viewContext)
                                                .inflate(R.layout.player_view_texture, null, false) as PlayerView)
                                                .apply {
                                                    player = playerViewModel.exoPlayer
                                                    useController = false
                                                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                                    layoutParams = ViewGroup.LayoutParams(
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT
                                                    )
                                                }
                                        },
                                        onRelease = { it.player = null },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                // 页内占位封面：
                                // - 播放页：等首帧渲染完成（hasFirstFrame）才 120ms 淡出；
                                //   此前封面盖着、视频在下方已完整渲染，撤封面零黑帧；
                                // - 非播放页（相邻页 / 被切走的页）：瞬时整显——切走发生在
                                //   落定去抖之后（旧页已在屏外），瞬时切换不会被看见。
                                val coverTarget = if (isPlayerPage && state.hasFirstFrame) 0f else 1f
                                val coverAlpha by androidx.compose.animation.core.animateFloatAsState(
                                    targetValue = coverTarget,
                                    animationSpec = if (coverTarget == 0f) {
                                        tween(durationMillis = 120)
                                    } else {
                                        androidx.compose.animation.core.snap()
                                    },
                                    label = "playerPageCover"
                                )
                                if (coverAlpha > 0.01f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .alpha(coverAlpha)
                                    ) {
                                        PlayerCoverContent(entity = entity, modifier = Modifier.fillMaxSize())
                                        // 页码指示（右上角）：帮助用户感知列表位置
                                        Text(
                                            text = "${page + 1}/${playlist.size}",
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .statusBarsPadding()
                                                .padding(top = 8.dp, end = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 顶部覆盖层（2026-09-15 对齐下载器项目 X 风）：返回 + 头像/@handle 行 + ⋮ 菜单
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 返回（X 风 ←）：统一走退场动画（先暂停再飞回）
                        Text(
                            text = "←",
                            color = Color.White,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .padding(6.dp)
                                .clickable(
                                    interactionSource = backgroundInteraction,
                                    indication = null,
                                ) { startExit() }
                        )
                        Spacer(Modifier.weight(1f))
                        Box {
                            Text(
                                text = "⋮",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(6.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { feedMenuOpen = true }
                            )
                            DropdownMenu(
                                expanded = feedMenuOpen,
                                onDismissRequest = { feedMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("跳转到该视频") },
                                    onClick = {
                                        feedMenuOpen = false
                                        current?.let { openSource(context, it, notify) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("跳转到该作者主页") },
                                    onClick = {
                                        feedMenuOpen = false
                                        val handle = current?.uploader?.takeIf { it.isNotBlank() }
                                        if (handle != null && openXProfile(context, handle)) {
                                            // 已尝试打开
                                        } else {
                                            notify(FeedbackKind.ERROR, "无法打开作者主页")
                                        }
                                    }
                                )
                                // 订阅开关（2026-09-15 v2 批次4：新作品自动下载，进程级持久队列兜底）
                                if (current?.uploader?.isNotBlank() == true) {
                                    DropdownMenuItem(
                                        text = { Text(if (authorSubscribed) "退订该作者" else "订阅该作者 · 自动下载新作品") },
                                        onClick = {
                                            feedMenuOpen = false
                                            val handle = current?.uploader?.takeIf { it.isNotBlank() } ?: return@DropdownMenuItem
                                            menuScope.launch {
                                                val result = runCatching { SubscriptionManager.toggle(context, handle) }
                                                    .getOrDefault(authorSubscribed)
                                                authorSubscribed = result
                                                notify(
                                                    if (result) FeedbackKind.SUCCESS else FeedbackKind.NEUTRAL,
                                                    if (result) "已订阅 @${handle}，发现新作品将自动下载" else "已退订 @${handle}"
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    // 头像 + 作者行（X 风：头像徽章 + 显示名 + @handle）
                    // 2026-09-16 修复「头像、ID 显示问题」：
                    // ①真实头像（avatarUrl 来自 FXTwitter author.avatar_url，DB v9 新列），
                    //   加载失败/缺失回退字母块；
                    // ②第一行显示 X 昵称（authorName），第二行才是 @handle——
                    //   此前两行都渲染 handle（重复且无辨识度）。
                    val avatarUrl = current?.avatarUrl.orEmpty()
                    val displayName = current?.authorName?.takeIf { it.isNotBlank() }
                        ?: current?.uploader?.takeIf { it.isNotBlank() }
                        ?: "未知作者"
                    val handle = current?.uploader.orEmpty()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF26324A)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarUrl.isNotBlank()) {
                                coil.compose.AsyncImage(
                                    model = avatarUrl,
                                    contentDescription = displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    text = handle.take(1).uppercase().ifBlank { "?" },
                                    color = Color(0xB3FFFFFF),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = displayName,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (handle.isNotBlank()) {
                                Text(
                                    text = "@$handle",
                                    color = Color(0x99FFFFFF),
                                    fontSize = 12.5.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // 底部：操作面板 + 控制层
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    GlassActionsPanel(
                        playlist = playlist,
                        current = current,
                        rowState = rowState,
                        visible = showActions,
                        // 2026-09-16 选片翻页修复：程序化选片必须显式把 pager 滚到目标页
                        //（反向 animate 驱动已删，这里成为唯一翻页入口），落定后由
                        // snapshotFlow 去抖链路自动 playVideo（同路径早退，不重复切换）。
                        onPlayVideo = { entity ->
                            val target = playlist.indexOfFirst { it.filePath == entity.filePath }
                            if (target >= 0) {
                                menuScope.launch {
                                    runCatching { pagerState.animateScrollToPage(target) }
                                }
                            }
                            playerViewModel.playVideo(entity)
                        },
                        onOpenFile = { current?.let { openMediaFile(context, it, notify) } },
                        onOpenSource = { current?.let { openSource(context, it, notify) } },
                        onCopyLink = { current?.let { copySourceLink(context, it, notify) } },
                        onShare = { current?.let { shareMedia(context, it, notify) } },
                        onDelete = { current?.let { pendingDelete = it } }
                    )
                    // 底部中央：暂停/播放圆钮（X 风，64dp 半透明黑底 + 双竖条 Canvas）
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 10.dp)
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { playerViewModel.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isPlaying) {
                            androidx.compose.foundation.Canvas(Modifier.size(22.dp, 24.dp)) {
                                val w = size.width
                                val barW = w * 0.28f
                                val h = size.height
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                                    size = androidx.compose.ui.geometry.Size(barW, h),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                                )
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = androidx.compose.ui.geometry.Offset(w - barW, 0f),
                                    size = androidx.compose.ui.geometry.Size(barW, h),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                                )
                            }
                        } else {
                            Text(text = "▶", color = Color.White, fontSize = 26.sp)
                        }
                    }
                    GlassPlayerControls(
                        progress = progress,
                        positionText = formatDuration(position),
                        durationText = formatDuration(duration),
                        speedText = formatSpeed(state.playbackSpeed),
                        muted = state.isMuted,
                        isPlaying = state.isPlaying,
                        visible = controlsVisible,
                        isImageMode = sessionIsImage,
                        onSeek = playerViewModel::seekTo,
                        onSpeed = playerViewModel::cyclePlaybackSpeed,
                        onMute = playerViewModel::toggleMute,
                        onPlayPause = playerViewModel::togglePlayPause,
                        onFullscreen = playerViewModel::toggleFullscreen,
                        onToggleActions = { showActions = !showActions; controlsVisible = true },
                        showActions = showActions
                    )
                }
            }

            pendingDelete?.let { entity ->
                SmoothAlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text("删除媒体") },
                    text = { Text("可以只移除媒体库记录，也可以同时删除本地文件。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingDelete = null
                                historyViewModel.deleteHistory(entity, deleteLocalFile = true)
                                startExit()
                            }
                        ) { Text("删除文件") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                pendingDelete = null
                                historyViewModel.deleteHistory(entity, deleteLocalFile = false)
                                startExit()
                            }
                        ) { Text("仅移除记录") }
                    }
                )
            }
        }
    }
}

/** 封面内容：本地缩略图 → 远程缩略图 → 标题文字，Crop 取景（与媒体库卡片一致，保证过渡重合无缝）。 */
@Composable
private fun PlayerCoverContent(
    entity: DownloadHistoryEntity?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.onSurface)) {
        if (entity == null) return@Box
        val thumbFile = entity.thumbnail.takeIf {
            it.isNotBlank() && it.startsWith("/")
        }?.let(::File)
        when {
            thumbFile != null && thumbFile.exists() -> {
                coil.compose.AsyncImage(
                    model = thumbFile,
                    contentDescription = entity.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            entity.thumbnail.isNotBlank() -> {
                coil.compose.AsyncImage(
                    model = entity.thumbnail,
                    contentDescription = entity.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Text(
                    text = entity.title.ifBlank { entity.filePath.substringAfterLast('/') },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp)
                )
            }
        }
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "%.1fx".format(speed) else "${speed}x"

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun copySourceLink(
    context: Context,
    entity: DownloadHistoryEntity,
    notify: (FeedbackKind, String) -> Unit
) {
    val link = sourceUrl(entity)
    if (link.isBlank()) {
        notify(FeedbackKind.ERROR, "暂无可复制的链接")
        return
    }
    copyToClipboard(context, "Edqiu link", link)
    notify(FeedbackKind.SUCCESS, "已复制链接")
}

private fun openSource(
    context: Context,
    entity: DownloadHistoryEntity,
    notify: (FeedbackKind, String) -> Unit
) {
    val tweetId = tweetIdOf(entity)
    val opened = if (tweetId != null) {
        openTwitterStatus(context, tweetId)
    } else {
        val link = sourceUrl(entity)
        link.isNotBlank() && runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            true
        }.getOrDefault(false)
    }
    if (!opened) notify(FeedbackKind.ERROR, "无法打开 X/Twitter")
}

private fun openMediaFile(
    context: Context,
    entity: DownloadHistoryEntity,
    notify: (FeedbackKind, String) -> Unit
) {
    val file = File(entity.filePath)
    if (!file.exists()) {
        notify(FeedbackKind.ERROR, "本地文件不存在")
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        notify(FeedbackKind.ERROR, "无法打开文件")
    }
}

private fun shareMedia(
    context: Context,
    entity: DownloadHistoryEntity,
    notify: (FeedbackKind, String) -> Unit
) {
    val file = File(entity.filePath)
    if (!file.exists()) {
        notify(FeedbackKind.ERROR, "本地文件不存在")
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = context.contentResolver.getType(uri) ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "分享媒体"
            )
        )
    }.onFailure {
        notify(FeedbackKind.ERROR, "分享失败")
    }
}

private fun sourceUrl(entity: DownloadHistoryEntity): String {
    val tweetId = tweetIdOf(entity)
    return when {
        tweetId != null -> "https://x.com/i/status/$tweetId"
        entity.url.isNotBlank() -> entity.url
        else -> ""
    }
}

private fun tweetIdOf(entity: DownloadHistoryEntity): String? =
    TweetIdExtractor.fromUrl(entity.url)
        ?: TweetIdExtractor.fromFileName(File(entity.filePath).name)
        ?: TweetIdExtractor.fromFileName(entity.id)

private fun openXProfile(context: Context, handle: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/$handle")))
    true
}.getOrDefault(false)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Activity.setPlayerFullscreen(fullscreen: Boolean) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    // 横竖屏切换由窗口自行处理（清单声明了 configChanges），用系统交叉淡入动画过渡，
    // 避免画面瞬间跳变
    window.attributes = window.attributes.apply {
        rotationAnimation = android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE
    }
    if (fullscreen) {
        // 横屏观看：锁定传感器横屏 + 隐藏状态栏/导航栏（下滑召唤）
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        // 退出横屏：带动画恢复并锁定竖屏
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
