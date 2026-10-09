package com.ed.edqiu.ui.player

import com.ed.edqiu.ui.util.pressableNoRipple
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.ui.components.ThumbnailWithFallback
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.MediaGlassSurface
import com.ed.edqiu.ui.components.GlassTier
import kotlinx.coroutines.delay
import com.ed.edqiu.ui.anim.Motion

/**
 * 播放器玻璃控制层（设计文档 §6）。
 *
 * 功能零损失：播放/暂停、±15s、seek、倍速循环、静音、全屏、播放列表全部保留，
 * 仅重构为 L2 玻璃浮层 + 3 秒自动隐藏。
 */

// ---------- 顶部玻璃条 ----------
@Composable
fun GlassTopBar(
    title: String,
    subtitle: String,
    fullscreen: Boolean,
    visible: Boolean,
    onBack: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        // 2026-10-10 全局动画统一：顶部条与主控制层同规格（220/160ms），替代默认时长
        enter = fadeIn(tween(durationMillis = 220)),
        exit = fadeOut(tween(durationMillis = 160)),
        modifier = modifier
    ) {
        // 深色玻璃面板：顶部贴边（无圆角、无避让），底部 16dp 圆角过渡
        // 面板更薄：内部 padding 收敛，减少对视频画面的遮挡
        MediaGlassSurface(
            shape = RoundedCornerShape(
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
                topStart = 0.dp,
                topEnd = 0.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (fullscreen) 26.dp else 16.dp,
                    top = if (fullscreen) 14.dp else 14.dp,
                    end = if (fullscreen) 26.dp else 16.dp,
                    bottom = 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (fullscreen) {
                Spacer(Modifier.width(12.dp))
                GlassIconButton(
                    icon = Icons.Outlined.OpenInFull,
                    contentDescription = "全屏",
                    onClick = onFullscreen
                )
            }
        }
        }
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 42
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFFFFFF).copy(alpha = 0.14f))
            .pressableNoRipple { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size((size - 16).dp)
        )
    }
}

