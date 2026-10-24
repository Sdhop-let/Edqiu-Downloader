package com.ed.edqiu.ui.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.edqiu.data.database.SubscriptionEntity
import com.ed.edqiu.data.database.SubscriptionMessageEntity
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.ui.components.LinkCard
import com.ed.edqiu.ui.components.DynamicSwitch
import com.ed.edqiu.ui.components.EdqiuTextField
import com.ed.edqiu.ui.components.FeedbackDialog
import com.ed.edqiu.ui.components.SmoothAlertDialog
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.PillChip
import com.ed.edqiu.ui.components.rememberTitleCollapseProgress
import com.ed.edqiu.ui.components.titleCollapseAlpha
import com.ed.edqiu.ui.components.titleCollapseSize
import com.ed.edqiu.ui.util.pressableNoRipple
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 作品订阅页（2026-10 真正作品订阅，二级菜单页）。
 *
 * - 订阅列表：平台徽标（X / Bluesky / Instagram）+ handle + 启停开关 + 删除 +
 *   上次检测/上次发现新作品时间 + 逐条「检测」按钮；
 * - 手动添加：handle 输入 + 平台选择（主要入口在作者中心/播放器的订阅按钮）；
 * - 「立即检测」：触发一轮全量检测，新作品收录进本页订阅作品，点按后再下载；
 * - 不透明全屏页（与同步详情页同规格，不透出底部 Tab）。
 */
@Composable
fun SubscriptionScreen(
    vm: SubscriptionViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    // 订阅消息（2026-10-17 重做）：①新作品数量 ②引擎/失败状态 ③帖子列表（收件箱同款）
    val messages by vm.messages.collectAsStateWithLifecycle()
    val posts by vm.subscriptionPosts.collectAsStateWithLifecycle()
    val pendingNewCount by vm.pendingNewCount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.markMessagesRead() }

    FeedbackDialog(
        message = state.message,
        onDismiss = vm::consumeMessage
    )

    // 2026-10-24 审计补齐：订阅作品删除入口——长按作品卡确认后走收件箱同款 archive
    // 链路（此前订阅线全工程无删除入口，不想要的帖子永久留在库）
    var deletePostTarget by remember { mutableStateOf<String?>(null) }
    deletePostTarget?.let { tweetId ->
        SmoothAlertDialog(
            onDismissRequest = { deletePostTarget = null },
            title = { Text("删除作品") },
            text = { Text("从订阅作品中移除这条记录，可从回收站恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePost(tweetId)
                    deletePostTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletePostTarget = null }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 2026-10-21 液态玻璃：去掉实底，透出 GlassBackground 色场
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
            // 说明卡
            item {
                GlassSurface(
                    tier = GlassTier.L2,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "订阅作者，新作品自动收录",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "后台每 25 分钟轮询订阅作者的主页（支持 X / Bluesky / Instagram），" +
                                "发现的新作品收录到下方「订阅作品」列表，由你点按后再下载。" +
                                "可在作者中心或播放器内一键订阅。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = vm::detectNow,
                                enabled = !state.detecting,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.detecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text("检测中…")
                                } else {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text("立即检测全部")
                                }
                            }
                        }
                    }
                }
            }

            // ===== 订阅消息区（2026-10-17 重做）：数量 → 引擎状态 → 帖子 =====
            // ① 顶部：新作品数量（有作品或待下载时才显示）
            if (pendingNewCount > 0 || posts.isNotEmpty()) {
                item {
                    NewWorksHeaderCard(
                        pendingCount = pendingNewCount,
                        totalCount = posts.size,
                        onClearMessages = vm::clearMessages,
                    )
                }
            }
            // ② 引擎/失败状态：逐作者上次检测 + 结果
            if (subs.isNotEmpty()) {
                item { EngineStatusCard(subs = subs, events = messages) }
            }
            // ③ 帖子列表：与收件箱同款 LinkCard（详情=浏览器打开，待处理/失败可直接下载）
            if (posts.isNotEmpty()) {
                item {
                    Text(
                        "订阅作品（${posts.size}）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                items(posts, key = { it.tweetId }) { link ->
                    LinkCard(
                        link = link,
                        onClick = { openInBrowser(context, link.rawUrl) },
                        // 2026-10-24：长按删除订阅作品（此前订阅线无任何删除入口）
                        onLongClick = { deletePostTarget = link.tweetId },
                        onDownload = if (link.status == LinkStatus.PENDING ||
                            link.status == LinkStatus.FAILED
                        ) {
                            { vm.downloadPost(link.tweetId) }
                        } else null,
                    )
                }
            }

            // 手动添加
            item {
                GlassSurface(
                    tier = GlassTier.L1,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "手动添加订阅",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SubPlatform.entries.forEach { p ->
                                PillChip(
                                    label = p.label,
                                    selected = state.platform == p,
                                    onClick = { vm.setPlatform(p) },
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            EdqiuTextField(
                                value = state.handleInput,
                                onValueChange = vm::setHandleInput,
                                label = "作者 handle（不含 @）",
                                placeholder = when (state.platform) {
                                    SubPlatform.TWITTER -> "elonmusk"
                                    SubPlatform.BLUESKY -> "author.bsky.social"
                                    SubPlatform.INSTAGRAM -> "author"
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = vm::addSubscription,
                                shape = RoundedCornerShape(14.dp),
                            ) { Text("订阅") }
                        }
                    }
                }
            }

            // 订阅列表
            if (subs.isEmpty()) {
                item {
                    GlassSurface(
                        tier = GlassTier.L1,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "还没有订阅任何作者——去「作者中心」或播放器里一键订阅",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            items(subs, key = { it.platform + ":" + it.screenName }) { sub ->
                SubscriptionRow(
                    sub = sub,
                    onToggle = { vm.toggleSubscription(sub) },
                    onRemove = { vm.removeSubscription(sub) },
                    onDetect = { vm.detectOne(sub) },
                    onSetStartFrom = { at -> vm.setStartFromAt(sub, at) },
                )
            }
        }
    }
}

// ================= 列表行 =================

@Composable
private fun SubscriptionRow(
    sub: SubscriptionEntity,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onDetect: () -> Unit,
    onSetStartFrom: (Long?) -> Unit,
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlatformBadge(sub.platform)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "@${sub.screenName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append("上次检测：")
                            append(sub.lastCheckedAt?.let { formatTime(it) } ?: "未检测")
                            append(" · 上次发现：")
                            append(sub.lastVideoAt?.let { formatTime(it) } ?: "无")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDetect) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "检测该作者",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.NotificationsActive,
                        contentDescription = "删除订阅",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 2026-10-24 文案修正：实际行为=自动入库、点按下载（capture-only）
                    if (sub.enabled) "订阅中 · 新作品自动入库，点按下载" else "已暂停 · 检测轮询跳过",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sub.enabled) Color(0xFF1F8A3D) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                DynamicSwitch(checked = sub.enabled, onCheckedChange = { onToggle() })
            }
            // 2026-10-17：自定义起始检测日期（只捞该日期后发布的新作品）
            StartFromChip(startFromAt = sub.startFromAt, onPick = onSetStartFrom)
            // 2026-10-17：检测失败/降级原因透出行——不再把「通道挂了」伪装成「暂无新作品」
            if (!sub.lastError.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFFB26A00),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = sub.lastError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB26A00),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformBadge(platform: String) {
    val (label, color) = when (platform) {
        SubscriptionEntity.PLATFORM_BLUESKY -> "BSky" to Color(0xFF1185FE)
        SubscriptionEntity.PLATFORM_INSTAGRAM -> "IG" to Color(0xFFC13584)
        else -> "X" to Color(0xFF1D9BF0)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                text = "作品订阅",
                fontSize = titleCollapseSize(collapse, 24.sp, 18.sp),
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "订阅作者主页 · 新作品自动入库",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = titleCollapseAlpha(collapse) }
            )
        }
    }
}

