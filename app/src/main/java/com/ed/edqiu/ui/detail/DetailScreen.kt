package com.ed.edqiu.ui.detail

import com.ed.edqiu.ui.util.pressableNoRipple
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.StatusBadge
import com.ed.edqiu.ui.navigation.LocalSnackbarController
import com.ed.edqiu.ui.util.copyToClipboard
import com.ed.edqiu.ui.util.formatRelativeTime
import com.ed.edqiu.ui.util.openTwitterProfile
import com.ed.edqiu.ui.util.openTwitterStatus

@Composable
fun DetailScreen(
    vm: DetailViewModel,
    tweetId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    val link by vm.link.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val downloadFeedback by vm.downloadFeedback.collectAsStateWithLifecycle()
    // 实时下载进度（tweetId → 0..99）：下载按钮转圈时显示当前百分比
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()

    LaunchedEffect(tweetId) { vm.load(tweetId) }
    // 下载结果走底部玻璃胶囊（2026-09-15：原居中 AlertDialog 打断浏览，状态类反馈统一胶囊化）
    LaunchedEffect(downloadFeedback) {
        downloadFeedback?.let { feedback ->
            snackbar.show(
                message = feedback.message,
                kind = when (feedback.success) {
                    true -> FeedbackKind.SUCCESS
                    false -> FeedbackKind.ERROR
                    null -> FeedbackKind.NEUTRAL
                }
            )
            vm.clearDownloadFeedback()
        }
    }

    val data = link
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        if (data == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailTopBar(
                data = data,
                onBack = onBack,
                onDownload = vm::requestDownload,
                downloading = downloading,
                downloadProgress = downloadProgress[data.tweetId],
                onOpenProfile = { handle ->
                    if (!openTwitterProfile(context, handle)) snackbar.show("无法打开作者主页")
                }
            )

            MediaPreview(data = data)

            // 视频/图片预览下方的文案（视频资源显示其推文文案，长文案可折叠）
            if (!data.caption.isNullOrBlank()) {
                InfoCard(title = "文案") {
                    CollapsibleCaption(caption = data.caption)
                }
            }

            AuthorCard(
                data = data,
                onOpenProfile = { handle ->
                    if (!openTwitterProfile(context, handle)) snackbar.show("无法打开作者主页")
                }
            )

            RecordsCard(data = data)

            OriginalLinkCard(
                data = data,
                onCopy = {
                    copyToClipboard(context, "Edqiu 链接", data.rawUrl)
                    snackbar.show("已复制链接")
                },
                onOpenTwitter = {
                    val success = openTwitterStatus(context, data.tweetId)
                    if (!success) snackbar.show("无法打开 X/Twitter")
                }
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 顶部导航栏：返回（左）· 蓝色胶囊作者名（中）· 下载（右），独立一行始终位于屏幕顶部。 */
@Composable
private fun DetailTopBar(
    data: SavedLink,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    downloading: Boolean,
    // 实时下载百分比（0..99）：下载中且有进度时按钮显示数字替代纯转圈
    downloadProgress: Int? = null,
    onOpenProfile: (String) -> Unit
) {
    val authorName = data.authorName ?: data.authorId ?: "未知作者"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavCircleButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        // 中间作者名（可点击跳作者主页）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(
                    if (data.authorId != null) {
                        Modifier.pressableNoRipple { onOpenProfile(data.authorId!!) }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = authorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .widthIn(max = 200.dp)
                )
            }
        }
        NavCircleButton(
            onClick = onDownload,
            // 已下载无需再下；推文不存在则下载无意义；下载中防重复点击（转圈显示进度）
            enabled = data.status != LinkStatus.DOWNLOADED &&
                data.status != LinkStatus.DELETED && !downloading
        ) {
            when {
                // 有实时进度：直接显示百分比数字（比纯转圈信息量更高）
                downloading && downloadProgress != null -> Text(
                    text = "$downloadProgress%",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                downloading -> CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
                else -> Icon(Icons.Default.Download, contentDescription = "下载")
            }
        }
    }
}

@Composable
private fun NavCircleButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.size(40.dp),
        content = content
    )
}

