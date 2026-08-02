package com.ed.twitterdownloader.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ed.twitterdownloader.data.database.DownloadHistoryEntity
import com.ed.twitterdownloader.data.model.MediaType
import com.ed.twitterdownloader.ui.components.ThumbnailWithFallback
import com.ed.twitterdownloader.viewmodel.HistoryViewModel
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LIBRARY_PERIODIC_SCAN_INTERVAL_MS = 45_000L

// 应用启动每日自动扫描：同一天只扫一次，隔天重置（2026-08-02 优化）
private const val KEY_LAST_SCAN_DATE = "media_library_last_scan_date"

private enum class LibraryScanTrigger {
    AutoEnter,
    Periodic,
    Manual
}

/** 媒体库筛选 */
private enum class LibraryFilter(val label: String) {
    ALL("全部"),
    VIDEO("视频"),
    IMAGE("图片")
}

@Composable
fun MediaLibraryScreen(
    historyViewModel: HistoryViewModel,
    onNavigateToPlayer: (String) -> Unit
) {
    val context = LocalContext.current
    val allItems by historyViewModel.historyList.collectAsState()
    val videoCount = allItems.count { it.mediaType == MediaType.VIDEO }
    val imageCount = allItems.count { it.mediaType == MediaType.IMAGE }
    val totalBytes = allItems.sumOf { it.fileSize.coerceAtLeast(0L) }
    val scope = rememberCoroutineScope()
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncToken by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    val scanCoordinator = remember { MediaLibraryAutoScanCoordinator() }
    val startLibraryScan: (LibraryScanTrigger) -> Unit = { trigger ->
        val token = syncToken + 1
        val accepted = scanCoordinator.requestScan { markComplete ->
            syncToken = token
            if (trigger != LibraryScanTrigger.Periodic) {
                syncMessage = if (trigger == LibraryScanTrigger.AutoEnter) "正在自动刷新媒体库..." else "正在同步媒体库..."
            }
            historyViewModel.scanLocalVideos {
                markComplete()
                if (trigger != LibraryScanTrigger.Periodic) {
                    syncMessage = if (trigger == LibraryScanTrigger.AutoEnter) "媒体库已自动刷新" else "同步完成，媒体库已刷新"
                    scope.launch {
                        delay(2200)
                        if (syncToken == token) syncMessage = null
                    }
                }
            }
        }
        if (!accepted && trigger == LibraryScanTrigger.Manual) {
            syncMessage = "媒体库正在刷新中..."
        }
    }
    val latestStartLibraryScan by rememberUpdatedState(startLibraryScan)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanPrefs = remember(context) {
        context.getSharedPreferences("media_library_scan", android.content.Context.MODE_PRIVATE)
    }
    // 自动刷新策略（2026-08-02 优化）：不再每次进入媒体库就刷新；
    // 改为「应用启动时自动扫描一次，同一天多次进入不重复扫描，隔天重置」
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val lastScanDate = scanPrefs.getString(KEY_LAST_SCAN_DATE, null)
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                if (lastScanDate != today) {
                    scanPrefs.edit().putString(KEY_LAST_SCAN_DATE, today).apply()
                    latestStartLibraryScan(LibraryScanTrigger.AutoEnter)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(LIBRARY_PERIODIC_SCAN_INTERVAL_MS)
                latestStartLibraryScan(LibraryScanTrigger.Periodic)
            }
        }
    }
    val syncLibrary = { startLibraryScan(LibraryScanTrigger.Manual) }

    val items = when (filter) {
        LibraryFilter.ALL -> allItems
        LibraryFilter.VIDEO -> allItems.filter { it.mediaType == MediaType.VIDEO }
        LibraryFilter.IMAGE -> allItems.filter { it.mediaType == MediaType.IMAGE }
    }

    // 同作者序号：为每条媒体分配它在作者内的递增序号；作者总数 ≥2 时显示徽章（区分重复视频）
    val authorSeq: Map<String, Int> = remember(items) {
        val counts = mutableMapOf<String, Int>()
        buildMap {
            items.forEach { entity ->
                val author = mediaAuthorOf(entity)
                val next = (counts[author] ?: 0) + 1
                counts[author] = next
                put(entity.id, next)
            }
        }
    }
    val authorTotal: Map<String, Int> = remember(items) {
        items.groupingBy { mediaAuthorOf(it) }.eachCount()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Transparent),  // 透出 GlassBackground
        contentPadding = PaddingValues(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            LibraryHeader(
                filter = filter,
                allCount = allItems.size,
                videoCount = videoCount,
                imageCount = imageCount,
                totalBytes = totalBytes,
                onFilterChange = { filter = it },
                syncMessage = syncMessage,
                onScan = syncLibrary
            )
        }

        if (items.isEmpty()) {
            item { EmptyLibraryState(onScan = syncLibrary) }
        } else {
            items(items, key = { it.id }) { entity ->
                val author = mediaAuthorOf(entity)
                MediaCard(
                    entity = entity,
                    authorIndex = if ((authorTotal[author] ?: 0) >= 2) (authorSeq[entity.id] ?: 1) else null,
                    onPlay = { onNavigateToPlayer(entity.filePath) },
                    onShare = {
                        runCatching {
                            val file = File(entity.filePath)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "*/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "分享媒体"
                                )
                            )
                        }
                    },
                    onDelete = { deleteLocal -> historyViewModel.deleteHistory(entity, deleteLocal) }
                )
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    filter: LibraryFilter,
    allCount: Int,
    videoCount: Int,
    imageCount: Int,
    totalBytes: Long,
    onFilterChange: (LibraryFilter) -> Unit,
    syncMessage: String?,
    onScan: () -> Unit
) {
    // L2 玻璃头部（方案 A 单行头）：E 徽章 + 媒体库 | 占用 · 刷新，下接筛选胶囊
    // 顶部加 statusBarsPadding 让玻璃卡从状态栏底部开始（对齐收件箱）
    GlassSurface(
        tier = GlassTier.L2,
        shape = RoundedCornerShape(30.dp),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        )
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 单行头：E 徽章 + 标题 | 占用 + 刷新
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
                ) {
                    Box(
                        modifier = Modifier.size(22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "E",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "媒体库",
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.02).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatFileSize(totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                LibraryHeaderIconButton(onClick = onScan, contentDescription = "刷新媒体库") {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                }
            }
            // 筛选胶囊（带计数 + 选中态 primaryContainer + 语义点）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val options = listOf(
                    LibraryFilter.ALL to allCount,
                    LibraryFilter.VIDEO to videoCount,
                    LibraryFilter.IMAGE to imageCount
                )
                options.forEach { (value, count) ->
                    val selected = filter == value
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onFilterChange(value) },
                        shape = CircleShape,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = "${value.label} $count",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            // 同步状态
            syncMessage?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeaderIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun EmptyLibraryState(onScan: () -> Unit) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(58.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                }
            }
            Text("还没有可播放媒体", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "下载完成的视频、图片和本地扫描结果会统一放在这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Button(onClick = onScan) {
                Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("扫描下载目录")
            }
        }
    }
}

