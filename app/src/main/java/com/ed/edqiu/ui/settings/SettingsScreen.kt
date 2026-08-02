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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.edqiu.data.backup.HistoryBackupRepository
import com.ed.edqiu.data.backup.RestoreMode
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.system.DeviceCapabilityReader
import com.ed.edqiu.ui.components.DynamicSwitch
import com.ed.edqiu.ui.navigation.LocalSnackbarController
import com.ed.edqiu.ui.theme.Monet
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

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbar.show(it)
            backupVm.clearFeedback()
        }
    }

    val monitorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && context.persistTreePermission(uri)) {
            scope.launch { settings.setMonitorDirUri(uri.toString()) }
            snackbar.show("监控目录已更新")
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
                        text = "收件箱设置",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "外观、存储备份、捕获与同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (section == null || section == XSection.APPEARANCE) {
        // 外观：莫奈种子色选择器
        SectionCard(
            title = "外观",
            subtitle = "莫奈种子色决定全 App 的主色调（状态色不随壁纸变化）",
            icon = Icons.Default.AutoAwesome
        ) {
            SwitchRow(
                title = "动态取色（跟随壁纸）",
                description = "关闭后使用下方预设种子色；开启时跟随 Android 12+ 系统壁纸自动取色",
                checked = dynamicColor,
                onCheckedChange = { scope.launch { settings.setDynamicColor(it) } }
            )
            // 动态取色开启时灰显种子色选择（提示用户当前在跟随壁纸）
            SeedColorSelector(
                selectedIndex = seedIndex,
                enabled = !dynamicColor,
                onSelect = { index ->
                    scope.launch {
                        // 选种子色 = 明确手动指定 → 自动关闭动态取色
                        settings.setDynamicColor(false)
                        settings.setSeedColorIndex(index)
                    }
                    snackbar.show("已切换到「${Monet.seedPresets.getOrNull(index)?.name ?: "墨蓝"}」种子色")
                }
            )
        }
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
                text = "默认扫描 com.ed.twitterdownload，并兼容 com.ed.Edqiu、com.ed.edqiu、com.ed.twitterdownloader 旧目录。Android/data 可通过默认路径或手动路径保存。",
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
                    snackbar.show("监控目录已更新")
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("保存监控路径") }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
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
    "/storage/emulated/0/Android/data/com.ed.twitterdownload/files/Download"