// ---------- 底部玻璃控制层 ----------
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GlassPlayerControls(
    progress: Float,
    positionText: String,
    durationText: String,
    speedText: String,
    muted: Boolean,
    isPlaying: Boolean,
    visible: Boolean,
    onSeek: (Float) -> Unit,
    onSpeed: () -> Unit,
    onMute: () -> Unit,
    onPlayPause: () -> Unit,
    onFullscreen: () -> Unit,
    onToggleActions: () -> Unit,
    showActions: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        // 2026-09-30 播放页动画规格：控件淡入 tween(220)（配合 PlayerScreen 延迟 200ms 置 visible）
        enter = fadeIn(tween(Motion.PanelIn)) +
            slideInVertically(tween(durationMillis = 220)) { it / 3 },
        exit = fadeOut(tween(Motion.Panel)) +
            slideOutVertically(tween(durationMillis = 160)) { it / 3 },
        modifier = modifier
    ) {
        MediaGlassSurface(
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // 2026-10-02 批次A：移除 isImageMode 分支——图片会话不再渲染本控制条
                //（PlayerScreen 改挂 GlassImageViewerBar），视频路径行为保持不变
                // 胶囊滑杆（2026-10-10 视觉增强版）：拖动时 thumb 放大 + track 变粗，
                // 手势反馈「触摸即增强、松手平滑回落」（§5.7 进度拖动规范）。
                // 2026-10 整改保留：拖动期间只更新本地 dragProgress，松手才真正 seek 一次。
                var dragProgress by remember { mutableStateOf<Float?>(null) }
                val sliderInteraction = remember { MutableInteractionSource() }
                val sliderPressed by sliderInteraction.collectIsPressedAsState()
                val seeking = dragProgress != null || sliderPressed
                val thumbScale by animateFloatAsState(
                    targetValue = if (seeking) 3f else 1f,
                    animationSpec = Motion.thumb(),
                    label = "player_thumb_scale"
                )
                val trackGrowth by animateFloatAsState(
                    targetValue = if (seeking) 2.4f else 1f,
                    animationSpec = tween(Motion.Control),
                    label = "player_track_growth"
                )
                Slider(
                    value = dragProgress ?: progress,
                    onValueChange = { dragProgress = it },
                    onValueChangeFinished = {
                        dragProgress?.let(onSeek)
                        dragProgress = null
                    },
                    interactionSource = sliderInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .graphicsLayer {
                                    scaleX = thumbScale
                                    scaleY = thumbScale
                                }
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    },
                    track = { state ->
                        val fraction = state.coercedValueAsFraction
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp * trackGrowth)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.22f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = positionText,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = durationText,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }

                Spacer(Modifier.height(6.dp))

                // 主控制排
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左：倍速 + 静音
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChipButton(
                            text = speedText,
                            onClick = onSpeed,
                            highlighted = true
                        )
                        GlassChipButton(
                            text = if (muted) "已静音" else "音量",
                            onClick = onMute,
                            danger = muted,
                            icon = if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // 中：播放键（52dp 液态圆钮，适配深色玻璃——白色描边环 + 白色填充）
                    // 2026-10-10：播放/暂停图标 Crossfade 平滑转换（替代瞬间切换）
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                            .pressableNoRipple { onPlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Crossfade(
                            targetState = isPlaying,
                            animationSpec = tween(Motion.Micro),
                            label = "player_playpause_icon"
                        ) { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // 右：全屏 + 更多
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChipButton(
                            text = "横屏",
                            onClick = onFullscreen,
                            icon = Icons.Outlined.OpenInFull
                        )
                        GlassChipButton(
                            text = if (showActions) "收起" else "更多",
                            onClick = onToggleActions,
                            icon = if (showActions) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlassChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    highlighted: Boolean = false,
    danger: Boolean = false
) {
    // 适配深色玻璃面板：背景为白色细描边（0.16 alpha）+ 极浅填充
    // 在 MediaGlassSurface (黑底 60%) 上不再泛白，与深色底形成清晰边界
    val bg = when {
        danger -> Color(0xFFFF3B30).copy(alpha = 0.18f)
        highlighted -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.04f)
    }
    val borderColor = when {
        danger -> Color(0xFFFFB4AB).copy(alpha = 0.40f)
        highlighted -> Color.White.copy(alpha = 0.28f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val content = if (danger) Color(0xFFFFB4AB) else Color.White
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .pressableNoRipple { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
        }
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            fontSize = 13.sp
        )
    }
}

// ---------- 播放列表 / 操作面板 ----------
@Composable
fun GlassActionsPanel(
    playlist: List<DownloadHistoryEntity>,
    current: DownloadHistoryEntity?,
    rowState: androidx.compose.foundation.lazy.LazyListState,
    visible: Boolean,
    onPlayVideo: (DownloadHistoryEntity) -> Unit,
    onOpenFile: () -> Unit,
    onOpenSource: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        // 2026-10-10 动画统一：对齐主控制层规格（220/160ms）
        enter = fadeIn(tween(Motion.PanelIn)) + slideInVertically(tween(Motion.PanelIn)) { it },
        exit = fadeOut(tween(Motion.Panel)) + slideOutVertically(tween(Motion.Panel)) { it },
        modifier = modifier
    ) {
        MediaGlassSurface(
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 动作行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassActionItem(
                        text = "文件",
                        icon = Icons.Default.FileOpen,
                        onClick = onOpenFile,
                        modifier = Modifier.weight(1f)
                    )
                    GlassActionItem(
                        text = "打开X",
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = onOpenSource,
                        modifier = Modifier.weight(1f)
                    )
                    GlassActionItem(
                        text = "复制",
                        icon = Icons.Default.ContentCopy,
                        onClick = onCopyLink,
                        modifier = Modifier.weight(1f)
                    )
                    GlassActionItem(
                        text = "分享",
                        icon = Icons.Outlined.Share,
                        onClick = onShare,
                        modifier = Modifier.weight(1f)
                    )
                    GlassActionItem(
                        text = "删除",
                        icon = Icons.Default.Delete,
                        onClick = onDelete,
                        danger = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                // 播放列表
                if (playlist.isNotEmpty()) {
                    LazyRow(
                        state = rowState,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(playlist, key = { _, item -> item.id }) { index, item ->
                            GlassPlaylistThumb(
                                item = item,
                                selected = item.filePath == current?.filePath,
                                frameOffset = ((index + 1) * 1.5f).coerceAtMost(12f),
                                onClick = { onPlayVideo(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassActionItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bg = if (danger) Color(0xFFFF3B30).copy(alpha = 0.28f)
    else Color.White.copy(alpha = 0.12f)
    val content = if (danger) Color(0xFFFFB4AB) else Color.White
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .pressableNoRipple { onClick() }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GlassPlaylistThumb(
    item: DownloadHistoryEntity,
    selected: Boolean,
    frameOffset: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 84.dp, height = 42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .pressableNoRipple { onClick() }
    ) {
        ThumbnailWithFallback(
            thumbnailUrl = item.thumbnail,
            videoFilePath = item.filePath,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            frameOffsetSeconds = frameOffset,
            preferLocalFrame = item.thumbnail.isBlank()
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            )
        }
    }
}

// ---------- 迷你播放条 ----------
// 2026-09-30 v1.6.9：MiniPlayerBar 已移除（用户反馈退场后媒体库底部闪现的标题胶囊
// 不符合预期）。播放器退场即完全收起，不再有迷你续播条。

/** 自动隐藏计时器辅助：3 秒后回调隐藏。 */
@Composable
fun rememberAutoHide(enabled: Boolean, onHide: () -> Unit, delayMs: Long = 3000L) {
    LaunchedEffect(enabled) {
        if (enabled) {
            delay(delayMs)
            onHide()
        }
    }
}
