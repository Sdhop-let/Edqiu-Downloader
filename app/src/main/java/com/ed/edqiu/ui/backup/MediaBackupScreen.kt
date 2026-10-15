package com.ed.edqiu.ui.backup

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.ed.edqiu.service.WebDavManualSync
import com.ed.edqiu.ui.components.FeedbackDialog
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.PillChip
import com.ed.edqiu.ui.components.rememberTitleCollapseProgress
import com.ed.edqiu.ui.components.titleCollapseAlpha
import com.ed.edqiu.ui.components.titleCollapseSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步情况详情（2026-10 重设计）。
 *
 * - 顶部：返回 + 大标题折叠 + 网盘状态卡；
 * - **双分页**：「同步」= 未同步队列（待上传 + 冲突 + 正在同步），「已同步」= 两端一致账面；
 * - **正在同步**：与进度流匹配的条目高亮置顶，显示单项进度条（文件名 + 百分比）；
 * - **逐文件控制**：待上传条目 ⏸ 暂停 / ▶ 恢复 / ⬆ 优先同步（插队 + 置顶），
 *   暂停对传输中的文件立即生效（.part 保留，恢复后断点续传）；
 * - 同步 Tab 内聚合「立即同步」按钮；整体风格与全页 GlassSurface 体系统一。
 */
@Composable
fun MediaBackupScreen(
    vm: MediaBackupViewModel,
    onBack: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val videoLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }

    FeedbackDialog(
        message = state.message,
        onDismiss = vm::consumeMessage
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 2026-10：不透明全屏页——玻璃浮层会透出底层主界面与悬浮 Tab 栏（用户反馈
            // 「底部不要显示其他界面」），本页改为实底背景，彻底遮住下层内容
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val listState = rememberLazyListState()
        val titleCollapse by rememberTitleCollapseProgress(listState)

        HeaderRow(onBack = onBack, collapse = titleCollapse)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 底部不再为悬浮 Tab 预留 96dp（本页无 Tab），仅留导航栏安全区 + 呼吸空间
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ProviderCard(state) }

            if (state.error != null) {
                item { ErrorCard(state, vm::refresh) }
            } else {
                // 分页 Tab（iOS 分段控制语义）：同步 | 已同步 + 刷新
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillChip(
                            label = "同步 ${state.pendingCount + state.conflictCount}",
                            selected = state.tab == SyncDetailTab.SYNCING,
                            onClick = { vm.setTab(SyncDetailTab.SYNCING) },
                        )
                        PillChip(
                            label = "已同步 ${state.syncedCount}",
                            selected = state.tab == SyncDetailTab.SYNCED,
                            onClick = { vm.setTab(SyncDetailTab.SYNCED) },
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = vm::refresh, enabled = !state.loading && !state.syncRunning) {
                            if (state.loading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = "重新对账",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // 同步 Tab 聚合：立即同步按钮 + 运行进度
                if (state.tab == SyncDetailTab.SYNCING) {
                    item {
                        SyncActionCard(
                            state = state,
                            onSyncNow = vm::syncNow,
                            onToggleSmallFirst = { vm.setSmallFirst(!state.smallFirst) },
                        )
                    }
                }

                // 正在同步的条目高亮置顶（单项进度条）
                val syncingItem = state.syncingItem
                if (state.syncRunning && syncingItem != null) {
                    item { SyncingHighlightCard(state, syncingItem, videoLoader) }
                }

                val items = state.tabItems.filter { it.remotePath != syncingItem?.remotePath }
                if (items.isEmpty() && !state.loading) {
                    item { EmptyCard(state) }
                }

                items(items, key = { it.remotePath }) { item ->
                    if (state.tab == SyncDetailTab.SYNCED) {
                        DetailItemRow(item, videoLoader)
                    } else {
                        SyncingQueueRow(
                            item = item,
                            paused = item.remotePath in state.pausedSet,
                            prioritized = item.remotePath in state.prioritizedSet,
                            onPause = { vm.pauseItem(item) },
                            onResume = { vm.resumeItem(item) },
                            onPrioritize = { vm.prioritizeItem(item) },
                            videoLoader = videoLoader,
                        )
                    }
                }
            }
        }
    }
}

// ================= 卡片 =================

@Composable
private fun ErrorCard(state: SyncDetailUiState, onRetry: () -> Unit) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    state.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = onRetry,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("重试对账") }
        }
    }
}

