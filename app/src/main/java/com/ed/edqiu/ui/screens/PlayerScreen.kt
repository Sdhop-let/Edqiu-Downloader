package com.ed.edqiu.ui.screens

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImagePainter
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
import com.ed.edqiu.ui.player.GlassImageViewerBar
import com.ed.edqiu.ui.player.GlassPlayerControls
import com.ed.edqiu.ui.player.rememberAutoHide
import com.ed.edqiu.ui.util.copyToClipboard
import com.ed.edqiu.ui.util.openTwitterStatus
import com.ed.edqiu.viewmodel.HistoryViewModel
import com.ed.edqiu.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 *
 * 2026-10-02 批次A：图片会话改为「图片查看器」形态 —— 双指缩放/单指平移/双击 1x↔2.5x
 * 手势 + 专属底部功能栏（保存到相册/分享/打开X/复制链接/删除），顶部改为常驻头像行 +
 * 随控件显隐的详情行；视频会话路径保持原行为零损失。
 *
 * 2026-10-03 批次C：会话页媒体层改为「居中媒体框按自身比例布局」+ 比例变动过渡动画 ——
 * 竖屏下媒体框 fit-contained（框外维持黑底 + radialGradient 氛围层），在屏页面共享一个
 * Animatable 显示比例：滑动跟手 snapTo 插值、落定 spring(0.8/380) 归位目标页真实比例、
 * 进场 16:9「呼吸」归位；横屏全屏（isFullscreen）时框禁用恢复全屏 FIT。播放/查看侧
 * 实测宽高写回历史库自愈（视频 onVideoSizeChanged 旋转校正、图片 Coil onState），
 * 实体更新即时驱动框比例（perf：比例只在 measure 块内读取，仅重测量不重组）。
 *
 * 2026-10-03 批次F：旗舰机（8GB RAM + 非低内存标记 + API 29+，见 FlagshipDetector）
 * 自动开启「真双播放器」—— 用户手指拖拽翻页越过 15% 时为拖拽目标页启动第二个
 * 静音 ExoPlayer（previewPlayer，恒 volume=0 不出声），滑入页在拖拽期间即渲染
 * 「活的视频」；同一 PlayerView 经 AndroidView.update 按页角色重绑（邻页落定变
 * 播放页 → 改绑主播放器，反向切走 → 解绑），主播放器首帧上屏后释放预览。
 * 非旗舰 / 预览未就绪 / 构建失败时完全回退批次E 真帧封面路径，行为零变化。
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
    // 2026-10-05 定位失效修复：实时读取宿主状态的取值器——退场先等媒体库定位回传
    //（远处卡片定位链 ≈500-900ms > 旧收缩窗口 460ms，旧实现终点已落定在原卡），
    // 再开始收缩。快照流内经此 lambda 读宿主 mutableState，追踪生效
    targetBoundsNow: () -> Rect? = { targetBounds },
    // 拖拽/缩小进度（0=播放层盖住背景，1=背景完全还原）：宿主据此缩放+压暗背景
    onDragProgress: (Float) -> Unit = {},
    // 退场开始时回调当前视频路径：宿主据此驱动媒体库滚动定位（用户滑动切过
    // 视频后返回，定位的是切到的那条，不是进入时的卡片）
    onRequestExitLocate: (String) -> Unit = {},
    // 退场动画完成后回调（宿主同帧切 playerVisible=false 收起浮层、卡片信息淡入）
    onBack: () -> Unit,
    // 2026-10 修复：胶囊反馈回调（宿主接线全局 CapsuleFeedbackController）。
    // 旧实现在本文件自建 controller 但从未挂 Host，播放器内全部操作反馈静默丢失。
    onFeedback: (FeedbackKind, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val state by playerViewModel.playerState.collectAsState()
    // 2026-09-30 v1.6.9：翻页列表 = 播放会话列表（openPlayer 按入口类型分流的稳定快照）——
    // 视频会话只翻视频、图片会话只翻图片（优化1）；会话内冻结，后台库重排不再在手指下换页
    val playlist by playerViewModel.playerPlaylist.collectAsState()
    // 2026-10-03 批次F：真双播放器预览状态 —— previewFilePath = 挂着静音预览的条目，
    // previewHasFirstFrame = 预览首帧已上屏的路径（邻页封面淡出依据）。非旗舰机两值
    // 恒 null，全部新增分支短路，完全走批次E 路径。collect 在屏级：预览状态变化会
    // 重组页内容并触发 PlayerView 的 update 重绑（批次F「邻页落定改绑主播放器」关键坑的落实点）
    val previewFilePath by playerViewModel.previewFilePath.collectAsState()
    val previewHasFirstFrame by playerViewModel.previewHasFirstFrame.collectAsState()
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

    // ===================== 媒体框比例引擎（2026-10-03 批次C） =====================
    // 共享「显示比例」：在屏页面（当前页 + beyondViewportPageCount 预组合邻页）共用一个
    // Animatable，视觉上 = 一个媒体框在连续变形（详见下方比例过渡 LaunchedEffect）。
    // 初始 16:9 与进场封面裁切比例一致 —— 进场展开落定后「呼吸」归位到真实比例，
    // 正好衔接进场观感（perf：该值只在 mediaFrame 的 measure 块内逐帧读取，不触发重组）。
    val displayedRatio = remember { Animatable(16f / 9f) }
    // 进场未落定（geoT<1）前不启动比例引擎：框保持 16:9，避免与进场飞行动画叠加抖动
    var ratioEngineReady by remember { mutableStateOf(false) }

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
        // 2026-10-03 批次C：进场展开落定后才启动比例引擎（见下方 LaunchedEffect），
        // 此时框才从 16:9「呼吸」到当前页真实比例，与进场封面衔接
        ratioEngineReady = true
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
            // 2026-10-05 定位失效修复：等媒体库定位回传终点矩形（≤500ms）再收缩——
            // 远处卡片的定位链（瞬跳+居中+读表）长于收缩窗口，旧实现立即收缩导致
            // 终点落定在原卡、定位回传迟到作废（滑得越多目标越远必现）。
            // snapshotFlow 首发即当前值：已回传则立即返回，无额外等待
            if (targetBoundsNow() == null) {
                withTimeoutOrNull(500L) {
                    snapshotFlow { targetBoundsNow() }.first { it != null }
                }
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

    // 2026-10-03 批次E：落定预取邻页真帧 —— settledPage 一变（不等 180ms 去抖）就预热
    // ±1 页视频条目的首帧缓存（PlayerViewModel.prefetchNeighborFirstFrames，IO 抽帧），
    // 滑动时预组合邻页大概率直接命中：滑入页显示真帧而非卡片缩略图，且无「封面→真帧」替换闪烁。
    // 与去抖切播链路、批次C 比例动画流各自独立 collect，互不干扰
    LaunchedEffect(playlist.size) {
        snapshotFlow { pagerState.settledPage }
            .collect { page -> playerViewModel.prefetchNeighborFirstFrames(page) }
    }

    // ===================== 比例变动过渡动画（2026-10-03 批次C / 2026-10-05 丝滑性重写） =====================
    // 插值锚点 = 「连续翻页位置」currentPage + currentPageOffsetFraction：
    // 旧公式 lerp(ratio(page), ratio(target), |frac|) 在手指拖拽期退化——拖拽中
    // targetPage 恒等于 currentPage（snap 目标跟随手指），lerp(r,r,·)=r 全程不形变，
    // 翻过 50% 时 currentPage 翻转 → 比例瞬间硬切到新页值（用户反馈「做不到丝滑变化」）。
    // 连续位置 = currentPage + frac 跨翻转点单调无跳变，lerp 在相邻两页真实比例间
    // 按位置插值，天然跟手；比例缺失的条目不再跳 16:9 兜底估值（16:9↔9:16 大跳变
    // =「每次滑动都缩大」），保持当前比例等写回自愈归准。落定/进场呼吸经
    // isScrollInProgress 分流走 spring 收敛（规格同拖拽回弹 spring 0.8/380）。
    LaunchedEffect(ratioEngineReady, playlist, sessionIsImage) {
        if (!ratioEngineReady) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
            .collect { position ->
                val lastIndex = playlist.lastIndex
                if (lastIndex < 0) return@collect
                val fromPage = kotlin.math.floor(position).toInt().coerceIn(0, lastIndex)
                val toPage = (fromPage + 1).coerceAtMost(lastIndex)
                val t = (position - fromPage).coerceIn(0f, 1f)
                val from = playlist.getOrNull(fromPage)?.let { mediaFrameRatio(it, sessionIsImage) }
                val to = playlist.getOrNull(toPage)?.let { mediaFrameRatio(it, sessionIsImage) }
                // 尺寸缺失不跳兜底估值：过半前保持当前比例，过半后采纳邻页已知比例，
                // 双缺保持现状——等播放实测/图片加载/backfill 写回后本流重发自动归准
                val target = when {
                    from != null && to != null -> lerp(from, to, t)
                    from == null && to == null -> displayedRatio.value
                    from == null -> if (t < 0.5f) displayedRatio.value else to
                    else -> if (t < 0.5f) from else displayedRatio.value
                } ?: displayedRatio.value
                if (pagerState.isScrollInProgress || abs(pagerState.currentPageOffsetFraction) > 0.001f) {
                    // 滑动中：跟手（snapTo 后到者胜，可打断在飞的收敛动画）
                    displayedRatio.snapTo(target)
                } else {
                    // 进场「呼吸」/写回自愈归位：spring 收敛
                    displayedRatio.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 380f))
                }
            }
    }

    // ===================== 竖向拖拽过半暂停 / 回弹恢复（2026-10-03 批次E） =====================
    // 用户定案：竖向翻页拖拽中 |currentPageOffsetFraction| ≥ 0.5（页面滑过一半）即暂停
    // 当前视频一次（标志位防重复调用）；拖拽继续到落定 → 上方去抖链路 playVideo 新条目
    // 正常起播；回弹（落定页未变）且起拖时在播 → play() 恢复。
    // 只对用户手指拖拽生效：用 interactionSource 的 DragInteraction 区分手势与程序化滚动
    //（操作面板选片的 animateScrollToPage 不发交互，避免误暂停刚起播的新条目）。
    // 图片会话无播放概念，整体跳过。仅读取 pager 状态做逻辑裁决，不逐帧驱动 UI。
    LaunchedEffect(sessionIsImage) {
        if (sessionIsImage) return@LaunchedEffect
        var userDragging = false          // 手指正在 Pager 上拖拽（程序化滚动为 false）
        var pausedByDrag = false          // 本次拖拽已因过半暂停过（防重复暂停）
        var pageAtPause = 0               // 暂停发生时的落定页（回弹判定基准）
        var wasPlayingBeforeDrag = false  // 起拖瞬间的播放状态（回弹恢复依据）
        launch {
            pagerState.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        userDragging = true
                        // 起拖即记录播放状态（此后到暂停点之间无其他起停来源）
                        wasPlayingBeforeDrag = playerViewModel.playerState.value.isPlaying
                    }
                    is DragInteraction.Stop, is DragInteraction.Cancel -> userDragging = false
                }
            }
        }
        snapshotFlow {
            Triple(
                pagerState.isScrollInProgress,
                pagerState.currentPageOffsetFraction,
                pagerState.settledPage
            )
        }.collect { (scrolling, fraction, settled) ->
            when {
                // ① 拖拽过半：暂停一次当前视频
                scrolling && userDragging && !pausedByDrag && abs(fraction) >= 0.5f -> {
                    pausedByDrag = true
                    pageAtPause = settled
                    playerViewModel.pauseVideo()
                }
                // ② 滚动落定后裁决：回弹（仍在暂停时页）→ 按起拖状态恢复；
                //    换页 → 清标志，起播交给去抖链路 playVideo
                !scrolling && pausedByDrag -> {
                    if (settled == pageAtPause && wasPlayingBeforeDrag) playerViewModel.play()
                    pausedByDrag = false
                }
            }
        }
    }

    // ===================== 真双播放器预览触发（2026-10-03 批次F） =====================
    // 旗舰机：用户手指拖拽翻页越过 15% 时，为拖拽目标页启动静音预览播放 ——
    // 滑入页在拖拽期间就渲染「活的视频」（第二个 ExoPlayer 实例），而非静态真帧/封面；
    // 落定后由下方释放裁决链路交接给主播放器。
    // 只对用户手指拖拽生效：DragInteraction 区分手势与程序化滚动（操作面板
    // animateScrollToPage 不发交互，不触发预览），与批次E 50% 暂停同一约束口径。
    // 目标页推导：拖拽中 currentPage 要到过半才翻转，targetPage 按「最贴近当前位置」
    // 语义在拖拽中恒等于当前页（取不到邻页），故按偏移方向取邻页 ——
    // fraction>0 = 滑向下一页（高索引），<0 = 滑向上一页；方向在 currentPage
    // 翻转前后自洽（预览已建立后重复请求同路径被 ViewModel 幂等拒绝）。
    // 图片会话无播放概念，整体跳过（requestPreviewPlayer 内部同样拒绝图片路径双保险）。
    LaunchedEffect(sessionIsImage) {
        if (sessionIsImage) return@LaunchedEffect
        // 2026-10-03 批次F 定案升级：双播放开关 = 设置开关 ∧ 旗舰检测（RAM≥16GB），
        // 订阅 StateFlow —— 设置页关闭/开启即时生效（未开启时不挂任何预览监听）
        playerViewModel.isDualPlayerEnabled.collect { dualEnabled ->
            if (!dualEnabled) return@collect
            var userDragging = false          // 手指正在 Pager 上拖拽（程序化滚动为 false）
            launch {
                pagerState.interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start -> userDragging = true
                        is DragInteraction.Stop, is DragInteraction.Cancel -> userDragging = false
                    }
                }
            }
            snapshotFlow {
                Triple(
                    pagerState.isScrollInProgress,
                    pagerState.currentPageOffsetFraction,
                    pagerState.currentPage
                )
            }.collect { (scrolling, fraction, page) ->
                if (!scrolling || !userDragging || abs(fraction) <= 0.15f) return@collect
                val target = playlist.getOrNull(page + if (fraction > 0f) 1 else -1)
                    ?: return@collect
                if (MediaFileTypes.isImageFile(target.filePath)) return@collect
                playerViewModel.requestPreviewPlayer(target.filePath)
            }
        }
    }

    // ===================== 预览播放器释放裁决（2026-10-03 批次F） =====================
    // 预览激活后的结局（previewFilePath 变化会重启本 effect，逐值重新裁决）：
    // ① 回弹（滚动结束但落定页 ≠ 预览页）：没换页，预览失去意义 → 立即释放；
    // ② 换页落定：等去抖链路 playVideo 切到预览条目（1s 防御，切条异常直接释放
    //    回退批次E 封面），再等主播放器 hasFirstFrame（500ms 兜底）→ 释放 ——
    //    等待期间预览持续渲染活画面，主首帧上屏才交接，视觉无缝；
    // ③ 方向改到另一邻页：本 effect 由新值重启，旧裁决作废（requestPreviewPlayer
    //    复用同一实例换目标）；
    // ④ 退出播放器/新会话：ViewModel 钩子（stopPlayer/openPlayer/onCleared）兜底释放。
    LaunchedEffect(previewFilePath) {
        val previewPath = previewFilePath ?: return@LaunchedEffect
        // 预览在拖拽中激活：先等本轮 Pager 滚动结束（回弹/落定都在此刻裁决）
        snapshotFlow { pagerState.isScrollInProgress }.first { !it }
        val previewIndex = playlist.indexOfFirst { it.filePath == previewPath }
        if (pagerState.settledPage != previewIndex) {
            // ① 回弹：没换页
            playerViewModel.releasePreviewPlayer()
            return@LaunchedEffect
        }
        // ② 换页落定：等 playVideo 切到预览条目（StateFlow 现值即目标则立即通过）
        val switched = withTimeoutOrNull(1000L) {
            playerViewModel.playerState.first { it.currentVideo?.filePath == previewPath }
        } != null
        if (!switched) {
            playerViewModel.releasePreviewPlayer()
            return@LaunchedEffect
        }
        // 主播放器首帧上屏（≤500ms 兜底）后释放预览 —— 期间预览持续静音渲染
        withTimeoutOrNull(500L) {
            playerViewModel.playerState.first { it.hasFirstFrame }
        }
        playerViewModel.releasePreviewPlayer()
    }

    // 播放器操作反馈（2026-10 修复）：走宿主注入的全局胶囊控制器（AppNav 渲染 Host），
    // 旧实现自建 controller 无 Host 渲染，复制/分享/打开等反馈全部静默丢失。
    val notify: (FeedbackKind, String) -> Unit = onFeedback

    val duration = state.duration.takeIf { it > 0 } ?: ((current?.duration ?: 0L) * 1000L)
    val position = state.position.coerceIn(0L, duration.coerceAtLeast(0L))
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    // 2026-10-02 批次A：常驻详情行数据 —— ID 短显（点击复制完整帖子ID）、日期
    //（publishedAt ?: completedAt，MM-dd）、组内序号、尾部信息（视频=时长+大小，
    // 图片=大小）；某段无数据整段跳过，保证分隔符 " · " 不悬空
    val tweetId = current?.let { tweetIdOf(it) }
    val detailTail = buildList {
        val dateMs = current?.publishedAt?.takeIf { it > 0 }
            ?: current?.completedAt?.takeIf { it > 0 }
        if (dateMs != null) {
            add(SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(dateMs)))
        }
        // 2026-10-03 终验修正：mediaIndex 全链路 1-based（FXTwitterResolver toFormat(index+1)、
        // 扫描文件名第 N 张），直接展示，不再 +1（否则首图显示成「组内第2个」）
        current?.mediaIndex?.takeIf { it > 0 }?.let { add("组内第${it}个媒体") }
        if (!sessionIsImage && duration > 0) add(formatDuration(duration))
        current?.fileSize?.takeIf { it > 0 }?.let {
            add("%.1f MB".format(it / (1024f * 1024f)))
        }
    }.joinToString(" · ")

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
                    .background(Color.Black) // 2026-10：黑幕固定黑色（onSurface 在暗色主题是白色）
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
                                Color.Black
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
                                // 图片会话页（2026-10-02 批次A）：图片查看器形态 ——
                                // 双指缩放 + 单指平移 + 双击 1x↔2.5x，切页落定复位；
                                // 1x 且无偏移时手势完全放行（Pager 竖滑与外层横向拖拽退场
                                // 不受影响）。邻页由 beyondViewportPageCount 预组合，滑动无缝
                                // 2026-10-03 批次C：图片媒体框按比例布局（共享 displayedRatio，
                                // 框内 Crop 铺满；缩放手势/1x 手势穿透行为不变，框比例随
                                // 写回自愈归准；横屏全屏时框禁用——图片会话无全屏入口，恒为框）
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .mediaFrame(
                                            ratio = { displayedRatio.value },
                                            fullscreen = { state.isFullscreen }
                                        )
                                ) {
                                    ZoomableImageViewerPage(
                                        filePath = entity.filePath,
                                        contentDescription = entity.title,
                                        page = page,
                                        settledPage = pagerState.settledPage,
                                        onTap = { controlsVisible = !controlsVisible },
                                        // 批次C：加载成功回传实测宽高 → ViewModel 写回自愈
                                        //（实体缺失/差 ≥2px 才写，同值去重在 ViewModel 内）
                                        onImageDimensions = { w, h ->
                                            playerViewModel.reportImageDimensions(entity.filePath, w, h)
                                        }
                                    )
                                }
                                Text(
                                    text = "${page + 1}/${playlist.size}",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .statusBarsPadding()
                                        // 批次A：头像行常驻后页码下移一行，避免与 ⋮ 菜单重叠
                                        .padding(top = 52.dp, end = 16.dp)
                                )
                            } else {
                                val isPlayerPage = entity.filePath == current?.filePath
                                // 2026-10-03 批次F：本页是否为静音预览邻页（拖拽中的目标页活画面）。
                                // 播放页优先 —— 预览绝不会挂到主播放条目（requestPreviewPlayer 拒绝）
                                val isPreviewPage = !isPlayerPage && previewFilePath == entity.filePath
                                if (isPlayerPage || isPreviewPage) {
                                    // 播放器视图：TextureView（见 layout/player_view_texture.xml 头注释——
                                    // SurfaceView 不跟随 graphicsLayer 变换，转场错位/丢画面）。
                                    // 固定 RESIZE_MODE_FIT：按下载时原始宽高比完整播放。
                                    // 2026-10-03 批次F：同一 PlayerView 随页角色重绑（关键坑）——
                                    // factory 只跑一次且不写死 player，绑定统一由 update 裁决：
                                    // 播放页 → 主播放器；预览邻页 → 静音 previewPlayer。
                                    // 邻页（曾绑 previewPlayer）落定变成播放页时原地改绑主播放器、
                                    // 反向切走时解绑，视图不重挂（重挂会闪黑一帧）。预览实例
                                    // 全局唯一（previewPlayer），与主播放器合计 ≤2 实例；
                                    // 离开组合时 onRelease 解绑，避免持已销毁页面的 surface
                                    AndroidView(
                                        factory = { viewContext ->
                                            (android.view.LayoutInflater.from(viewContext)
                                                .inflate(R.layout.player_view_texture, null, false) as PlayerView)
                                                .apply {
                                                    useController = false
                                                    // 2026-10-05：FIT → ZOOM——媒体框按实体比例布局后，
                                                    // 框比例=源比例时 ZOOM≡FIT（无裁切）；形变过渡期
                                                    // 框比例≠源比例时 ZOOM 裁切补位，消除 FIT 黑边
                                                    // 随形变伸缩造成的「视频忽大忽小」
                                                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                                    layoutParams = ViewGroup.LayoutParams(
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT
                                                    )
                                                }
                                        },
                                        update = { pv ->
                                            // 2026-10-03 批次F：按页角色重绑（读屏级 collect 的
                                            // previewFilePath/previewHasFirstFrame，状态变化必重组到这里）
                                            pv.player = when {
                                                isPlayerPage -> playerViewModel.exoPlayer
                                                isPreviewPage -> playerViewModel.previewPlayer
                                                else -> null
                                            }
                                        },
                                        onRelease = { it.player = null },
                                        // 2026-10-03 批次C：视频媒体框按比例布局（共享
                                        // displayedRatio）——框比例准了 RESIZE_MODE_FIT 自然贴合；
                                        // 比例为兜底估值时 FIT 防拉伸（出黑边=正确行为）；动画期间
                                        // 框比例≠视频比例的短暂黑边为预期过渡。横屏全屏
                                        //（isFullscreen）时框禁用，恢复旧 fillMaxSize + FIT 行为
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .mediaFrame(
                                                ratio = { displayedRatio.value },
                                                fullscreen = { state.isFullscreen }
                                            )
                                    )
                                }
                                // 页内占位封面：
                                // - 播放页：等首帧渲染完成（hasFirstFrame）才 120ms 淡出；
                                //   此前封面盖着、视频在下方已完整渲染，撤封面零黑帧；
                                // - 非播放页（相邻页 / 被切走的页）：瞬时整显——切走发生在
                                //   落定去抖之后（旧页已在屏外），瞬时切换不会被看见。
                                // 2026-10-03 批次F：预览邻页在 previewHasFirstFrame（静音预览
                                // 首帧上屏）前保持封面，上屏后同 120ms 淡出露出活视频；预览已
                                // 揭示的页落定变播放页后，封面在主播放器 hasFirstFrame 前保持
                                // 隐藏（previewHasFirstFrame 尚未清除，接力到主首帧），交接零闪面。
                                // preview 不可用/未就绪/非旗舰时两态皆 false → 完全走批次E 路径。
                                val coverTarget = if (
                                    (isPlayerPage && state.hasFirstFrame) ||
                                    previewHasFirstFrame == entity.filePath
                                ) 0f else 1f
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
                                        // 2026-10-03 批次E：视频页封面优先渲染邻页预取的真实首帧
                                        //（非播放页 peek 未命中会补取；播放页仅 peek，不额外抽帧），
                                        // 未就绪/失败回退旧卡片封面 —— 淡出节奏与 hasFirstFrame 逻辑不变
                                        VideoPageCoverFrame(
                                            filePath = entity.filePath,
                                            isPlayerPage = isPlayerPage,
                                            playerViewModel = playerViewModel,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            PlayerCoverContent(entity = entity, modifier = Modifier.fillMaxSize())
                                        }
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

            // 顶部覆盖层（2026-10-02 批次A 重构为两段）：第一段「头像行」常驻显示——
            // 保留黑→透明渐变兜底，黑底视频上仍可读；返回 ← 与 ⋮ 菜单仍随 controlsVisible
            // 淡入淡出（固定占位，显隐切换时头像行不跳动）；第二段「详情行」随
            // controlsVisible 淡入淡出。1.5s 自动隐藏仍由上方 rememberAutoHide 驱动
            //（调用未动，头像行不受影响）。
            Column(
                Modifier
                    .align(Alignment.TopCenter)
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
                    // 返回（X 风 ←）：统一走退场动画（先暂停再飞回）；随 controlsVisible 显隐
                    Box(
                        Modifier.width(34.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // 全限定调用：避免解析到已弃用的 RowScope.AnimatedVisibility 扩展
                        androidx.compose.animation.AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(tween(220)),
                            exit = fadeOut(tween(200))
                        ) {
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
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                    // 头像 + 作者行（X 风：头像徽章 + 显示名 + @handle）—— 批次A 起常驻，
                    // 不再随 controlsVisible 消失
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
                        Modifier.weight(1f),
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (handle.isNotBlank()) {
                                Text(
                                    text = "@$handle",
                                    color = Color(0x99FFFFFF),
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // ⋮ 菜单：随 controlsVisible 显隐（固定占位）
                    Box(
                        Modifier.width(36.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        // 全限定调用：避免解析到已弃用的 RowScope.AnimatedVisibility 扩展
                        androidx.compose.animation.AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(tween(220)),
                            exit = fadeOut(tween(200))
                        ) {
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
                                            val menuHandle = current?.uploader?.takeIf { it.isNotBlank() }
                                            if (menuHandle != null && openXProfile(context, menuHandle)) {
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
                                                val subHandle = current?.uploader?.takeIf { it.isNotBlank() } ?: return@DropdownMenuItem
                                                menuScope.launch {
                                                    val result = runCatching { SubscriptionManager.toggle(context, subHandle) }
                                                        .getOrDefault(authorSubscribed)
                                                    authorSubscribed = result
                                                    notify(
                                                        if (result) FeedbackKind.SUCCESS else FeedbackKind.NEUTRAL,
                                                        if (result) "已订阅 @${subHandle}，发现新作品将自动下载" else "已退订 @${subHandle}"
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 详情行（第二行，随 controlsVisible 淡入淡出）：ID <短ID> · <MM-dd> ·
                // 组内第N个媒体 · <尾部信息>；ID 段点击复制完整帖子ID，段落无数据整段跳过
                if (tweetId != null || detailTail.isNotBlank()) {
                    AnimatedVisibility(
                        visible = controlsVisible,
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(200))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 44.dp, end = 8.dp, bottom = 2.dp)
                        ) {
                            if (tweetId != null) {
                                Text(
                                    text = "ID ${tweetId.take(10)}…",
                                    color = Color.White.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        copyToClipboard(context, "Edqiu tweet id", tweetId)
                                        notify(FeedbackKind.SUCCESS, "已复制帖子ID")
                                    }
                                )
                                if (detailTail.isNotBlank()) {
                                    Text(
                                        text = " · $detailTail",
                                        color = Color.White.copy(alpha = 0.55f),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                Text(
                                    text = detailTail,
                                    color = Color.White.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // 底部：操作面板 + 控制层（2026-10-02 批次A：图片会话整体替换为图片查看器
            // 专属底部栏 —— 播放圆钮/控制条/视频动作面板/播放列表横条均不再渲染）
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
                    if (sessionIsImage) {
                        // 图片会话专属底部功能栏（批次A）：保存到相册/分享/打开X/复制链接/删除，
                        // 随 controlsVisible 进出；前 4 项复用既有 notify 反馈函数，删除复用
                        // pendingDelete 弹窗流程（仅移除记录/删除文件）
                        GlassImageViewerBar(
                            visible = controlsVisible,
                            onSaveToGallery = {
                                current?.let { entity ->
                                    scope.launch { saveImageToGallery(context, entity, notify) }
                                }
                            },
                            onShare = { current?.let { shareMedia(context, it, notify) } },
                            onOpenX = { current?.let { openSource(context, it, notify) } },
                            onCopyLink = { current?.let { copySourceLink(context, it, notify) } },
                            onDelete = { current?.let { pendingDelete = it } }
                        )
                    } else {
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
    Box(modifier = modifier.background(Color.Black)) {
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
                    color = Color.White.copy(alpha = 0.7f), // 2026-10：黑底上必须用浅色文字
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

/**
 * 2026-10-03 批次E：视频页封面真帧层 —— 优先渲染预取的视频真实首帧
 *（RGB_565 降采样缓存，见 VideoFirstFrameCache；渲染在页内媒体层、Crop 取景，
 * 与旧卡片封面同一占位，页面激活时封面内容无跳变），未就绪/失败回退旧卡片封面。
 * - 非播放页：peek 未命中时异步补取（落定预取先行，通常直接命中）；
 * - 播放页：只 peek 不补取（PlayerView 在下方渲染真画面，不为当前页额外抽帧）——
 *   该页帧若在邻页时期已预取，封面即真帧，120ms 淡出交接 PlayerView 更无缝。
 * 帧以 remember(filePath) 状态承载：重组不重取，避免取帧期间闪烁；抽帧全程 IO，
 * 页面离开组合取消本协程也不影响结果落缓存（缓存自持作用域）。
 */
@Composable
private fun VideoPageCoverFrame(
    filePath: String,
    isPlayerPage: Boolean,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    fallbackContent: @Composable () -> Unit
) {
    var frame by remember(filePath) { mutableStateOf(playerViewModel.peekFirstFrame(filePath)) }
    LaunchedEffect(filePath, isPlayerPage) {
        if (frame == null && !isPlayerPage) {
            frame = playerViewModel.firstFrameFor(filePath)
        }
    }
    val bitmap = frame
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        fallbackContent()
    }
}

// ===================== 媒体框工具（2026-10-03 批次C） =====================

/**
 * 媒体框比例来源（批次C / 2026-10-05 修订）：实体 mediaWidth/mediaHeight 均有效（>0）时
 * 取 w/h（显示宽高 px，视频为旋转校正后尺寸）；**缺失时返回 null**——引擎保持当前比例
 * 不跳兜底估值（旧实现 16:9 兜底 vs 竖版视频实测 9:16，写回自愈瞬间框「缩大」，
 * 即用户反馈的「每次滑动都是缩大后再播放」），等播放实测/backfill 写回后归准。
 * 视频面 RESIZE_MODE_ZOOM 铺框：形变期框比例≠源比例时裁切补位而非黑边，无忽大忽小。
 */
private fun mediaFrameRatio(entity: DownloadHistoryEntity, isImage: Boolean): Float? {
    val w = entity.mediaWidth
    val h = entity.mediaHeight
    if (w != null && h != null && w > 0 && h > 0) return w.toFloat() / h.toFloat()
    return null
}

/**
 * 比例驱动的媒体框布局（批次C）：竖屏会话页把媒体层从「全屏 FIT」改为
 * 「居中媒体框」——框在页面内 fit-contained：
 * frameW = min(pageW, pageH × ratio)，frameH = frameW / ratio（Constraints.fixed）。
 * 框外露出的区域由既有的黑底 + radialGradient 氛围层兜底（背景层未动）。
 *
 * 性能红线：ratio()（Animatable.value）与 fullscreen()（State 读取）都在 measure 块内
 * 读取 —— snapshot 观察自动触发「仅重测量」，动画每帧不重组整页；禁用
 * animateDpAsState+重组方案。fullscreen=true（横屏全屏）或约束无界时透传原约束，
 * 行为与旧 fillMaxSize 完全一致（媒体框禁用）。
 */
private fun Modifier.mediaFrame(
    ratio: () -> Float,
    fullscreen: () -> Boolean
): Modifier = layout { measurable, constraints ->
    val placeable = if (fullscreen() || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        measurable.measure(constraints)
    } else {
        val r = ratio().takeIf { it > 0f } ?: 16f / 9f
        val frameW = min(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat() * r)
        val w = frameW.roundToInt().coerceIn(0, constraints.maxWidth)
        val h = (frameW / r).roundToInt().coerceIn(0, constraints.maxHeight)
        measurable.measure(Constraints.fixed(w, h))
    }
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

// 2026-10-02 批次A：图片查看器缩放参数 —— 双指捏合上限 5x，双击切换目标 2.5x
private const val MAX_IMAGE_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * 图片查看器页（2026-10-02 批次A）：双指缩放（1x–5x，锚定双指质心）+ 单指平移 +
 * 双击 1x↔2.5x 切换（带动画）+ 单击切换控制层显隐。
 *
 * 与外层手势共存的关键约束：
 * - 仅在「多指」或「scale>1 的单指拖动」时接管并消费指针事件；1x 且无偏移时
 *   完全不消费——VerticalPager 竖滑、外层横向拖拽退场、单击行为都不受影响；
 * - 平移边界随 scale 收紧（|t| ≤ (scale-1)×尺寸/2），scale 回到 1 时偏移被钳制
 *   归零，放行条件自动恢复；
 * - 缩放/平移值全部在 graphicsLayer 块内逐帧读取，不触发逐帧重组；
 * - 切页落定（settledPage 变为本页）时复位缩放/平移，清掉相邻预组合页的残留状态。
 *
 * 2026-10-03 批次C：本页被外层 mediaFrame 框约束尺寸（竖屏会话页按媒体比例居中出框），
 * fillMaxSize 即框尺寸；contentScale 改为 Crop 铺满框（框比例准了即完整显示，无取景差）；
 * 加载成功后经 onImageDimensions 上报实测宽高供写回自愈。缩放手势全部套在框内，
 * 1x 手势穿透行为不变。
 */
@Composable
private fun ZoomableImageViewerPage(
    filePath: String,
    contentDescription: String?,
    page: Int,
    settledPage: Int,
    onTap: () -> Unit,
    // 2026-10-03 批次C：Coil 加载成功后回传 (宽, 高)（intrinsic 尺寸），由 ViewModel 判定写回
    onImageDimensions: (Int, Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var nodeSize by remember { mutableStateOf(IntSize.Zero) }

    // 切页落定复位（首次落定同样触发，1x→1x 无视觉变化）；snapTo 会顺带取消
    // 在飞的双击动画，避免复位后被旧动画改写
    LaunchedEffect(settledPage) {
        if (settledPage == page) {
            scale.snapTo(1f)
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
        }
    }

    coil.compose.AsyncImage(
        model = File(filePath),
        contentDescription = contentDescription,
        // 2026-10-03 批次C：Fit → Crop 铺满媒体框（框比例 = 实体比例时取景差为零；
        // 兜底估值期间轻微取景，写回自愈后随即归准）
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        onState = { imageState ->
            // 2026-10-03 批次C：加载成功回传实测宽高（intrinsic 尺寸 >0 才报；
            // 去重与写库在 ViewModel.reportImageDimensions 内）
            if (imageState is AsyncImagePainter.State.Success) {
                val drawable = imageState.result.drawable
                val w = drawable.intrinsicWidth
                val h = drawable.intrinsicHeight
                if (w > 0 && h > 0) onImageDimensions(w, h)
            }
        },
        modifier = Modifier
            .fillMaxSize()
            // 缩放溢出裁剪在页内，防止放大后的画面越过本页边界渗染邻页区域
            .clipToBounds()
            .onSizeChanged { nodeSize = it }
            // 单击切控制层 + 双击缩放：detectTapGestures 会消费 down/up，
            // 因此外层页面 Box 的 clickable 不会重复触发切换
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        scope.launch {
                            val target = if (scale.value > 1.01f) 1f else DOUBLE_TAP_SCALE
                            // 以双击点为锚换算目标平移（等比锚点公式 + 边界钳制）
                            val ratio = target / scale.value
                            val cx = pos.x - nodeSize.width / 2f
                            val cy = pos.y - nodeSize.height / 2f
                            val maxX = (target - 1f) * nodeSize.width / 2f
                            val maxY = (target - 1f) * nodeSize.height / 2f
                            val tx = (cx - (cx - offsetX.value) * ratio).coerceIn(-maxX, maxX)
                            val ty = (cy - (cy - offsetY.value) * ratio).coerceIn(-maxY, maxY)
                            launch { scale.animateTo(target, tween(durationMillis = 220)) }
                            launch { offsetX.animateTo(tx, tween(durationMillis = 220)) }
                            launch { offsetY.animateTo(ty, tween(durationMillis = 220)) }
                        }
                    }
                )
            }
            // 自定义变换手势：仅「多指」或「scale>1 的单指拖动」消费事件；
            // 1x 的单指（横竖向）完全放行给 Pager 竖滑与外层横向拖拽退场
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var active = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // 上层（Pager/外层退场/点击检测）已消费且本手势未接管 → 让位
                        if (!active && event.changes.fastAny { it.isConsumed }) break
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) break
                        if (!active) {
                            if (pressedCount >= 2) {
                                // 双指：立即接管（即使尚未过触摸判定阈，避免缩放被 Pager 抢走）
                                active = true
                            } else {
                                val primary = event.changes.fastFirstOrNull { it.id == down.id }
                                    ?: break  // 首指已抬起且无多指 → 结束（让单击判定完成）
                                if (scale.value > 1f &&
                                    (primary.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    // 缩放中的单指拖动 → 接管为平移
                                    active = true
                                }
                            }
                        }
                        if (active) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            // calculatePan 在无有效指针时返回 Offset.Zero（永不为 NaN），
                            // 这里只需校验 zoom 与质心有效性
                            if (!zoomChange.isNaN() && !centroid.isUnspecified) {
                                // 锚点公式：保持质心下的图像点跟随手指。本 pointerInput
                                // 位于 graphicsLayer 外层，坐标即未变换的页面坐标
                                val newScale = (scale.value * zoomChange).coerceIn(1f, MAX_IMAGE_SCALE)
                                val ratio = newScale / scale.value
                                val cx = centroid.x - nodeSize.width / 2f
                                val cy = centroid.y - nodeSize.height / 2f
                                // 平移边界：仅允许平移到放大超出的部分；scale=1 时边界为 0
                                //（自动归零，横向/竖向事件随之恢复放行）
                                val maxX = (newScale - 1f) * nodeSize.width / 2f
                                val maxY = (newScale - 1f) * nodeSize.height / 2f
                                val tx = (cx + panChange.x - (cx - offsetX.value) * ratio)
                                    .coerceIn(-maxX, maxX)
                                val ty = (cy + panChange.y - (cy - offsetY.value) * ratio)
                                    .coerceIn(-maxY, maxY)
                                // snapTo 是普通挂起函数，不能在 pointerInput 的受限挂起作用域
                                // 内直接调用——按事件派发到主队列顺序执行（与外层横向拖拽的
                                // slideX.snapTo 同一模式）；单次 launch 内三个值成组更新
                                scope.launch {
                                    scale.snapTo(newScale)
                                    offsetX.snapTo(tx)
                                    offsetY.snapTo(ty)
                                }
                            }
                            // 接管期间消费全部位移：Pager 竖滑与外层横向退场均不再触发
                            event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                }
            }
            // 变换渲染：scale/offset 在此逐帧读取（不触发重组）
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offsetX.value
                translationY = offsetY.value
            }
    )
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

// 2026-10-02 批次A：图片会话「保存到相册」—— MediaStore.Images 插入（源为本地文件，
// MIME 按扩展名映射，gif → image/gif）：API 29+ 走 RELATIVE_PATH "Pictures/Edqiu" +
// IS_PENDING 两段式写入；低版本走传统落盘 Pictures/Edqiu + MediaStore 登记 + 媒体扫描
//（WRITE_EXTERNAL_STORAGE 已在清单声明 maxSdkVersion=29）。结果统一走 notify 胶囊反馈。
@Suppress("DEPRECATION")  // MediaStore.Images.Media.DATA / getExternalStoragePublicDirectory 仅低版本分支使用
private suspend fun saveImageToGallery(
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
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = MediaFileTypes.mimeTypeForExtension(file.extension)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 分区存储：IS_PENDING 占位 → 写入流 → 置 0 转正（相册可见）
                val target = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Edqiu")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                ) ?: error("相册插入失败")
                resolver.openOutputStream(target)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("相册输出流打开失败")
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            } else {
                // 旧版本：落盘 Pictures/Edqiu（重名加时间戳）→ MediaStore 登记 → 扫描
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Edqiu"
                )
                if (!dir.exists()) dir.mkdirs()
                var target = File(dir, file.name)
                if (target.exists()) {
                    target = File(
                        dir,
                        "${file.nameWithoutExtension}_${System.currentTimeMillis()}.${file.extension}"
                    )
                }
                file.copyTo(target, overwrite = true)
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, target.name)
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        put(MediaStore.Images.Media.DATA, target.absolutePath)
                    }
                ) ?: error("相册插入失败")
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            }
        }
    }.onSuccess {
        notify(FeedbackKind.SUCCESS, "已保存到相册")
    }.onFailure {
        notify(FeedbackKind.ERROR, "保存失败")
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
        // （requestedOrientation 临时覆盖清单竖屏锁，系统旋转锁关闭也能横屏观看）
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        // 2026-10-03 用户定案：App 整体锁定竖屏——退出横屏恢复 PORTRAIT
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
