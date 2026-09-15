package com.ed.edqiu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.theme.statusColor
import com.ed.edqiu.ui.util.formatRelativeTime

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinkCard(
    link: SavedLink,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelectionToggle: () -> Unit = {},
    onQuickDelete: (() -> Unit)? = null,
    // 卡片直接下载（待处理/失败时显示，免进详情页即可下载）
    onDownload: (() -> Unit)? = null,
    // 实时下载进度（2026-09-15）：0..99，null = 该条当前未在下载中。
    // 数据来自 DownloadTaskBus（双引擎 300ms 节流回写），下载中显示
    // 「下载中 xx%」pill + 细进度条，下载完成即由 DB 的 DOWNLOADED 状态接管。
    downloadProgress: Int? = null
) {
    val isDownloading = downloadProgress != null
    val accent = if (isDownloading) {
        MaterialTheme.colorScheme.primary
    } else {
        statusColor(link.status)
    }
    val author = link.authorName?.takeIf { it.isNotBlank() }
        ?: link.authorId?.takeIf { it.isNotBlank() }
        ?: "未知作者"
    val preview = link.caption?.takeIf { it.isNotBlank() } ?: link.rawUrl
    val metadata = link.authorId?.takeIf { it.isNotBlank() }
        ?: "x.com/status/${link.tweetId.takeLast(8)}"

    // L1 玻璃卡片（双层玻璃：卡片在玻璃背景上的玻璃）—— 圆角 20dp 对齐原型 .lcard
    // 列表滚动项关闭 shadow：Modifier.shadow 创建独立 RenderNode，滚动合成压力大
    // 按压反馈（2026-09-14）：iOS 风按压缩放（0.975）替代涟漪 —— 按下即跟手，松手弹性回位
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 1100f),
        label = "linkCardPress"
    )
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        elevated = false,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = if (selectionMode) onSelectionToggle else onClick,
                onLongClick = onLongClick
            )
    ) {
        // 选中态：状态色描边 + 淡底
        Box(
            Modifier
                .matchParentSize()
                .background(
                    if (selected) accent.copy(alpha = 0.08f) else Color.Transparent
                )
        )
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelectionToggle() }
                )
            }
            // 批量模式下也保留视频/图片预览缩略图（勾选框与缩略图并排）
            LinkPreviewImage(link = link, accent = accent)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = author,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 下载中：pill 换成「下载中 xx%」（主题蓝），下载完成由 DOWNLOADED 状态接管
                    if (isDownloading && downloadProgress != null) {
                        DownloadingPill(progress = downloadProgress)
                    } else {
                        StatusPill(status = link.status)
                    }
                    // 待处理/失败记录直接下载：免进详情页，点卡片旁下载键即触发（推文不存在则无意义）
                    // 下载中不显示下载键，防止重复触发
                    if ((link.status == LinkStatus.PENDING || link.status == LinkStatus.FAILED) &&
                        !isDownloading &&
                        !selectionMode && onDownload != null
                    ) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            onClick = onDownload,
                            shape = CircleShape,
                            color = accent.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "下载",
                                tint = accent,
                                modifier = Modifier.padding(4.dp).size(16.dp)
                            )
                        }
                    }
                    // 失败/推文不存在记录快速删除键：非批量选择模式时显示（死链最需要一键清理）
                    if ((link.status == LinkStatus.FAILED || link.status == LinkStatus.DELETED) &&
                        !selectionMode && onQuickDelete != null
                    ) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val deleteTint = if (link.status == LinkStatus.DELETED) {
                            statusColor(LinkStatus.DELETED)
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                        Surface(
                            onClick = onQuickDelete,
                            shape = CircleShape,
                            color = deleteTint.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, deleteTint.copy(alpha = 0.25f))
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除失败记录",
                                tint = deleteTint,
                                modifier = Modifier.padding(4.dp).size(16.dp)
                            )
                        }
                    }
                }

                Text(
                    text = preview,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    minLines = 1
                )

                // 实时下载进度条（仅下载中显示）：4dp 细条 + 主题蓝，随百分比平滑推进
                if (isDownloading && downloadProgress != null) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = downloadProgress / 100f,
                        animationSpec = tween(durationMillis = 280),
                        label = "linkCardDownloadProgress"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress.coerceIn(0.02f, 1f))
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatRelativeTime(link.savedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkPreviewImage(link: SavedLink, accent: Color) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(MaterialTheme.shapes.medium)
            // 2026-08-17 去蓝：不再用 primary→tertiary→accent 的蓝色渐变底（滚动时形成
            // "蓝色方块遮挡卡片"的视觉），改为中性浅底 + 状态色淡 tint；有封面图时仍显示封面
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.surfaceContainer,
                        accent.copy(alpha = 0.30f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!link.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = link.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "X",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shadowElevation = 1.dp
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(5.dp)
                    .size(18.dp)
            )
        }

        if (!link.avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = link.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8))
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(accent)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}

@Composable
private fun StatusPill(status: LinkStatus) {
    val color = statusColor(status)
    val label = when (status) {
        LinkStatus.PENDING -> "未下载"
        LinkStatus.DOWNLOADED -> "已下载"
        LinkStatus.FAILED -> "失败"
        LinkStatus.DELETED -> "推文不存在"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** 下载中状态 pill：「下载中 43%」，主题蓝，与 [StatusPill] 同形态。 */
@Composable
private fun DownloadingPill(progress: Int) {
    Text(
        text = "下载中 $progress%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
