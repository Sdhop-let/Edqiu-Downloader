package com.ed.edqiu.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ed.edqiu.data.model.ProxySettings
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.DownloadPathPreferences
import com.ed.edqiu.data.preferences.PreDownloadPreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.preferences.ThirdPartyApiPreferences
import com.ed.edqiu.data.proxy.ProxyDetector
import com.ed.edqiu.data.proxy.ProxyTester
import com.ed.edqiu.service.AppUpdateInfo
import com.ed.edqiu.service.AppUpdateService
import com.ed.edqiu.service.WebDavSyncService
import com.ed.edqiu.service.YoutubeDLService
import com.ed.edqiu.backup.provider.WebDavEngine
import com.ed.edqiu.backup.provider.WebDavCredential
import com.ed.edqiu.background.WebDavAutoBackupScheduler
import com.ed.edqiu.BuildConfig
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.FeedbackMessage
import com.ed.edqiu.ui.components.InlineFeedbackBar
import com.ed.edqiu.ui.components.DynamicSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
private val Accent = Color(0xFF0F766E)
private val Ink = Color(0xFF101417)
private val Muted = Color(0xFF64748B)
private val Soft = Color(0xFFF1F5F9)

/** 下载器设置分组标识（二级菜单入口用） */
object DlSection {
    const val PATH = "path"
    const val UPDATE = "update"
    const val NETWORK = "network"
    const val WEBDAV = "webdav"
    const val PREDOWNLOAD = "predownload"
    const val ABOUT = "about"
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    showBack: Boolean = false,
    section: String? = null,
    onOpenMediaBackup: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 内联反馈：操作结果固定显示在触发按钮所在卡片内，5s 后动画消失（不再在页面顶部挤压内容）
    var inlineFeedback by remember { mutableStateOf<FeedbackMessage?>(null) }

    val proxyPreferences = remember { ProxyPreferences(context) }
    val cookiePreferences = remember { CookiePreferences(context) }
    val pathPreferences = remember { DownloadPathPreferences(context) }
    val cloudSyncPreferences = remember { CloudSyncPreferences(context) }
    val preDownloadPrefs = remember { PreDownloadPreferences(context) }

    var updateStatus by remember { mutableStateOf<String?>(null) }
    var isUpdatingYtdlp by remember { mutableStateOf(false) }
    var appUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var isCheckingAppUpdate by remember { mutableStateOf(false) }
    var isDownloadingAppUpdate by remember { mutableStateOf(false) }
    var appUpdateProgress by remember { mutableStateOf(0f) }

    var proxyEnabled by remember { mutableStateOf(false) }
    var proxyHost by remember { mutableStateOf("127.0.0.1") }
    var proxyPort by remember { mutableStateOf("7890") }
    var proxyType by remember { mutableStateOf(ProxySettings.TYPE_HTTP) }
    var isTestingProxy by remember { mutableStateOf(false) }

    // 第三方解析兜底（P0-1 第三层）：端点留空 = 关闭
    val thirdPartyApiPreferences = remember { ThirdPartyApiPreferences(context) }
    var tpEndpoint by remember { mutableStateOf("") }
    var tpApiKey by remember { mutableStateOf("") }
    var tpConfigured by remember { mutableStateOf(false) }

    var authToken by remember { mutableStateOf("") }
    var ct0 by remember { mutableStateOf("") }
    var cookiesConfigured by remember { mutableStateOf(false) }

    var useCustomPath by remember { mutableStateOf(false) }
    var customPath by remember { mutableStateOf("") }
    var displayPath by remember { mutableStateOf("") }

