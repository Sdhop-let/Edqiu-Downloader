package com.ed.edqiu.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.edqiu.data.backup.HistoryBackupRepository
import com.ed.edqiu.data.backup.RestoreMode
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.system.DeviceCapabilityReader
import com.ed.edqiu.ui.components.DynamicSwitch
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.InlineFeedbackBar
import com.ed.edqiu.ui.navigation.LocalSnackbarController
import com.ed.edqiu.ui.navigation.SnackbarController
import com.ed.edqiu.ui.theme.Monet
import com.ed.edqiu.ui.theme.MonetSpec
import com.ed.edqiu.ui.theme.ThemeMode
import com.ed.edqiu.ui.theme.TonalStyle
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private val Accent = Color(0xFF0F766E)
private val Ink = Color(0xFF101417)
private val Muted = Color(0xFF64748B)

/** 收件箱设置分组标识（二级菜单入口用） */
object XSection {
    const val APPEARANCE = "appearance"
    const val BACKUP = "backup"
    const val CAPTURE = "capture"
    const val ABOUT = "about"
}

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    backupVm: BackupViewModel,
    onBack: () -> Unit,
    showBack: Boolean = false,
    section: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val monitorUri by settings.monitorDirUriFlow.collectAsStateWithLifecycle(initialValue = null)
    val autoCapture by settings.autoCaptureFlow.collectAsStateWithLifecycle(initialValue = true)
    val autoRetry by settings.autoRetryFlow.collectAsStateWithLifecycle(initialValue = true)
    val backgroundSync by settings.backgroundSyncFlow.collectAsStateWithLifecycle(initialValue = true)
    val backupDirUri by backupVm.backupDirUri.collectAsStateWithLifecycle()
    val automaticBackup by backupVm.automaticBackup.collectAsStateWithLifecycle()
    val lastBackupAt by backupVm.lastBackupAt.collectAsStateWithLifecycle()
    val lastBackupError by backupVm.lastBackupError.collectAsStateWithLifecycle()
    val pendingImport by backupVm.pendingImport.collectAsStateWithLifecycle()
    val busy by backupVm.busy.collectAsStateWithLifecycle()
    val feedback by backupVm.feedback.collectAsStateWithLifecycle()
    var manualMonitorPath by remember { mutableStateOf("") }
    var manualBackupPath by remember { mutableStateOf("") }
    var confirmReplace by remember { mutableStateOf(false) }

    LaunchedEffect(onBack) {
        // Keep the callback part of the signature for navigation parity with other screens.
    }

    LaunchedEffect(monitorUri) {
        manualMonitorPath = when (monitorUri) {
            null, SettingsRepository.DEFAULT_MONITOR_URI -> DEFAULT_DOWNLOAD_PATH
            else -> monitorUri.orEmpty().removePrefix("file://")
        }
    }

    LaunchedEffect(backupDirUri) {
        manualBackupPath = backupDirUri.orEmpty().removePrefix("file://")
    }

    // 内联反馈：操作结果固定显示在触发按钮所属卡片内，5s 后动画消失（替代全局顶部 Snackbar）
    var backupFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(feedback) {
        feedback?.let {
            backupFeedback = it
            backupVm.clearFeedback()
        }
    }

    var monitorFeedback by remember { mutableStateOf<String?>(null) }

    val monitorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && context.persistTreePermission(uri)) {
            scope.launch { settings.setMonitorDirUri(uri.toString()) }
            monitorFeedback = "监控目录已更新"
        }
    }
    val backupDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && context.persistTreePermission(uri)) backupVm.setBackupDirectory(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(HistoryBackupRepository.MIME_TYPE)) { uri ->
        uri?.let(backupVm::exportTo)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(backupVm::prepareImport)
    }

    val seedIndex by settings.seedColorIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
    val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)

    pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = backupVm::cancelImport,
            title = { Text("导入备份") },
            text = { Text("备份包含 ${backup.preview.activeCount} 条收件箱记录和 ${backup.preview.historyCount} 条回收站记录。已下载状态、文件路径和错误重试信息会一起恢复。") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { backupVm.restorePending(RestoreMode.MERGE) }) { Text("合并导入") }
                    TextButton(onClick = { confirmReplace = true }) { Text("替换全部") }
                }
            },
            dismissButton = { TextButton(onClick = backupVm::cancelImport) { Text("取消") } }
        )
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("确认替换全部数据？") },
            text = { Text("当前数据会先生成安全快照，然后由备份内容替换。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    backupVm.restorePending(RestoreMode.REPLACE)
                }) { Text("确认替换") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // statusBarsPadding + 顶部 14dp 视觉呼吸 → 返回箭头明显避开状态栏
            .statusBarsPadding()
            .padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (showBack) {
            // 二级页返回箭头 + 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onBack),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
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
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (section == XSection.APPEARANCE) "主题设置" else "收件箱设置",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (section == XSection.APPEARANCE) "外观、莫奈取色与界面缩放" else "外观、存储备份、捕获与同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (section == null || section == XSection.APPEARANCE) {
            // 主题设置页（2026-08-15 按设计稿重构）
            ThemeSettingsSection(
                settings = settings,
                scope = scope,
                snackbar = snackbar
            )
        }

        if (section == null || section == XSection.BACKUP) {
        SectionCard(
            title = "存储与备份",
            subtitle = "Android/data 监控、自动备份和导入恢复集中管理",
            icon = Icons.Default.FolderOpen
        ) {
            SettingRow(
                icon = Icons.Default.FolderOpen,
                title = "下载监控目录",
                subtitle = displayMonitorUri(monitorUri),
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted) },
                onClick = { monitorPicker.launch(null) }
            )
            Text(
                text = "默认扫描 com.ed.Edqiu，并兼容 com.ed.edqiu、com.ed.twitterdownload 旧目录。Android/data 可通过默认路径或手动路径保存。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = manualMonitorPath,
                onValueChange = { manualMonitorPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手动监控路径") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            FilledTonalButton(
                onClick = {
                    val normalized = normalizeMonitorPath(manualMonitorPath)
                    scope.launch { settings.setMonitorDirUri(normalized) }
                    monitorFeedback = "监控目录已更新"
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("保存监控路径") }
            // 反馈固定显示在「保存监控路径」按钮下方，5s 后动画消失
            InlineFeedbackBar(
                message = monitorFeedback,
                onDismiss = { monitorFeedback = null }
            )
            TextButton(onClick = { scope.launch { settings.setMonitorDirUri(null) } }) {
                Text("恢复默认监控目录")
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFFE2E8F0))

            SettingRow(
                icon = Icons.Default.Backup,
                title = "自动备份目录",
                subtitle = backupDirUri ?: "未设置",
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted) },
                onClick = { backupDirectoryPicker.launch(null) }
            )
            OutlinedTextField(
                value = manualBackupPath,
                onValueChange = { manualBackupPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手动备份路径") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            FilledTonalButton(
                onClick = { backupVm.setBackupDirectoryPath(normalizeMonitorPath(manualBackupPath)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("保存备份路径") }
            SwitchRow("每日自动备份", "每天保留最近 7 份备份", automaticBackup, backupVm::setAutomaticBackup)
            Button(
                onClick = backupVm::backupNow,
                enabled = !busy && backupDirUri != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("立即备份")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportPicker.launch(HistoryBackupRepository.backupFileName(System.currentTimeMillis())) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("导出") }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("application/json", "text/plain")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("导入") }
            }
            lastBackupAt?.let {
                StatusLine(Icons.Default.CloudDone, "最近备份：${DateFormat.getDateTimeInstance().format(Date(it))}")
            }
            lastBackupError?.let {
                Text("最近错误：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            // 备份/导入/导出反馈固定显示在卡片按钮下方，5s 后动画消失
            InlineFeedbackBar(
                message = backupFeedback,
                onDismiss = { backupFeedback = null }
            )
        }
        }

        if (section == null || section == XSection.CAPTURE) {
        SectionCard(
            title = "捕获与同步",
            subtitle = "剪贴板捕获、失败重试和后台状态刷新",
            icon = Icons.Default.Sync
        ) {
            SwitchRow("回到前台检查剪贴板", "App 回到前台时读取一次当前剪贴板", autoCapture) {
                scope.launch { settings.setAutoCapture(it) }
            }
            SwitchRow("下载失败后自动重试", "按退避间隔自动重试失败链接", autoRetry) {
                scope.launch { settings.setAutoRetry(it) }
            }
            SwitchRow("后台更新下载状态", "系统允许时定期扫描监控目录", backgroundSync) {
                scope.launch { settings.setBackgroundSync(it) }
            }
            val accessibilityEnabled = remember { DeviceCapabilityReader.isAccessibilityCaptureEnabled(context) }
            StatusLine(
                icon = Icons.Default.Verified,
                text = "无障碍捕获：${if (accessibilityEnabled) "已开启" else "未开启"}",
                color = if (accessibilityEnabled) Accent else MaterialTheme.colorScheme.error
            )
            OutlinedButton(
                onClick = { runCatching { context.startActivity(DeviceCapabilityReader.accessibilitySettingsIntent()) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("打开系统无障碍设置")
            }
        }
        }

        if (section == null || section == XSection.ABOUT) {
        SectionCard(
            title = "关于与诊断",
            subtitle = "版本、输入法和当前运行状态",
            icon = Icons.Default.Info
        ) {
            val inputMethod = remember { DeviceCapabilityReader.currentInputMethod(context) }
            StatusLine(Icons.Default.AutoAwesome, "当前输入法：${inputMethod?.displayName ?: "无法识别"}")
            inputMethod?.componentName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StatusLine(Icons.Default.Info, "Edqiu 1.2.0 (build 3)")
            StatusLine(Icons.Default.Restore, "导入恢复会保留已下载状态、文件路径和下载时间")
        }
        }
    }
}

@Composable
private fun SeedColorSelector(
    selectedIndex: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit
) {
    val presets = Monet.seedPresets
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            presets.forEachIndexed { index, preset ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(preset.seed.copy(alpha = if (enabled) 1f else 0.32f))
                        .then(
                            if (!enabled) Modifier.border(1.dp, Color.White, CircleShape)
                            else if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            else Modifier.border(1.dp, Color.White, CircleShape)
                        )
                        .clickable(enabled = enabled) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected && enabled) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    } else if (!enabled) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
        Text(
            text = presets.getOrNull(selectedIndex)?.name ?: "墨蓝",
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) Muted else Muted.copy(alpha = 0.5f),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        tier = GlassTier.L1,
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = Accent.copy(alpha = 0.10f), shape = CircleShape) {
                    Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.padding(10.dp).size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Ink)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clickable(onClick = onClick)
    Row(
        modifier = modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        DynamicSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusLine(
    icon: ImageVector,
    text: String,
    color: Color = Muted
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun displayMonitorUri(uri: String?): String = when (uri) {
    null, SettingsRepository.DEFAULT_MONITOR_URI -> DEFAULT_DOWNLOAD_PATH
    else -> uri.removePrefix("file://")
}

private fun normalizeMonitorPath(value: String): String {
    val trimmed = value.trim().trim('"')
    if (trimmed.isBlank()) return SettingsRepository.DEFAULT_MONITOR_URI
    if (trimmed == SettingsRepository.DEFAULT_MONITOR_URI || trimmed.startsWith("content://")) return trimmed
    val withoutFileScheme = trimmed.removePrefix("file://")
    if (withoutFileScheme.startsWith("/")) return withoutFileScheme
    val clean = withoutFileScheme
        .removePrefix("内部存储/")
        .removePrefix("内置存储/")
        .removePrefix("sdcard/")
        .removePrefix("/sdcard/")
        .trimStart('/')
    return File(Environment.getExternalStorageDirectory(), clean).absolutePath
}

private fun android.content.Context.persistTreePermission(uri: Uri): Boolean {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    return runCatching {
        contentResolver.takePersistableUriPermission(uri, flags)
        true
    }.getOrElse { error ->
        Log.w("SettingsScreen", "Failed to persist tree permission for $uri", error)
        false
    }
}

private const val DEFAULT_DOWNLOAD_PATH =
    "/storage/emulated/0/Android/data/com.ed.Edqiu/files/Download"

private val ScienceIcon = Icons.Outlined.Science
private val StyleIcon = Icons.Outlined.Style

/**
 * 主题设置页（2026-08-15 按设计稿重构）
 *
 * 布局：顶部预览卡 + 主题模式 segmented + 三卡组（颜色 / 效果 / 手势）。
 * 所有开关 / 下拉接 SettingsRepository 持久化。
 */
@Composable
private fun ThemeSettingsSection(
    settings: SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarController
) {
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
    val accentColor by settings.accentColorFlow.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_ACCENT_COLOR)
    val tonalStyle by settings.tonalStyleFlow.collectAsStateWithLifecycle(initialValue = TonalStyle.TONAL_SPOT)
    val monetSpec by settings.monetSpecFlow.collectAsStateWithLifecycle(initialValue = MonetSpec.SPEC_2021)
    val blurEnabled by settings.blurEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val blurIntensity by settings.blurIntensityFlow.collectAsStateWithLifecycle(initialValue = 0.6f)
    val floatingTabBar by settings.floatingTabBarFlow.collectAsStateWithLifecycle(initialValue = true)
    val liquidGlass by settings.liquidGlassEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val predictiveBack by settings.predictiveBackFlow.collectAsStateWithLifecycle(initialValue = true)
    val displayScale by settings.displayScaleFlow.collectAsStateWithLifecycle(initialValue = 0.8f)
    val accent = Color(accentColor)

    var accentPickerOpen by remember { mutableStateOf(false) }
    var tonalExpanded by remember { mutableStateOf(false) }
    var specExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 顶部预览卡
        ThemePreviewCard(keyColor = accent, liquidGlassEnabled = liquidGlass)

        // 主题模式 segmented
        ThemeSegmented(
            selected = themeMode,
            onSelect = { mode -> scope.launch { settings.setThemeMode(mode) } }
        )

        // ===== 颜色卡组 =====
        GroupCard {
            SettingItemRow(
                icon = Icons.Outlined.Palette,
                title = "启用 Monet 颜色",
                subtitle = "开启后中性面（背景/卡片）跟随 Android 12+ 系统壁纸",
                trailing = {
                    DynamicSwitch(
                        checked = dynamicColor,
                        onCheckedChange = { scope.launch { settings.setDynamicColor(it) } }
                    )
                }
            )
            ThinDivider()
            SettingItemRow(
                icon = Icons.Outlined.Brush,
                title = "强调色",
                subtitle = "全局强调色，实时作用于按钮、链接与选中态",
                onClick = { accentPickerOpen = true },
                trailing = {
                    AccentSwatch(accent)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                }
            )
            ThinDivider()
            Box {
                SettingItemRow(
                    icon = StyleIcon,
                    title = "色彩风格",
                    subtitle = "Material 3 多风格色板（TonalSpot/Vibrant/Expressive…）",
                    onClick = { tonalExpanded = true },
                    trailing = { ValueDropdown(tonalStyle.label) }
                )
                DropdownMenu(expanded = tonalExpanded, onDismissRequest = { tonalExpanded = false }) {
                    TonalStyle.entries.forEach { style ->
                        DropdownMenuItem(
                            text = { Text(style.label) },
                            onClick = {
                                scope.launch { settings.setTonalStyle(style) }
                                tonalExpanded = false
                            }
                        )
                    }
                }
            }
            ThinDivider()
            Box {
                SettingItemRow(
                    icon = ScienceIcon,
                    title = "色彩标准",
                    subtitle = "色度计算标准，切换后配色即时更新",
                    onClick = { specExpanded = true },
                    trailing = { ValueDropdown(monetSpec.label) }
                )
                DropdownMenu(expanded = specExpanded, onDismissRequest = { specExpanded = false }) {
                    MonetSpec.entries.forEach { spec ->
                        DropdownMenuItem(
                            text = { Text(spec.label) },
                            onClick = {
                                scope.launch { settings.setMonetSpec(spec) }
                                specExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // ===== 效果卡组 =====
        GroupCard {
            SettingItemRow(
                icon = Icons.Outlined.BlurOn,
                title = "模糊",
                subtitle = "背景莫奈色域与玻璃磨砂层的模糊",
                trailing = {
                    DynamicSwitch(
                        checked = blurEnabled,
                        onCheckedChange = { scope.launch { settings.setBlurEnabled(it) } }
                    )
                }
            )
            if (blurEnabled) {
                Text(
                    text = "模糊强度 ${(blurIntensity * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )
                Slider(
                    value = blurIntensity,
                    onValueChange = { scope.launch { settings.setBlurIntensity(it) } },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }
            ThinDivider()
            SettingItemRow(
                icon = Icons.Outlined.Visibility,
                title = "悬浮底栏",
                subtitle = "使用 Apple 风格的悬浮底栏",
                trailing = {
                    DynamicSwitch(
                        checked = floatingTabBar,
                        onCheckedChange = { scope.launch { settings.setFloatingTabBar(it) } }
                    )
                }
            )
            ThinDivider()
            SettingItemRow(
                icon = Icons.Outlined.Layers,
                title = "液态玻璃",
                subtitle = "全局玻璃质感（半透明/磨砂/折射），各层级统一生效",
                trailing = {
                    DynamicSwitch(
                        checked = liquidGlass,
                        onCheckedChange = { scope.launch { settings.setLiquidGlassEnabled(it) } }
                    )
                }
            )
        }

        // ===== 手势卡组 =====
        GroupCard {
            SettingItemRow(
                icon = Icons.Outlined.SwipeRight,
                title = "预测性返回手势",
                subtitle = "从屏幕边缘右滑返回，动画实时跟随手指（Android 14+）",
                trailing = {
                    DynamicSwitch(
                        checked = predictiveBack,
                        onCheckedChange = { scope.launch { settings.setPredictiveBack(it) } }
                    )
                }
            )
            SettingItemRow(
                icon = Icons.Outlined.AspectRatio,
                title = "界面缩放",
                subtitle = "调整全局显示比例",
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${(displayScale * 100).toInt()}%",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Muted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
            Slider(
                value = displayScale,
                onValueChange = { scope.launch { settings.setDisplayScale(it) } },
                valueRange = 0.7f..1.0f,
                steps = 5,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }
    }

    if (accentPickerOpen) {
        AccentColorPickerDialog(
            initial = accent,
            onDismiss = { accentPickerOpen = false },
            onConfirm = { argb ->
                scope.launch { settings.setAccentColor(argb) }
                accentPickerOpen = false
            }
        )
    }
}

/** 强调色小圆点（右侧值预览） */
@Composable
private fun AccentSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.5.dp, Color.White, CircleShape)
    )
}

/** 强调色扩展预设（预设色 + 常用色） */
private val AccentPresets: List<Pair<String, Int>> = listOf(
    "墨蓝" to 0xFF2F4C8F.toInt(),
    "珊瑚橙" to 0xFFE8583A.toInt(),
    "抹茶绿" to 0xFF3E7C4F.toInt(),
    "薰衣草紫" to 0xFF7C5CBF.toInt(),
    "玫瑰粉" to 0xFFC45B7E.toInt(),
    "曜石黑" to 0xFF101417.toInt(),
    "天空蓝" to 0xFF2563EB.toInt(),
    "青瓷" to 0xFF0F766E.toInt(),
    "琥珀" to 0xFFB45309.toInt(),
    "莓红" to 0xFFB91C1C.toInt(),
    "靛蓝" to 0xFF4F46E5.toInt(),
    "灰蓝" to 0xFF64748B.toInt()
)

/**
 * 强调色完整选择器：预设色板 + HSV 三滑块（色相/饱和度/明度）+ 实时预览。
 * 满足「完整的颜色选择」——可选取任意色，选中即写入强调色并全局生效。
 */
@Composable
private fun AccentColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val initHsv = remember(initial) { colorToHsv(initial) }
    var hue by remember { mutableStateOf(initHsv[0]) }
    var sat by remember { mutableStateOf(initHsv[1]) }
    var value by remember { mutableStateOf(initHsv[2]) }
    val current = Color.hsv(hue, sat, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择强调色", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 预设色板
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentPresets.chunked(6).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { (name, argb) ->
                                val c = Color(argb)
                                val selected = argb == current.toArgb()
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val hsv = colorToHsv(c)
                                            hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .border(
                                                width = if (selected) 2.5.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.White,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                    Text(name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                // 实时预览 + 色相滑杆
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(current)
                            .border(1.dp, Color.White, RoundedCornerShape(14.dp))
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("色相", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Brush.horizontalGradient((0..360 step 36).map { Color.hsv(it.toFloat(), 1f, 1f) }))
                        ) {
                            Slider(
                                value = hue,
                                onValueChange = { hue = it },
                                valueRange = 0f..360f,
                                modifier = Modifier.matchParentSize(),
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color.Transparent,
                                    inactiveTrackColor = Color.Transparent,
                                    thumbColor = Color.White,
                                    activeTickColor = Color.Transparent,
                                    inactiveTickColor = Color.Transparent
                                )
                            )
                        }
                    }
                }

                // 饱和度滑杆
                HueSatValSlider(
                    label = "饱和度",
                    value = sat,
                    onValueChange = { sat = it },
                    gradient = Brush.horizontalGradient(
                        listOf(
                            Color.hsv(hue, 0f, value),
                            Color.hsv(hue, 1f, value)
                        )
                    )
                )
                // 明度滑杆
                HueSatValSlider(
                    label = "明度",
                    value = value,
                    onValueChange = { value = it },
                    gradient = Brush.horizontalGradient(
                        listOf(
                            Color.hsv(hue, sat, 0f),
                            Color.hsv(hue, sat, 1f)
                        )
                    )
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(current.toArgb()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 通用 HSV 滑杆（带渐变轨道） */
@Composable
private fun HueSatValSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    gradient: Brush
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(gradient)
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..1f,
                modifier = Modifier.matchParentSize(),
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = Color.White,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
        }
    }
}

/** RGB → HSV（h ∈ [0,360), s/v ∈ [0,1]） */
private fun colorToHsv(c: Color): FloatArray {
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val v = max
    val d = max - min
    val s = if (max == 0f) 0f else d / max
    var h = 0f
    if (d > 0f) {
        h = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
    }
    return floatArrayOf(h, s, v)
}

/** 顶部预览卡：mini header + tab 栏 + 4 色块（按设计稿） */
@Composable
private fun ThemePreviewCard(keyColor: Color, liquidGlassEnabled: Boolean) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // header 预览（灰色矩形）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            )
            // tab 栏预览（玻璃时高一点，圆角更大）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (liquidGlassEnabled) 40.dp else 32.dp)
                    .clip(RoundedCornerShape(if (liquidGlassEnabled) 14.dp else 10.dp))
                    .background(
                        if (liquidGlassEnabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.30f))
                )
            }
            // 4 色块（强调色 + 3 深色）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(keyColor))
                Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)))
                Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f)))
                Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)))
            }
        }
    }
}

/** 主题模式 segmented：跟随系统 / 浅色 / 深色 */
@Composable
private fun ThemeSegmented(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        tier = GlassTier.L1,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            ThemeMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.surface.copy(alpha = 0.92f) else Color.Transparent)
                        .clickable { onSelect(mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mode.label,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 玻璃卡组容器 */
@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            content()
        }
    }
}

/** 设置项：图标 + 标题 + 副标题 + 右侧控件 */
@Composable
private fun SettingItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val rowMod = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
    Row(
        modifier = rowMod.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}

/** 右侧值 + 下拉箭头（按钮样式） */
@Composable
private fun ValueDropdown(value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

/** 卡组内细分割线 */
@Composable
private fun ThinDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    )
}
