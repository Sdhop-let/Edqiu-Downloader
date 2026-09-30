package com.ed.edqiu.ui.screens

import com.ed.edqiu.ui.util.pressableNoRipple
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ed.edqiu.BuildConfig
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.settings.XSection
import com.ed.edqiu.navigation.MineNav
import com.ed.edqiu.ui.screens.DlSection

private enum class SubMenu { CONTENT, DOWNLOADER, APPEARANCE, STORAGE }

@Composable
fun MineScreen(mineNav: MineNav) {
    var expandedSection by remember { mutableStateOf<SubMenu?>(null) }

    BackHandler(enabled = expandedSection != null) {
        expandedSection = null
    }

    MineMainView(
        expandedSection = expandedSection,
        onToggleSection = { section ->
            expandedSection = if (expandedSection == section) null else section
        },
        onNavigateToTheme = { mineNav.openEdqiuSection(XSection.APPEARANCE) },
        onDownloadCenter = mineNav.openDownloadCenter,
        onTrash = mineNav.openTrash,
        onAuthors = mineNav.openAuthors,
        onBackup = mineNav.openBackupCenter,
        onNetwork = { mineNav.openDownloaderSection(DlSection.NETWORK) },
        onWebdav = { mineNav.openDownloaderSection(DlSection.WEBDAV) },
        onPreDownload = { mineNav.openDownloaderSection(DlSection.PREDOWNLOAD) },
        onUpdate = { mineNav.openDownloaderSection(DlSection.UPDATE) },
        onAbout = { mineNav.openDownloaderSection(DlSection.ABOUT) },
        onAppearance = { mineNav.openEdqiuSection(XSection.APPEARANCE) },
        onStorage = { mineNav.openEdqiuSection(XSection.STORAGE) },
        onBackupRestore = { mineNav.openEdqiuSection(XSection.BACKUP) },
        onCapture = { mineNav.openEdqiuSection(XSection.CAPTURE) }
    )
}