/**
 * 订阅起始日期选择（2026-10-17）：用户自定义（如 10-17）后，轮询只把该日期**之后**
 * 发布的推文视为新作品。「不限」= 全部新帖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartFromChip(startFromAt: Long?, onPick: (Long?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val label = startFromAt?.let {
        "起始：" + SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(it))
    } ?: "起始日期：不限"
    TextButton(
        onClick = { showPicker = true },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showPicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = startFromAt ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        onPick(null)
                        showPicker = false
                    }) { Text("不限") }
                    TextButton(onClick = {
                        onPick(dateState.selectedDateMillis)
                        showPicker = false
                    }) { Text("确定") }
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

// ==================== 订阅消息区（2026-10-17 重做：数量/引擎状态/帖子）====================

/** 事件类型 → 状态色点（绿=新增/恢复，红=失败，琥珀=降级提示）。 */
private fun messageColor(kind: String): Color = when (kind) {
    SubscriptionMessageEntity.KIND_NEW_WORKS,
    SubscriptionMessageEntity.KIND_RECOVERED -> Color(0xFF1F8A3D)
    SubscriptionMessageEntity.KIND_FAILURE -> Color(0xFFC5311F)
    else -> Color(0xFFB26A00)
}

/**
 * ① 顶部：新作品数量卡——待下载订阅作品计数 + 事件记录清理。
 */
@Composable
private fun NewWorksHeaderCard(
    pendingCount: Int,
    totalCount: Int,
    onClearMessages: () -> Unit,
) {
    GlassSurface(
        tier = GlassTier.L2,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (pendingCount > 0) "$pendingCount 个新作品待下载" else "订阅作品已全部处理",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "共收录 $totalCount 条订阅作品 · 点按下方帖子即可下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onClearMessages,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("清空事件", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * ② 引擎/失败状态卡：逐作者上次检测结果 + 最近事件（失败出现/失败消失/新作品）。
 */
@Composable
private fun EngineStatusCard(
    subs: List<SubscriptionEntity>,
    events: List<SubscriptionMessageEntity> = emptyList(),
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "检测状态",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            subs.forEach { sub ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (sub.lastError.isNullOrBlank()) Color(0xFF1F8A3D)
                                else Color(0xFFC5311F)
                            ),
                    )
                    Text(
                        text = "@" + sub.screenName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = sub.lastCheckedAt?.let { "检测于 " + formatTime(it) } ?: "未检测",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!sub.lastError.isNullOrBlank()) {
                    Text(
                        text = "⚠ " + sub.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB26A00),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            if (events.isNotEmpty()) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
                    thickness = 0.5.dp,
                )
                Text(
                    "最近事件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                events.take(3).forEach { event ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(messageColor(event.kind)),
                        )
                        Text(
                            text = event.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatTime(event.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 用系统浏览器打开推文（订阅浮层内不叠详情浮层，单级浮层限制）。 */
private fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
