package com.ed.edqiu.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.ed.edqiu.backup.BackupScope
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.InlineFeedbackBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频 / 图片备份显示通道。
 *
 * - 顶部：返回 + 「视频图片备份」标题 + 当前网盘状态卡；
 * - 统计卡：已上传 / 未上传 / 上传中 / 失败 四维计数；
 * - 分段筛选：全部 / 视频 / 图片；「全部上传」批量补传；
 * - 文件列表：缩略图 + 文件名 + 大小 + 状态徽标；未上传条目最右侧为「上传到云盘」按钮，
 *   失败条目为「重试」按钮，上传中显示进度条。
 *
 * 数据流：MediaBackupViewModel 扫描监控目录 + 任务/账本比对，任务流变化实时重建。
 */
@Composable
fun MediaBackupScreen(
    vm: MediaBackupViewModel,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 页面级复用同一视频帧加载器（避免每个列表项各建一个 ImageLoader）
    val videoLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderRow(onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ProviderCard(state) }
            item { StatsRow(state) }
            item {
                // 视频 / 图片 两个 Tab：均分剩余宽度铺满整行（不再左侧小块留白），右侧「同步」按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.filter == BackupScope.VIDEO,
                            onClick = { vm.setFilter(BackupScope.VIDEO) },
                            label = { Text("视频") },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = state.filter == BackupScope.IMAGE,
                            onClick = { vm.setFilter(BackupScope.IMAGE) },
                            label = { Text("图片") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = vm::uploadAll,
                        enabled = state.providerConfigured && !state.running && state.canUploadAll,
                    ) {
                        Icon(Icons.Outlined.CloudUpload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("同步")
                    }
                }
            }
            item {
                InlineFeedbackBar(
                    message = state.message,
                    onDismiss = vm::consumeMessage,
                )
            }

            if (state.filteredItems.isEmpty()) {
                item { EmptyHint() }
            } else {
                items(state.filteredItems, key = { it.remotePath }) { item ->
                    MediaBackupItemRow(
                        item = item,
                        enabled = state.providerConfigured && !state.running,
                        onUpload = { vm.uploadItem(item) },
                        videoLoader = videoLoader,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 10.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
            shape = CircleShape,
            shadowElevation = 0.dp,
            modifier = Modifier.size(44.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "同步情况",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "视频/图片上传状态一览",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderCard(state: MediaBackupUiState) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "备份目标网盘",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                !state.providerConfigured -> Text(
                    "「${state.providerName ?: "WebDAV"}」尚未配置，请先到「下载器设置 → WebDAV 同步」填写并测试连接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    "「${state.providerName ?: "WebDAV"}」· 已就绪",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StatsRow(state: MediaBackupUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCell("已上传", state.uploadedCount, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        StatCell("未上传", state.notUploadedCount, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
        StatCell("上传中", state.uploadingCount, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
        StatCell("失败", state.failedCount, MaterialTheme.colorScheme.error, Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, count: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(16.dp),
        tint = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaBackupItemRow(
    item: MediaBackupItem,
    enabled: Boolean,
    onUpload: () -> Unit,
    videoLoader: ImageLoader,
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(item = item, videoLoader = videoLoader)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.remotePath,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatSize(item.size)} · ${formatTime(item.modifiedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (item.state) {
                    MediaUploadState.UPLOADING -> {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { item.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "上传中 ${(item.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    MediaUploadState.FAILED -> Text(
                        item.errorMessage ?: "上传失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Unit
                }
            }
            Spacer(Modifier.width(8.dp))
            when (item.state) {
                MediaUploadState.UPLOADED -> Row {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "已上传",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                MediaUploadState.NOT_UPLOADED -> Button(
                    onClick = onUpload,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Outlined.CloudUpload, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("上传到云盘", style = MaterialTheme.typography.labelMedium)
                }
                MediaUploadState.UPLOADING -> Unit
                MediaUploadState.FAILED -> OutlinedButton(
                    onClick = onUpload,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重试", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(item: MediaBackupItem, videoLoader: ImageLoader) {
    val shape = RoundedCornerShape(12.dp)
    if (item.isVideo) {
        AsyncImage(
            model = File(item.file.absolutePath),
            contentDescription = item.remotePath,
            contentScale = ContentScale.Crop,
            imageLoader = videoLoader,
            // 解码失败（无帧/损坏）时回退到电影图标
            error = rememberVectorPainter(Icons.Outlined.Movie),
            modifier = Modifier
                .size(56.dp)
                .clip(shape),
        )
    } else {
        AsyncImage(
            model = File(item.file.absolutePath),
            contentDescription = item.remotePath,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(shape),
            placeholder = null,
            fallback = null,
        )
    }
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Image,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "下载目录暂无媒体文件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[idx])
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
