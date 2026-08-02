package com.ed.twitterdownloader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ed.twitterdownloader.data.model.DownloadStatus
import com.ed.twitterdownloader.data.model.DownloadTask
import com.ed.twitterdownloader.ui.components.ThumbnailWithFallback
import com.ed.twitterdownloader.viewmodel.DownloadViewModel
import com.ed.twitterdownloader.viewmodel.HistoryViewModel
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier

private val Accent = Color(0xFF0F766E)
private val Blue = Color(0xFF2563EB)
private val Ink = Color(0xFF101417)
private val Muted = Color(0xFF64748B)
// 注：当前仅在 Compose 外/常量使用，组件内已切到 MaterialTheme 颜色

@Composable
fun DownloadScreen(
    downloadViewModel: DownloadViewModel,
    historyViewModel: HistoryViewModel,
    onBack: (() -> Unit)? = null,
    onNavigateToPlayer: (String) -> Unit
) {
    val state by downloadViewModel.uiState.collectAsState()
    val activeCount = state.tasks.count { it.isActive }
    val completedCount = state.tasks.count { it.status == DownloadStatus.COMPLETED }
    val failedCount = state.tasks.count { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),  // 透出 GlassBackground 莫奈色域
        contentPadding = PaddingValues(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            DownloadHeader(
                activeCount = activeCount,
                completedCount = completedCount,
                failedCount = failedCount,
                onBack = onBack,
                onScan = { historyViewModel.scanLocalVideos() }
            )
        }

        item {
            DownloadActions(
                onScan = { historyViewModel.scanLocalVideos() },
                onClearCompleted = downloadViewModel::clearCompleted
            )
        }

        if (state.tasks.isEmpty()) {
            item {
                EmptyDownloadState(onScan = { historyViewModel.scanLocalVideos() })
            }
        } else {
            items(state.tasks, key = { it.id }) { task ->
                DownloadTaskCard(
                    task = task,
                    onCancel = { downloadViewModel.cancelDownload(task.id) },
                    onRemove = { downloadViewModel.removeTask(task.id) },
                    onPlay = {
                        if (task.outputPath.isNotBlank()) onNavigateToPlayer(task.outputPath)
                    }
                )
            }
        }
    }
}

@Composable
private fun DownloadHeader(
    activeCount: Int,
    completedCount: Int,
    failedCount: Int,
    onBack: (() -> Unit)?,
    onScan: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                    shape = CircleShape,
                    shadowElevation = 0.dp,
                    modifier = Modifier.size(44.dp)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = "下载中心",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                shape = CircleShape,
                shadowElevation = 0.dp
            ) {
                TextButton(onClick = onScan, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Outlined.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
                }
            }
        }

        // L1 玻璃统计卡
        GlassSurface(
            tier = GlassTier.L1,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatBlock(activeCount.toString(), "进行中")
                StatBlock(completedCount.toString(), "已完成")
                StatBlock(failedCount.toString(), "异常")
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DownloadActions(onScan: () -> Unit, onClearCompleted: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = Blue,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            TextButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("扫描本地", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            TextButton(onClick = onClearCompleted, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("清理完成", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun EmptyDownloadState(onScan: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("暂无下载任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "从收件箱粘贴链接后，解析、下载和失败状态会集中显示在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onScan) {
                Text("扫描", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onPlay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(112.dp)
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    ThumbnailWithFallback(
                        thumbnailUrl = task.thumbnail,
                        videoFilePath = task.outputPath.ifBlank { null },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        preferLocalFrame = task.outputPath.isNotBlank()
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.44f),
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.padding(7.dp).size(18.dp))
                    }
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
Text(
                        task.title.ifBlank { task.url },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    StatusPill(task.status)
                }
                Text(
                    downloadMeta(task),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                    if (task.errorMessage.isNotBlank()) {
                        Text(
                            task.errorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB91C1C),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (task.status == DownloadStatus.DOWNLOADING || task.progress > 0f) {
                LinearProgressIndicator(
                    progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = statusTint(task.status),
                    trackColor = Color(0xFFE2E8F0)
                )
                Text(
                    "${task.progressInt}%${task.etaSeconds.takeIf { it > 0 }?.let { " · 剩余 ${it}s" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (task.isActive) {
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                } else {
                    TextButton(onClick = onRemove) { Text("移除") }
                    if (task.outputPath.isNotBlank()) {
                        Button(onClick = onPlay) { Text("播放") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: DownloadStatus) {
    Surface(
        color = statusTint(status).copy(alpha = 0.10f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(statusIcon(status), null, tint = statusTint(status), modifier = Modifier.size(14.dp))
            Text(statusText(status), color = statusTint(status), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
        }
    }
}

private fun downloadMeta(task: DownloadTask): String =
    listOf(
        task.uploader.ifBlank { "未知作者" },
        task.quality.ifBlank { task.formatId },
        task.ext.uppercase()
    ).joinToString(" · ")

private fun statusText(status: DownloadStatus): String = when (status) {
    DownloadStatus.PENDING -> "等待"
    DownloadStatus.RESOLVING -> "解析"
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.COMPLETED -> "完成"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.CANCELLED -> "已取消"
    DownloadStatus.PAUSED -> "暂停"
}

private fun statusTint(status: DownloadStatus): Color = when (status) {
    DownloadStatus.COMPLETED -> Color(0xFF087251)
    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> Color(0xFFB91C1C)
    DownloadStatus.PAUSED -> Color(0xFFB45309)
    DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING -> Accent
    else -> Blue
}

private fun statusIcon(status: DownloadStatus): ImageVector = when (status) {
    DownloadStatus.COMPLETED -> Icons.Outlined.CheckCircle
    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> Icons.Outlined.ErrorOutline
    DownloadStatus.PAUSED, DownloadStatus.PENDING -> Icons.Outlined.Schedule
    DownloadStatus.RESOLVING -> Icons.Outlined.HourglassTop
    DownloadStatus.DOWNLOADING -> Icons.Outlined.CloudDownload
}