/** 媒体预判区：大尺寸视频/图片预览图卡片，右下角叠加状态徽章。 */
@Composable
private fun MediaPreview(data: SavedLink) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(300.dp)
            .clip(RoundedCornerShape(24.dp))
    ) {
        // 背景：无缩略图时回退到品牌渐变
        if (!data.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = data.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF101417), Color(0xFF2563EB), Color(0xFF14B8A6))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(52.dp)
                )
            }
        }
        // 底部状态徽章
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 3.dp
        ) {
            StatusBadge(status = data.status, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** 作者卡片：头像 + 名称/推特 ID + 主页简介，最右侧为主页跳转按钮。 */
@Composable
private fun AuthorCard(data: SavedLink, onOpenProfile: (String) -> Unit) {
    val handle = data.authorId
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像可点击跳作者主页
            if (handle != null) {
                Box(modifier = Modifier.pressableNoRipple { onOpenProfile(handle) }) {
                    Avatar(data = data)
                }
            } else {
                Avatar(data = data)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.authorName ?: handle ?: "未知作者",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = handle?.let { "@$it" } ?: "推文 ${data.tweetId.takeLast(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 作者主页简介（fxtwitter 提供，尽力而为）
                if (!data.authorBio.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = data.authorBio,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 最右侧：跳转作者主页按钮
            IconButton(
                onClick = { handle?.let(onOpenProfile) },
                enabled = handle != null,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "打开作者主页")
            }
        }
    }
}

@Composable
private fun Avatar(data: SavedLink, bordered: Boolean = true) {
    val size = if (bordered) 44.dp else 40.dp
    val borderModifier = if (bordered) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
    } else {
        Modifier.border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
    }
    if (!data.avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = data.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .then(borderModifier)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFF38BDF8))
                .then(borderModifier),
            contentAlignment = Alignment.Center
        ) {
            Text("X", fontWeight = FontWeight.Black, color = Color.White, fontSize = 15.sp)
        }
    }
}

/** 记录卡片：捕获时间、下载时间及下载诊断信息。成功下载时捕获/下载时间并排展示。 */
@Composable
private fun RecordsCard(data: SavedLink) {
    InfoCard(title = "记录") {
        if (data.downloadedAt != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SnapshotTimeCell("捕获时间", formatRelativeTime(data.savedAt), Modifier.weight(1f))
                SnapshotTimeCell("下载时间", formatRelativeTime(data.downloadedAt), Modifier.weight(1f))
            }
        } else {
            // 未成功：捕获时间、最近尝试、尝试次数在同一行压缩展示
            val items = buildList {
                add("捕获时间" to formatRelativeTime(data.savedAt))
                data.lastAttemptAt?.let { add("最近尝试" to formatRelativeTime(it)) }
                if (data.attemptCount > 0) add("尝试次数" to "${data.attemptCount} 次")
            }
            if (items.size >= 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items.forEach { (label, value) ->
                        SnapshotTimeCell(label, value, Modifier.weight(1f))
                    }
                }
            } else {
                DetailRow("捕获时间", formatRelativeTime(data.savedAt))
            }
        }
        data.nextRetryAt?.let { DetailRow("自动重试", formatRelativeTime(it)) }
        if (!data.lastError.isNullOrBlank()) {
            // 推文不存在不是「错误」：用灰色而非错误红，弱化警示感
            val valueColor = if (data.status == LinkStatus.DELETED) {
                com.ed.edqiu.ui.theme.statusColor(LinkStatus.DELETED)
            } else {
                MaterialTheme.colorScheme.error
            }
            DetailRow(
                "失败原因",
                data.lastError,
                valueColor = valueColor
            )
        }
    }
}

/** 等宽时间小格：标签（小字）+ 时间（小字），用于捕获/下载时间并排展示。 */
@Composable
private fun SnapshotTimeCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 原始链接卡片：链接文本 + 复制按钮 + 跳转该推文详细页按钮。 */
@Composable
private fun OriginalLinkCard(
    data: SavedLink,
    onCopy: () -> Unit,
    onOpenTwitter: () -> Unit
) {
    InfoCard(title = "原始链接") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.rawUrl,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
            IconButton(
                onClick = onCopy,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制链接")
            }
            IconButton(
                onClick = onOpenTwitter,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "打开该推文")
            }
        }
    }
}

/** 长文案可折叠：默认收起到 3 行，点击「展开/收起」切换，带高度过渡动画。 */
@Composable
private fun CollapsibleCaption(caption: String) {
    var expanded by remember { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodyLarge.copy(
        lineHeight = 26.sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(durationMillis = 220))
    ) {
        Column {
            SelectionContainer {
                Text(
                    text = caption,
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    onTextLayout = { layout ->
                        if (!expanded) overflowing = layout.hasVisualOverflow
                    }
                )
            }
            if (overflowing || expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .pressableNoRipple { expanded = !expanded }
                        .padding(vertical = 2.dp)
                )
            }
        }
    }
}

/** 玻璃信息卡：统一的分组标题（弱化、字距加宽）+ 内容槽位。 */
@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

/**
 * 键值信息行：标签弱色窄列 + 数值主色宽列。
 * @param valueColor 数值颜色（默认主文本色；失败原因等传 error 色）
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
            modifier = Modifier.weight(1f)
        )
    }
}