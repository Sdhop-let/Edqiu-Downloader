package com.ed.edqiu.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
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
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                InboxHeader(
                    pendingCount = links.count { it.status == LinkStatus.PENDING },
                    downloadedCount = links.count { it.status == LinkStatus.DOWNLOADED },
                    failedCount = links.count { it.status == LinkStatus.FAILED },
                    searchQuery = searchQuery,
                    onSearchChange = vm::setSearchQuery,
                    selectedFilter = filter,
                    onFilterChange = { filter = it },
                    onPaste = { showPasteDialog = true },
                    onToggleSelection = vm::toggleSelectionMode,
                    selectionMode = selectionMode,
                    sortExpanded = sortExpanded,
                    onSortExpandedChange = { sortExpanded = it },
                    onSortSelected = {
                        vm.setSortOrder(it)
                        sortExpanded = false
                    }
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = navBarBottom + 66.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isFirstLoad && links.isEmpty()) {
                        items(4) {
                            SkeletonCard(modifier = Modifier)
                        }
                    } else if (shown.isEmpty()) {
                        item {
                            EmptyStateCard(
                                searching = searchQuery.isNotBlank(),
                                onPaste = { showPasteDialog = true }
                            )
                        }
                    } else {
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
            }

            if (showScrollTop) {
                Surface(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    shape = CircleShape,
                    color = Color(0xFF101417),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .zIndex(5f)
                        .padding(end = 22.dp, bottom = 104.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "返回顶部",
                        tint = Color.White,
                        modifier = Modifier.padding(10.dp).size(28.dp)
                    )
                }
            }

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
private fun InboxHeader(
    pendingCount: Int,
    downloadedCount: Int,
    failedCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedFilter: Filter,
    onFilterChange: (Filter) -> Unit,
    onPaste: () -> Unit,
    onToggleSelection: () -> Unit,
    selectionMode: Boolean,
    sortExpanded: Boolean,
    onSortExpandedChange: (Boolean) -> Unit,
    onSortSelected: (LinkSortOrder) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "收件箱",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "捕获链接、下载进度与历史记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderIconButton(onClick = onPaste, contentDescription = "粘贴链接") {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                HeaderIconButton(
                    onClick = onToggleSelection,
                    contentDescription = if (selectionMode) "完成批量选择" else "进入批量选择"
                ) {
                    Crossfade(
                        targetState = selectionMode,
                        label = "batch_button_text"
                    ) { isSelection ->
                        Text(
                            if (isSelection) "完成" else "批量",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                )
                            ),
                            modifier = Modifier.offset(y = (-1).dp)
                        )
                    }
                }
            }
        }

        GlassSurface(
            tier = GlassTier.L1,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricItem("待处理", pendingCount.toString())
                MetricDivider()
                MetricItem("已完成", downloadedCount.toString())
                MetricDivider()
                MetricItem("失败", failedCount.toString())
            }
        }

        GlassSurface(
            tier = GlassTier.L1,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp)
                .height(46.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 15.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 9.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "搜索链接或标题",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                            inner()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清空搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onSearchChange("") }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                        )
                )

                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 14.dp)
                            .clickable { onSortExpandedChange(true) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "排序",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Filter.entries.forEach { f ->
                val selected = f == selectedFilter
                GlassSurface(
                    tier = if (selected) GlassTier.L2 else GlassTier.L1,
                    shape = RoundedCornerShape(12.dp),
                    elevated = false,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onFilterChange(f) }
                ) {
                    Text(
                        text = f.label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
    )
}

@Composable
private fun HeaderIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = CircleShape,
        modifier = Modifier
            .size(44.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun EmptyStateCard(searching: Boolean, onPaste: () -> Unit) {
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
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(29.dp)
                    )
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
                Text(statusText, color = com.ed.edqiu.ui.theme.statusColor(link.status), fontWeight = FontWeight.Medium)
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

enum class Filter(val label: String) {
    ALL("全部"),
    PENDING("待处理"),
    DOWNLOADED("已完成"),
    FAILED("失败")
}
