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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VideoLibrary
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

private enum class SubMenu { LIBRARY, AUTOMATION, NETWORK, LOOK, STORAGE, GENERAL }

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
        onNetwork = { mineNav.openDownloaderSection(DlSection.NETWORK) },
        onWebdav = { mineNav.openDownloaderSection(DlSection.WEBDAV) },
        onSubscription = { mineNav.openSubscription() },
        onUpdate = { mineNav.openDownloaderSection(DlSection.UPDATE) },
        onAbout = { mineNav.openDownloaderSection(DlSection.ABOUT) },
        onAppearance = { mineNav.openEdqiuSection(XSection.APPEARANCE) },
        onStorage = { mineNav.openEdqiuSection(XSection.STORAGE) },
        onBackupRestore = { mineNav.openEdqiuSection(XSection.BACKUP) },
        onCapture = { mineNav.openEdqiuSection(XSection.CAPTURE) },
        onPlayback = { mineNav.openEdqiuSection(XSection.PLAYBACK) }
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
    onNetwork: () -> Unit,
    onWebdav: () -> Unit,
    onSubscription: () -> Unit,
    onUpdate: () -> Unit,
    onAbout: () -> Unit,
    onAppearance: () -> Unit,
    onStorage: () -> Unit,
    onBackupRestore: () -> Unit,
    onCapture: () -> Unit,
    onPlayback: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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

        // 2026-10-15 IA 重做：去掉「组标题套同名卡」的套娃层级——卡即分组，
        // 六张领域卡纵向排列，全部入口重命名（卡名/子项名/描述一次到位）。
        // 心智模型：作品库（看与管）→ 订阅与自动化（让作品自己来）→ 网络与同步（连云端）
        // → 外观与播放（观感）→ 存储与备份（数据放哪）→ 通用（版本维护）
        ExpandableMenuSection(
            icon = Icons.Default.VideoLibrary,
            title = "作品库",
            subtitle = "浏览与管理已下载的内容",
            isExpanded = expandedSection == SubMenu.LIBRARY,
            onToggle = { onToggleSection(SubMenu.LIBRARY) }
        ) {
            SubMenuItemRow(Icons.Default.Download, "下载中心", "正在下载与已完成的作品", onDownloadCenter)
            SubMenuDivider()
            SubMenuItemRow(Icons.Default.Delete, "回收站", "已删除项目保留 30 天，可随时恢复", onTrash)
            SubMenuDivider()
            SubMenuItemRow(Icons.Default.Person, "作者中心", "按作者归档浏览与订阅", onAuthors)
        }
        ExpandableMenuSection(
            icon = Icons.Default.Bolt,
            title = "订阅与自动化",
            subtitle = "让新作品自己找上门",
            isExpanded = expandedSection == SubMenu.AUTOMATION,
            onToggle = { onToggleSection(SubMenu.AUTOMATION) }
        ) {
            SubMenuItemRow(Icons.Default.NotificationsActive, "作品订阅", "订阅作者主页，新作品自动入库下载", onSubscription)
            SubMenuDivider()
            SubMenuItemRow(Icons.Default.ContentPaste, "下载规则", "失败自动重试与后台状态核对", onCapture)
        }
        ExpandableMenuSection(
            icon = Icons.Default.Public,
            title = "网络与同步",
            subtitle = "连接方式与云端备份",
            isExpanded = expandedSection == SubMenu.NETWORK,
            onToggle = { onToggleSection(SubMenu.NETWORK) }
        ) {
            SubMenuItemRow(Icons.Default.Lock, "网络连接", "代理 / Cookie / 受限内容解析", onNetwork)
            SubMenuDivider()
            SubMenuItemRow(Icons.Default.Sync, "网盘同步", "WebDAV 直连私有云盘，双向同步备份", onWebdav)
        }
        ExpandableMenuSection(
            icon = Icons.Default.AutoAwesome,
            title = "外观与播放",
            subtitle = "界面观感与播放体验",
            isExpanded = expandedSection == SubMenu.LOOK,
            onToggle = { onToggleSection(SubMenu.LOOK) }
        ) {
            SubMenuItemRow(Icons.Default.AutoAwesome, "外观质感", "主题颜色 / 玻璃与底栏 / 显示 / 触感", onAppearance)
            SubMenuDivider()
            SubMenuItemRow(Icons.Default.SmartDisplay, "播放体验", "双播放器 / 滑动手势 / 退场定位", onPlayback)
        }
        ExpandableMenuSection(
            icon = Icons.Default.Save,
            title = "存储与备份",
            subtitle = "数据放在哪、怎么保",
            isExpanded = expandedSection == SubMenu.STORAGE,
            onToggle = { onToggleSection(SubMenu.STORAGE) }
        ) {
            SubMenuItemRow(Icons.Default.Save, "存储路径", "下载存储 · 监控目录 · 数据备份", onStorage)
            SubMenuDivider()
            SubMenuItemRow(Icons.Default.Backup, "备份与恢复", "收件箱与回收站数据导入导出", onBackupRestore)
        }
        ExpandableMenuSection(
            icon = Icons.Default.Settings,
            title = "通用",
            subtitle = "版本更新与维护工具",
            isExpanded = expandedSection == SubMenu.GENERAL,
            onToggle = { onToggleSection(SubMenu.GENERAL) }
        ) {
            SubMenuItemRow(Icons.Default.Settings, "更新与工具", "版本更新、日志与调试工具", onUpdate)
        }
        StaticMenuRow(
            icon = Icons.Default.Info,
            title = "关于本机",
            subtitle = "当前版本 v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
            onClick = onAbout
        )

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

    // 2026-10-15 IA 重做：玻璃底从组级（SettingsGroup）移到卡级——每张领域卡
    // 独立玻璃卡（同款 L1 / 20dp 圆角），展开内容收进同一块玻璃内
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp)
    ) {
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
    // 2026-10-15：与领域卡同款玻璃底（卡级统一，关于本机不再裸奔）
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp)
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
