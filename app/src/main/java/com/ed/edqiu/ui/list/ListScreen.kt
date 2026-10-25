package com.ed.edqiu.ui.list

import com.ed.edqiu.ui.util.pressableNoRipple
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.Search
import com.ed.edqiu.ui.components.SmoothAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
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
import coil.compose.AsyncImage
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.ui.theme.statusColor
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.ui.components.EdqiuTextField
import com.ed.edqiu.ui.components.EdgeFadeEdge
import com.ed.edqiu.ui.components.EdgeFadeScrim
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.rememberTitleCollapseProgress
import com.ed.edqiu.ui.components.titleCollapseAlpha
import com.ed.edqiu.ui.components.titleCollapseSize
import com.ed.edqiu.ui.components.LinkCard
import com.ed.edqiu.ui.components.SkeletonCard
import com.ed.edqiu.ui.components.FeedbackDialog
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.FeedbackMessage
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
    val monitorUri by vm.monitorUri.collectAsStateWithLifecycle()
    val captureFeedback by vm.captureFeedback.collectAsStateWithLifecycle()
    val actionFeedback by vm.actionFeedback.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by vm.sortOrder.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedIds.collectAsStateWithLifecycle()
    val selectionMode by vm.selectionMode.collectAsStateWithLifecycle()
    val batchDownloadState by vm.batchDownloadState.collectAsStateWithLifecycle()
    // 收件箱实时下载进度（tweetId → 0..99）：卡片显示「下载中 xx%」+ 进度条
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()

    // 筛选 tab 用 rememberSaveable：详情页/二级页往返后保留进入前的筛选，
    // 避免返回时被重置回「全部」（曾出现：失败 tab 进详情，返回后跳回全部列表）
    var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(Filter.ALL) }
    var showBatchPreview by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    // 2026-10-21 粘贴升级（一键读剪贴板）：点按先看剪贴板——含 X 链接直接捕获
    // （单条走原提示、多条走批量汇总），没有链接才弹手输框并把剪贴板原文预填进去。
    // Android 12+ 首次读取会由系统弹出「已粘贴」提示，属正常。
    val pasteFromClipboard: () -> Unit = {
        val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager)?.primaryClip
        val clipText = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        } else {
            ""
        }
        val hasLink = clipText.isNotBlank() &&
            com.ed.edqiu.domain.BatchTweetUrlExtractor
                .extractWithOriginals(clipText).isNotEmpty()
        if (hasLink) {
            vm.captureText(clipText)
        } else {
            pasteText = if (clipText.length <= 2000) clipText else ""
            showPasteDialog = true
        }
    }
    var sortExpanded by remember { mutableStateOf(false) }
    var longPressedLink by remember { mutableStateOf<SavedLink?>(null) }
    var isFirstLoad by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    // iOS 大标题折叠：收件箱标题随列表滚动收缩为紧凑导航标题
    val titleCollapse by rememberTitleCollapseProgress(listState)
    val scope = rememberCoroutineScope()
    val showScrollTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 200 } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
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

    // 操作结果中央弹窗（2026-10-17）：下载结果/批量汇总/无效链接等带原因的操作反馈
    // 从底部胶囊升级为居中弹窗——胶囊仅保留瞬时状态类（已复制/正在下载进度等）
    var centerDialog by remember { mutableStateOf<FeedbackMessage?>(null) }

    LaunchedEffect(captureFeedback) {
        captureFeedback?.let {
            val msg = when (it) {
                ListViewModel.CaptureFeedback.Added -> "已保存到收件箱"
                ListViewModel.CaptureFeedback.Duplicate -> "这条链接已存在"
                ListViewModel.CaptureFeedback.NotTwitter -> "不是有效的 X/Twitter 链接"
            }
            // 2026-10-17：成功=瞬时状态走胶囊；无效链接=需要用户注意的错误 → 中央弹窗
            if (it == ListViewModel.CaptureFeedback.NotTwitter) {
                centerDialog = FeedbackMessage(msg, FeedbackKind.ERROR)
            } else {
                snackbar.show(
                    message = msg,
                    kind = when (it) {
                        ListViewModel.CaptureFeedback.Added -> FeedbackKind.SUCCESS
                        else -> FeedbackKind.NEUTRAL
                    }
                )
            }
            vm.clearFeedback()
        }
    }

    LaunchedEffect(actionFeedback) {
        actionFeedback?.let { feedback ->
            // 2026-10-17：asDialog（下载类结果，含失败原因）→ 中央弹窗；
            // 带撤销按钮 → 胶囊（可操作，不打断）；其余瞬时状态 → 胶囊
            when {
                feedback.asDialog -> centerDialog = FeedbackMessage(
                    text = feedback.message,
                    kind = when (feedback.success) {
                        true -> FeedbackKind.SUCCESS
                        false -> FeedbackKind.ERROR
                        null -> FeedbackKind.NEUTRAL
                    },
                )
                // 2026-10-24：撤销类消息补 kind → 走胶囊（胶囊已支持操作按钮，
                // M3 Snackbar 退役路径）；语义色沿用三态映射
                feedback.actionLabel != null -> snackbar.show(
                    message = feedback.message,
                    actionLabel = feedback.actionLabel,
                    onAction = feedback.onAction,
                    kind = when (feedback.success) {
                        true -> FeedbackKind.SUCCESS
                        false -> FeedbackKind.ERROR
                        null -> FeedbackKind.NEUTRAL
                    }
                )
                else -> snackbar.show(
                    message = feedback.message,
                    kind = when (feedback.success) {
                        true -> FeedbackKind.SUCCESS
                        false -> FeedbackKind.ERROR
                        null -> FeedbackKind.NEUTRAL
                    }
                )
            }
            vm.clearActionFeedback()
        }
    }

    // 2026-09-15：单条/批量下载的进行中反馈统一由底部进度胶囊承担
    // （「下载中 · 剩余 N 条 · P%」，实时待下载数 + 实时进度），
    // 原「正在下载，请稍候…」Snackbar 已由胶囊替代删除。

    val shown = remember(links, filter) {
        when (filter) {
            Filter.ALL -> links
            Filter.PENDING -> links.filter { it.status == LinkStatus.PENDING }
            Filter.DOWNLOADED -> links.filter { it.status == LinkStatus.DOWNLOADED }
            // 「失败」tab 同时收纳推文不存在（DELETED）的死链，方便用户统一清理
            Filter.FAILED -> links.filter {
                it.status == LinkStatus.FAILED || it.status == LinkStatus.DELETED
            }
        }
    }

    // 是否已全选当前列表（全选后批量下载 → 确认即退出批量模式）
    val allSelected = shown.isNotEmpty() && selectedIds.containsAll(shown.map { it.tweetId })

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
                    onPaste = pasteFromClipboard,
                    onToggleSelection = vm::toggleSelectionMode,
                    selectionMode = selectionMode,
                    selectedCount = selectedIds.size,
                    onSelectAll = { vm.toggleSelectAll(shown.map { it.tweetId }) },
                    allSelected = allSelected,
                    batchEnabled = selectedIds.isNotEmpty(),
                    onBatchCopy = {
                        val text = links
                            .filter { it.tweetId in selectedIds }
                            .joinToString("\n") { it.rawUrl }
                        copyToClipboard(context, "Edqiu 批量链接", text)
                        snackbar.show("已复制 ${selectedIds.size} 条链接", kind = FeedbackKind.SUCCESS)
                    },
                    onBatchDownload = { showBatchPreview = true },
                    onBatchDelete = vm::deleteSelected,
                    // 2026-10-24：统计总览卡（数据与「全部下载」动作合一）移入固定头部区，
                    // 随滚动收缩折叠；右上角「下载」按钮退役（动作进了统计卡）
                    pendingCount = links.count { it.status == LinkStatus.PENDING },
                    downloadedCount = links.count { it.status == LinkStatus.DOWNLOADED },
                    failedCount = links.count {
                        it.status == LinkStatus.FAILED || it.status == LinkStatus.DELETED
                    },
                    onDownloadAll = vm::downloadAllPending,
                    collapse = titleCollapse
                )

                // ── 方案A 软着陆（2026-10-22）：列表边缘渐隐缓冲 ──
                // 卡片撞顶（header 交界）与撞底（滑入底栏玻璃前）不再硬切，
                // 被两层背景色调渐变温柔接住；scrim 不参与布局、不挡点击
                Box(modifier = Modifier.fillMaxSize()) {
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
                    // ── 2026-10-24：统计卡已上移固定区（InboxOverviewCard 收缩折叠），
                    // 列表首项只留搜索行（偶发动作，随内容滚走）
                    item(key = "inbox_search") {
                        InboxListHeader(
                            searchQuery = searchQuery,
                            onSearchChange = vm::setSearchQuery,
                            sortExpanded = sortExpanded,
                            onSortExpandedChange = { sortExpanded = it },
                            onSortSelected = {
                                vm.setSortOrder(it)
                                sortExpanded = false
                            }
                        )
                    }
                    stickyHeader(key = "inbox_filters") {
                        InboxFilterBar(
                            selectedFilter = filter,
                            onFilterChange = { filter = it }
                        )
                    }
                    if (isFirstLoad && links.isEmpty()) {
                        items(4) {
                            SkeletonCard(modifier = Modifier)
                        }
                    } else if (shown.isEmpty()) {
                        item {
                            EmptyStateCard(
                                searching = searchQuery.isNotBlank(),
                                onPaste = pasteFromClipboard
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
                                onQuickDelete = if (link.status == LinkStatus.FAILED || link.status == LinkStatus.DELETED) {
                                    { vm.delete(link.tweetId) }
                                } else null,
                                onDownload = if (link.status == LinkStatus.PENDING || link.status == LinkStatus.FAILED) {
                                    { vm.requestDownload(link.tweetId) }
                                } else null,
                                downloadProgress = downloadProgress[link.tweetId]
                            )
                        }
                    }
                }

                // 边缘渐隐：顶部 24dp 三段缓曲线（标题行底缘不再僵硬）+ 底部 24dp 沉降带
                // 2026-10-25 白带修复：阴影式渐隐（原 background 白渐变在壁纸上=白色分界线）
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

            if (showScrollTop) {
                Surface(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    shape = CircleShape,
                    // 2026-10-10 固定配色：回顶钮改主题反色面（浅=石墨/深=浅灰，iOS 惯例）
                    color = MaterialTheme.colorScheme.inverseSurface,
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

            // 批量下载进行中：底部进度胶囊（非模态，不遮挡列表；悬浮 Tab 栏上方）
            AnimatedVisibility(
                visible = batchDownloadState?.running == true,
                enter = fadeIn(tween(160)) + scaleIn(
                    initialScale = 0.88f,
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 420f)
                ),
                exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.9f, animationSpec = tween(140)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
                    .padding(bottom = navBarBottom + 96.dp)
            ) {
                BatchProgressCapsule(
                    total = batchDownloadState?.total ?: 0,
                    remaining = batchDownloadState?.remaining ?: 0,
                    currentProgress = batchDownloadState?.currentProgress
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
                snackbar.show("已复制链接", kind = FeedbackKind.SUCCESS)
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

    // 批量下载预览弹窗：展示所选条目缩略图，确认后再发起下载
    if (showBatchPreview) {
        val selectedLinks = links.filter { it.tweetId in selectedIds }
        BatchDownloadPreviewDialog(
            selected = selectedLinks,
            onDismiss = { showBatchPreview = false },
            onConfirm = {
                showBatchPreview = false
                // 全选后下载：视为整批操作，确认后直接退出批量模式回到主界面；
                // 手动多选下载则停留在批量界面继续管理
                if (allSelected) vm.toggleSelectionMode()
                vm.downloadSelected()
            }
        )
    }

    // 批量下载状态：进行中显示底部进度胶囊（实时剩余+百分比，不遮挡列表）；
    // 完成态汇总（成功/失败/跳过条数）→ 中央弹窗（2026-10-17：失败条数需要用户点掉确认）
    LaunchedEffect(batchDownloadState?.running, batchDownloadState?.success) {
        val state = batchDownloadState
        if (state != null && !state.running && state.success != null) {
            centerDialog = FeedbackMessage(
                text = state.message,
                kind = if (state.success == true) FeedbackKind.SUCCESS else FeedbackKind.ERROR,
            )
            vm.dismissBatchDownloadState()
        }
    }

    FeedbackDialog(message = centerDialog, onDismiss = { centerDialog = null })

    if (showPasteDialog) {
        SmoothAlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴 X/Twitter 链接") },
            text = {
                EdqiuTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    placeholder = "https://x.com/.../status/...",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
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
    onPaste: () -> Unit,
    onToggleSelection: () -> Unit,
    selectionMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    batchEnabled: Boolean,
    onBatchCopy: () -> Unit,
    onBatchDownload: () -> Unit,
    onBatchDelete: () -> Unit,
    // 2026-10-24：统计总览卡数据与动作（「全部下载」随待处理数进卡，不再占标题行按钮位）
    pendingCount: Int,
    downloadedCount: Int,
    failedCount: Int,
    onDownloadAll: () -> Unit,
    // 大标题折叠进度（2026-10-10）：0=展开（大标题+副标题）1=折叠（紧凑标题）
    collapse: Float = 0f
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
        // animateContentSize 已移除（2026-10-22 方案A）：它对滚动驱动的逐帧
        // 高度折叠有 spring 缓动，造成「折叠追赶滚动」的迟滞感；浏览↔批量
        // 切换的变形由下方 Crossfade 自己完成，不需要额外尺寸动画
    ) {
        // 顶部区域在「浏览态 ↔ 批量态」之间平滑变形：
        // 浏览态 = 标题 + 粘贴/批量入口；批量态 = 退出 + 已选计数 + 全选胶囊
        Crossfade(
            targetState = selectionMode,
            animationSpec = androidx.compose.animation.core.tween(220),
            label = "inbox_header_mode"
        ) { isSelecting ->
            if (isSelecting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeaderIconButton(
                        onClick = onToggleSelection,
                        contentDescription = "退出批量选择"
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = "批量管理",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 24.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (selectedCount > 0) "已选 $selectedCount 项，点卡片可增减" else "点击卡片选择要处理的链接",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    SelectAllCapsule(
                        allSelected = allSelected,
                        onClick = onSelectAll
                    )
                }
            } else {
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
                                fontSize = titleCollapseSize(collapse, 28.sp, 21.sp)
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.graphicsLayer {
                                // 折叠后半程整体上提，形成"缩进紧凑导航行"的空间感
                                translationY = collapse * (-2).dp.toPx()
                            }
                        )
                        // 方案A 空间折叠（2026-10-22）：副标题行高度随 collapse 收到 0——
                        // 空间真正让位给列表（此前只淡出，高度不动，滚动时「顶着一堵墙」）；
                        // 高度裁剪 + alpha 渐隐双通道，20dp 行高全程 180px 滚动行程内收完
                        if (collapse < 0.98f) {
                            Text(
                                text = "捕获链接、下载进度与历史记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier
                                    .height((20 * (1f - collapse)).dp)
                                    .graphicsLayer { alpha = titleCollapseAlpha(collapse) }
                            )
                        }
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
                        // 2026-10-24：「下载」按钮退役——全部下载动作并入统计总览卡
                        //（动作与待处理数据同位，语义更直观）；标题行只留 粘贴/多选
                        HeaderIconButton(
                            onClick = onToggleSelection,
                            contentDescription = "进入批量选择"
                        ) {
                            Text(
                                "多选",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        }

        // 统计总览卡（2026-10-24 重设计）：固定头部区内随滚动收缩——
        // 完整态（三指标+全部下载）↔ 紧凑态（进度条+摘要），与标题折叠同进度驱动，
        // 滚动全程连续变形无弹簧迟滞；批量模式下退场聚焦操作
        androidx.compose.animation.AnimatedVisibility(
            visible = !selectionMode,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()
        ) {
            InboxOverviewCard(
                pendingCount = pendingCount,
                downloadedCount = downloadedCount,
                failedCount = failedCount,
                onDownloadAll = onDownloadAll,
                collapse = collapse,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // 批量模式下在「待处理」统计版块上方展开操作栏（尺寸与搜索栏统一）
        AnimatedVisibility(
            visible = selectionMode,
            enter = fadeIn() + androidx.compose.animation.expandVertically(),
            exit = fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            BatchActionBar(
                selectedCount = selectedCount,
                enabled = batchEnabled,
                onCopy = onBatchCopy,
                onDownload = onBatchDownload,
                onDelete = onBatchDelete
            )
        }
    }
}

/**
 * 收件箱统计总览卡（2026-10-24 重设计）：数据与「全部下载」动作合一 + 滚动收缩折叠。
 *
 * - 完整态（collapse=0）：三指标（待处理/已完成/失败，语义色圆点+tnum 等宽数字）
 *   + 第 4 槽「全部下载」（下载图标+待处理数，动作与数据同位）；底部 3dp 完成度条常驻
 * - 紧凑态（collapse=1）：指标行收 0，浮出 22dp 摘要行（mini 完成度条 + "283/312"）
 *   ——统计信息以最密度形态留在导航区，不随列表滚走丢失
 * - 过渡：全部由滚动进度直接驱动（高度/alpha 双通道线性映射），无弹簧补间 =
 *   跟手零迟滞（与标题折叠同一驱动源，2026-10-22 方案A 移除 animateContentSize 教训）
 */
@Composable
private fun InboxOverviewCard(
    pendingCount: Int,
    downloadedCount: Int,
    failedCount: Int,
    onDownloadAll: () -> Unit,
    collapse: Float,
    modifier: Modifier = Modifier
) {
    val inboxTotal = (pendingCount + downloadedCount + failedCount).coerceAtLeast(1)
    val doneFraction = (downloadedCount.toFloat() / inboxTotal).coerceIn(0f, 1f)
    val progressBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary
        )
    )
    GlassSurface(
        // 2026-10-24 对比度加固：L1→L2——统计卡是信息密集组件，文字需要稳定基座；
        // L1 采样层在「亮壁纸+深色主题」组合下透出亮色壁纸把浅色文字洗掉（用户实报）。
        // L2 表面更实，文字对比不依赖壁纸明暗；液态感由折射边缘与色散保留
        tier = GlassTier.L2,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // ── 完整态：指标行 + 全部下载槽（随 collapse 收到 0 高）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 2026-10-25：46→54dp——第四槽（图标+数字+label）比三列指标高，
                    // 46dp 时 label「全部下载」被卡片下缘裁掉（用户实报文字丢失）
                    .height((54 * (1f - collapse)).dp)
                    .graphicsLayer { alpha = 1f - collapse }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricItem(
                    label = "待处理",
                    value = pendingCount.toString(),
                    dotColor = statusColor(LinkStatus.PENDING),
                    emphasize = pendingCount > 0
                )
                MetricDivider()
                MetricItem(
                    label = "已完成",
                    value = downloadedCount.toString(),
                    dotColor = statusColor(LinkStatus.DOWNLOADED)
                )
                MetricDivider()
                MetricItem(
                    label = "失败",
                    value = failedCount.toString(),
                    dotColor = statusColor(LinkStatus.FAILED),
                    emphasize = failedCount > 0
                )
                MetricDivider()
                // 第 4 槽：全部下载（动作与待处理数据同位）——有待处理时主色强调，
                // 无待处理时降透明度禁用（不再是永远可点的孤立按钮）
                val active = pendingCount > 0
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .graphicsLayer { alpha = if (active) 1f else 0.35f }
                        .pressableNoRipple(enabled = active) { onDownloadAll() }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            pendingCount.toString(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.02).sp,
                            color = MaterialTheme.colorScheme.primary,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "全部下载",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // ── 紧凑态：摘要行（mini 完成度条 + 计数，随 collapse 浮出）──
            if (collapse > 0.02f) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((24 * collapse).dp)
                        .graphicsLayer { alpha = collapse }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(doneFraction)
                                .fillMaxHeight()
                                .background(progressBrush)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "$downloadedCount/$inboxTotal",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                    )
                }
            }
            // ── 底部完成度条（完整态常驻，与紧凑态摘要条无缝衔接）──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            ) {
                if (downloadedCount > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(doneFraction)
                            .fillMaxHeight()
                            .background(progressBrush)
                    )
                }
            }
        }
    }
}

