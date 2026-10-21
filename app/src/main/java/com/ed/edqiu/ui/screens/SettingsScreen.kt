package com.ed.edqiu.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.ed.edqiu.ui.components.SmoothAlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.edqiu.data.model.ProxySettings
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.preferences.ThirdPartyApiPreferences
import com.ed.edqiu.data.proxy.ProxyDetector
import com.ed.edqiu.data.proxy.ProxyTester
import com.ed.edqiu.service.AppUpdateInfo
import com.ed.edqiu.service.AppUpdateService
import com.ed.edqiu.service.WebDavManualSync
import com.ed.edqiu.service.WebDavSyncPhase
import com.ed.edqiu.service.WebDavSyncProgress
import com.ed.edqiu.service.YoutubeDLService
import com.ed.edqiu.backup.provider.WebDavEngine
import com.ed.edqiu.backup.provider.WebDavCredential
import com.ed.edqiu.data.preferences.WebDavAccountConfig
import com.ed.edqiu.background.WebDavAutoBackupScheduler
import com.ed.edqiu.BuildConfig
import com.ed.edqiu.ui.components.EdgeSwipeBackBox
import com.ed.edqiu.ui.components.EdqiuTextField
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.FeedbackMessage
import com.ed.edqiu.ui.components.FeedbackDialog
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.util.pressableNoRipple
import com.ed.edqiu.ui.components.PillChip
import com.ed.edqiu.ui.components.DynamicSwitch
import com.ed.edqiu.ui.navigation.LocalSnackbarController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// 2026-10-10 固定配色重构：Accent→primary（强调色 tint）、Ink 死代码删除、
// Soft→surfaceContainerHighest、Muted 固定 iOS secondaryLabel
private val Muted = Color(0xFF8A8A8E)

/** 下载器设置分组标识（二级菜单入口用） */
object DlSection {
    const val UPDATE = "update"
    const val NETWORK = "network"
    const val WEBDAV = "webdav"
    const val ABOUT = "about"
}

