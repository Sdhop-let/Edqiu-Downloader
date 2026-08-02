package com.ed.edqiu.ui.navigation

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ed.twitterdownloader.navigation.AppNavigation
import com.ed.twitterdownloader.navigation.MineNav
import com.ed.twitterdownloader.ui.screens.DlSection
import com.ed.edqiu.di.AppContainer
import com.ed.edqiu.di.EdqiuViewModelFactory
import com.ed.edqiu.ui.components.GlassBackground
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.detail.DetailScreen
import com.ed.edqiu.ui.detail.DetailViewModel
import com.ed.edqiu.ui.history.HistoryScreen
import com.ed.edqiu.ui.history.HistoryViewModel
import com.ed.edqiu.ui.list.ListScreen
import com.ed.edqiu.ui.list.ListViewModel
import com.ed.edqiu.ui.backup.CloudBackupScreen
import com.ed.edqiu.ui.backup.CloudBackupViewModel
import com.ed.edqiu.ui.settings.BackupViewModel
import com.ed.edqiu.ui.settings.SettingsScreen as EdqiuSettingsScreen
import com.ed.edqiu.ui.settings.XSection
import com.ed.edqiu.ui.theme.Monet
import com.ed.edqiu.ui.theme.EdqiuTheme

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未提供")
}

val LocalSnackbarController = staticCompositionLocalOf<SnackbarController> {
    error("SnackbarController 未提供")
}

private object Routes {
    const val DOWNLOAD_SHELL = "download_shell"
    const val DETAIL = "detail/{tweetId}"
    const val BACKUP = "backup_center"
    fun detail(tweetId: String) = "detail/$tweetId"
}