    var useWebDavSync by remember { mutableStateOf(false) }
    var webDavServerUrl by remember { mutableStateOf("") }
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var webDavRemotePath by remember { mutableStateOf("Edqiu") }
    var webDavProviderId by remember { mutableStateOf("custom") }
    var lastSyncTime by remember { mutableLongStateOf(0L) }
    var syncedCount by remember { mutableIntStateOf(0) }
    var isWebDavSyncing by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionVerified by remember { mutableStateOf(false) }
    var autoBackupEnabled by remember { mutableStateOf(false) }
    var autoBackupDays by remember { mutableIntStateOf(1) }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(it, flags)
            }.isSuccess
            if (!persisted) {
                scope.launch { inlineFeedback = FeedbackMessage("无法持久化目录授权，保存失败，请重试", FeedbackKind.ERROR) }
                return@let
            }
            val path = uriToDisplayPath(context, it) ?: it.toString()
            displayPath = path
            customPath = it.toString()
            pathPreferences.saveCustomTreeUri(customPath, displayPath)
            useCustomPath = true
            scope.launch { inlineFeedback = FeedbackMessage("下载保存路径已更新", FeedbackKind.SUCCESS) }
        }
    }

    LaunchedEffect(Unit) {
        val proxy = proxyPreferences.getProxySettings()
        proxyEnabled = proxy.enabled
        proxyHost = proxy.host
        proxyPort = proxy.port.toString()
        proxyType = proxy.type

        tpEndpoint = thirdPartyApiPreferences.getEndpoint()
        tpApiKey = thirdPartyApiPreferences.getApiKey()
        tpConfigured = thirdPartyApiPreferences.isConfigured

        authToken = cookiePreferences.getAuthToken()
        ct0 = cookiePreferences.getCt0()
        cookiesConfigured = cookiePreferences.hasCookies()

        useCustomPath = pathPreferences.useCustomPath
        customPath = pathPreferences.customPath
        displayPath = pathPreferences.displayDownloadDir(context)

        useWebDavSync = cloudSyncPreferences.enabled
        webDavServerUrl = cloudSyncPreferences.serverUrl
        webDavUsername = cloudSyncPreferences.username
        webDavPassword = cloudSyncPreferences.password
        webDavRemotePath = cloudSyncPreferences.remotePath
        webDavProviderId = cloudSyncPreferences.providerId
        lastSyncTime = cloudSyncPreferences.lastSyncTime
        syncedCount = cloudSyncPreferences.syncedFileCount
        connectionVerified = cloudSyncPreferences.connectionVerified
        autoBackupEnabled = cloudSyncPreferences.autoBackupEnabled
        autoBackupDays = cloudSyncPreferences.autoBackupDays
    }

    LaunchedEffect(proxyEnabled, proxyHost, proxyPort) {
        proxyPreferences.saveProxySettings(
            ProxySettings(
                enabled = proxyEnabled,
                host = proxyHost.ifBlank { "127.0.0.1" },
                port = proxyPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 7890
            )
        )
    }

    fun checkAppUpdate() {
        if (isCheckingAppUpdate || isDownloadingAppUpdate) return
        scope.launch {
            isCheckingAppUpdate = true
            AppUpdateService.checkForUpdate(context)
                .onSuccess { info ->
                    if (info == null) inlineFeedback = FeedbackMessage("当前已是最新版本", FeedbackKind.NEUTRAL)
                    else appUpdateInfo = info
                }
                .onFailure { e ->
                    inlineFeedback = FeedbackMessage(
                        "检查更新失败：${e.message ?: "网络异常"}",
                        FeedbackKind.ERROR
                    )
                }
            isCheckingAppUpdate = false
        }
    }

    fun downloadAndInstallAppUpdate(info: AppUpdateInfo) {
        if (isDownloadingAppUpdate) return
        scope.launch {
            isDownloadingAppUpdate = true
            appUpdateProgress = 0f
            AppUpdateService.downloadApk(context, info) { appUpdateProgress = it }
                .onSuccess { apkFile ->
                    val installStarted = AppUpdateService.installApk(context, apkFile)
                    appUpdateInfo = null
                    inlineFeedback = FeedbackMessage(
                        if (installStarted) "系统安装器已打开，请按提示覆盖安装"
                        else "请允许安装未知来源应用后，再点击立即更新",
                        if (installStarted) FeedbackKind.SUCCESS else FeedbackKind.NEUTRAL
                    )
                }
                .onFailure { e ->
                    inlineFeedback = FeedbackMessage(
                        "下载新版本失败：${e.message ?: "网络异常"}",
                        FeedbackKind.ERROR
                    )
                }
            isDownloadingAppUpdate = false
        }
    }

    val pendingAppUpdate = appUpdateInfo
    if (pendingAppUpdate != null) {
        AlertDialog(
            onDismissRequest = { appUpdateInfo = null },
            title = { Text("发现新版本 ${pendingAppUpdate.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Text("新版本：${pendingAppUpdate.versionName} (${pendingAppUpdate.versionCode})")
                    if (pendingAppUpdate.publishedAt.isNotBlank()) Text("发布时间：${pendingAppUpdate.publishedAt}")
                    Text(
                        "下载在后台进行，完成后自动调用系统安装器，旧数据和本地媒体会保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                // 2026-09-15：确认即关闭弹窗并后台下载（原下载中禁用全部按钮且不可关闭，模态锁死）
                TextButton(onClick = {
                    appUpdateInfo = null
                    downloadAndInstallAppUpdate(pendingAppUpdate)
                }) { Text("立即更新") }
            },
            dismissButton = {
                TextButton(onClick = { appUpdateInfo = null }) { Text("稍后") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                if (showBack || section != null) {
                    // 二级页返回箭头
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(onClick = onBack),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                        shadowElevation = 0.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                } else {
                    SettingsHeader(
                        pathLabel = if (useCustomPath) "自定义目录" else "默认目录",
                        proxyEnabled = proxyEnabled,
                        cookiesConfigured = cookiesConfigured,
                        webDavEnabled = useWebDavSync
                    )
                }
            }

            if (section == null || section == DlSection.PATH) {
            item {
                SectionCard(title = "下载保存", subtitle = "设置媒体文件保存位置", icon = Icons.Outlined.Folder) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = Soft
                    ) {
                        SettingRow(
                            title = if (useCustomPath) "当前自定义路径" else "当前默认路径",
                            subtitle = displayPath.ifBlank { "默认应用下载目录" },
                            icon = Icons.Outlined.FolderOpen,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFFE2E8F0))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("使用自定义路径", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text("支持手动写入 Android/data 路径，也可使用系统文件夹选择器", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DynamicSwitch(
                            checked = useCustomPath,
                            onCheckedChange = {
                                useCustomPath = it
                                pathPreferences.useCustomPath = it
                                displayPath = pathPreferences.displayDownloadDir(context)
                            }
                        )
                    }
                    if (useCustomPath) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = displayPath.takeIf { !it.startsWith("content://") } ?: "",
                            onValueChange = {
                                customPath = it
                                displayPath = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("手动保存路径") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            shape = RoundedCornerShape(16.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(
                                onClick = { dirPickerLauncher.launch(null) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("浏览目录") }
                            Button(
                                onClick = {
                                    if (customPath.startsWith("content://")) pathPreferences.saveCustomTreeUri(customPath, displayPath.ifBlank { customPath })
                                    else pathPreferences.customPath = customPath
                                    pathPreferences.useCustomPath = true
                                    displayPath = pathPreferences.displayDownloadDir(context)
                                    scope.launch { inlineFeedback = FeedbackMessage("下载路径已保存", FeedbackKind.SUCCESS) }
                                },
                                enabled = customPath.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("保存路径")
                            }
                        }
                    InlineFeedbackBar(
                        message = inlineFeedback,
                        onDismiss = { inlineFeedback = null }
                    )
                    }
                }
            }
            }

            if (section == null || section == DlSection.UPDATE) {
            item {
                SectionCard(title = "更新与工具", subtitle = "保持解析器和 App 版本可用", icon = Icons.Outlined.SystemUpdate) {
                    ActionRow(
                        title = "更新 yt-dlp",
                        subtitle = updateStatus ?: "用于提升 X/Twitter 视频解析成功率",
                        icon = Icons.Outlined.Download,
                        busy = isUpdatingYtdlp,
                        actionText = if (isUpdatingYtdlp) "更新中" else "更新",
                        onClick = {
                            if (isUpdatingYtdlp) return@ActionRow
                            scope.launch {
                                isUpdatingYtdlp = true
                                YoutubeDLService.updateYoutubeDL(context)
                                    .onSuccess { updateStatus = "yt-dlp 已更新完成" }
                                    .onFailure { e -> updateStatus = "更新失败：${e.message ?: "未知错误"}" }
                                isUpdatingYtdlp = false
                            }
                        }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFFE2E8F0))
                    ActionRow(
                        title = "检查 App 更新",
                        subtitle = "当前版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        icon = Icons.Outlined.SystemUpdate,
                        busy = isCheckingAppUpdate,
                        actionText = if (isCheckingAppUpdate) "检查中" else "检查",
                        onClick = { checkAppUpdate() }
                    )
                    // 下载进度内联显示（2026-09-15）：更新下载从模态弹窗移出，期间可继续使用页面
                    if (isDownloadingAppUpdate) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { appUpdateProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "正在下载更新 ${(appUpdateProgress * 100).toInt()}%…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    InlineFeedbackBar(
                        message = inlineFeedback,
                        onDismiss = { inlineFeedback = null }
                    )
                }
            }
            }

            if (section == null || section == DlSection.NETWORK) {
            item {
                SectionCard(title = "网络与认证", subtitle = "代理、Cookie 和受限内容解析", icon = Icons.Outlined.Security) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.VpnLock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用代理", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (proxyEnabled) "当前 ${if (proxyType == ProxySettings.TYPE_SOCKS5) "socks5" else "http"}://${proxyHost.ifBlank { "127.0.0.1" }}:${proxyPort.ifBlank { "7890" }}" else "未启用，全部引擎直连",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DynamicSwitch(
                            checked = proxyEnabled,
                            onCheckedChange = { enabled ->
                                proxyEnabled = enabled
                                proxyPreferences.saveProxySettings(
                                    ProxySettings(
                                        enabled = enabled,
                                        host = proxyHost.ifBlank { "127.0.0.1" },
                                        port = proxyPort.toIntOrNull() ?: 7890,
                                        type = proxyType
                                    )
                                )
                            }
                        )
                    }
                    if (proxyEnabled) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = { proxyHost = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("主机") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { value -> if (value.isEmpty() || value.all(Char::isDigit)) proxyPort = value },
                                modifier = Modifier.width(118.dp),
                                label = { Text("端口") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "代理类型（Clash 混合端口两者通用；yt-dlp 回退建议 HTTP）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ProxySettings.TYPE_HTTP to "HTTP",
                                ProxySettings.TYPE_SOCKS5 to "SOCKS5"
                            ).forEach { (value, label) ->
                                val selected = proxyType == value
                                OutlinedButton(
                                    onClick = {
                                        proxyType = value
                                        proxyPreferences.saveProxySettings(
                                            ProxySettings(
                                                enabled = proxyEnabled,
                                                host = proxyHost.ifBlank { "127.0.0.1" },
                                                port = proxyPort.toIntOrNull() ?: 7890,
                                                type = value
                                            )
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = if (selected) {
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        )
                                    } else {
                                        ButtonDefaults.outlinedButtonColors()
                                    }
                                ) {
                                    Text(
                                        label,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // 自动检测主流代理（Clash / FlClash / Clash Verge / v2rayNG / Shadowsocks）+ 一键连通性测试（P0-3）
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val detection = withContext(Dispatchers.IO) { ProxyDetector.detect(context) }
                                        when (detection.kind) {
                                            "VPN" -> {
                                                // 系统 VPN 隧道：直连即可，无需填代理
                                                inlineFeedback = FeedbackMessage(detection.description, FeedbackKind.NEUTRAL)
                                            }
                                            "PROXY" -> {
                                                detection.port?.let { port ->
                                                    proxyHost = detection.host
                                                    proxyPort = port.toString()
                                                    proxyEnabled = true
                                                    proxyPreferences.saveProxySettings(
                                                        ProxySettings(enabled = true, host = detection.host, port = port, type = proxyType)
                                                    )
                                                }
                                                inlineFeedback = FeedbackMessage(detection.description, FeedbackKind.SUCCESS)
                                            }
                                            else -> inlineFeedback = FeedbackMessage(detection.description, FeedbackKind.NEUTRAL)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("自动检测代理") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isTestingProxy = true
                                        val outcome = ProxyTester.test(
                                            ProxySettings(
                                                enabled = true,
                                                host = proxyHost.ifBlank { "127.0.0.1" },
                                                port = proxyPort.toIntOrNull() ?: 7890,
                                                type = proxyType
                                            )
                                        )
                                        isTestingProxy = false
                                        inlineFeedback = outcome.fold(
                                            onSuccess = { FeedbackMessage(it, FeedbackKind.SUCCESS) },
                                            onFailure = { FeedbackMessage(it.message ?: "测试失败", FeedbackKind.ERROR) }
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isTestingProxy
                            ) { Text(if (isTestingProxy) "测试中…" else "测试连通") }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFFE2E8F0))
                    SettingRow(
                        title = "Twitter / X Cookie",
                        subtitle = if (cookiesConfigured) "已配置，可用于登录态解析" else "未配置，默认使用公开解析能力",
                        icon = Icons.Outlined.Cookie
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = authToken,
                        onValueChange = { authToken = it },
                        label = { Text("auth_token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ct0,
                        onValueChange = { ct0 = it },
                        label = { Text("ct0") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = authToken.isNotBlank() && ct0.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            onClick = {
                                cookiePreferences.saveCookies(authToken, ct0)
                                cookiesConfigured = cookiePreferences.hasCookies()
                                scope.launch { inlineFeedback = FeedbackMessage("Cookie 已保存", FeedbackKind.SUCCESS) }
                            }
                        ) { Text("保存 Cookie") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            onClick = {
                                cookiePreferences.clearCookies()
                                authToken = ""
                                ct0 = ""
                                cookiesConfigured = false
                            }
                        ) { Text("清除") }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFFE2E8F0))
                    // 第三方解析兜底（P0-1 第三层）：端点留空 = 关闭
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("第三方解析兜底", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (tpConfigured) "已启用：FXTwitter 与 yt-dlp 均失败后启用" else "未启用（端点留空即关闭）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tpEndpoint,
                        onValueChange = { tpEndpoint = it },
                        label = { Text("API 端点（支持 {id} 占位符）") },
                        placeholder = { Text("https://api.example.com/twitter/status/{id}") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tpApiKey,
                        onValueChange = { tpApiKey = it },
                        label = { Text("API Key（可留空）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            thirdPartyApiPreferences.save(tpEndpoint, tpApiKey)
                            tpConfigured = thirdPartyApiPreferences.isConfigured
                            scope.launch {
                                inlineFeedback = FeedbackMessage(
                                    if (tpConfigured) "第三方兜底已保存并启用" else "第三方兜底已关闭（端点为空）",
                                    if (tpConfigured) FeedbackKind.SUCCESS else FeedbackKind.NEUTRAL
                                )
                            }
                        }
                    ) { Text("保存第三方兜底") }

                    InlineFeedbackBar(
                        message = inlineFeedback,
                        onDismiss = { inlineFeedback = null }
                    )
                }
            }
            }

            if (section == null || section == DlSection.WEBDAV) {
            item {
                SectionCard(title = "WebDAV 同步", subtitle = "把下载媒体同步到私有云盘", icon = Icons.Outlined.Cloud) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用 WebDAV", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (lastSyncTime > 0) "上次同步 ${formatSyncTime(lastSyncTime)} · 已上传 $syncedCount 个文件" else "填写地址、账号和密码后即可同步",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DynamicSwitch(
                            checked = useWebDavSync,
                            onCheckedChange = {
                                useWebDavSync = it
                                cloudSyncPreferences.enabled = it
                            }
                        )
                    }
                    if (useWebDavSync) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "网盘预设",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        WebDavProviderSelector(
                            selectedProviderId = webDavProviderId,
                            onProviderSelected = { provider ->
                                webDavProviderId = provider.id
                                cloudSyncPreferences.providerId = provider.id
                                if (provider.serverUrlTemplate.isNotBlank()) {
                                    webDavServerUrl = provider.serverUrlTemplate
                                    cloudSyncPreferences.serverUrl = provider.serverUrlTemplate
                                }
                                if (provider.remotePathTemplate.isNotBlank()) {
                                    webDavRemotePath = provider.remotePathTemplate
                                    cloudSyncPreferences.remotePath = provider.remotePathTemplate
                                }
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = (webDavProviders.firstOrNull { it.id == webDavProviderId }
                                ?: webDavProviders.last()).description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = webDavServerUrl,
                            onValueChange = {
                                webDavServerUrl = it
                                cloudSyncPreferences.serverUrl = it
                            },
                            label = { Text("WebDAV 地址") },
                            placeholder = { Text("https://example.com/dav") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = webDavUsername,
                                onValueChange = {
                                    webDavUsername = it
                                    cloudSyncPreferences.username = it
                                },
                                label = { Text("账号") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedTextField(
                                value = webDavPassword,
                                onValueChange = {
                                    webDavPassword = it
                                    cloudSyncPreferences.password = it
                                },
                                label = { Text("密码") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = webDavRemotePath,
                            onValueChange = {
                                webDavRemotePath = it
                                cloudSyncPreferences.remotePath = it
                            },
                            label = { Text("远程目录") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    cloudSyncPreferences.serverUrl = webDavServerUrl
                                    cloudSyncPreferences.username = webDavUsername
                                    cloudSyncPreferences.password = webDavPassword
                                    cloudSyncPreferences.remotePath = webDavRemotePath
                                    scope.launch {
                                        isTestingConnection = true
                                        val credential = WebDavCredential(
                                            serverUrl = webDavServerUrl,
                                            username = webDavUsername,
                                            password = webDavPassword,
                                            remotePath = webDavRemotePath,
                                        )
                                        WebDavEngine().probe(credential.serverUrl, credential)
                                            .onSuccess {
                                                connectionVerified = true
                                                cloudSyncPreferences.connectionVerified = true
                                                inlineFeedback = FeedbackMessage("连接成功，WebDAV 配置已保存", FeedbackKind.SUCCESS)
                                            }
                                            .onFailure { e ->
                                                connectionVerified = false
                                                cloudSyncPreferences.connectionVerified = false
                                                inlineFeedback = FeedbackMessage("连接失败：${e.message ?: "未知错误"}", FeedbackKind.ERROR)
                                            }
                                        isTestingConnection = false
                                    }
                                },
                                enabled = !isTestingConnection && webDavServerUrl.isNotBlank() && webDavUsername.isNotBlank() && webDavPassword.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text(if (isTestingConnection) "测试中" else "测试连接") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        isWebDavSyncing = true
                                        WebDavSyncService.syncDownloads(context, cloudSyncPreferences)
                                            .onSuccess { (newCount, skipCount) ->
                                                if (newCount > 0) {
                                                    syncedCount += newCount
                                                    cloudSyncPreferences.syncedFileCount = syncedCount
                                                    cloudSyncPreferences.lastSyncTime = System.currentTimeMillis()
                                                    lastSyncTime = cloudSyncPreferences.lastSyncTime
                                                }
                                                inlineFeedback = FeedbackMessage("同步完成：新增 $newCount 个，跳过 $skipCount 个", FeedbackKind.SUCCESS)
                                            }
                                            .onFailure { e -> inlineFeedback = FeedbackMessage("WebDAV 同步失败：${e.message ?: "未知错误"}", FeedbackKind.ERROR) }
                                        isWebDavSyncing = false
                                    }
                                },
                                enabled = !isWebDavSyncing && connectionVerified,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (isWebDavSyncing) "同步中" else "立即同步") }
                        }

                        if (connectionVerified) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            Spacer(Modifier.height(12.dp))
                            // 测试连接成功后：备份显示通道入口 —— 详细查看每条视频/图片的同步状态
                            AssistChip(
                                onClick = onOpenMediaBackup,
                                label = { Text("详细查看同步情况", fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Cloud,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(999.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("自动备份", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        if (autoBackupEnabled) "每 ${autoBackupDays} 天自动同步" else "开启后定时同步到网盘",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DynamicSwitch(
                                    checked = autoBackupEnabled,
                                    onCheckedChange = {
                                        autoBackupEnabled = it
                                        cloudSyncPreferences.autoBackupEnabled = it
                                        if (it) {
                                            WebDavAutoBackupScheduler.schedule(context, autoBackupDays)
                                            scope.launch { inlineFeedback = FeedbackMessage("已开启自动备份（每 ${autoBackupDays} 天）", FeedbackKind.SUCCESS) }
                                        } else {
                                            WebDavAutoBackupScheduler.cancel(context)
                                            scope.launch { inlineFeedback = FeedbackMessage("已关闭自动备份", FeedbackKind.NEUTRAL) }
                                        }
                                    }
                                )
                            }
                            if (autoBackupEnabled) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    val options = listOf(1 to "每天", 3 to "每3天", 7 to "每周")
                                    options.forEach { (days, label) ->
                                        FilterChip(
                                            selected = autoBackupDays == days,
                                            onClick = {
                                                autoBackupDays = days
                                                cloudSyncPreferences.autoBackupDays = days
                                                WebDavAutoBackupScheduler.schedule(context, days)
                                            },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }
                        }
                    InlineFeedbackBar(
                        message = inlineFeedback,
                        onDismiss = { inlineFeedback = null }
                    )
                    }
                }
            }
            }

            if (section == null || section == DlSection.PREDOWNLOAD) {
            item {
                SectionCard(title = "预下载", subtitle = "保存攒批自动下载与网盘联动", icon = Icons.Outlined.Download) {
                    var autoPreDownload by remember { mutableStateOf(preDownloadPrefs.autoPreDownload) }
                    var autoStart by remember { mutableStateOf(preDownloadPrefs.autoStartDownload) }
                    var syncCloud by remember { mutableStateOf(preDownloadPrefs.syncToCloud) }
                    var batchSizeText by remember { mutableStateOf(preDownloadPrefs.batchSize.toString()) }

                    // 预下载总开关
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("预下载功能", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text("保存链接后攒批自动后台下载，防止视频下架", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DynamicSwitch(
                            checked = autoPreDownload,
                            onCheckedChange = {
                                autoPreDownload = it
                                preDownloadPrefs.autoPreDownload = it
                            }
                        )
                    }

                    if (autoPreDownload) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        Spacer(Modifier.height(12.dp))

                        // 攒批条数
                        Text("攒到多少条开始", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 20).forEach { n ->
                                FilterChip(
                                    selected = batchSizeText == n.toString(),
                                    onClick = {
                                        batchSizeText = n.toString()
                                        preDownloadPrefs.batchSize = n
                                    },
                                    label = { Text("$n 条") }
                                )
                            }
                            OutlinedTextField(
                                value = batchSizeText,
                                onValueChange = { value ->
                                    batchSizeText = value.filter(Char::isDigit).take(2)
                                    batchSizeText.toIntOrNull()?.let { preDownloadPrefs.batchSize = it }
                                },
                                modifier = Modifier.width(96.dp),
                                label = { Text("自定义") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        // 自动开始下载
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("自动开始下载", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Text("攒满后立即开始；关闭则等待手动批量下载", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DynamicSwitch(
                                checked = autoStart,
                                onCheckedChange = {
                                    autoStart = it
                                    preDownloadPrefs.autoStartDownload = it
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        // 预下载上传网盘
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("预下载上传到网盘", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Text("完成后自动同步到备份中心选中网盘", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DynamicSwitch(
                                checked = syncCloud,
                                onCheckedChange = {
                                    syncCloud = it
                                    preDownloadPrefs.syncToCloud = it
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "关闭预下载后，手动下载完成仍会自动联动网盘同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }

            // ── 作品订阅管理（2026-09-15 v2 批次4：P2-1 作者订阅自动下载）──
            if (section == null || section == DlSection.PREDOWNLOAD) {
            item {
                val subs by com.ed.edqiu.service.SubscriptionManager.observe(context)
                    .collectAsState(initial = emptyList())
                SectionCard(title = "作品订阅", subtitle = "关注的作者发新视频自动下载", icon = Icons.Outlined.Notifications) {
                    if (subs.isEmpty()) {
                        Text(
                            "暂无订阅——进入播放页点右上 ⋮ 选「订阅该作者」即可。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    subs.forEach { sub ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "@${sub.screenName}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = buildString {
                                        append(if (sub.enabled) "订阅中" else "已暂停")
                                        sub.lastVideoAt?.let {
                                            append(" · 上次新作品 ")
                                            append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)))
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DynamicSwitch(
                                checked = sub.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        com.ed.edqiu.service.SubscriptionManager.setEnabled(context, sub.screenName, enabled)
                                    }
                                }
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    com.ed.edqiu.service.SubscriptionManager.remove(context, sub.screenName)
                                }
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "后台每 25 分钟检测一轮（每轮最多 3 个作者，每作者最多自动下载 3 条新作品）。" +
                            "检测走 yt-dlp 用户页，需网络可达 X（自动套用代理设置）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }

            if (section == null || section == DlSection.ABOUT) {
            item {
                SectionCard(title = "关于", subtitle = "版本与使用说明", icon = Icons.Outlined.Info) {
                    Text("Edqiu v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text("仅供个人学习与内容整理使用。下载内容版权归原作者所有，请遵守 Twitter / X 服务条款。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    pathLabel: String,
    proxyEnabled: Boolean,
    cookiesConfigured: Boolean,
    webDavEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(color = Accent.copy(alpha = 0.12f), shape = CircleShape) {
                    Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(11.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("下载器设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    Text("路径、解析、代理、Cookie 与云同步", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StatusChip(
                label = listOf(
                    pathLabel,
                    if (proxyEnabled) "代理开" else "代理关",
                    if (cookiesConfigured) "Cookie 已配" else "Cookie 未配",
                    if (webDavEnabled) "WebDAV 开" else "WebDAV 关"
                ).joinToString(" · "),
                active = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (active) Accent.copy(alpha = 0.10f) else Soft
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (active) Accent else Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = Accent.copy(alpha = 0.10f), shape = CircleShape) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(9.dp).size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    busy: Boolean,
    actionText: String,
    onClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(enabled = !busy, onClick = onClick) { Text(actionText) }
    }
}

private fun uriToDisplayPath(context: Context, uri: Uri): String? {
    return runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        if (treeId.contains("primary:")) {
            "/内部存储/" + treeId.substringAfter("primary:").replace(':', '/')
        } else {
            uri.path ?: uri.toString()
        }
    }.getOrElse { uri.path ?: uri.toString() }
}

private fun formatSyncTime(millis: Long): String {
    if (millis == 0L) return "从未"
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
