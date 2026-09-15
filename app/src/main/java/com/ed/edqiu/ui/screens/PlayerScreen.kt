package com.ed.edqiu.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.ed.edqiu.service.SubscriptionManager
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.viewmodel.HistoryViewModel
import com.ed.edqiu.viewmodel.PlayerViewModel
import com.ed.edqiu.domain.TweetIdExtractor
import com.ed.edqiu.ui.components.CapsuleFeedbackController
import com.ed.edqiu.ui.components.CapsuleFeedbackHost
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.player.GlassActionsPanel
import com.ed.edqiu.ui.player.GlassPlayerControls
import com.ed.edqiu.ui.player.GlassTopBar
import com.ed.edqiu.ui.player.rememberAutoHide
import com.ed.edqiu.ui.util.copyToClipboard
import com.ed.edqiu.ui.util.openTwitterStatus
import java.io.File

/**
 * 播放器屏幕（Glass You 重构版）。
 *
 * 铁律：功能零损失 —— 播放/暂停、seek、倍速、静音、全屏、播放列表、
 * 打开文件/原推文/复制/分享/删除 全部保留，仅更换控制层为玻璃材质。
 */
@Composable
fun PlayerScreen(
    playerViewModel: PlayerViewModel,
    historyViewModel: HistoryViewModel,
    autoPlayFilePath: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val state by playerViewModel.playerState.collectAsState()
    val playlist by playerViewModel.videoList.collectAsState()
    val rowState = rememberLazyListState()
    val current = state.currentVideo
    val currentIndex = playlist.indexOfFirst { it.filePath == current?.filePath }.takeIf { it >= 0 } ?: 0
    // 图片查看分流：本地图片文件走查看器（Coil 全屏），视频才走 ExoPlayer
    val isImageView = current?.filePath?.let { MediaFileTypes.isImageFile(it) } == true
    val backgroundInteraction = remember { MutableInteractionSource() }
    var pendingDelete by remember { mutableStateOf<DownloadHistoryEntity?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    // 垂直连贯播放（2026-09-15，抖音式）：上下滑切换上下条视频——列表首页只能往下滑，
    // 末页只能往上滑，中间页双向可滑（Pager 边界天然行为）。
    // 页内容 = 该条封面；落定且媒体就绪后封面淡出露出下层 ExoPlayer 画面。
    val pagerState = rememberPagerState(initialPage = 0) { playlist.size.coerceAtLeast(0) }
    // 首次对齐标志：playlist 异步到达后需要瞬时定位到点击条目（分组视图展示顺序≠SQL
    // 播放列表顺序，若用动画滚动会路过一堆别的封面，而 ExoPlayer 已在播目标——
    // 表现为「卡在封面页但声音正常」的 bug，2026-09-15 用户反馈）
    var initialAligned by remember { mutableStateOf(false) }
    LaunchedEffect(currentIndex, playlist.size) {
        if (playlist.isEmpty()) return@LaunchedEffect
        val target = currentIndex.coerceIn(0, playlist.lastIndex)
        if (!initialAligned) {
            runCatching { pagerState.scrollToPage(target) }
            initialAligned = true
        } else if (pagerState.settledPage != currentIndex) {
            // 后续选片（操作面板/翻页）：动画滚动到位
            runCatching { pagerState.animateScrollToPage(target) }
        }
        if (playlist.isNotEmpty()) rowState.animateScrollToItem(currentIndex.coerceIn(0, playlist.lastIndex))
    }
    LaunchedEffect(pagerState.settledPage, playlist.size) {
        // 滑动落定：切到落定页对应条目（playVideo 内部有同路径早退，不会打断当前播放）
        playlist.getOrNull(pagerState.settledPage)?.let { entity ->
            if (entity.filePath != current?.filePath) playerViewModel.playVideo(entity)
        }
    }

    // 播放器独立反馈胶囊（2026-09-15）：替代系统 Toast，样式与主 App 玻璃胶囊统一。
    // 播放器不在主 Scaffold 内，自持一套 controller。
    val capsule = remember { CapsuleFeedbackController() }
    val notify: (FeedbackKind, String) -> Unit = { kind, text -> capsule.show(kind, text) }

    LaunchedEffect(autoPlayFilePath) { autoPlayFilePath?.let(playerViewModel::playVideo) }
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
            activity?.setPlayerFullscreen(false)
            // 2026-09-14：pause 而非 stop —— 退场动画期间 SurfaceView 冻结在最后帧（静止合成不掉帧），
            // stop 清空媒体会黑屏闪烁；真正的 stop 在 onDispose（退场动画结束后触发）
            playerViewModel.pauseVideo()
            onBack()
        }
    }

    // 3 秒自动隐藏控制层（正在播放且未展开操作面板时）
    rememberAutoHide(
        enabled = controlsVisible && state.isPlaying && !showActions,
        onHide = { controlsVisible = false },
        delayMs = 1500L  // 2026-09-15 对齐下载器项目：1.5s 沉浸
    )
    // ⋮ 菜单（X 风）：跳转到该视频 / 跳转到该作者主页 / 订阅该作者（2026-09-15 批次4）
    var feedMenuOpen by remember { mutableStateOf(false) }
    val menuScope = rememberCoroutineScope()
    var authorSubscribed by remember { mutableStateOf(false) }
    LaunchedEffect(current?.uploader) {
        val handle = current?.uploader?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        authorSubscribed = runCatching { SubscriptionManager.isSubscribed(context, handle) }.getOrDefault(false)
    }

    val duration = state.duration.takeIf { it > 0 } ?: ((current?.duration ?: 0L) * 1000L)
    val position = state.position.coerceIn(0L, duration.coerceAtLeast(0L))
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    // 2026-09-15 进入播放器过渡动画：淡入 + 轻微放大入场（300ms，iOS 风柔和曲线）
    val entrance = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(durationMillis = 300))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = entrance.value
                val scale = 0.965f + 0.035f * entrance.value
                scaleX = scale
                scaleY = scale
            }
            // 必须完全不透明！SurfaceView 在视图层级中是"硬件层洞"，
            // 父级背景只要有任何透明区域，下面的媒体库页就会从洞里透出
            .background(MaterialTheme.colorScheme.onSurface)
    ) {
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

        if (isImageView) {
            // 图片查看器：本地图片全屏展示（Coil 加载，点击唤出控制层）
            val imgFile = current?.filePath?.let(::File)
            if (imgFile != null) {
                coil.compose.AsyncImage(
                    model = imgFile,
                    contentDescription = current?.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = backgroundInteraction,
                            indication = null,
                            onClick = { controlsVisible = !controlsVisible }
                        ),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
        } else {
        // 2026-09-15 比例自适应缩放（保护像素 + 不做半屏/小屏）：
        // - 竖屏/方形视频在竖屏方向 → FIT（几乎铺满，零裁切零放大，像素无损）；
        // - 横屏视频在竖屏方向 → ZOOM（保持大画面观感，宁可裁边不缩成半屏）；
        // - 全屏横屏模式（屏幕已横置）→ FIT（横屏屏 ≈ 视频比例，完整像素铺满）。
        // 视频真实宽高由 ExoPlayer onVideoSizeChanged 回传。
        val landscapeVideo = state.videoWidth > state.videoHeight
        val targetResizeMode = when {
            state.isFullscreen -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            landscapeVideo -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = playerViewModel.exoPlayer
                    useController = false
                    resizeMode = targetResizeMode
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.resizeMode = targetResizeMode }
        )
        }

        // 垂直翻页手势层（覆盖媒体区）：每页显示该条封面，落定页且媒体就绪后封面
        // 淡出露出下层播放画面；点击页唤出/隐藏控制层（替代原背景点击层职责）。
        if (playlist.isNotEmpty()) {
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 0,
                // 标准抖音方向（2026-09-15 用户定案）：手指从底部向上滑 = 下一条，
                // 向下滑 = 返回上一条；边界天然无反应（首页没有上一条、末页没有下一条）。
                // 注意：勿加 reverseLayout（那会翻转成"下滑=下一条"，与用户习惯相反）。
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val entity = playlist.getOrNull(page)
                // 落定 + 当前条目 + 无翻页手势进行中 → 封面才淡出。
                // 兜底：isPlaying（声音已出）时无论 isReady 状态如何都必须让位给画面，
                // 防止任何时序抖动导致「封面常驻、只见声音不见画面」（2026-09-15 用户反馈）
                val coverTarget = if (!pagerState.isScrollInProgress &&
                    page == pagerState.settledPage &&
                    entity?.filePath == current?.filePath && (state.isReady || state.isPlaying)
                ) 0f else 1f
                val coverAlpha by animateFloatAsState(
                    targetValue = coverTarget,
                    animationSpec = tween(durationMillis = 220),
                    label = "playerPageCover"
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { controlsVisible = !controlsVisible }
                        )
                ) {
                    if (coverAlpha > 0.01f && entity != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(coverAlpha)
                                .background(MaterialTheme.colorScheme.onSurface)
                        ) {
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

        // 顶部覆盖层（2026-09-15 对齐下载器项目 X 风）：返回 + 头像/@handle 行 + ⋮ 菜单
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
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
                    // 返回（X 风 ←）
                    Text(
                        text = "←",
                        color = Color.White,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .padding(6.dp)
                            .clickable(
                                interactionSource = backgroundInteraction,
                                indication = null,
                            ) {
                                playerViewModel.exitFullscreen()
                                activity?.setPlayerFullscreen(false)
                                onBack()
                            }
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
                        androidx.compose.material3.DropdownMenu(
                            expanded = feedMenuOpen,
                            onDismissRequest = { feedMenuOpen = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("跳转到该视频") },
                                onClick = {
                                    feedMenuOpen = false
                                    current?.let { openSource(context, it, notify) }
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
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
                                androidx.compose.material3.DropdownMenuItem(
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
                // 头像 + 作者行（X 风：头像徽章 + @handle + 文案）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFF26324A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (current?.uploader ?: "").take(1).uppercase().ifBlank { "?" },
                            color = Color(0xB3FFFFFF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = current?.uploader?.takeIf { it.isNotBlank() } ?: "未知作者",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "@${current?.uploader ?: ""}",
                            color = Color(0x99FFFFFF),
                            fontSize = 12.5.sp,
                            maxLines = 1
                        )
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
                    onPlayVideo = playerViewModel::playVideo,
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
                        .clip(androidx.compose.foundation.shape.CircleShape)
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
                    isImageMode = isImageView,
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
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除媒体") },
                text = { Text("可以只移除媒体库记录，也可以同时删除本地文件。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            playerViewModel.pauseVideo()
                            playerViewModel.exitFullscreen()
                            activity?.setPlayerFullscreen(false)
                            historyViewModel.deleteHistory(entity, deleteLocalFile = true)
                            onBack()
                        }
                    ) { Text("删除文件") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            playerViewModel.pauseVideo()
                            playerViewModel.exitFullscreen()
                            activity?.setPlayerFullscreen(false)
                            historyViewModel.deleteHistory(entity, deleteLocalFile = false)
                            onBack()
                        }
                    ) { Text("仅移除记录") }
                }
            )
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
