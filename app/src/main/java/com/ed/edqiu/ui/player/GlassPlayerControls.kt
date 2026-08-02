package com.ed.edqiu.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ed.twitterdownloader.data.database.DownloadHistoryEntity
import com.ed.twitterdownloader.ui.components.ThumbnailWithFallback
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.MediaGlassSurface
import com.ed.edqiu.ui.components.GlassTier
import kotlinx.coroutines.delay

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
        enter = fadeIn(),
        exit = fadeOut(),
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
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
fun GlassPlayerControls(
    progress: Float,
    positionText: String,
    durationText: String,
    speedText: String,
    muted: Boolean,
    isPlaying: Boolean,
    visible: Boolean,
    isImageMode: Boolean = false,
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
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
        modifier = modifier
    ) {
        MediaGlassSurface(
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // 图片模式：无进度概念，隐藏滑杆与时间行；仅保留操作入口
                if (!isImageMode) {
                // 胶囊滑杆（细化）：thumb 4dp + track 2dp 细线
                Slider(
                    value = progress,
                    onValueChange = onSeek,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                    )
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
                }

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
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onPlayPause
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
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
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
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
@Composable
fun MiniPlayerBar(
    title: String,
    subtitle: String,
    progress: Float,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    MediaGlassSurface(
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(modifier = Modifier.height(58.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onTogglePlay
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            // 底部进度细线
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

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
