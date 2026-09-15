package com.ed.edqiu.ui.backup

import com.ed.edqiu.ui.util.pressableNoRipple
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.edqiu.backup.BackupScope
import com.ed.edqiu.backup.model.BackupTask
import com.ed.edqiu.backup.model.BackupTaskStatus
import com.ed.edqiu.backup.model.ProviderId
import com.ed.edqiu.ui.components.DynamicSwitch
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.FeedbackMessage
import com.ed.edqiu.ui.components.InlineFeedbackBar
import com.ed.edqiu.ui.components.GlassTier
import kotlinx.coroutines.launch

/**
 * 网盘直连备份中心页。
 *
 * - 顶部：返回箭头 + 「网盘备份」品牌卡（GlassSurface L2 风格，E 徽章）；
 * - 网盘列表：每行显示登录状态（未配置 / 已授权 + token 脱敏尾部），点击进入授权流程；
 * - 备份设置：备份范围（全部/仅视频/仅图片）、手动备份、自动备份开关；
 * - 备份状态区：进行中任务进度、最近任务列表、失败重试。
 *
 * 授权 Dialog 按 [CloudBackupUiState.pendingAuthProviderId] 条件渲染：
 * 百度 → [BaiduAuthDialog]；阿里 → [AliAuthDialog]；123 / 自定义 WebDAV → [WebDavCredentialDialog]。
 */
@Composable
fun CloudBackupScreen(
    vm: CloudBackupViewModel,
    onBack: () -> Unit,
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    // 内联反馈：消息固定显示在备份设置卡片下方，5s 后动画消失（替代全局顶部 Snackbar）
    var inlineFeedback by remember { mutableStateOf<FeedbackMessage?>(null) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            inlineFeedback = FeedbackMessage(it, uiState.messageKind)
            vm.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader(onBack = onBack)
        BrandCard()

        // 网盘列表
        ProviderListCard(
            providers = uiState.providers,
            selectedProviderId = uiState.selectedProviderId,
            onProviderClick = vm::onProviderClick,
            onRefreshToken = vm::refreshToken,
        )

        // 已选中网盘的备份设置
        uiState.selectedProviderId?.let { providerId ->
            val provider = uiState.providers.find { it.id == providerId }
            if (provider != null && provider.authorized) {
                BackupSettingsCard(
                    displayName = provider.displayName,
                    scope = uiState.backupScope,
                    autoBackup = uiState.autoBackupEnabled,
                    running = uiState.running,
                    onScopeChange = vm::setBackupScope,
                    onAutoChange = vm::toggleAutoBackup,
                    onBackupNow = vm::backupNow,
                    onClearAuth = { vm.clearAuth(providerId) },
                )
            } else if (provider != null && provider.id != ProviderId.CLOUDDRIVE2) {
                NotAuthorizedHint(displayName = provider.displayName)
            }
        }

        // 操作反馈：固定在备份设置卡片（含立即备份等按钮）下方，5s 后动画消失
        InlineFeedbackBar(
            message = inlineFeedback,
            onDismiss = { inlineFeedback = null }
        )

        // 备份状态区
        BackupStatusCard(
            tasks = uiState.tasks,
            running = uiState.running,
            lastSummary = uiState.lastSummary,
            onRetry = vm::retryFailed,
            onCancel = vm::cancelTask,
            onCleanup = { uiState.selectedProviderId?.let(vm::cleanupFinishedTasks) },
        )
    }

    // 授权 Dialog 路由
    when (uiState.pendingAuthProviderId) {
        ProviderId.BAIDU -> BaiduAuthDialog(
            state = uiState.baiduAuth,
            onDismiss = vm::cancelAuth,
            onCancelAuth = vm::cancelAuth,
            onSuccess = vm::onBaiduAuthSuccess,
        )

        ProviderId.ALIYUN -> AliAuthDialog(
            onDismiss = vm::cancelAuth,
            onTokenReceived = vm::onAliTokenReceived,
        )

        ProviderId.PAN123_OPEN -> uiState.pan123AuthorizeUrl?.let { url ->
            Pan123AuthDialog(
                authorizeUrl = url,
                onDismiss = vm::cancelAuth,
                onCodeReceived = vm::onPan123CodeReceived,
            )
        }

        ProviderId.PAN123 -> WebDavCredentialDialog(
            title = "登录 123 网盘",
            subtitle = "账号为手机号/邮箱，密码为 123 网盘网页端生成的 WebDAV 密码。服务器地址可留空（按账号 ID 自动拼接）。",
            serverUrlLabel = "服务器地址 / 账号 ID（可选）",
            serverUrlPlaceholder = "留空自动拼接 webdav-{id}.pd1.123pan.cn",
            onSubmit = { url, user, pass, path ->
                vm.submitWebDavCredential(ProviderId.PAN123, url, user, pass, path)
            },
            onDismiss = vm::cancelAuth,
        )

        ProviderId.WEBDAV -> WebDavCredentialDialog(
            title = "配置自定义 WebDAV",
            subtitle = "填写任意支持 WebDAV 的云盘或自建服务地址、账号与密码（如 NAS / alist / Nextcloud）。",
            serverUrlLabel = "WebDAV 服务器地址",
            serverUrlPlaceholder = "https://dav.example.com",
            onSubmit = { url, user, pass, path ->
                vm.submitWebDavCredential(ProviderId.WEBDAV, url, user, pass, path)
            },
            onDismiss = vm::cancelAuth,
        )
    }
}

