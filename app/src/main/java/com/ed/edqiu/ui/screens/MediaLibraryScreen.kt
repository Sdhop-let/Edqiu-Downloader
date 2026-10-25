package com.ed.edqiu.ui.screens

import com.ed.edqiu.ui.util.pressableNoRipple
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.ed.edqiu.ui.components.SmoothAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.model.MediaType
import com.ed.edqiu.ui.components.ThumbnailWithFallback
import com.ed.edqiu.viewmodel.HistoryViewModel
import com.ed.edqiu.ui.components.EdqiuTextField
import com.ed.edqiu.ui.components.EdgeFadeEdge
import com.ed.edqiu.ui.components.EdgeFadeScrim
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.StickySectionHeader
import com.ed.edqiu.ui.components.rememberTitleCollapseProgress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/**
 * 媒体库排序模式（2026-10-24 排序重构）：
 * - DOWNLOAD（默认）：按下载完成时间倒序 + 下载日分组（今天/昨天/日期）——
 *   用户心智=下载管理器「刚下完的在最上」；旧发布时间排序会让今天下载的老作品沉底。
 * - PUBLISH：按发布时间倒序 + 作者·发布日分组（旧默认，保留为可切换项）。
 */
enum class LibrarySortMode { DOWNLOAD, PUBLISH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLibraryScreen(
    historyViewModel: HistoryViewModel,
    // 2026-09-30 共享封面过渡：coverBounds = 点击卡片的封面区域（窗口坐标），
    // 播放器封面据此从卡片位置飞入全屏；null（不可用时）播放器降级为滑入
    onNavigateToPlayer: (filePath: String, coverBounds: Rect?) -> Unit,
    // 2026-09-30 v1.6.8 播放返回定位：非空时把列表滚动到该视频的卡片，
    // 滚动落定后通过 onLocateBounds 上报卡片封面矩形，供退场封面精准落点
    locateFilePath: String? = null,
    onLocateBounds: (Rect) -> Unit = {},
    // 容器变形退场（2026-09-30 第二版）：播放层从全屏缩小到该卡片封面的过程中，
    // 目标卡片的信息区（作者/文案/下载信息）先隐藏；infoRevealed 置真后淡入
    infoHiddenFor: String? = null,
    infoRevealed: Boolean = false
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
    var grouped by remember { mutableStateOf(false) }
    // 2026-10-24 排序重构：默认按下载时间（刚下完的在最上）；选择持久化
    val sortPrefs = remember { context.getSharedPreferences("media_library_ui", android.content.Context.MODE_PRIVATE) }
    var sortMode by remember {
        mutableStateOf(if (sortPrefs.getString("sort_mode", null) == "PUBLISH") LibrarySortMode.PUBLISH else LibrarySortMode.DOWNLOAD)
    }
    val onSortToggle = {
        sortMode = if (sortMode == LibrarySortMode.DOWNLOAD) LibrarySortMode.PUBLISH else LibrarySortMode.DOWNLOAD
        sortPrefs.edit().putString("sort_mode", sortMode.name).apply()
    }
    // 2026-09-15 v2 批次5（P1-4 文本语义搜索）：文案/作者/文件名模糊搜索
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // 2026-09-15 v2 批次3（P1-4① pHash 查重）
    var showDuplicates by remember { mutableStateOf(false) }
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
                // 发布时间补拉（内部 10 分钟节流）：旧记录联网补齐排序键
                historyViewModel.backfillPublishedTimes()
                // 画质升级检测（内部档位节流；慢网自动暂停）：换更高码率/分辨率版本
                historyViewModel.upgradeMediaQuality()
                // pHash 补算（内部 10 分钟节流，纯 CPU）：重复媒体检测底料（2026-09-15 批次3）
                historyViewModel.backfillPhashes()
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

    // 发布时间补拉 / 画质升级实时状态（2026-09-15）：升级 pill 实时显示
    // 「补齐 done/total · 网络档位」/「升级 done/total · 网络档位」，完成消息进消息条
    val backfillState by com.ed.edqiu.service.PublishedAtBackfiller.uiState.collectAsState()
    val upgradeState by com.ed.edqiu.service.MediaQualityUpgrader.uiState.collectAsState()
    val upgradingNow = backfillState?.running == true || upgradeState?.running == true
    val upgradePillText = when {
        backfillState?.running == true ->
            "补齐 ${backfillState!!.done}/${backfillState!!.total} · ${backfillState!!.tier.label}"
        upgradeState?.running == true ->
            "升级 ${upgradeState!!.done}/${upgradeState!!.total} · ${upgradeState!!.tier.label}"
        else -> "升级"
    }
    androidx.compose.runtime.LaunchedEffect(backfillState?.at) {
        val state = backfillState
        if (state != null && !state.running && state.message != null) {
            syncMessage = state.message
            delay(4000)
            if (syncMessage == state.message) syncMessage = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(upgradeState?.at) {
        val state = upgradeState
        if (state != null && !state.running && state.message != null) {
            syncMessage = state.message
            delay(4000)
            if (syncMessage == state.message) syncMessage = null
        }
    }

    val filteredByType = when (filter) {
        LibraryFilter.ALL -> allItems
        LibraryFilter.VIDEO -> allItems.filter { it.mediaType == MediaType.VIDEO }
        LibraryFilter.IMAGE -> allItems.filter { it.mediaType == MediaType.IMAGE }
    }
    val items = if (searchQuery.isBlank()) filteredByType else {
        val q = searchQuery.trim()
        filteredByType.filter { entity ->
            entity.title.contains(q, ignoreCase = true) ||
                entity.uploader.contains(q, ignoreCase = true) ||
                entity.filePath.substringAfterLast('/').contains(q, ignoreCase = true)
        }
    }

    // P1-4① 重复检测（2026-09-15 批次3）：pHash 汉明距离 ≤4 聚组
    // 2026-10 整改：O(n²) 计算移出主线程（remember 直算，几百条起掉帧），异步产出+自动重算
    val duplicateGroupsList by produceState(emptyList(), allItems) {
        value = withContext(Dispatchers.Default) { duplicateGroups(allItems) }
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
    // 平铺展示序：DOWNLOAD 模式客户端按完成时间重排（查询序=发布时间）
    val displayItems = remember(items, sortMode) {
        if (sortMode == LibrarySortMode.DOWNLOAD) items.sortedByDescending { it.completedAt } else items
    }
    // 分组：发布模式=作者·发布日（旧语义）；下载模式=下载日（今天/昨天/日期）
    val groups = remember(displayItems, grouped, sortMode) {
        when {
            sortMode == LibrarySortMode.PUBLISH && grouped -> groupLibraryByTweet(displayItems)
            sortMode == LibrarySortMode.DOWNLOAD && grouped -> groupLibraryByDownloadDay(displayItems)
            else -> emptyList()
        }
    }

    // ── 播放返回定位（2026-09-30 v1.6.8；2026-09-30 v1.6.9 修复定位失效；2026-10-03 批次D 目标卡片屏幕居中 + 落位高亮）──
    // 卡片封面矩形表（onGloballyPositioned 持续上报，仅可见卡片在表内）
    val coverBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    // 2026-10-24 卡片新方案：双列封面网格（LazyVerticalGrid）；定位/居中逻辑 API 同构移植
    val listState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // 2026-10-03 批次D：落位高亮确认——居中完成且矩形上报后置为目标 filePath，
    // 目标卡片主色描边脉冲 1.2s 自动淡出。
    // 2026-10-03 终验修正：高亮计时独立成 effect——定位 effect 的生命周期绑定
    // locateFilePath，而宿主在收缩落定（onBack）即清空该值并把本 effect 取消，
    // 原先 delay(1200) 放在定位 effect 内会被 finally 立即清掉（高亮只活几十毫秒）。
    // 现在定位 effect 只点亮，独立 effect 负责到点熄灭，不受 locateFilePath 清空影响。
    var highlightedPath by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(highlightedPath) {
        if (highlightedPath != null) {
            delay(1200L)
            highlightedPath = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(locateFilePath, items.size, grouped, filter, sortMode) {
        val target = locateFilePath ?: return@LaunchedEffect
        // LazyColumn 内部索引：头部占 index 0；分组模式还要累加各组头部
        val lazyIndex = if (grouped) {
            var acc = 1
            var found = -1
            groups.forEach { group ->
                if (found < 0) {
                    val pos = group.items.indexOfFirst { it.filePath == target }
                    if (pos >= 0) found = acc + pos
                }
                acc += 1 + group.items.size
            }
            found
        } else {
            displayItems.indexOfFirst { it.filePath == target }.takeIf { it >= 0 }?.plus(1) ?: -1
        }
        if (lazyIndex < 0) {
            // 2026-10-03 批次D 筛选兜底：目标不在当前筛选视图时切回「全部作品」后返回，
            // effect key 含 filter（items.size 亦随列表变化），列表刷新后自动重跑定位；
            // 已是全部作品仍找不到则按现状放弃（只兜一次，避免死循环）
            if (filter != LibraryFilter.ALL) filter = LibraryFilter.ALL
            return@LaunchedEffect
        }
        // 2026-09-30 v1.6.9 的经验保留：
        // ① 瞬时 scrollToItem（animateScrollToItem 在 460ms 收尾动画内滚不完）；
        // ② 滚动后 onGloballyPositioned 要到下一帧布局才把矩形写入 coverBoundsMap，
        //   上报必须等矩形表出现新值。
        val boundsBeforeLocate = coverBoundsMap[target]
        // 2026-10-03 批次D 两段式居中第一段：目标不在可见区才瞬跳（列表在浮层背后，
        // 用户不可见）；目标本就在 visibleItemsInfo 中则跳过瞬跳，避免无谓跳变
        val targetVisibleNow = listState.layoutInfo.visibleItemsInfo.any { it.index == lazyIndex }
        if (!targetVisibleNow) {
            runCatching { listState.scrollToItem(lazyIndex) }
        }
        // 第二段：读最新布局找目标项，按「项中心 - 视口中心」差值平滑滚到竖直居中。
        // layoutInfo 的 item offset 与 viewportStart/EndOffset 同属内容坐标系，视口占
        // [viewportStartOffset, viewportEndOffset]。订阅首帧若已拿到目标（目标本就可见 /
        // scrollToItem 已强制重测布局）则立即返回，否则等瞬跳后的新布局（200ms 兜底直读）。
        // 220ms 平滑滚动发生在播放层收缩动画（460ms）期间、浮层背后，视觉安全；
        // 首尾滚不够时 animateScrollBy 自然钳制（卡片停在端头），无需特判
        val targetInfo = withTimeoutOrNull(200L) {
            androidx.compose.runtime.snapshotFlow { listState.layoutInfo }
                .map { layout -> layout.visibleItemsInfo.firstOrNull { it.index == lazyIndex } }
                .filterNotNull()
                .first()
        } ?: listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
        if (targetInfo != null) {
            val layout = listState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            val delta = (targetInfo.offset.y + targetInfo.size.height / 2) - viewportCenter
            if (delta != 0) {
                // 2026-10-05 定位失效修复：居中改瞬时（scrollBy）——旧 220ms 平滑动画
                // 使终点矩形上报晚于播放器收缩窗口（远处卡片必现「定位无效果」）；
                // 居中发生在浮层背后用户不可见，瞬时无观感代价、上报提前 ~260ms
                runCatching { listState.animateScrollBy(delta.toFloat(), androidx.compose.animation.core.snap()) }
            }
        }
        // 居中完成后立即上报矩形（瞬时居中后 1-2 帧内 onGloballyPositioned 已写入新值）。
        kotlinx.coroutines.delay(60L)
        val boundsAfterLocate = coverBoundsMap[target]
            ?: withTimeoutOrNull(400L) {
                androidx.compose.runtime.snapshotFlow { coverBoundsMap[target] }
                    .drop(1)
                    .filterNotNull()
                    .first()
            }
        // 超时回退：优先当前表值，再退定位前现值
        (boundsAfterLocate ?: boundsBeforeLocate)?.let { bounds ->
            onLocateBounds(bounds)
            // 2026-10-03 批次D：落位高亮确认（1.2s 计时由上方独立 effect 负责，
            // 不随本协程被 locateFilePath 清空而取消——终验修正）
            highlightedPath = target
        }
    }

    // ── 方案A v4（2026-10-22）：固定标题条（主行+数据副行折叠）对称收件箱；
    // 搜索条/视图行/筛选数据卡留在列表内随内容滚走 ──
    // Grid 折叠进度（rememberTitleCollapseProgress 只支持 LazyListState，网格内联同语义）：
    // 首项滚出即视为完全折叠，首项内按滚动量线性收敛
    val titleCollapse by remember {
        androidx.compose.runtime.derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 160f).coerceIn(0f, 1f)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        MediaLibraryFixedBar(
            allCount = allItems.size,
            totalBytes = totalBytes,
            // 2026-10-24 排版重排：筛选胶囊行固定在 title 正下方（用户要求滚动时吸顶）；
            // 升级 pill 下移到列表内操作行（标题行只留 搜索/刷新，按钮不再拥挤）
            filter = filter,
            videoCount = videoCount,
            imageCount = imageCount,
            onFilterChange = { filter = it },
            searchActive = searchActive,
            onSearchToggle = {
                searchActive = !searchActive
                if (!searchActive) searchQuery = ""
            },
            onScan = syncLibrary,
            collapse = titleCollapse
        )
    // ── 方案A 软着陆（2026-10-22）：列表边缘渐隐缓冲（与收件箱同款）──
    Box(modifier = Modifier.fillMaxSize()) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        state = listState,
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().background(Color.Transparent),  // 透出 GlassBackground
        contentPadding = PaddingValues(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            LibraryHeader(
                syncMessage = syncMessage,
                grouped = grouped,
                onGroupToggle = { grouped = !grouped },
                sortMode = sortMode,
                onSortToggle = onSortToggle,
                searchActive = searchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                duplicateCount = duplicateGroupsList.size,
                onOpenDuplicates = { showDuplicates = true },
                // 2026-10-24 排版重排：升级入口从固定条标题行移入操作行（原位置让位给筛选胶囊）
                upgrading = upgradingNow,
                upgradeLabel = upgradePillText,
                onUpgrade = {
                    scope.launch {
                        val summary = historyViewModel.manualUpgradeNow()
                        syncMessage = summary
                        delay(4000)
                        if (syncMessage == summary) syncMessage = null
                    }
                }
            )
        }

        if (items.isEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { EmptyLibraryState(onScan = syncLibrary) }
        } else {
            val cardFor: @Composable (DownloadHistoryEntity) -> Unit = { entity ->
                GridMediaCard(
                    entity = entity,
                    onPlay = { coverBounds -> onNavigateToPlayer(entity.filePath, coverBounds) },
                    onCoverBounds = { coverBoundsMap[entity.filePath] = it },
                    // 容器变形退场：播放层缩小落定过程中隐藏该卡片信息区，落定后淡入
                    infoHidden = entity.filePath == infoHiddenFor && !infoRevealed,
                    // 2026-10-03 批次D：播放返回定位落定后的目标卡片高亮脉冲（1.2s 自动淡出）
                    highlighted = entity.filePath == highlightedPath,
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
            if (grouped) {
                groups.forEach { group ->
                    val headerKey = "group_${group.author}_${group.dayLabel}"
                    // 2026-10-20：分组节头改吸顶 + 共享组件 StickySectionHeader
                    //（对齐 edqiu-screens-content.html 媒体库规格：82% 表面 + 头像 + 作者·日期 + 计数）
                    item(key = "group_header_$headerKey", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                        // 2026-10-24：下载日分组（author 空）只渲染日期标签；作者·发布日分组保持原样
                        if (group.author.isEmpty()) {
                            StickySectionHeader(
                                title = group.dayLabel,
                                modifier = Modifier.padding(vertical = 4.dp),
                                trailingText = "${group.items.size} 个媒体"
                            )
                        } else {
                            StickySectionHeader(
                                title = "${group.author} · ${group.dayLabel}",
                                modifier = Modifier.padding(vertical = 4.dp),
                                avatarInitial = group.author.firstOrNull()?.toString(),
                                trailingText = "${group.items.size} 个媒体"
                            )
                        }
                    }
                    items(group.items, key = { it.id }) { entity -> cardFor(entity) }
                }
            } else {
                items(displayItems, key = { it.id }) { entity -> cardFor(entity) }
            }
        }
    }

    // 边缘渐隐：顶部 24dp 三段缓曲线 + 底部 24dp 沉降带（与收件箱同款软着陆）
    EdgeFadeScrim(
        edge = EdgeFadeEdge.Top,
        color = Color.Black,
        height = 24.dp,
        maxAlpha = 0.26f,
        modifier = Modifier.align(Alignment.TopCenter)
    )
    EdgeFadeScrim(
        edge = EdgeFadeEdge.Bottom,
        color = Color.Black,
        height = 24.dp,
        maxAlpha = 0.30f,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }
    }

    // ── 疑似重复作品 Sheet（2026-09-15 批次3：P1-4① pHash 查重）──
    if (showDuplicates) {
        ModalBottomSheet(onDismissRequest = { showDuplicates = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "疑似重复作品",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "按感知哈希识别画面相似的视频/图片。每组自动保留画质最高的一条，其余点「删除」移除（含本地文件）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (duplicateGroupsList.isEmpty()) {
                    Text(
                        text = "未发现疑似重复。新下载的媒体会自动计算指纹，稍后再看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                duplicateGroupsList.forEach { group ->
                    val keep = group.maxByOrNull { it.fileSize.coerceAtLeast(0L) } ?: group.first()
                    GlassSurface(
                        tier = GlassTier.L1,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${keep.uploader.ifBlank { "未知作者" }} · ${group.size} 条画面相似",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "保留：${keep.title.take(28)} · ${formatFileSize(keep.fileSize)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            group.filter { it.id != keep.id }.forEach { dup ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dup.title.take(30).ifBlank { dup.filePath.substringAfterLast('/') },
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${formatFileSize(dup.fileSize)} · ${dup.quality}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = {
                                        historyViewModel.deleteHistory(dup, deleteLocalFile = true)
                                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 媒体库固定标题条（2026-10-22 方案A v4，对称收件箱）。
 *
 * 主行元素固定不滚：E 徽章 + 「媒体库」标题 + 数据副行（件数·占用，随
 * collapse 折叠收 0）+ 升级 pill + 搜索 + 刷新。搜索条/视图行/筛选数据卡
 * 仍在列表内随内容滚走——滚动后顶上只剩一条紧凑标题行。
 */
@Composable
private fun MediaLibraryFixedBar(
    allCount: Int,
    totalBytes: Long,
    // 2026-10-24 排版重排：筛选胶囊进固定区（title 正下方），升级移列表操作行
    filter: LibraryFilter,
    videoCount: Int,
    imageCount: Int,
    onFilterChange: (LibraryFilter) -> Unit,
    searchActive: Boolean,
    onSearchToggle: () -> Unit,
    onScan: () -> Unit,
    collapse: Float
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
        ) {
            Box(
                modifier = Modifier.size(34.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "E",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "媒体库",
                // 2026-10-24 与收件箱折叠语言对齐：标题随滚动 20→17sp 收缩
                fontSize = com.ed.edqiu.ui.components.titleCollapseSize(collapse, 20.sp, 17.sp),
                lineHeight = 25.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.02).sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            // 数据副行：随 collapse 高度收到 0（空间真正让位，对齐收件箱副标题）
            if (collapse < 0.98f) {
                Text(
                    text = "$allCount 件作品 · 共 ${formatFileSize(totalBytes)}",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .height((16 * (1f - collapse)).dp)
                        .graphicsLayer { alpha = 1f - collapse }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 搜索入口（2026-09-15 批次5）：文案/作者/文件名模糊搜索
        LibraryHeaderIconButton(onClick = onSearchToggle, contentDescription = "搜索媒体库") {
            Icon(
                if (searchActive) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                contentDescription = null,
                tint = if (searchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        LibraryHeaderIconButton(onClick = onScan, contentDescription = "刷新媒体库") {
            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        }
        }

        // ── 筛选胶囊行（2026-10-24 用户要求：固定在 title 正下方，滚动不离开）──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = listOf(
                Triple(LibraryFilter.ALL, "全部", allCount),
                Triple(LibraryFilter.VIDEO, "视频", videoCount),
                Triple(LibraryFilter.IMAGE, "图片", imageCount)
            )
            options.forEach { (value, label, count) ->
                val selected = filter == value
                GlassSurface(
                    tier = if (selected) GlassTier.L2 else GlassTier.L1,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .pressableNoRipple { onFilterChange(value) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "$count",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    syncMessage: String?,
    grouped: Boolean,
    onGroupToggle: () -> Unit,
    // 2026-10-24 排序重构：下载时间（默认）/发布时间可切换
    sortMode: LibrarySortMode,
    onSortToggle: () -> Unit,
    // 2026-10-24 排版重排：升级入口从固定条移入本操作行
    upgrading: Boolean,
    upgradeLabel: String,
    onUpgrade: () -> Unit,
    // 2026-09-15 v2 批次5：文本搜索（文案/作者/文件名）
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    // 2026-09-15 v2 批次3：pHash 查重入口
    duplicateCount: Int,
    onOpenDuplicates: () -> Unit
) {
    // 方案A v4（2026-10-22）→ 2026-10-24 排版精简：主行已上移固定条；本区从
    // 「L2 大卡整块包裹」改为直接浮在壁纸上的轻量元素（搜索条/筛选胶囊/视图行）——
    // 与收件箱筛选胶囊同语言，削减头部栈高度与视觉噪音（用户反馈排版怪）。
    Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── 搜索条（展开时出现，2026-09-15 批次5）──
            AnimatedVisibility(visible = searchActive) {
                EdqiuTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "搜索文案、作者或文件名",
                    singleLine = true
                )
            }
            // ── 视图行：排序切换 + 分组切换 pill（低频操作次行）──
            // 2026-10-24 排序重构：默认「按下载时间」（刚下完的在最上——旧发布时间排序
            // 让今天下载的老作品沉底，用户困惑根源）；点击在 下载/发布 时间间切换并持久化
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 升级 pill（2026-10-24 自固定条标题行移入）
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.pressableNoRipple(enabled = !upgrading) { onUpgrade() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (upgrading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = upgradeLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.pressableNoRipple { onSortToggle() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (sortMode == LibrarySortMode.DOWNLOAD) "按下载时间" else "按发布时间",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = if (grouped) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                    else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                    border = BorderStroke(
                        1.dp,
                        if (grouped) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                    ),
                    modifier = Modifier.pressableNoRipple { onGroupToggle() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            if (grouped) Icons.Outlined.ViewStream else Icons.Outlined.ViewAgenda,
                            contentDescription = null,
                            tint = if (grouped) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (grouped) "按作者·日期分组" else "作品分组",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (grouped) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 查重入口（2026-09-15 批次3）：仅检出重复组时显示
                if (duplicateCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                        modifier = Modifier.pressableNoRipple { onOpenDuplicates() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Outlined.FileCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "重复 $duplicateCount 组",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            // 筛选胶囊行已上移固定区（title 正下方）

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

/** 筛选卡四元组（2026-10-21 统计卡重设计）：筛选值 + 标签 + 数量 + 类型图标。 */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun LibraryHeaderIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    GlassSurface(
        tier = GlassTier.L3,
        shape = CircleShape,
        modifier = Modifier
            .size(44.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .pressableNoRipple { onClick() }
    ) {
        // 固定尺寸圆钮：内层 Box 必须 fillMaxSize 才能撑满 44dp 让图标居中——
        // GlassSurface 的内容 Box 默认 TopStart 且不传最小约束（不同于 Material3
        // Surface 的 propagateMinConstraints=true），裸 Box(contentAlignment=Center)
        // 只包图标尺寸钉在左上角，会探出圆钮圆弧外（2026-10-20 实机 bug 修复）
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
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
private fun GridMediaCard(
    entity: DownloadHistoryEntity,
    onPlay: (coverBounds: Rect?) -> Unit,
    onShare: () -> Unit,
    onDelete: (deleteLocalFile: Boolean) -> Unit,
    // 播放返回定位的落点来源
    onCoverBounds: ((Rect) -> Unit)? = null,
    // 容器变形退场：true 时信息行隐藏（播放层正缩小飞向本卡片）
    infoHidden: Boolean = false,
    // 落位确认高亮（主色描边脉冲，限时 3s）
    highlighted: Boolean = false
) {
    var showDelete by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // 共享封面过渡：封面区域窗口坐标边界，点击时作为「封面飞入全屏」几何起点
    var coverBounds by remember { mutableStateOf<Rect?>(null) }
    val frameOffset = ((entity.mediaIndex ?: 1).coerceAtLeast(1) * 1.7f).coerceAtMost(12f)

    // 落位脉冲（限时 3s 后转静态描边——无限脉冲=持续帧源，冻结攻坚定案）
    val pulseColor = MaterialTheme.colorScheme.primary
    val pulseAlpha = if (highlighted) {
        var pulsing by remember { mutableStateOf(true) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(3000L)
            pulsing = false
        }
        if (pulsing) {
            rememberInfiniteTransition(label = "locatePulse").animateFloat(
                initialValue = 0.9f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(durationMillis = 300, easing = LinearEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "locatePulseAlpha"
            ).value
        } else 0.6f
    } else null

    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val alpha = pulseAlpha ?: return@drawWithContent
                val strokeWidth = 2.5.dp.toPx()
                drawRoundRect(
                    color = pulseColor.copy(alpha = alpha),
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = strokeWidth)
                )
            }
            .clickable(onClick = { onPlay(coverBounds) })
    ) {
        Column {
            // ── 封面区：1:1 方形（双列网格），点击即播放 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .onGloballyPositioned {
                        coverBounds = it.boundsInWindow()
                        onCoverBounds?.invoke(it.boundsInWindow())
                    }
            ) {
                ThumbnailWithFallback(
                    thumbnailUrl = entity.thumbnail,
                    videoFilePath = entity.filePath,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    frameOffsetSeconds = frameOffset,
                    preferLocalFrame = entity.thumbnail.isBlank()
                )
                Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.08f)))
                // 播放指示（视频）：中央半透明圆钮
                if (entity.mediaType == MediaType.VIDEO) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.38f),
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(
                            Icons.Outlined.PlayArrow, null, tint = Color.White,
                            modifier = Modifier.padding(8.dp).size(20.dp)
                        )
                    }
                }
                // 时长胶囊（视频，右下角）
                if (entity.mediaType == MediaType.VIDEO && entity.duration > 0) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.52f),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    ) {
                        Text(
                            text = formatDuration(entity.duration),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                // 菜单（右上角）：分享 / 删除
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    CircleIconAction(onClick = { showMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("分享") },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            onClick = { showMenu = false; onShare() }
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFE11D48)) },
                            onClick = { showMenu = false; showDelete = true }
                        )
                    }
                }
            }
            // ── 信息行：作者 + 画质（容器变形退场时隐藏）──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (infoHidden) 0f else 1f }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Icon(
                    if (entity.mediaType == MediaType.IMAGE) Icons.Outlined.Image else Icons.Outlined.VideoLibrary,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = mediaAuthorOf(entity),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = entity.quality.ifBlank { "·" },
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }

    if (showDelete) {
        SmoothAlertDialog(
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
        modifier = Modifier.size(30.dp).pressableNoRipple { onClick() }
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

/** pHash 重复分组（2026-09-15 批次3）：汉明距离 ≤4 判疑似重复，贪心聚组（纯内存）。 */
private fun duplicateGroups(items: List<DownloadHistoryEntity>): List<List<DownloadHistoryEntity>> {
    val withHash = items.filter { it.phash != null && it.phash != 0L }
    val used = mutableSetOf<String>()
    val groups = mutableListOf<List<DownloadHistoryEntity>>()
    for (i in withHash.indices) {
        val a = withHash[i]
        if (a.id in used) continue
        val group = mutableListOf(a)
        for (j in i + 1 until withHash.size) {
            val b = withHash[j]
            if (b.id in used) continue
            if (com.ed.edqiu.service.PhashService.hamming(a.phash!!, b.phash!!) <=
                com.ed.edqiu.service.PhashService.HAMMING_THRESHOLD
            ) {
                group += b
            }
        }
        if (group.size >= 2) {
            group.forEach { used += it.id }
            groups += group
        }
    }
    return groups
}

private fun mediaMeta(entity: DownloadHistoryEntity): String {
    // 质量 · 大小（类型交给缩略图角标，标题只保留作者；时间独立成行区分发帖/下载）
    val quality = entity.quality
        .removePrefix("video_")
        .removePrefix("image_")
        .ifBlank { null }
    return listOf(
        quality,
        formatFileSize(entity.fileSize)
    ).filterNotNull().joinToString(" · ")
}

/**
 * 时间行文本（2026-09-15 与下载时间明确区分）：
 * - 已归位（有发布时间）：主题色「发帖 MM-dd HH:mm」；发布与下载不同天时
 *   追加灰色「· 下载 MM-dd」辅助信息；
 * - 未归位：灰色「下载 MM-dd HH:mm」（补拉完成后自动切换为发帖时间）。
 */
private fun mediaTimeText(entity: DownloadHistoryEntity): String {
    val publishedAt = entity.publishedAt
    return if (publishedAt != null) {
        val base = "发帖 ${formatTime(publishedAt)}"
        val downloadDay = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(entity.completedAt))
        val publishDay = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(publishedAt))
        if (downloadDay != publishDay) "$base · 下载 $downloadDay" else base
    } else {
        "下载 ${formatTime(entity.completedAt)}"
    }
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