@Composable
private fun EmptyCard(state: SyncDetailUiState) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                state.items.isEmpty() -> "下载目录暂无媒体文件"
                state.tab == SyncDetailTab.SYNCING -> "没有待同步的文件，全部已同步 ✓"
                else -> "还没有已同步的文件，点「立即同步」开始"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun SyncActionCard(
    state: SyncDetailUiState,
    onSyncNow: () -> Unit,
    onToggleSmallFirst: () -> Unit,
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onSyncNow,
                enabled = !state.syncRunning && state.verified && !state.loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (state.syncRunning) "同步进行中…" else "立即同步（双向）")
            }
            val progress = state.syncProgress
            if (state.syncRunning && progress != null && progress.totalFiles > 0) {
                val overall = ((progress.currentIndex - 1).coerceAtLeast(0) + progress.fileProgress) /
                    progress.totalFiles
                LinearProgressIndicator(
                    progress = { overall.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                )
                val phaseLabel = if (progress.phase == com.ed.edqiu.service.WebDavSyncPhase.DOWNLOAD) "正在下载" else "正在上传"
                Text(
                    "$phaseLabel ${progress.currentIndex}/${progress.totalFiles} · " +
                        "${progress.currentFileName} · ${(progress.fileProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.pausedSet.isNotEmpty()) {
                    Text(
                        "已暂停 ${state.pausedSet.size} 个文件（恢复后从断点续传）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (!state.syncRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "待上传条目可逐个 ⏸ 暂停或 ⬆ 置顶优先；暂停不丢进度。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    PillChip(
                        label = if (state.smallFirst) "小文件优先" else "时间优先",
                        selected = state.smallFirst,
                        onClick = onToggleSmallFirst,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncingHighlightCard(
    state: SyncDetailUiState,
    item: SyncDetailItem,
    videoLoader: ImageLoader,
) {
    val progress = state.syncProgress
    val fileProgress = progress?.fileProgress ?: 0f
    val phaseLabel = if (progress?.phase == com.ed.edqiu.service.WebDavSyncPhase.DOWNLOAD) "正在下载" else "正在上传"
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = item.file,
                contentDescription = null,
                imageLoader = videoLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.remotePath,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { fileProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                )
                Text(
                    "$phaseLabel · ${(fileProgress * 100).toInt()}% · ${formatSize(item.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ================= 列表行 =================

@Composable
private fun SyncingQueueRow(
    item: SyncDetailItem,
    paused: Boolean,
    prioritized: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPrioritize: () -> Unit,
    videoLoader: ImageLoader,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (prioritized) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
        },
        border = if (prioritized) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = item.file,
                contentDescription = null,
                imageLoader = videoLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.remotePath,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${formatSize(item.size)} · ${formatTime(item.modifiedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (prioritized) {
                        Text(
                            "已置顶优先",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (item.state == SyncItemState.CONFLICT) {
                ConflictBadge()
            }
            IconButton(onClick = if (paused) onResume else onPause) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (paused) "恢复同步" else "暂停同步",
                    tint = if (paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (!paused && !prioritized) {
                IconButton(onClick = onPrioritize) {
                    Icon(
                        Icons.Outlined.VerticalAlignTop,
                        contentDescription = "优先同步",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailItemRow(item: SyncDetailItem, videoLoader: ImageLoader) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = item.file,
                contentDescription = null,
                imageLoader = videoLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.remotePath,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatSize(item.size)} · ${formatTime(item.modifiedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1F8A3D).copy(alpha = 0.12f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF1F8A3D),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        "已同步",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F8A3D),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictBadge() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
    ) {
        Text(
            "冲突",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

// ================= 头部与小工具 =================

@Composable
private fun HeaderRow(onBack: () -> Unit, collapse: Float) {
    Row(
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column {
            Text(
                text = "同步情况详情",
                fontSize = titleCollapseSize(collapse, 24.sp, 18.sp),
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "本地 × 云端逐文件对账",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = titleCollapseAlpha(collapse) }
            )
        }
    }
}

@Composable
private fun ProviderCard(state: SyncDetailUiState) {
    GlassSurface(
        tier = GlassTier.L2,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.providerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        state.serverUrl.ifBlank { "未配置服务器地址" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (state.verified) {
                        Color(0xFF1F8A3D).copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    },
                ) {
                    Text(
                        if (state.verified) "已验证" else "未验证",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (state.verified) Color(0xFF1F8A3D) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            if (state.lastRefreshAt > 0) {
                Text(
                    "对账时间：${formatTime(state.lastRefreshAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
