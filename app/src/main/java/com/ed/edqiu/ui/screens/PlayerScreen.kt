package com.ed.edqiu.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    LaunchedEffect(autoPlayFilePath) { autoPlayFilePath?.let(playerViewModel::playVideo) }
    LaunchedEffect(currentIndex, playlist.size) {
        if (playlist.isNotEmpty()) rowState.animateScrollToItem(currentIndex.coerceIn(0, playlist.lastIndex))
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
            activity?.setPlayerFullscreen(false)
            playerViewModel.stopPlayer()
            onBack()
        }
    }

    // 3 秒自动隐藏控制层（正在播放且未展开操作面板时）
    rememberAutoHide(
        enabled = controlsVisible && state.isPlaying && !showActions,
        onHide = { controlsVisible = false },
        delayMs = 3000L
    )

    val duration = state.duration.takeIf { it > 0 } ?: ((current?.duration ?: 0L) * 1000L)
    val position = state.position.coerceIn(0L, duration.coerceAtLeast(0L))
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
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
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = playerViewModel.exoPlayer
                    useController = false
                    // 视频铺满全屏：用 ZOOM 模式（牺牲边缘裁切换全屏填满）
                    // 用户反馈要求"完全铺满"，这是视频播放场景的标准做法（YouTube 同样默认 ZOOM）
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.player = playerViewModel.exoPlayer }
        )
        }

        // 顶部玻璃条
        GlassTopBar(
            title = current?.uploader?.takeIf { it.isNotBlank() } ?: "正在播放",
            subtitle = current?.title?.takeIf { it.isNotBlank() }
                ?: current?.filePath?.substringAfterLast('/') ?: "",
            fullscreen = state.isFullscreen,
            visible = controlsVisible,
            onBack = {
                playerViewModel.exitFullscreen()
                activity?.setPlayerFullscreen(false)
                onBack()
            },
            onFullscreen = playerViewModel::toggleFullscreen,
            modifier = Modifier.align(Alignment.TopCenter)
        )

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
                    onOpenFile = { current?.let { openMediaFile(context, it) } },
                    onOpenSource = { current?.let { openSource(context, it) } },
                    onCopyLink = { current?.let { copySourceLink(context, it) } },
                    onShare = { current?.let { shareMedia(context, it) } },
                    onDelete = { current?.let { pendingDelete = it } }
                )
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

private fun copySourceLink(context: Context, entity: DownloadHistoryEntity) {
    val link = sourceUrl(entity)
    if (link.isBlank()) {
        toast(context, "暂无可复制的链接")
        return
    }
    copyToClipboard(context, "Edqiu link", link)
    toast(context, "已复制链接")
}

private fun openSource(context: Context, entity: DownloadHistoryEntity) {
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
    if (!opened) toast(context, "无法打开 X/Twitter")
}

private fun openMediaFile(context: Context, entity: DownloadHistoryEntity) {
    val file = File(entity.filePath)
    if (!file.exists()) {
        toast(context, "本地文件不存在")
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
        toast(context, "无法打开文件")
    }
}

private fun shareMedia(context: Context, entity: DownloadHistoryEntity) {
    val file = File(entity.filePath)
    if (!file.exists()) {
        toast(context, "本地文件不存在")
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
        toast(context, "分享失败")
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

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

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