// ================= 顶部与品牌卡 =================

@Composable
private fun BackHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onBack),
            shape = CircleShape,
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
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "网盘备份",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "把下载媒体直连备份到云盘",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BrandCard() {
    GlassSurface(
        tier = GlassTier.L2,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "E",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "网盘备份",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.08.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "百度 / 123 / 阿里云盘直连，WebDAV 兼容任意云盘",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                ) {
                    Text(
                        text = "CLOUD",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// ================= 网盘列表 =================

@Composable
private fun ProviderListCard(
    providers: List<ProviderUiState>,
    selectedProviderId: String?,
    onProviderClick: (String) -> Unit,
    onRefreshToken: (String) -> Unit,
) {
    SectionGlass(title = "网盘列表") {
        if (providers.isEmpty()) {
            Text(
                text = "正在加载网盘…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@SectionGlass
        }
        providers.forEachIndexed { index, provider ->
            ProviderRow(
                provider = provider,
                selected = provider.id == selectedProviderId,
                onClick = { onProviderClick(provider.id) },
                onRefresh = { onRefreshToken(provider.id) },
            )
            if (index < providers.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderUiState,
    selected: Boolean,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                } else {
                    Modifier
                }
            )
            .pressableNoRipple { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        ProviderBadge(name = provider.displayName)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(color = statusColor(provider.statusText))
                Text(
                    text = provider.statusText + if (provider.maskSuffix.isNotBlank()) " · ${provider.maskSuffix}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 已授权网盘提供「刷新校验」快捷按钮
        if (provider.authorized && provider.id != ProviderId.CLOUDDRIVE2) {
            Surface(
                modifier = Modifier
                    .size(34.dp)
                    .pressableNoRipple { onRefresh() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新登录状态",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 网盘字母徽章（B / 1 / A / C / W，颜色随 provider 固定）。 */
@Composable
private fun ProviderBadge(name: String) {
    val letter = when {
        name.contains("百度") -> "B"
        name.startsWith("123") -> "1"
        name.contains("阿里") -> "A"
        name.contains("CloudDrive") -> "C"
        else -> "W"
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = letter,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ================= 备份设置 =================

@Composable
private fun BackupSettingsCard(
    displayName: String,
    scope: BackupScope,
    autoBackup: Boolean,
    running: Boolean,
    onScopeChange: (BackupScope) -> Unit,
    onAutoChange: (Boolean) -> Unit,
    onBackupNow: () -> Unit,
    onClearAuth: () -> Unit,
) {
    SectionGlass(title = "备份设置 · $displayName") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "备份范围",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScopeChip("全部", scope == BackupScope.ALL, enabled = !running) { onScopeChange(BackupScope.ALL) }
                ScopeChip("仅视频", scope == BackupScope.VIDEO, enabled = !running) { onScopeChange(BackupScope.VIDEO) }
                ScopeChip("仅图片", scope == BackupScope.IMAGE, enabled = !running) { onScopeChange(BackupScope.IMAGE) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自动备份",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "开启后每天后台自动上传新文件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DynamicSwitch(checked = autoBackup, onCheckedChange = onAutoChange)
            }
            Button(
                onClick = onBackupNow,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (running) "备份进行中…" else "立即备份")
            }
            TextButton(onClick = onClearAuth, modifier = Modifier.align(Alignment.End)) {
                Text("清除登录状态", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun NotAuthorizedHint(displayName: String) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = "完成「$displayName」授权后可设置备份范围、手动备份与自动备份",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ================= 备份状态区 =================

@Composable
private fun BackupStatusCard(
    tasks: List<BackupTask>,
    running: Boolean,
    lastSummary: com.ed.edqiu.backup.model.BackupSummary?,
    onRetry: () -> Unit,
    onCancel: (String) -> Unit,
    onCleanup: () -> Unit,
) {
    SectionGlass(title = "备份状态") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val active = tasks.firstOrNull { it.isActive }
            if (running && active != null) {
                // 进行中任务
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Filled.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "正在备份：${active.remotePath}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(active.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                LinearProgressIndicator(
                    progress = { active.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (tasks.none { it.isActive } && tasks.isNotEmpty()) {
                Text(
                    text = "队列空闲",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            lastSummary?.let { summary ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Filled.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "上次备份：成功 ${summary.succeeded} · 失败 ${summary.failed} · 跳过 ${summary.skipped}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val recent = tasks.takeLast(8)
            if (recent.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                recent.forEach { task -> TaskRow(task = task, onCancel = onCancel) }
            } else if (!running) {
                Text(
                    text = "暂无备份任务，点击网盘卡片进入设置后「立即备份」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (tasks.any { it.status == BackupTaskStatus.FAILED }) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("重试全部失败任务")
                }
            }

            TextButton(
                onClick = onCleanup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("清理已完成历史任务（30 天前）")
            }
        }
    }
}

@Composable
private fun TaskRow(task: BackupTask, onCancel: (String) -> Unit) {
    val statusColor = when (task.status) {
        BackupTaskStatus.DONE -> MaterialTheme.colorScheme.primary
        BackupTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        BackupTaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = when (task.status) {
                BackupTaskStatus.DONE -> Icons.Filled.CheckCircle
                BackupTaskStatus.FAILED -> Icons.Filled.ErrorOutline
                else -> Icons.Filled.Cloud
            },
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.remotePath,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.status == BackupTaskStatus.FAILED && !task.errorMessage.isNullOrBlank()) {
                Text(
                    text = task.errorMessage!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = statusLabel(task),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (task.isActive) {
            Text(
                text = "${(task.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
            )
            TextButton(onClick = { onCancel(task.taskId) }) { Text("取消") }
        }
    }
}

private fun statusLabel(task: BackupTask): String = when (task.status) {
    BackupTaskStatus.PENDING -> "等待上传"
    BackupTaskStatus.UPLOADING -> "上传中"
    BackupTaskStatus.DONE -> "已完成"
    BackupTaskStatus.FAILED -> "失败"
    BackupTaskStatus.CANCELLED -> "已取消"
}

// ================= WebDAV 授权弹窗 =================

@Composable
private fun WebDavCredentialDialog(
    title: String,
    subtitle: String,
    serverUrlLabel: String,
    serverUrlPlaceholder: String,
    onSubmit: suspend (serverUrl: String, username: String, password: String, remotePath: String) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remotePath by remember { mutableStateOf("Edqiu") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(serverUrlLabel) },
                    placeholder = { Text(serverUrlPlaceholder) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("账号") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码 / WebDAV 密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("远程根目录（可选）") },
                    singleLine = true,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotBlank() && !submitting,
                onClick = {
                    scope.launch {
                        submitting = true
                        error = null
                        try {
                            onSubmit(serverUrl, username, password, remotePath)
                                .onFailure { error = it.message ?: "配置失败" }
                        } catch (e: Exception) {
                            error = e.message ?: "配置失败"
                        } finally {
                            submitting = false
                        }
                    }
                },
            ) { Text(if (submitting) "验证中…" else "保存并验证") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )
}

// ================= 通用小组件 =================

@Composable
private fun SectionGlass(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun statusColor(statusText: String): Color =
    when {
        statusText.contains("已授权") || statusText.contains("本机已运行") -> Color(0xFF16A34A)
        statusText.contains("已配置") -> Color(0xFFF59E0B)
        statusText.contains("本机未检测到") -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
