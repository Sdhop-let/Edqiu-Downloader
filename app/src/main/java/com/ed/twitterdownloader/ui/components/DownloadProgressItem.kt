package com.ed.twitterdownloader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ed.twitterdownloader.data.model.DownloadStatus
import com.ed.twitterdownloader.data.model.DownloadTask

@Composable
fun DownloadProgressItem(
    task: DownloadTask,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    DownloadStatus.COMPLETED -> androidx.compose.ui.graphics.Color(0xFF0F8A5F)
    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> androidx.compose.ui.graphics.Color(0xFFC2410C)
    else -> androidx.compose.ui.graphics.Color(0xFF2563EB)
}