@Composable
private fun MediaCard(
    entity: DownloadHistoryEntity,
    authorIndex: Int? = null,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: (deleteLocalFile: Boolean) -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val frameOffset = ((entity.mediaIndex ?: 1).coerceAtLeast(1) * 1.7f).coerceAtMost(12f)

    // L1 玻璃媒体卡（列表滚动项关闭 shadow）
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        elevated = false,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                ThumbnailWithFallback(
                    thumbnailUrl = entity.thumbnail,
                    videoFilePath = entity.filePath,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    frameOffsetSeconds = frameOffset,
                    preferLocalFrame = entity.thumbnail.isBlank()
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.08f))
                )

                Surface(
                    color = Color.Black.copy(alpha = 0.48f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (entity.mediaType == MediaType.IMAGE) Icons.Outlined.Image else Icons.Outlined.VideoLibrary,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.size(5.dp))
                        Text(mediaBadge(entity), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black)
                    }
                }

                // 视频时长胶囊（缩略图右下角，视频独有信息）
                if (entity.mediaType == MediaType.VIDEO && entity.duration > 0) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.52f),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    ) {
                        Text(
                            text = formatDuration(entity.duration),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    CircleIconAction(onClick = { showMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("分享") },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            onClick = {
                                showMenu = false
                                onShare()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFE11D48)) },
                            onClick = {
                                showMenu = false
                                showDelete = true
                            }
                        )
                    }
                }

                Surface(color = Color.Black.copy(alpha = 0.42f), shape = CircleShape, modifier = Modifier.align(Alignment.Center)) {
                    Icon(Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.padding(11.dp).size(29.dp))
                }
            }

            Column(
                modifier = Modifier.padding(start = 13.dp, top = 9.dp, end = 13.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = mediaTitle(entity),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (authorIndex != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "#$authorIndex",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                val caption = entity.title.takeIf {
                    it.isNotBlank() && it != File(entity.filePath).nameWithoutExtension
                }
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = mediaMeta(entity),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape = RoundedCornerShape(999.dp)) {
                        Text(
                            "播放",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除媒体") },
            text = { Text("可以只移除媒体库记录，也可以同时删除本地文件。") },
            confirmButton = { TextButton(onClick = { showDelete = false; onDelete(true) }) { Text("删除文件") } },
            dismissButton = { TextButton(onClick = { showDelete = false; onDelete(false) }) { Text("仅移除记录") } }
        )
    }
}

@Composable
private fun CircleIconAction(onClick: () -> Unit, danger: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        color = if (danger) Color(0xFFE11D48).copy(alpha = 0.78f) else Color.Black.copy(alpha = 0.48f),
        shape = CircleShape,
        modifier = Modifier.size(30.dp).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private fun mediaAuthorOf(entity: DownloadHistoryEntity): String =
    entity.uploader.ifBlank {
        entity.title.substringBefore("_").takeIf { it.isNotBlank() && it != entity.title }
            ?: File(entity.filePath).nameWithoutExtension.substringBefore("_").ifBlank { "unknown" }
    }

private fun mediaTitle(entity: DownloadHistoryEntity): String = mediaAuthorOf(entity)

private fun mediaBadge(entity: DownloadHistoryEntity): String {
    val author = entity.uploader.ifBlank { File(entity.filePath).nameWithoutExtension.substringBefore("_").ifBlank { "unknown" } }
    return author
}

private fun mediaMeta(entity: DownloadHistoryEntity): String {
    // 质量 · 大小 · 时间（类型交给缩略图角标，标题只保留作者，消除重复）
    val quality = entity.quality
        .removePrefix("video_")
        .removePrefix("image_")
        .ifBlank { null }
    return listOf(
        quality,
        formatFileSize(entity.fileSize),
        formatTime(entity.completedAt)
    ).filterNotNull().joinToString(" · ")
}

private fun formatDuration(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1024f / 1024f
    return if (mb >= 1024f) String.format(Locale.getDefault(), "%.1f GB", mb / 1024f)
    else String.format(Locale.getDefault(), "%.1f MB", mb)
}