@Composable
private fun MineMainView(
    expandedSection: SubMenu?,
    onToggleSection: (SubMenu) -> Unit,
    onNavigateToTheme: () -> Unit,
    onDownloadCenter: () -> Unit,
    onTrash: () -> Unit,
    onAuthors: () -> Unit,
    onBackup: () -> Unit,
    onNetwork: () -> Unit,
    onWebdav: () -> Unit,
    onPreDownload: () -> Unit,
    onUpdate: () -> Unit,
    onAbout: () -> Unit,
    onAppearance: () -> Unit,
    onStorage: () -> Unit,
    onBackupRestore: () -> Unit,
    onCapture: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(
            text = "我的",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp
            ),
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            color = MaterialTheme.colorScheme.onBackground
        )

        ProfileCard()

        // 2026-09-30 版式重构（对齐设计稿）：单卡「设置」拆为三张分组卡，
        // 分组之间以小标题区隔；入口仍为「点按展开」以保证全部子功能可达。
        // ① 内容与下载：看与管内容 + 下载引擎自身的网络、管道与行为
        SettingsGroup(title = "内容与下载") {
            ExpandableMenuSection(
                icon = Icons.Default.Download,
                title = "内容管理",
                subtitle = "浏览与管理已下载的媒体内容",
                isExpanded = expandedSection == SubMenu.CONTENT,
                onToggle = { onToggleSection(SubMenu.CONTENT) }
            ) {
                SubMenuItemRow(Icons.Default.Download, "下载中心", "查看正在下载与已完成的内容", onDownloadCenter)
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Delete, "回收站", "已删除项目保留 30 天，可随时恢复", onTrash)
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Person, "作者作品", "按作者归档浏览已下载作品", onAuthors)
            }
            GroupItemDivider()
            ExpandableMenuSection(
                icon = Icons.Default.Settings,
                title = "下载器",
                subtitle = "下载网络、自动下载与重试行为",
                badge = "3",
                isExpanded = expandedSection == SubMenu.DOWNLOADER,
                onToggle = { onToggleSection(SubMenu.DOWNLOADER) }
            ) {
                SubMenuItemRow(Icons.Default.Lock, "网络与认证", "代理、Cookie 与受限内容解析", onNetwork)
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Download, "预下载", "攒批自动下载、作品订阅与网盘联动", onPreDownload)
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.ContentPaste, "下载行为", "失败自动重试与后台状态核对", onCapture)
            }
        }

        // ② 偏好设置：全局界面观感
        SettingsGroup(title = "偏好设置") {
            ExpandableMenuSection(
                icon = Icons.Default.AutoAwesome,
                title = "外观",
                subtitle = "主题颜色、玻璃质感与手势触感",
                isExpanded = expandedSection == SubMenu.APPEARANCE,
                onToggle = { onToggleSection(SubMenu.APPEARANCE) }
            ) {
                SubMenuItemRow(Icons.Default.AutoAwesome, "外观设置", "颜色 / 玻璃与底栏 / 显示 / 手势与触感", onAppearance)
            }
        }

        // ③ 系统与维护：文件与数据「存哪、怎么备份、怎么维护」+ 版本信息
        //    （2026-09-30：新增「关于本机」行，版本/构建号从顶部身份块右侧移入其副标题）
        SettingsGroup(title = "系统与维护") {
            ExpandableMenuSection(
                icon = Icons.Default.Save,
                title = "存储与维护",
                subtitle = "路径、备份与版本更新工具",
                isExpanded = expandedSection == SubMenu.STORAGE,
                onToggle = { onToggleSection(SubMenu.STORAGE) }
            ) {
                SubMenuItemRow(Icons.Default.Save, "存储路径", "① 下载存储 · ② 监控目录 · ③ 数据备份，三类路径分设", onStorage)
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Backup, "备份与恢复", "收件箱/回收站数据的本地备份与导入导出", onBackupRestore)
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Cloud, "网盘备份中心", "媒体云端备份：各网盘授权、任务与状态", onBackup)
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Sync, "WebDAV 同步", "私有云盘直连配置与自动同步", onWebdav)
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Settings, "更新与工具", "版本更新、日志与调试工具", onUpdate)
            }
            GroupItemDivider()
            StaticMenuRow(
                icon = Icons.Default.Info,
                title = "关于本机",
                subtitle = "当前版本 v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                onClick = onAbout
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

/**
 * 顶部身份块（2026-09-30 对齐设计稿）。
 *
 * - 头像：56dp 圆角方块 + 主色渐变 + 半透明白描边；
 * - 身份行：标题 Bold + 一行「本地账户 · 数据仅存于本机」说明；
 * - 右侧：改为进入箭头「›」（版本/构建号已移入「系统与维护 · 关于本机」行副标题，
 *   顶部身份块不再堆版本信息）。
 */
@Composable
private fun ProfileCard() {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "E",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    "Edqiu 专属用户",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.2.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "本地账户 · 数据仅存于本机",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 分组容器（2026-09-30）：小标题 + 一张玻璃卡，组内条目共享同一卡面，
 * 条目间以 GroupItemDivider 分隔——替代原先所有入口挤在同一张「设置」大卡里。
 */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(start = 14.dp, bottom = 7.dp)
        )
        GlassSurface(
            tier = GlassTier.L1,
            shape = RoundedCornerShape(20.dp)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun GroupItemDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
        thickness = 0.5.dp
    )
}

/**
 * 分组内的可展开入口行（2026-09-30 版式对齐设计稿：32dp 图标座 + 15sp 标题 + 徽标可选）。
 * 折叠时箭头朝右「›」，展开后旋转 90° 朝下；badge 为 null 时不显示徽标。
 */
@Composable
private fun ExpandableMenuSection(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String? = null,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "chevron"
    )

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pressableNoRipple { onToggle() }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (badge != null) {
                Text(
                    badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp)
                    .rotate(chevronRotation)
            )
        }

        // 2026-09-14：展开动画利落化 —— 原 MediumBouncy+StiffnessLow 慢弹跳拖沓生硬；
        // 改为 iOS 手感的微弹快速展开（~250ms 落定，弹跳极轻），fade 同步
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
            ) + fadeIn(tween(180)),
            exit = shrinkVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
            ) + fadeOut(tween(140))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 2.dp, bottom = 8.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * 静态入口行（2026-09-30）：无展开子项、点按直接跳转（如「关于本机」），
 * 与 ExpandableMenuSection 共用同一套行版式。
 */
@Composable
private fun StaticMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressableNoRipple { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(17.dp)
            )
        }
        Column(
            modifier = Modifier
                .padding(start = 14.dp)
                .weight(1f)
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(20.dp)
        )
    }
}

@Composable
private fun SubMenuItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressableNoRipple { onClick() }
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp)
            )
        }
        Column(
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f)
        ) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier
                .size(16.dp)
                .rotate(-90f)
        )
    }
}

@Composable
private fun SubMenuDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
        thickness = 0.5.dp
    )
}
