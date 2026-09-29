package com.ed.edqiu.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ed.edqiu.data.model.VideoInfo

@Composable
fun VideoInfoCard(
    videoInfo: VideoInfo,
    modifier: Modifier = Modifier,
    onUrlClick: (() -> Unit)? = null
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(videoInfo.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("@${videoInfo.uploader} · ${videoInfo.mediaItemLabel} · ${videoInfo.formats.size} 个可下载项", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("时长 ${videoInfo.durationFormatted}", style = MaterialTheme.typography.bodySmall)
            if (onUrlClick != null) Text(videoInfo.url, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onUrlClick() })
        }
    }
}
