package com.ed.edqiu.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SyncAlt
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.ed.edqiu.service.WebDavManualSync
import com.ed.edqiu.service.WebDavSyncPhase
import com.ed.edqiu.ui.components.FeedbackDialog
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.PillChip
import com.ed.edqiu.ui.components.rememberTitleCollapseProgress
import com.ed.edqiu.ui.components.titleCollapseAlpha
import com.ed.edqiu.ui.components.titleCollapseSize
import com.ed.edqiu.ui.util.pressableNoRipple
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 备份中心（2026-10 按设计规范 edqiu-screens-tools.html §03 重设计）。
 *
 * 版式（截图复刻）：
 * - L2 全宽标题栏：30dp 返回圆钮 + 「备份中心」16px/850；
 * - L1 连接卡：绿色渐变图标 + 「WebDAV · 网盘名」+ 等宽 URL + 已连接徽标；
 * - L1 同步进度卡：同步范围 + 「同步中 62%」徽标 + 进度条 + 等宽页脚
 *   （已上传 X｜剩余 N 个文件 · 中断自动续跑）；
 * - L1 最近备份卡：三段行（时间 / 类型·大小·结果）；
 * - L1 同步明细入口行：待同步/冲突计数，进入逐文件对账明细（原「同步情况详情」）；
 * - L3 全宽「立即同步」胶囊。
 *
 * 数据全部来自真实链路：[MediaBackupViewModel] 的 PROPFIND 对账 +
 * [WebDavManualSync] 应用级运行器 + CloudSyncPreferences 同步历史。
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

    // 明细内页返回：浮层自身 BackHandler 注册早于本页（LIFO 后注册者优先），
    // 明细打开时返回先收明细，再按返回才关浮层
    var showDetail by remember { mutableStateOf(false) }
    BackHandler(enabled = showDetail) {
        showDetail = false
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
            // 2026-10-21 液态玻璃：去掉实底，透出 GlassBackground 色场
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showDetail) {
            SyncDetailView(
                state = state,
                vm = vm,
                videoLoader = videoLoader,
                onBack = { showDetail = false },
            )
        } else {
            BackupCenterView(
                state = state,
                vm = vm,
                onBack = onBack,
                onOpenDetail = { showDetail = true },
            )
        }
    }
}

// ================= 备份中心（主页） =================

@Composable
private fun BackupCenterView(
    state: SyncDetailUiState,
    vm: MediaBackupViewModel,
    onBack: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部不再为悬浮 Tab 预留 96dp（本页无 Tab），仅留导航栏安全区 + 呼吸空间
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackupHeader(onBack = onBack) }
        item { ConnectionCard(state) }

        if (state.error != null) {
            item { ErrorCard(state, vm::refresh) }
        } else {
            item { SyncScopeCard(state) }
            item { BackupHistoryCard(state) }
            item { SyncDetailEntryCard(state, onClick = onOpenDetail) }
        }
        item { SyncNowCapsule(state = state, onSyncNow = vm::syncNow) }
    }
}

/** L2 全宽标题栏：30dp 返回圆钮 + 「备份中心」16px/850（设计规范 §03）。 */
@Composable
private fun BackupHeader(onBack: () -> Unit) {
    GlassSurface(
        tier = GlassTier.L2,
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .pressableNoRipple { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = "备份中心",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** L1 连接卡：渐变图标 + 网盘名 + 等宽 URL + 连接徽标。 */
@Composable
private fun ConnectionCard(state: SyncDetailUiState) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF34C759), Color(0xFF0A2540))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Inventory2,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WebDAV · ${state.providerName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.serverUrl.ifBlank { "未配置服务器地址" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ConnectionBadge(verified = state.verified)
        }
    }
}

/** 连接徽标：已连接（绿）/ 未验证（红），语义色 12% 透明底。 */
@Composable
private fun ConnectionBadge(verified: Boolean) {
    val color = if (verified) SyncGreen else MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = if (verified) "已连接" else "未验证",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** L1 同步进度卡：范围 + 运行徽标 + 进度条 + 等宽页脚（已上传｜剩余 · 续跑说明）。 */
@Composable
private fun SyncScopeCard(state: SyncDetailUiState) {
    val runProgress = state.runProgress
    val idleRatio = if (state.items.isEmpty()) 0f
    else state.syncedCount.toFloat() / state.items.size
    val barProgress = runProgress ?: idleRatio
    val badgeText: String
    val badgeColor: Color
    when {
        state.syncRunning -> {
            badgeText = "同步中 ${((runProgress ?: 0f) * 100).toInt()}%"
            badgeColor = MaterialTheme.colorScheme.primary
        }
        state.remainingFiles > 0 -> {
            badgeText = "待同步 ${state.remainingFiles} 个"
            badgeColor = SyncOrange
        }
        else -> {
            badgeText = "已同步"
            badgeColor = SyncGreen
        }
    }

    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "同步范围",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "媒体库视频 + 收件箱链接 · 不含缓存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = badgeColor.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { barProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "已上传 ${formatSize(state.uploadedBytes)}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "剩余 ${state.remainingFiles} 个文件 · 中断自动续跑",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** L1 最近备份卡：三段行（时间 / 类型 · 大小 · 结果），新→旧。 */
@Composable
private fun BackupHistoryCard(state: SyncDetailUiState) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "最近备份",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (state.history.isEmpty()) {
                Text(
                    text = "暂无同步记录，点下方「立即同步」开始第一次备份",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                state.history.take(3).forEachIndexed { index, record ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatHistoryTime(record.timestamp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${historyModeLabel(record.mode)} · ${formatSize(record.bytes)} · ${historyStatusLabel(record.status)}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                            color = historyStatusColor(record.status),
                        )
                    }
                }
            }
        }
    }
}

