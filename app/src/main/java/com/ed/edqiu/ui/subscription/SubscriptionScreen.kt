package com.ed.edqiu.ui.subscription

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.ed.edqiu.ui.components.DynamicSwitch
import com.ed.edqiu.ui.components.EdqiuTextField
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
 * 作品订阅页（2026-10 真正作品订阅，二级菜单页）。
 *
 * - 订阅列表：平台徽标（X / Bluesky / Instagram）+ handle + 启停开关 + 删除 +
 *   上次检测/上次发现新作品时间 + 逐条「检测」按钮；
 * - 手动添加：handle 输入 + 平台选择（主要入口在作者中心/播放器的订阅按钮）；
 * - 「立即检测」：触发一轮全量检测，新作品自动入收件箱并走下载链；
 * - 不透明全屏页（与同步详情页同规格，不透出底部 Tab）。
 */
@Composable
fun SubscriptionScreen(
    vm: SubscriptionViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()

    FeedbackDialog(
        message = state.message,
        onDismiss = vm::consumeMessage
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                            "订阅作者，新作品自动入库",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "后台每 25 分钟轮询订阅作者的主页（支持 X / Bluesky / Instagram），" +
                                "发现新作品自动加入收件箱并按下载链下载。可在作者中心或播放器内一键订阅。",
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
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
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
                    if (sub.enabled) "订阅中 · 新作品自动入库下载" else "已暂停 · 检测轮询跳过",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sub.enabled) Color(0xFF1F8A3D) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                DynamicSwitch(checked = sub.enabled, onCheckedChange = { onToggle() })
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

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
