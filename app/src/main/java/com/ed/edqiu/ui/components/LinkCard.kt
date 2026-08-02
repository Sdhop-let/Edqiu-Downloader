package com.ed.edqiu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    onQuickDelete: (() -> Unit)? = null
) {
    val accent = statusColor(link.status)
    val author = link.authorName?.takeIf { it.isNotBlank() }
        ?: link.authorId?.takeIf { it.isNotBlank() }
        ?: "未知作者"
    val preview = link.caption?.takeIf { it.isNotBlank() } ?: link.rawUrl
    val metadata = link.authorId?.takeIf { it.isNotBlank() }
        ?: "x.com/status/${link.tweetId.takeLast(8)}"

    // L1 玻璃卡片（双层玻璃：卡片在玻璃背景上的玻璃）—— 圆角 20dp 对齐原型 .lcard
    // 列表滚动项关闭 shadow：Modifier.shadow 创建独立 RenderNode，滚动合成压力大
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        elevated = false,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
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
            } else {
                LinkPreviewImage(link = link, accent = accent)
            }

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
                    StatusPill(status = link.status)
                    // 失败记录快速删除键：仅 FAILED 且非批量选择模式时显示
                    if (link.status == LinkStatus.FAILED && !selectionMode && onQuickDelete != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            onClick = onQuickDelete,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除失败记录",
                                tint = MaterialTheme.colorScheme.error,
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
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary,
                        accent
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
                color = Color.White
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
