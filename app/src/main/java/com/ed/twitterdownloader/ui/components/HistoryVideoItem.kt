package com.ed.twitterdownloader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ed.twitterdownloader.data.database.DownloadHistoryEntity
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryVideoItem(
    entity: DownloadHistoryEntity,
    onShare: () -> Unit,
    onDelete: (deleteLocalFile: Boolean) -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDelete by remember { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onPlay)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entity.title.ifBlank { entity.filePath.substringAfterLast('/') }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${entity.uploader.ifBlank { "unknown" }} · ${entity.quality.ifBlank { entity.mediaType.name }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entity.completedAt)), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPlay) { Text("播放") }
                OutlinedButton(onClick = onShare) { Text("分享") }
                TextButton(onClick = { showDelete = true }) { Text("删除") }
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除媒体") },
            text = { Text("仅从列表移除，还是同时删除本地文件？") },
            confirmButton = { TextButton(onClick = { showDelete = false; onDelete(true) }) { Text("删除文件") } },
            dismissButton = { TextButton(onClick = { showDelete = false; onDelete(false) }) { Text("仅移除记录") } }
        )
    }
}
