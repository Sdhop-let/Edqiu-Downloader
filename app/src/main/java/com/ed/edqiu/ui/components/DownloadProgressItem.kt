package com.ed.edqiu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask

@Composable
fun DownloadProgressItem(
    task: DownloadTask,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 预览图：解析完成后 yt-dlp 会带回缩略图地址
                TaskThumbnail(task)
                Column(Modifier.weight(1f)) {
                    Text(task.title.ifBlank { task.url }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${task.uploader.ifBlank { "unknown" }} · ${task.quality.ifBlank { task.formatId }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(statusTextValue(task), color = statusColor(task.status), style = MaterialTheme.typography.labelMedium)
            }
            if (task.status == DownloadStatus.DOWNLOADING || task.progress > 0f) {
                LinearProgressIndicator(progress = { (task.progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${task.progressInt}%${task.etaSeconds.takeIf { it > 0 }?.let { " · 剩余 ${it}s" } ?: ""}", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.isActive) OutlinedButton(onClick = { onCancel(task.id) }) { Text("取消") }
                if (!task.isActive) TextButton(onClick = { onRemove(task.id) }) { Text("移除") }
            }
        }
    }
}

/** 任务预览图：有缩略图 URL 时加载，否则显示占位块。 */
@Composable
private fun TaskThumbnail(task: DownloadTask) {
    val shape = RoundedCornerShape(10.dp)
    if (task.thumbnail.isNotBlank()) {
        AsyncImage(
            model = task.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 92.dp, height = 60.dp)
                .clip(shape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = 92.dp, height = 60.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

private fun statusTextValue(task: DownloadTask): String = when (task.status) {
    DownloadStatus.PENDING -> "等待中"
    DownloadStatus.RESOLVING -> "解析中"
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.COMPLETED -> "已完成"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.CANCELLED -> "已取消"
    DownloadStatus.PAUSED -> "已暂停"
}

private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.COMPLETED -> Color(0xFF0F8A5F)
    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> Color(0xFFC2410C)
    else -> Color(0xFF2563EB)
}