@Composable
fun DownloaderSettingsPage(
    onBack: () -> Unit,
    section: String? = null,
    onOpenMediaBackup: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 内联反馈：操作结果固定显示在触发按钮所在卡片内，5s 后动画消失（不再在页面顶部挤压内容）
    var inlineFeedback by remember { mutableStateOf<FeedbackMessage?>(null) }

    val proxyPreferences = remember { ProxyPreferences(context) }
    val cookiePreferences = remember { CookiePreferences(context) }
    val cloudSyncPreferences = remember { CloudSyncPreferences(context) }

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

    var useWebDavSync by remember { mutableStateOf(false) }
    var webDavServerUrl by remember { mutableStateOf("") }
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var webDavRemotePath by remember { mutableStateOf("Edqiu") }
    var webDavProviderId by remember { mutableStateOf("custom") }
    var lastSyncTime by remember { mutableLongStateOf(0L) }
    var syncedCount by remember { mutableIntStateOf(0) }
    // 2026-10：「立即同步」状态挂应用级运行器 WebDavManualSync——退出页面同步继续，
    // 回到本页补看结果；此前的 rememberCoroutineScope 版本会在离开页面时静默取消上传
    val webDavSyncState by WebDavManualSync.state.collectAsStateWithLifecycle()
    val isWebDavSyncing = webDavSyncState is WebDavManualSync.State.Syncing
    // 实时同步进度（第 i/N 个文件 + 单文件字节进度），驱动连接状态面板的进度条
    val webDavSyncProgress = (webDavSyncState as? WebDavManualSync.State.Syncing)?.progress
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionVerified by remember { mutableStateOf(false) }
    // 上次连接验证时间（epoch ms）：0 = 从未验证；驱动「连接状态」面板的鉴权结果展示
    var lastVerifiedAt by remember { mutableLongStateOf(0L) }
    // 允许自签名证书（HTTPS 自建 NAS 场景）；默认关
    var allowInsecureTls by remember { mutableStateOf(false) }
    var autoBackupEnabled by remember { mutableStateOf(false) }
    var autoBackupDays by remember { mutableIntStateOf(1) }
    // WebDAV 配置是否已从磁盘加载完成（去抖落盘防覆写开关，见下方 snapshotFlow）
    var webDavLoaded by remember { mutableStateOf(false) }

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

        useWebDavSync = cloudSyncPreferences.enabled
        webDavProviderId = cloudSyncPreferences.providerId
        // 2026-10 每网盘独立配置：输入框加载当前选中预设自己的凭证（cfg_{pid}_*）
        val account = cloudSyncPreferences.accountFor(webDavProviderId)
        webDavServerUrl = account.serverUrl
        webDavUsername = account.username
        webDavPassword = account.password
        webDavRemotePath = account.remotePath.ifBlank { "Edqiu" }
        connectionVerified = account.verified
        lastVerifiedAt = account.verifiedAt
        lastSyncTime = cloudSyncPreferences.lastSyncTime
        syncedCount = cloudSyncPreferences.syncedFileCount
        allowInsecureTls = cloudSyncPreferences.allowInsecureTls
        autoBackupEnabled = cloudSyncPreferences.autoBackupEnabled
        autoBackupDays = cloudSyncPreferences.autoBackupDays
        webDavLoaded = true
    }

    // 后台同步结果提醒（2026-10）：页面在前台时同步完成直接弹反馈；
    // 用户离开期间完成的，回到本页时补提示，随后复位状态
    LaunchedEffect(webDavSyncState) {
        when (val state = webDavSyncState) {
            is WebDavManualSync.State.Succeeded -> {
                lastSyncTime = cloudSyncPreferences.lastSyncTime
                syncedCount = cloudSyncPreferences.syncedFileCount
                val r = state.result
                val conflictNote = if (r.conflicts > 0) "，冲突 ${r.conflicts} 个（本地已保留，云端旧版改名 conflict-*）" else ""
                inlineFeedback = FeedbackMessage(
                    "双向同步完成：上传 ${r.uploaded} 个，下载 ${r.downloaded} 个，一致跳过 ${r.skippedExisting} 个$conflictNote",
                    if (r.conflicts > 0) FeedbackKind.NEUTRAL else FeedbackKind.SUCCESS
                )
                WebDavManualSync.consumeResult()
            }
            is WebDavManualSync.State.Failed -> {
                inlineFeedback = FeedbackMessage(
                    "WebDAV 同步失败：${state.message}",
                    FeedbackKind.ERROR
                )
                WebDavManualSync.consumeResult()
            }
            else -> {}
        }
    }

    // 退出页面时同步仍在进行：提醒用户任务不会中断（2026-10 新增，配合 WebDavManualSync）
    val snackbarController = LocalSnackbarController.current
    DisposableEffect(Unit) {
        onDispose {
            if (WebDavManualSync.isSyncing()) {
                snackbarController.show(
                    "WebDAV 同步仍在后台进行，完成后回到本页可查看结果",
                    kind = FeedbackKind.NEUTRAL
                )
            }
        }
    }

    // 2026-10 P2 整改：旧实现 LaunchedEffect(keys) 每敲一键取消重launch并写一次盘，
    // 引擎可能读到输入一半的代理地址；改为 snapshotFlow + 600ms 去抖，停手才落盘。
    LaunchedEffect(proxyType) {
        snapshotFlow { Triple(proxyEnabled, proxyHost, proxyPort) }
            .debounce(600)
            .collect { (enabled, host, port) ->
                proxyPreferences.saveProxySettings(
                    ProxySettings(
                        enabled = enabled,
                        host = host.ifBlank { "127.0.0.1" },
                        port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 7890,
                        // 必须回带当前 type：旧实现漏传导致已存 SOCKS5 被默认值 HTTP 静默覆盖
                        type = proxyType
                    )
                )
            }
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

    fun downloadAndInstallAppUpdate(info: AppUpdateInfo, useMirror: Boolean) {
        if (isDownloadingAppUpdate) return
        isDownloadingAppUpdate = true
        appUpdateProgress = 0f
        // 2026-10 P2 整改：下载挂应用级作用域——旧实现挂 rememberCoroutineScope，离开设置页
        // 协程被取消，APK 下完也不安装且无任何痕迹，与弹窗"后台进行"的文案矛盾。
        val appContext = context.applicationContext
        val launchScope = (appContext as? com.ed.edqiu.EdqiuApplication)?.container?.globalIoScope ?: scope
        launchScope.launch {
            // 2026-10-07 更新弹窗改版：useMirror = 国内镜像直连 / GitHub 官方直连 双源
            val result = AppUpdateService.downloadApk(appContext, info, useMirror) { progress ->
                appUpdateProgress = progress // Compose snapshot 跨线程写安全；页面销毁后写为无害 no-op
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { apkFile ->
                    val installStarted = AppUpdateService.installApk(appContext, apkFile)
                    appUpdateInfo = null
                    inlineFeedback = FeedbackMessage(
                        if (installStarted) "系统安装器已打开，请按提示覆盖安装"
                        else "下载完成，请允许安装未知来源应用后再试",
                        if (installStarted) FeedbackKind.SUCCESS else FeedbackKind.NEUTRAL
                    )
                }.onFailure { e ->
                    inlineFeedback = FeedbackMessage(
                        "下载新版本失败：${e.message ?: "网络异常"}",
                        FeedbackKind.ERROR
                    )
                }
                isDownloadingAppUpdate = false
            }
        }
    }

    // 进入「更新与工具」界面自动检查 GitHub Releases（2026-09-28）：
    // 有新版本直接弹窗提醒，无需手动点「检查更新」；手动按钮保留
    LaunchedEffect(section) {
        if (section == DlSection.UPDATE) checkAppUpdate()
    }

    val pendingAppUpdate = appUpdateInfo
    if (pendingAppUpdate != null) {
        // 2026-10-07 更新弹窗改版（大卡片）：版本对比 + 更新内容滚动区（Release Notes，
        // 修复/新增说明）+ 双下载源选择（国内镜像直连 / GitHub 官方直连）+ GitHub 主页跳转；
        // 下载进度卡内实时显示，可关闭弹窗后台继续（进度回落到「更新与工具」卡片）
        var updateUseMirror by remember { mutableStateOf(true) }
        SmoothAlertDialog(
            onDismissRequest = { appUpdateInfo = null },
            modifier = Modifier.fillMaxWidth(0.94f),
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "发现新版本 v${pendingAppUpdate.versionName}",
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "当前 v${BuildConfig.VERSION_NAME} · ${pendingAppUpdate.publishedAt} 发布",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pendingAppUpdate.releaseNotes.isNotBlank()) {
                        Text(
                            "更新内容",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                pendingAppUpdate.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 19.sp
                            )
                        }
                    }
                    if (isDownloadingAppUpdate) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // 2026-10-10 统一：6dp 圆角胶囊进度条（全工程一致）
                            LinearProgressIndicator(
                                progress = { appUpdateProgress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            Text(
                                "正在下载 ${(appUpdateProgress * 100).toInt()}% · 完成后自动调起安装",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            "选择下载方式",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                                .clickable { updateUseMirror = true }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = updateUseMirror, onClick = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "国内镜像直连",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "境内加速下载 · 网络不佳时推荐",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                                .clickable { updateUseMirror = false }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = !updateUseMirror, onClick = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "GitHub 官方直连",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "原始发布源 · 需可访问 GitHub",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(pendingAppUpdate.upgradeDocumentUrl)
                                        )
                                    )
                                }
                            }
                        ) {
                            Text(
                                "前往 GitHub Releases 主页查看与手动下载",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "下载在后台进行，完成后自动调起系统安装器，旧数据与本地媒体保留",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isDownloadingAppUpdate,
                    onClick = {
                        appUpdateInfo = null
                        downloadAndInstallAppUpdate(pendingAppUpdate, updateUseMirror)
                    }
                ) { Text(if (isDownloadingAppUpdate) "下载中…" else "立即更新") }
            },
            dismissButton = {
                TextButton(onClick = { appUpdateInfo = null }) { Text("稍后") }
            }
        )
    }

    // 二级页右缘跟手侧滑返回（2026-10）：此前该能力只挂在浮层路由上，
    // 设置二级页（含 WebDAV 同步页）只有返回箭头，侧滑返回不生效
    EdgeSwipeBackBox(
        enabled = true,
        onBack = onBack,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 2026-10-21 液态玻璃：去掉实底 background——旧实现用纯色铺满，
            // 把上层的 GlassBackground 色场整块盖死，页面成了白板；
            // 现在透出玻璃背景，卡片（GlassSurface L1）才有"浮在色场上"的层次
            .statusBarsPadding()
    ) {
        // 卡片内嵌通知 → 统一弹窗反馈（2026-09-28）：更新/代理/Cookie 等操作结果改弹窗展示
        FeedbackDialog(
            message = inlineFeedback,
            onDismiss = { inlineFeedback = null }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                // 2026-10-21 设置页合并：头部与新设置页（ui/settings/SettingsScreen）统一
                // —— L3 返回圆钮 + 分区标题/副标题，不再走旧的 SettingsHeader 概览卡
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = legacySectionTitle(section),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = legacySectionSubtitle(section),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 「下载保存」卡片已整合到 收件箱设置 → 存储路径（2026-09-28）

            if (section == null || section == DlSection.UPDATE) {
            item {
                SectionCard(title = "", subtitle = "", icon = Icons.Outlined.SystemUpdate) {
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
                }
            }
            }

            if (section == null || section == DlSection.NETWORK) {
            item {
                SectionCard(title = "", subtitle = "", icon = Icons.Outlined.Security) {
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
                            EdqiuTextField(
                                value = proxyHost,
                                onValueChange = { proxyHost = it },
                                modifier = Modifier.weight(1f),
                                label = "主机",
                                singleLine = true
                            )
                            EdqiuTextField(
                                value = proxyPort,
                                onValueChange = { value -> if (value.isEmpty() || value.all(Char::isDigit)) proxyPort = value },
                                modifier = Modifier.width(118.dp),
                                label = "端口",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
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
                    EdqiuTextField(
                        value = authToken,
                        onValueChange = { authToken = it },
                        label = "auth_token",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    EdqiuTextField(
                        value = ct0,
                        onValueChange = { ct0 = it },
                        label = "ct0",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
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
                                // 2026-10：同步删除落盘的明文 cookie 文件（auth_token/ct0）
                                com.ed.edqiu.service.YoutubeDLService.cleanupCookieFiles(context)
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
                    EdqiuTextField(
                        value = tpEndpoint,
                        onValueChange = { tpEndpoint = it },
                        label = "API 端点（支持 {id} 占位符）",
                        placeholder = "https://api.example.com/twitter/status/{id}",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    EdqiuTextField(
                        value = tpApiKey,
                        onValueChange = { tpApiKey = it },
                        label = "API Key（可留空）",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
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

                }
            }
            }

            if (section == null || section == DlSection.WEBDAV) {
            item {
                SectionCard(title = "", subtitle = "", icon = Icons.Outlined.Cloud) {
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
                            // 2026-10 每网盘独立配置：切换预设 = 切换到该网盘自己的凭证命名空间。
                            // 未保存的编辑直接丢弃（保存语义 = 测试连接成功才落盘）；
                            // 该网盘已保存过地址则显示已存值，模板仅在该网盘首次配置时填入。
                            onProviderSelected = { provider ->
                                webDavProviderId = provider.id
                                cloudSyncPreferences.providerId = provider.id
                                val saved = cloudSyncPreferences.accountFor(provider.id)
                                webDavServerUrl = saved.serverUrl.ifBlank { provider.serverUrlTemplate }
                                webDavUsername = saved.username
                                webDavPassword = saved.password
                                webDavRemotePath = saved.remotePath.ifBlank {
                                    provider.remotePathTemplate.ifBlank { "Edqiu" }
                                }
                                connectionVerified = saved.verified
                                lastVerifiedAt = saved.verifiedAt
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
                        EdqiuTextField(
                            value = webDavServerUrl,
                            // 2026-10 P1 整改：不再逐字符落盘（半成品 URL 会被周期自动备份读到）
                            onValueChange = { webDavServerUrl = it },
                            label = "WebDAV 地址",
                            placeholder = "https://example.com/dav",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EdqiuTextField(
                                value = webDavUsername,
                                onValueChange = { webDavUsername = it },
                                label = "账号",
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            EdqiuTextField(
                                value = webDavPassword,
                                onValueChange = { webDavPassword = it },
                                label = "密码",
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        EdqiuTextField(
                            value = webDavRemotePath,
                            onValueChange = { webDavRemotePath = it },
                            label = "远程目录",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isTestingConnection = true
                                    val credential = WebDavCredential(
                                        serverUrl = webDavServerUrl.trim(),
                                        username = webDavUsername.trim(),
                                        password = webDavPassword,
                                        remotePath = webDavRemotePath,
                                    )
                                    WebDavEngine(
                                        allowInsecureTls = { cloudSyncPreferences.allowInsecureTls }
                                    ).probe(credential.serverUrl, credential)
                                        .onSuccess {
                                            // 2026-10 保存语义（用户定案）：测试连接成功 → 落盘该网盘
                                            // 独立配置（cfg_{pid}_*）+ 验证结论；失败一律不保存
                                            connectionVerified = true
                                            lastVerifiedAt = System.currentTimeMillis()
                                            cloudSyncPreferences.saveAccount(
                                                webDavProviderId,
                                                WebDavAccountConfig(
                                                    serverUrl = credential.serverUrl,
                                                    username = credential.username,
                                                    password = credential.password,
                                                    remotePath = credential.remotePath,
                                                    verified = true,
                                                    verifiedAt = lastVerifiedAt,
                                                ),
                                            )
                                            inlineFeedback = FeedbackMessage("连接成功，配置已保存", FeedbackKind.SUCCESS)
                                        }
                                        .onFailure { e ->
                                            // 失败不保存：输入框保留编辑内容但不落盘；
                                            // verified 保持该网盘已保存配置的既有结论（编辑未生效）
                                            inlineFeedback = FeedbackMessage(
                                                "连接失败：${e.message ?: "未知错误"}（配置未保存）",
                                                FeedbackKind.ERROR
                                            )
                                        }
                                    isTestingConnection = false
                                }
                            },
                                enabled = !isTestingConnection && webDavServerUrl.isNotBlank() && webDavUsername.isNotBlank() && webDavPassword.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text(if (isTestingConnection) "测试中" else "测试连接") }
                            Button(
                                onClick = {
                                    // 同步读取已保存配置（当前选中预设的 cfg_{pid}_*）——
                                    // 未验证通过的编辑不会生效，杜绝「页面显示新值、同步跑旧值」错位
                                    WebDavManualSync.sync(context)
                                },
                                enabled = !isWebDavSyncing && connectionVerified,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (isWebDavSyncing) "同步中" else "立即同步") }
                        }

                        // 2026-10 统一 WebDAV 通道：连接状态面板——状态 / 地址 / 账号 /
                        // 鉴权结果 / 同步进度 / 错误提示集中展示（同步进度来自应用级运行器）
                        Spacer(Modifier.height(10.dp))
                        WebDavConnectionStatusPanel(
                            serverUrl = webDavServerUrl,
                            username = webDavUsername,
                            hasPassword = webDavPassword.isNotBlank(),
                            verified = connectionVerified,
                            isTesting = isTestingConnection,
                            isSyncing = isWebDavSyncing,
                            lastVerifiedAt = lastVerifiedAt,
                            syncProgress = webDavSyncProgress,
                            syncError = (webDavSyncState as? WebDavManualSync.State.Failed)?.message,
                        )

                        // 2026-10 HTTPS 引擎：自签名证书容忍开关（自建 NAS 的 https 自签场景）
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "允许自签名证书（HTTPS 自建 NAS）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "开启后不校验 TLS 证书链与主机名，仅建议内网自建服务使用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DynamicSwitch(
                                checked = allowInsecureTls,
                                onCheckedChange = {
                                    cloudSyncPreferences.allowInsecureTls = it
                                    allowInsecureTls = it
                                }
                            )
                        }

                        // 2026-10：网盘备份中心已删除（BackupEngine 队列/多网盘适配器/账本
                        // 整体退场）——WebDAV 同步页即唯一配置与同步入口，下面仅保留
                        // 「同步情况详情」入口（测试连接通过后可见）

                        if (connectionVerified) {
                            // 2026-10：测试通过后才显示「同步情况详情」矩形框入口
                            //（逐文件上传/下载/冲突对账，见 MediaBackupScreen）
                            Spacer(Modifier.height(14.dp))
                            Surface(
                                onClick = onOpenMediaBackup,
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Cloud,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "同步情况详情",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "逐文件对账 ›",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
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
                                        // 2026-10-10 统一分段胶囊（iOS 分段控制语义）
                                        PillChip(
                                            label = label,
                                            selected = autoBackupDays == days,
                                            onClick = {
                                                autoBackupDays = days
                                                cloudSyncPreferences.autoBackupDays = days
                                                WebDavAutoBackupScheduler.schedule(context, days)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }

            // ── 关于软件（2026-10-21 重写）：从「关于本机」升级为完整使用指南 ──
            // 排版沿用设置页同一套语言：GlassSurface 分区卡 + 卡内 GroupLabel 分组 +
            // 编号步骤行 / FAQ 行，不引入第二种卡片样式
            if (section == null || section == DlSection.ABOUT) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    SectionCard(title = "", subtitle = "", icon = Icons.Outlined.RocketLaunch) {
                        GroupLabel("快速开始")
                        AboutStepRow(1, "复制或分享链接", "在 X 里复制推文链接，或直接点分享选择 Edqiu——作者、文案、封面会自动归档。")
                        AboutStepRow(2, "确认已入收件箱", "链接进收件箱后状态为「待处理」。重复链接会被自动识别并跳过，不会产生多余记录。")
                        AboutStepRow(3, "点下载", "卡片右下角「下载」单条处理；右上角「下载」一次性处理全部待处理条目。")
                        AboutStepRow(4, "在媒体库查看", "下载完成的文件会出现在媒体库，按发布时间倒序排列，可直接播放或分享。")
                    }

                    SectionCard(title = "", subtitle = "", icon = Icons.Outlined.ContentPaste) {
                        GroupLabel("四种捕获方式")
                        AboutItemRow(
                            "分享面板",
                            "推特里点分享 → 选 Edqiu。适合正在浏览某条推文时直接保存。"
                        )
                        AboutItemRow(
                            "划词捕获",
                            "任意 App 里选中链接 → 更多 → Edqiu。适合在聊天记录、网页正文里遇到链接。"
                        )
                        AboutItemRow(
                            "应用内粘贴",
                            "收件箱右上角粘贴钮会直接读剪贴板：单条立即捕获，多条自动批量入库并汇总去重结果。"
                        )
                        AboutItemRow(
                            "跨 App 契约",
                            "自动化工具可发 com.ed.Edqiu.action.DOWNLOAD_TWEET，携带 URL 即可入库，无需人工操作。"
                        )
                    }

                    SectionCard(title = "", subtitle = "", icon = Icons.Outlined.Download) {
                        GroupLabel("下载与状态")
                        AboutItemRow("待处理", "已归档但尚未下载。可单条下载，或用右上角「下载」批量处理。")
                        AboutItemRow("下载中", "卡片显示实时百分比与进度条，切到别的页面也不会中断。")
                        AboutItemRow("已下载", "文件已落盘，可在媒体库播放、分享或升级画质。")
                        AboutItemRow("失败", "网络或解析异常。自动重试最多 3 次，间隔递增；重试耗尽后不再自动尝试。")
                        AboutItemRow("已删除", "原推文已 404 / 被删除，属于终态，不计入可下载条目。")
                        GroupLabel("媒体库")
                        AboutItemRow("筛选与搜索", "顶部三张统计卡（全部 / 视频 / 图片）兼作筛选器，搜索支持标题、作者与文案。")
                        AboutItemRow("分组浏览", "可按作者 + 发布日分组，组头随滚动吸顶。")
                        AboutItemRow("画质升级", "头部「升级」会补下更清晰的版本；HLS 源（m3u8）自动优选高码率。")
                    }

                    SectionCard(title = "", subtitle = "", icon = Icons.Outlined.NotificationsActive) {
                        GroupLabel("作者中心与订阅")
                        AboutItemRow("作者中心", "按作者归档浏览往期作品，在作者页可直接订阅。")
                        AboutItemRow("作品订阅", "订阅后后台定期检测作者主页，新作品只入库不自动下载，省流量也避免囤积。")
                        AboutItemRow("订阅消息", "订阅页的「订阅消息」列出每轮检测结果，点按即可下载其中想看的作品。")
                        AboutItemRow("下载去重", "订阅作品下载前会与媒体库比对，已在库的直接跳过并提示，不会重复占空间。")
                    }

                    SectionCard(title = "", subtitle = "", icon = Icons.Outlined.PlayCircle) {
                        GroupLabel("播放器")
                        AboutItemRow("竖滑流", "上滑看下一条，下滑回上一条，和推特的信息流手感一致。")
                        AboutItemRow("缩放手势", "竖屏与方形视频默认完整适配，横屏视频默认铺满，双击可在适配 / 铺满间切换。")
                        AboutItemRow("全屏", "横屏全屏播放，返回手势从右缘拖动即可跟手退出。")
                    }

                    SectionCard(title = "", subtitle = "", icon = Icons.Outlined.CloudSync) {
                        GroupLabel("数据与备份")
                        AboutItemRow("网盘同步", "在「网络与同步 → 网盘同步」配置 WebDAV，把下载的媒体同步到私有云盘。")
                        AboutItemRow("备份导出", "「存储与备份 → 备份与恢复」可导出收件箱与回收站记录为备份文件，用于换机迁移。")
                        AboutItemRow("回收站", "删除的条目保留 30 天，期间可随时恢复；到期后自动清理。")
                    }

                    SectionCard(title = "", subtitle = "", icon = Icons.Outlined.HelpOutline) {
                        GroupLabel("常见问题")
                        AboutFaqRow(
                            "下载一直失败？",
                            "先在「网络与认证」检查代理是否连通、Cookie 是否过期；失败条目会自动重试 3 次，仍失败通常是原推文受限。"
                        )
                        AboutFaqRow(
                            "分享面板里找不到 Edqiu？",
                            "系统分享面板需要先展开「更多」或「其他应用」，把 Edqiu 拖到常用位置。"
                        )
                        AboutFaqRow(
                            "为什么订阅的新作品不在收件箱？",
                            "订阅作品与手动保存是两条独立通道：订阅作品只进「作品订阅」页，避免污染收件箱。"
                        )
                        AboutFaqRow(
                            "视频看起来有点糊？",
                            "媒体库点「升级」补下清晰版本；若原推文只有低码率源，则无法再提升。"
                        )
                        AboutFaqRow(
                            "换手机怎么迁移？",
                            "先导出备份文件，再用 WebDAV 同步媒体文件；新机安装后导入备份即可接续。"
                        )
                    }

                    SectionCard(title = "", subtitle = "", icon = Icons.Outlined.Info) {
                        GroupLabel("版本与声明")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Edqiu",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "仅供个人学习与内容整理使用。下载内容版权归原作者所有，请遵守 Twitter / X 服务条款，不要用于二次分发或商业用途。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
            }
        }
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
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (active) MaterialTheme.colorScheme.primary else Muted,
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
    // 2026-10-20 设计系统对齐：M3 Card（surfaceContainer 78% 实底）→ L1 玻璃，
    // 与 Edqiu 设置页 SectionCard 同材质（edqiu-design-system.html 组件规范）。
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        tier = GlassTier.L1,
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 2026-10-21 设置页合并：分区标题已由页面头部承担——传空标题时卡片不再
            // 重复渲染一遍标题行（避免「网络与认证」在页头和卡片里各出现一次）
            if (title.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape = CircleShape) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(9.dp).size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
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

// ================= WebDAV 连接状态面板（2026-10 统一通道新增） =================

/**
 * WebDAV 连接状态面板：接入配置与同步状态的集中展示位。
 *
 * 展示项（对齐标准化同步方案要求）：
 * - 连接状态：未配置 / 待验证 / 正在验证 / 已连接（鉴权通过）/ 上次验证失败；
 * - 地址：当前 WebDAV 服务器地址；
 * - 账号：登录账号（密码永不展示，仅显示「已保存」）；
 * - 鉴权结果：上次「测试连接」的时间与结论；
 * - 同步进度：第 i/N 个文件 + 单文件字节进度（来自应用级运行器 WebDavManualSync）；
 * - 错误提示：最近一次同步失败的中文原因（常驻展示直到下次同步覆盖）。
 */
@Composable
private fun WebDavConnectionStatusPanel(
    serverUrl: String,
    username: String,
    hasPassword: Boolean,
    verified: Boolean,
    isTesting: Boolean,
    isSyncing: Boolean,
    lastVerifiedAt: Long,
    syncProgress: WebDavSyncProgress?,
    syncError: String?,
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // ── 连接状态行（语义点 + 文案） ──
            val (statusColor, statusText) = when {
                isTesting -> MaterialTheme.colorScheme.tertiary to "正在验证连接…"
                verified -> Color(0xFF1F8A3D) to "已连接（鉴权通过）"
                lastVerifiedAt > 0 -> MaterialTheme.colorScheme.error to "上次验证失败，请检查后重新测试"
                serverUrl.isNotBlank() && username.isNotBlank() && hasPassword -> Color(0xFFC93400) to "已填写，待验证"
                else -> MaterialTheme.colorScheme.onSurfaceVariant to "未配置"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    "连接状态",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }

            WebDavStatusField("地址", serverUrl.ifBlank { "未填写" })
            WebDavStatusField("账号", username.ifBlank { "未填写" })
            WebDavStatusField("密码", if (hasPassword) "已保存（不展示）" else "未填写")
            if (lastVerifiedAt > 0) {
                WebDavStatusField("上次验证", "${formatSyncTime(lastVerifiedAt)}（${if (verified) "通过" else "失败"}）")
            }

            // ── 同步进度（应用级运行器实时驱动，双向：上传/下载分阶段展示） ──
            if (syncProgress != null && syncProgress.totalFiles > 0) {
                val overall = ((syncProgress.currentIndex - 1).coerceAtLeast(0) + syncProgress.fileProgress) /
                    syncProgress.totalFiles
                LinearProgressIndicator(
                    progress = { overall.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                val phaseLabel = if (syncProgress.phase == WebDavSyncPhase.DOWNLOAD) "正在下载" else "正在上传"
                Text(
                    text = "$phaseLabel ${syncProgress.currentIndex}/${syncProgress.totalFiles} · " +
                        "${syncProgress.currentFileName} · ${(syncProgress.fileProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── 最近一次同步失败原因（常驻，直到下次同步开始覆盖） ──
            if (syncError != null && !isSyncing) {
                Text(
                    "上次同步失败：$syncError",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 状态面板单行字段：左侧灰色标签 + 右侧值（可省略号截断）。 */
@Composable
private fun WebDavStatusField(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/* ── 分区标题映射（2026-10-21 设置页合并）：与新设置页 ui/settings/SettingsScreen 的
   二级页头部保持同一套文案与层级，用户从「我的」任意入口进来看到的标题行一致。 ── */
private fun legacySectionTitle(section: String?): String = when (section) {
    DlSection.UPDATE -> "更新与工具"
    DlSection.NETWORK -> "网络与认证"
    DlSection.WEBDAV -> "网盘同步"
    DlSection.ABOUT -> "关于软件"
    else -> "设置"
}

private fun legacySectionSubtitle(section: String?): String = when (section) {
    DlSection.UPDATE -> "yt-dlp 解析器与 App 版本更新"
    DlSection.NETWORK -> "代理、Cookie 与第三方解析兜底"
    DlSection.WEBDAV -> "WebDAV 连接、双向同步与自动备份"
    DlSection.ABOUT -> "使用指南、下载流程与常见问题"
    else -> "下载器、网络与更新"
}


/* ── 关于软件 · 使用指南排版组件（2026-10-21）──────────────────────────────
   三种行共用同一套视觉语法：主色圆点/序号 + 主标题 + 说明文字，
   与设置页 SettingRow 同源（同字号、同行距、同主色），不引入新样式。 ── */

/** 编号步骤行：圆形序号 + 标题 + 说明。 */
@Composable
private fun AboutStepRow(index: Int, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$index",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

/** 要点行：主色小圆点 + 标题 + 说明（用于「状态说明」「能力列表」这类无序号条目）。 */
@Composable
private fun AboutItemRow(title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

/** FAQ 行：主色 Q 标记 + 问题，下方缩进回答。 */
@Composable
private fun AboutFaqRow(question: String, answer: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Q",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                question,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            answer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
            modifier = Modifier.padding(start = 20.dp, top = 3.dp)
        )
    }
}

/** 卡内分组小标题（与 ui/settings 的 GroupLabel 同规格：11sp ExtraBold 主色 + 0.06 字距）。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.06.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}
