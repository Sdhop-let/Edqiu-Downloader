package com.ed.edqiu.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.LinkCard
import com.ed.edqiu.ui.components.SkeletonCard
import com.ed.edqiu.ui.navigation.LocalSnackbarController
import com.ed.edqiu.ui.theme.statusColor
import com.ed.edqiu.ui.util.copyToClipboard
import kotlinx.coroutines.launch

@Composable
fun ListScreen(
    vm: ListViewModel,
    onOpenDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    val links by vm.links.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val monitorUri by vm.monitorUri.collectAsStateWithLifecycle()
    val autoCapture by vm.autoCapture.collectAsStateWithLifecycle()
    val captureFeedback by vm.captureFeedback.collectAsStateWithLifecycle()
    val actionFeedback by vm.actionFeedback.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by vm.sortOrder.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedIds.collectAsStateWithLifecycle()
    val selectionMode by vm.selectionMode.collectAsStateWithLifecycle()

    var filter by remember { mutableStateOf(Filter.ALL) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var sortExpanded by remember { mutableStateOf(false) }
    var longPressedLink by remember { mutableStateOf<SavedLink?>(null) }
    var isFirstLoad by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showScrollTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 200 } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, autoCapture) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (autoCapture) vm.captureFromClipboard()
                vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(monitorUri) {
        if (!monitorUri.isNullOrBlank()) {
            vm.refresh()
            isFirstLoad = false
        }
    }

    LaunchedEffect(captureFeedback) {
        captureFeedback?.let {
            val msg = when (it) {
                ListViewModel.CaptureFeedback.Added -> "已保存到收件箱"
                ListViewModel.CaptureFeedback.Duplicate -> "这条链接已存在"
                ListViewModel.CaptureFeedback.NotTwitter -> "不是有效的 X/Twitter 链接"
                ListViewModel.CaptureFeedback.Empty -> "剪贴板为空"
            }
            snackbar.show(msg)
            vm.clearFeedback()
        }
    }

    LaunchedEffect(actionFeedback) {
        actionFeedback?.let { feedback ->
            snackbar.show(
                message = feedback.message,
                actionLabel = feedback.actionLabel,
                onAction = feedback.onAction
            )
            vm.clearActionFeedback()
        }
    }

    val shown = remember(links, filter) {
        when (filter) {
            Filter.ALL -> links
            Filter.PENDING -> links.filter { it.status == LinkStatus.PENDING }
            Filter.DOWNLOADED -> links.filter { it.status == LinkStatus.DOWNLOADED }
            Filter.FAILED -> links.filter { it.status == LinkStatus.FAILED }
        }
    }

    Scaffold(
        // 透明容器：让 GlassBackground 的莫奈色域从底下透出，玻璃才有折射内容
        containerColor = Color.Transparent,
        // 不应用 insets：让 HeaderPanel 顶部嵌入状态栏区域（玻璃卡真正靠近手机顶部）
        contentWindowInsets = WindowInsets(0),
        // 不设 bottomBar：批量选择工具条改为顶层 Box overlay（避免与底部悬浮 Tab 栏冲突）
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 半透明主题色背景（30% alpha）—— 让 GlassBackground 莫奈色域从底下透出
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.30f))
            ) {
                HeaderPanel(
                    selectionMode = selectionMode,
                    onPaste = { showPasteDialog = true },
                    onToggleSelection = vm::toggleSelectionMode
                )

                InboxToolPanel(
                    query = searchQuery,
                    onQueryChange = vm::setSearchQuery,
                    sortLabel = sortOrder.label,
                    sortExpanded = sortExpanded,
                    onSortExpandedChange = { sortExpanded = it },
                    onSortSelected = {
                        vm.setSortOrder(it)
                        sortExpanded = false
                    },
                    filter = filter,
                    allCount = links.size,
                    pendingCount = links.count { it.status == LinkStatus.PENDING },
                    downloadedCount = links.count { it.status == LinkStatus.DOWNLOADED },
                    failedCount = links.count { it.status == LinkStatus.FAILED },
                    totalCount = links.size,
                    pendingTotal = pendingCount,
                    failedTotal = links.count { it.status == LinkStatus.FAILED },
                    onFilterChange = { filter = it }
                )

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        isFirstLoad && links.isEmpty() -> LoadingList()
                        shown.isEmpty() -> InboxEmptyState(
                            searching = searchQuery.isNotBlank(),
                            onPaste = { showPasteDialog = true }
                        )
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // 底部 112dp 避让悬浮 Tab 栏 + FAB
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = 12.dp,
                                end = 16.dp,
                                bottom = 112.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
items(shown, key = { it.tweetId }) { link ->
                            LinkCard(
                                link = link,
                                onClick = { onOpenDetail(link.tweetId) },
                                onLongClick = { longPressedLink = link },
                                selectionMode = selectionMode,
                                selected = link.tweetId in selectedIds,
                                onSelectionToggle = { vm.toggleSelected(link.tweetId) },
                                onQuickDelete = if (link.status == LinkStatus.FAILED) {
                                    { vm.delete(link.tweetId) }
                                } else null
                            )
                        }
                        }
                    }

                    // FAB：粘贴链接快捷入口（原型右下角 + 按钮）
                    Surface(
                        onClick = { showPasteDialog = true },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 18.dp, bottom = 104.dp)
                            .size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "粘贴链接",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    if (showScrollTop) {
                        Surface(
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                            shape = CircleShape,
                            color = Color(0xFF101417),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 22.dp, bottom = 172.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "返回顶部",
                                tint = Color.White,
                                modifier = Modifier.padding(10.dp).size(28.dp)
                            )
                        }
                    }
                }
            }

            // 批量选择工具条：顶层 Box overlay，从顶部滑入；不与底部悬浮 Tab 栏冲突
            // padding(top=180dp)：避开 HeaderPanel 玻璃卡（不盖"视频收件箱"标题），
            // 同时保留 HeaderPanel 的"完成"按钮可见可点
            AnimatedVisibility(
                visible = selectionMode,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f)
                    .padding(top = 180.dp)
            ) {
                SelectionBar(
                    selectedCount = selectedIds.size,
                    onSelectAll = { vm.selectAll(shown.map { it.tweetId }) },
                    onCopy = {
                        val text = links
                            .filter { it.tweetId in selectedIds }
                            .joinToString("\n") { it.rawUrl }
                        copyToClipboard(context, "Edqiu 批量链接", text)
                        snackbar.show("已复制 ${selectedIds.size} 条链接")
                    },
                    onDownload = vm::downloadSelected,
                    onDelete = vm::deleteSelected,
                    onExit = { vm.toggleSelectionMode() },
                    enabled = selectedIds.isNotEmpty()
                )
            }
        }
    }

    longPressedLink?.let { link ->
        LinkActionDialog(
            link = link,
            onDismiss = { longPressedLink = null },
            onCopy = {
                copyToClipboard(context, "Edqiu 链接", link.rawUrl)
                snackbar.show("已复制链接")
                longPressedLink = null
            },
            onDownload = {
                vm.requestDownload(link.tweetId)
                longPressedLink = null
            },
            onDelete = {
                vm.delete(link.tweetId)
                longPressedLink = null
            }
        )
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴 X/Twitter 链接") },
            text = {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    placeholder = { Text("https://x.com/.../status/...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.captureText(pasteText)
                    pasteText = ""
                    showPasteDialog = false
                }) { Text("捕获") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HeaderPanel(
    selectionMode: Boolean,
    onPaste: () -> Unit,
    onToggleSelection: () -> Unit
) {
    // L2 玻璃头部：仅标题区（统计卡已拆到 InboxToolPanel）
    // 玻璃卡刚好从状态栏底部开始（statusBarsPadding），Scaffold 已设 WindowInsets(0)
    GlassSurface(
        tier = GlassTier.L2,
        shape = RoundedCornerShape(30.dp),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 品牌行：E 徽章 + EDQIU LOAD
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
                    ) {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "E",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "EDQIU LOAD",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "视频收件箱",
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.02).sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "X / Twitter 链接自动捕获与状态归档",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderIconButton(onClick = onPaste, contentDescription = "粘贴链接") {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                HeaderIconButton(
                    onClick = onToggleSelection,
                    contentDescription = if (selectionMode) "完成批量选择" else "进入批量选择"
                ) {
                    Text(
                        if (selectionMode) "完成" else "批量",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
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
private fun InboxToolPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    sortLabel: String,
    sortExpanded: Boolean,
    onSortExpandedChange: (Boolean) -> Unit,
    onSortSelected: (LinkSortOrder) -> Unit,
    filter: Filter,
    allCount: Int,
    pendingCount: Int,
    downloadedCount: Int,
    failedCount: Int,
    totalCount: Int,
    pendingTotal: Int,
    failedTotal: Int,
    onFilterChange: (Filter) -> Unit
) {
    // 搜索 + 筛选 + 统计工具面板：L1 玻璃（三段平铺：搜索 / chips / stats）
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchSortBar(
                query = query,
                onQueryChange = onQueryChange,
                sortLabel = sortLabel,
                sortExpanded = sortExpanded,
                onSortExpandedChange = onSortExpandedChange,
                onSortSelected = onSortSelected
            )
            FilterTabs(
                filter = filter,
                allCount = allCount,
                pendingCount = pendingCount,
                downloadedCount = downloadedCount,
                failedCount = failedCount,
                onFilterChange = onFilterChange
            )
            // 统计摘要行：横向扁平（状态色点 + 数字 + 标签），弱化为背景性信息
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.40f))
                    .padding(horizontal = 4.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatSummary("全部", totalCount, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatSummary("待处理", pendingTotal, statusColor(LinkStatus.PENDING), Modifier.weight(1f))
                StatSummary("失败", failedTotal, statusColor(LinkStatus.FAILED), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatSummary(label: String, value: Int, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = value.toString(),
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchSortBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sortLabel: String,
    sortExpanded: Boolean,
    onSortExpandedChange: (Boolean) -> Unit,
    onSortSelected: (LinkSortOrder) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // L2 玻璃搜索胶囊：替换 M3 白底输入框，与原型 glass g2 对齐
        GlassSurface(
            tier = GlassTier.L2,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = "搜索作者、文案或链接",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    Surface(
                        onClick = { onQueryChange("") },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清空搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp).size(14.dp)
                        )
                    }
                }
            }
        }
        Box {
            Surface(
                shape = RoundedCornerShape(17.dp),
                color = Color(0xFF101417),
                shadowElevation = 0.dp,
                modifier = Modifier.clickable { onSortExpandedChange(true) }
            ) {
                Row(
                    modifier = Modifier
                        .height(50.dp)
                        .padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(sortLabel, maxLines = 1, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                }
            }
            DropdownMenu(
                expanded = sortExpanded,
                onDismissRequest = { onSortExpandedChange(false) }
            ) {
                LinkSortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.label) },
                        onClick = { onSortSelected(order) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterTabs(
    filter: Filter,
    allCount: Int,
    pendingCount: Int,
    downloadedCount: Int,
    failedCount: Int,
    onFilterChange: (Filter) -> Unit
) {
    val options = listOf(
        Filter.ALL to ("全部" to allCount),
        Filter.PENDING to ("未下载" to pendingCount),
        Filter.DOWNLOADED to ("已下载" to downloadedCount),
        Filter.FAILED to ("失败" to failedCount)
    )
    // 玻璃胶囊 chips：原型 .chip 样式（未选中 glass 底，选中 primaryContainer + 内描边）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, data) ->
            val selected = filter == value
            val accent = when (value) {
                Filter.ALL -> MaterialTheme.colorScheme.primary
                Filter.PENDING -> statusColor(LinkStatus.PENDING)
                Filter.DOWNLOADED -> statusColor(LinkStatus.DOWNLOADED)
                Filter.FAILED -> statusColor(LinkStatus.FAILED)
            }
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
                    // 状态语义点：选中时保留双通道辨识（无障碍要求）
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = "${data.first} ${data.second}",
                        style = MaterialTheme.typography.labelSmall,
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
}

@Composable
private fun InboxEmptyState(searching: Boolean, onPaste: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Top
    ) {
        // 玻璃空态卡：替换纯白 Surface
        GlassSurface(
            tier = GlassTier.L1,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(58.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(29.dp))
                    }
                }
                Text(
                    text = if (searching) "没有匹配的链接" else "收件箱等待第一条链接",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (searching) {
                        "换一个关键词，或切换上方状态筛选。"
                    } else {
                        "复制或分享 X/Twitter 链接后，作者头像、ID、文案和下载状态会自动归档。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (!searching) {
                    Button(onClick = onPaste, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("粘贴链接")
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
    enabled: Boolean
) {
    // L3 玻璃批量操作栏：primary 渐变 tint，替代纯色块
    // zIndex(10f) 让 SelectionBar 浮在底部悬浮 Tab 栏（LiquidTabBar）之上，避免视觉冲突
    Box(
        modifier = Modifier
            .zIndex(10f)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
    GlassSurface(
        tier = GlassTier.L3,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.zIndex(10f)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                        )
                    )
                )
        )
        val buttonColors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.34f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 返回键：退出批量选择模式（误触"全选"后也能退回）
            TextButton(onClick = onExit, colors = buttonColors) {
                Icon(Icons.Default.Close, contentDescription = "退出批量选择", modifier = Modifier.size(18.dp))
            }
            Icon(Icons.Default.LibraryAddCheck, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            Text(
                "已选 $selectedCount",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            TextButton(onClick = onSelectAll, colors = buttonColors) { Text("全选") }
            TextButton(enabled = enabled, onClick = onCopy, colors = buttonColors) { Text("复制") }
            TextButton(enabled = enabled, onClick = onDownload, colors = buttonColors) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("下载")
            }
            TextButton(enabled = enabled, onClick = onDelete, colors = buttonColors) { Text("删除") }
        }
    }
    }
}

@Composable
private fun LoadingList() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(4) { SkeletonCard(modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}

@Composable
private fun LinkActionDialog(
    link: SavedLink,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val statusText = when (link.status) {
        LinkStatus.PENDING -> "未下载"
        LinkStatus.DOWNLOADED -> "已下载"
        LinkStatus.FAILED -> "失败${link.lastError?.let { "：$it" } ?: ""}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(link.authorName ?: link.authorId ?: "未知作者") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusText, color = statusColor(link.status), fontWeight = FontWeight.Medium)
                Text(link.caption ?: link.rawUrl, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy) { Text("复制") }
                TextButton(onClick = onDownload) { Text(if (link.status == LinkStatus.FAILED) "重试" else "下载") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

enum class Filter { ALL, PENDING, DOWNLOADED, FAILED }
