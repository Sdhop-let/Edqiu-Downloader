package com.ed.edqiu.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.ui.components.StatusBadge
import com.ed.edqiu.ui.navigation.LocalSnackbarController
import com.ed.edqiu.ui.util.copyToClipboard
import com.ed.edqiu.ui.util.formatRelativeTime
import com.ed.edqiu.ui.util.openDownloadedFile
import com.ed.edqiu.ui.util.openTwitterStatus

@Composable
fun DetailScreen(
    vm: DetailViewModel,
    tweetId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    val link by vm.link.collectAsStateWithLifecycle()
    val monitorUri by vm.monitorUri.collectAsStateWithLifecycle()
    val actionFeedback by vm.actionFeedback.collectAsStateWithLifecycle()

    LaunchedEffect(tweetId) { vm.load(tweetId) }
    LaunchedEffect(actionFeedback) {
        actionFeedback?.let {
            snackbar.show(it)
            vm.clearActionFeedback()
        }
    }

    val data = link
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (data != null) {
                DetailActionBar(
                    data = data,
                    onDownload = vm::requestDownload,
                    onCopy = {
                        copyToClipboard(context, "Edqiu 链接", data.rawUrl)
                        snackbar.show("已复制链接")
                    },
                    onOpenTwitter = {
                        val success = openTwitterStatus(context, data.tweetId)
                        if (!success) snackbar.show("无法打开 X/Twitter")
                    },
                    onOpenFile = {
                        val success = openDownloadedFile(context, monitorUri, data.filePath)
                        if (!success) snackbar.show("无法打开文件")
                    }
                )
            }
        }
    ) { padding ->
        if (data == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DetailHero(data = data, onBack = onBack, onRefresh = vm::refresh)
            SummaryCard(data = data)

            if (!data.caption.isNullOrBlank()) {
                InfoCard(title = "视频文案") {
                    SelectionContainer {
                        Text(data.caption, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            InfoCard(title = "记录") {
                DetailRow("捕获时间", formatRelativeTime(data.savedAt))
                data.downloadedAt?.let { DetailRow("下载时间", formatRelativeTime(it)) }
                data.lastAttemptAt?.let { DetailRow("最近尝试", formatRelativeTime(it)) }
                if (data.attemptCount > 0) DetailRow("尝试次数", data.attemptCount.toString())
                data.nextRetryAt?.let { DetailRow("自动重试", formatRelativeTime(it)) }
                if (!data.lastError.isNullOrBlank()) DetailRow("失败原因", data.lastError)
            }

            InfoCard(title = "原始链接") {
                SelectionContainer {
                    Text(
                        text = data.rawUrl,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2563EB),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun DetailHero(data: SavedLink, onBack: () -> Unit, onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF101417), Color(0xFF2563EB), Color(0xFF14B8A6))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!data.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = data.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(48.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            FloatingIconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 3.dp
        ) {
            StatusBadge(status = data.status, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun FloatingIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            contentColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.size(38.dp),
        content = content
    )
}

@Composable
private fun SummaryCard(data: SavedLink) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Avatar(data = data)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        data.authorName ?: data.authorId ?: "未知作者",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        data.authorId ?: "x.com/status/${data.tweetId.takeLast(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = "x.com/status/${data.tweetId.takeLast(8)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2563EB),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Avatar(data: SavedLink) {
    if (!data.avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = data.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFF38BDF8))
                .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("X", fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF087251),
                fontWeight = FontWeight.ExtraBold
            )
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.62f)
        )
    }
}

@Composable
private fun DetailActionBar(
    data: SavedLink,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onOpenTwitter: () -> Unit,
    onOpenFile: () -> Unit
) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onDownload,
                enabled = data.status != LinkStatus.DOWNLOADED,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (data.status == LinkStatus.FAILED) " 重新下载" else " 打开下载器")
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制链接")
            }
            IconButton(onClick = onOpenTwitter) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "打开 X/Twitter")
            }
            IconButton(
                onClick = onOpenFile,
                enabled = data.status == LinkStatus.DOWNLOADED
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = "打开文件")
            }
        }
    }
}