/**
 * 列表内滚动头部（2026-10-24 瘦身）：只留搜索框 + 排序入口。
 *
 * 统计卡 2026-10-24 移入固定头部区（InboxOverviewCard，随滚动收缩折叠）——
 * 搜索是「偶发动作」随内容滚走（iOS large title 语义），统计是「常态信息」
 * 以紧凑形态留在导航区，两类信息各归其位。
 */
@Composable
private fun InboxListHeader(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortExpanded: Boolean,
    onSortExpandedChange: (Boolean) -> Unit,
    onSortSelected: (LinkSortOrder) -> Unit
) {
    Column {
        // 搜索框 + 排序入口
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
                                .pressableNoRipple { onSearchChange("") }
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
                            .pressableNoRipple { onSortExpandedChange(true) },
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
    }
}

/**
 * 吸顶筛选行（2026-10-22 方案A v2）：stickyHeader 常驻。
 *
 * 2026-10-22 用户反馈：去掉外部长方形玻璃包围——胶囊直接浮在壁纸上，
 * 与列表卡片同语言（无通栏底色块）；胶囊间缝隙透出滚动内容属于「透」的自然延伸。
 */
@Composable
private fun InboxFilterBar(
    selectedFilter: Filter,
    onFilterChange: (Filter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Filter.entries.forEach { f ->
            val selected = f == selectedFilter
            GlassSurface(
                tier = if (selected) GlassTier.L2 else GlassTier.L1,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .pressableNoRipple { onFilterChange(f) }
            ) {
                Text(
                    text = f.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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

/**
 * 统计指标单元（2026-10-21 重设计）。
 *
 * - 状态色圆点：与 [statusColor] 语义同源（橙/绿/红），数字颜色不再各自为政
 * - tabular-nums 等宽数字：计数变化时不抖动（iOS 数字排版惯例）
 * - [emphasize]：有待处理/失败时数字提为语义色并加重——异常状态自己浮出来，
 *   平静态保持中性（主次分明，非异常不抢眼）
 */
@Composable
private fun MetricItem(
    label: String,
    value: String,
    dotColor: Color,
    emphasize: Boolean = false
) {
    val accentColor = if (emphasize) dotColor else MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = if (value == "0") 0.30f else 0.95f))
            )
            Text(
                value,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.02).sp,
                color = accentColor,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 渐变淡出分隔线：两端透明中段显形，比实线更轻（2026-10-21 统计卡重设计配套）。 */
@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(30.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
                        Color.Transparent
                    )
                )
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
            .pressableNoRipple { onClick() }
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

/**
 * 批量下载进行中底部胶囊（2026-09-14）：非模态进度提示，替代原中央转圈弹窗。
 * 深色玻璃 pill + 小转圈 + 条数，悬浮于底部导航之上，不遮挡列表视野；
 * 完成后由结果胶囊接管（running=false 时本胶囊退场）。
 */
@Composable
private fun BatchProgressCapsule(total: Int, remaining: Int, currentProgress: Int? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(50),
                ambientColor = Color.Black.copy(alpha = 0.16f),
                spotColor = Color.Black.copy(alpha = 0.24f)
            )
            .clip(RoundedCornerShape(50))
            .background(Color(0xF01A1D21))
            .padding(start = 14.dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = Color(0xFF8FB6FF)
        )
        Spacer(Modifier.size(9.dp))
        Text(
            // 2026-09-15 实时化：剩余待下载条数随完成递减（含下载中那一条），附当前条实时百分比
            text = buildString {
                append("下载中")
                append(" · 剩余 $remaining 条")
                currentProgress?.let { append(" · $it%") }
            },
            color = Color(0xFFEDEEF1),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 批量模式头部右侧的「全选」胶囊（L2 玻璃 + 主色，与筛选胶囊同语言）。 */
@Composable
private fun SelectAllCapsule(
    allSelected: Boolean,
    onClick: () -> Unit
) {
    GlassSurface(
        tier = GlassTier.L2,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = if (allSelected) "取消全选" else "全选",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 批量操作栏：批量模式下展开在「待处理」统计版块上方，
 * 尺寸与搜索栏统一（46dp 高、16dp 圆角、L1 玻璃）。
 * 左侧为已选计数胶囊，右侧为复制 / 下载 / 删除三个操作位；删除使用错误色区分。
 */
@Composable
private fun BatchActionBar(
    selectedCount: Int,
    enabled: Boolean,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .height(46.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = "已选 $selectedCount",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            BatchAction(Icons.Default.ContentCopy, "复制", enabled, onClick = onCopy)
            BatchAction(Icons.Default.Download, "下载", enabled, onClick = onDownload)
            BatchAction(Icons.Default.Delete, "删除", enabled, onClick = onDelete, destructive = true)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BatchAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .pressableNoRipple(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
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
        LinkStatus.DELETED -> "推文不存在"
    }
    SmoothAlertDialog(
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
                // 推文已不存在：无意义再下载，隐藏下载按钮，只留复制/删除
                if (link.status != LinkStatus.DELETED) {
                    TextButton(onClick = onDownload) { Text(if (link.status == LinkStatus.FAILED) "重试" else "下载") }
                }
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

/** [Filter] 的 rememberSaveable Saver：按 name 字符串存取，进程重建后也能恢复。 */
private val FilterSaver = listSaver<Filter, String>(
    save = { listOf(it.name) },
    restore = { Filter.valueOf(it[0]) }
)

/**
 * 批量下载预览弹窗：横向滚动展示所选条目的缩略图（无图时显示占位），
 * 确认后才发起批量下载。
 */
@Composable
private fun BatchDownloadPreviewDialog(
    selected: List<SavedLink>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SmoothAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量下载预览（${selected.size} 条）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selected.isEmpty()) {
                    Text("没有已选中的条目")
                } else {
                    Text(
                        "即将下载以下 ${selected.size} 条内容：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(selected, key = { it.tweetId }) { link ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.width(88.dp)
                            ) {
                                if (link.thumbnailUrl.isNullOrBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .size(88.dp, 66.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                } else {
                                    AsyncImage(
                                        model = link.thumbnailUrl,
                                        contentDescription = link.caption ?: link.tweetId,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(88.dp, 66.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                }
                                Text(
                                    text = link.authorName ?: link.authorId ?: link.tweetId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = selected.isNotEmpty()
            ) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