@Composable
fun EdqiuApp(container: AppContainer) {
    val seedIndex by container.settingsRepository.seedColorIndexFlow
        .collectAsStateWithLifecycle(initialValue = 0)
    val dynamicColor by container.settingsRepository.dynamicColorFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val seedColor = Monet.seedPresets.getOrNull(seedIndex)?.seed ?: Monet.seedPresets[0].seed

    // dynamicColor=true → API 31+ 跟随壁纸；false（默认）→ 锁定种子色方案（保证状态色/胶囊稳定）
    EdqiuTheme(seed = seedColor, dynamicColor = dynamicColor) {
        CompositionLocalProvider(LocalAppContainer provides container) {
            val nav = rememberNavController()
            val application = LocalContext.current.applicationContext as Application
            val factory = remember(container, application) {
                EdqiuViewModelFactory(
                    savedLinkRepository = container.savedLinkRepository,
                    settingsRepository = container.settingsRepository,
                    linkCaptureCoordinator = container.linkCaptureCoordinator,
                    linkHistoryRepository = container.linkHistoryRepository,
                    historyBackupRepository = container.historyBackupRepository,
                    backupProviderRegistry = container.backupProviderRegistry,
                    backupTaskStore = container.backupTaskStore,
                    backupEngine = container.backupEngine,
                    backupCredentialStore = container.backupCredentialStore,
                    application = application
                )
            }

            val snackbarHostState = remember { SnackbarHostState() }
            val snackbarController = remember { SnackbarController() }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                snackbarController.observe(scope, snackbarHostState)
            }

            CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
                GlassBackground(
                    seed = seedColor,
                    blurRadius = 0.dp
                ) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        // 让内容延伸到状态栏后（每个页面的 HeaderPanel 自己加 statusBarsPadding）
                        contentWindowInsets = WindowInsets(0),
                        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                    ) { innerPadding ->
                        NavHost(
                            navController = nav,
                            startDestination = Routes.DOWNLOAD_SHELL,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            composable(Routes.DOWNLOAD_SHELL) {
                                AppNavigation(
                                    inboxContent = {
                                        val vm = viewModel<ListViewModel>(factory = factory)
                                        ListScreen(
                                            vm = vm,
                                            onOpenDetail = { nav.navigate(Routes.detail(it)) }
                                        )
                                    },
                                    trashContent = {
                                        val vm = viewModel<HistoryViewModel>(factory = factory)
                                        HistoryScreen(vm = vm)
                                    },
                                    settingsContent = { _, mineNav ->
                                        UnifiedSettingsScreen(
                                            mineNav = mineNav
                                        )
                                    },
                                    edqiuSettingsContent = { section, onBack ->
                                        val backupVm = viewModel<BackupViewModel>(factory = factory)
                                        EdqiuSettingsScreen(
                                            settings = container.settingsRepository,
                                            backupVm = backupVm,
                                            onBack = onBack,
                                            showBack = true,
                                            section = section
                                        )
                                    },
                                    openCloudBackup = { nav.navigate(Routes.BACKUP) }
                                )
                            }
                            composable(
                                route = Routes.DETAIL,
                                arguments = listOf(navArgument("tweetId") { type = NavType.StringType })
                            ) { backStack ->
                                val tweetId = backStack.arguments?.getString("tweetId").orEmpty()
                                val vm = viewModel<DetailViewModel>(factory = factory)
                                DetailScreen(
                                    vm = vm,
                                    tweetId = tweetId,
                                    onBack = { nav.popBackStack() }
                                )
                            }
                            composable(Routes.BACKUP) {
                                val vm = viewModel<CloudBackupViewModel>(factory = factory)
                                CloudBackupScreen(
                                    vm = vm,
                                    onBack = { nav.popBackStack() }
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
private fun UnifiedSettingsScreen(
    mineNav: MineNav
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 头部：Edqiu Load 品牌卡
        // statusBarsPadding 在 GlassSurface 自身（保证 inset），
        // 但外层 Column 仍加 padding top=14dp 让玻璃卡**离屏幕顶有一定间隙**（用户要求"不要太靠边"）
        Column(modifier = Modifier.statusBarsPadding().padding(top = 14.dp)) {
        GlassSurface(
            tier = GlassTier.L2,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "E",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "EDQIU LOAD",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.08.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "下载、存储与收件箱集中管理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = "v${com.ed.edqiu.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        }

        // 分组一：内容管理
        MineGroupCard(title = "内容") {
            MineEntryRow(
                icon = Icons.Outlined.TaskAlt,
                title = "下载中心",
                subtitle = "查看进行中与已完成的下载任务",
                onClick = mineNav.openDownloadCenter
            )
            MineEntryRow(
                icon = Icons.Outlined.Delete,
                title = "回收站",
                subtitle = "已删除的链接与历史记录",
                onClick = mineNav.openTrash
            )
        }

        // 分组二：下载器设置
        MineGroupCard(title = "下载器") {
            MineEntryRow(
                icon = Icons.Outlined.Folder,
                title = "下载保存",
                subtitle = "媒体文件保存位置与自定义路径",
                onClick = { mineNav.openDownloaderSection(DlSection.PATH) }
            )
            MineEntryRow(
                icon = Icons.Outlined.Security,
                title = "网络与认证",
                subtitle = "代理、Cookie 与受限内容解析",
                onClick = { mineNav.openDownloaderSection(DlSection.NETWORK) }
            )
            MineEntryRow(
                icon = Icons.Outlined.Cloud,
                title = "WebDAV 同步",
                subtitle = "把下载媒体同步到私有云盘",
                onClick = { mineNav.openDownloaderSection(DlSection.WEBDAV) }
            )
            MineEntryRow(
                icon = Icons.Outlined.SystemUpdate,
                title = "更新与工具",
                subtitle = "yt-dlp 与 App 版本更新",
                onClick = { mineNav.openDownloaderSection(DlSection.UPDATE) }
            )
        }

        // 分组三：收件箱设置
        MineGroupCard(title = "收件箱") {
            MineEntryRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "外观",
                subtitle = "莫奈种子色与动态取色",
                onClick = { mineNav.openEdqiuSection(XSection.APPEARANCE) }
            )
            MineEntryRow(
                icon = Icons.Outlined.FolderOpen,
                title = "存储与备份",
                subtitle = "监控目录、自动备份与导入恢复",
                onClick = { mineNav.openEdqiuSection(XSection.BACKUP) }
            )
            MineEntryRow(
                icon = Icons.Outlined.Sync,
                title = "捕获与同步",
                subtitle = "剪贴板捕获、失败重试与后台刷新",
                onClick = { mineNav.openEdqiuSection(XSection.CAPTURE) }
            )
            MineEntryRow(
                icon = Icons.Outlined.Info,
                title = "关于与诊断",
                subtitle = "版本、输入法与运行状态",
                onClick = { mineNav.openEdqiuSection(XSection.ABOUT) }
            )
        }

        // 分组四：网盘备份
        MineGroupCard(title = "网盘备份") {
            MineEntryRow(
                icon = Icons.Outlined.Cloud,
                title = "网盘备份",
                subtitle = "百度 / 123 / 阿里云盘直连备份下载媒体",
                onClick = mineNav.openBackupCenter
            )
        }
    }
}

/** 分组卡片：玻璃 + 组标题 */
@Composable
private fun MineGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            content()
        }
    }
}

/** 入口行：图标 + 标题/副标题 + 右箭头 */
@Composable
private fun MineEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