/** L1 同步明细入口行：待同步/冲突实时计数 → 逐文件对账明细（原同步情况详情）。 */
@Composable
private fun SyncDetailEntryCard(state: SyncDetailUiState, onClick: () -> Unit) {
    val subtitle = when {
        state.remainingFiles > 0 && state.conflictCount > 0 ->
            "${state.pendingCount} 个待上传 · ${state.conflictCount} 个冲突"
        state.remainingFiles > 0 -> "${state.remainingFiles} 个待同步"
        else -> "全部已同步 ✓"
    }
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressableNoRipple { onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(SyncBlue.copy(alpha = 0.12f))
                    .border(1.dp, SyncBlue.copy(alpha = 0.35f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.SyncAlt,
                    contentDescription = null,
                    tint = SyncBlue,
                    modifier = Modifier.size(15.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "同步明细",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** L3 全宽「立即同步」胶囊（设计规范 §03 主操作）。 */
@Composable
private fun SyncNowCapsule(state: SyncDetailUiState, onSyncNow: () -> Unit) {
    GlassSurface(
        tier = GlassTier.L3,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pressableNoRipple { onSyncNow() },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (state.syncRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = if (state.syncRunning) "同步进行中…" else "立即同步",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ================= 同步明细（内页：逐文件对账） =================

@Composable
private fun SyncDetailView(
    state: SyncDetailUiState,
    vm: MediaBackupViewModel,
    videoLoader: ImageLoader,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val titleCollapse by rememberTitleCollapseProgress(listState)

    HeaderRow(onBack = onBack, collapse = titleCollapse)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                val phaseLabel = if (progress.phase == WebDavSyncPhase.DOWNLOAD) "正在下载" else "正在上传"
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
    val phaseLabel = if (progress?.phase == WebDavSyncPhase.DOWNLOAD) "正在下载" else "正在上传"
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
    GlassSurface(
        tier = if (prioritized) GlassTier.L2 else GlassTier.L1,
        shape = RoundedCornerShape(16.dp),
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
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(16.dp),
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
                color = SyncGreen.copy(alpha = 0.12f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = SyncGreen,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        "已同步",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SyncGreen,
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
        GlassSurface(
            tier = GlassTier.L3,
            shape = CircleShape,
            modifier = Modifier.size(44.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pressableNoRipple { onBack() },
                contentAlignment = Alignment.Center
            ) {
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

// ================= 语义色与格式化 =================

/** 语义色（浅色主题口径，与设计规范一致）：绿=成功 / 橙=待同步 / 蓝=进行中。 */
private val SyncGreen = Color(0xFF1F8A3D)
private val SyncOrange = Color(0xFFC93400)
private val SyncBlue = Color(0xFF007AFF)

private fun historyModeLabel(mode: String): String = when (mode) {
    "full" -> "全量"
    else -> "增量"
}

private fun historyStatusLabel(status: String): String = when (status) {
    "success" -> "成功"
    "failed" -> "失败"
    "frozen_healed" -> "冻结自愈恢复"
    else -> status
}

private fun historyStatusColor(status: String): Color = when (status) {
    "success" -> SyncGreen
    "failed" -> Color(0xFFD70015)
    "frozen_healed" -> SyncOrange
    else -> Color(0xFF8E8E93)
}

/** 历史时间：今天 HH:mm / 昨天 HH:mm / M 月 d 日。 */
private fun formatHistoryTime(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    return when {
        isSameDay(now, then) -> "今天 $hm"
        isYesterday(now, then) -> "昨天 $hm"
        else -> SimpleDateFormat("M 月 d 日", Locale.getDefault()).format(Date(millis))
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun isYesterday(now: Calendar, then: Calendar): Boolean {
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return isSameDay(yesterday, then)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
