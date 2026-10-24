package com.ed.edqiu.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ed.edqiu.BuildConfig
import com.ed.edqiu.navigation.MineNav
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.settings.XSection
import com.ed.edqiu.ui.util.pressableNoRipple
import java.util.Locale

private enum class SubMenu { LIBRARY, AUTOMATION, NETWORK, LOOK, STORAGE, GENERAL }

/**
 * 我的（2026-10-20 定稿：新头部 + 统计卡保留，入口列表恢复 v1.9.2 旧版六领域卡）。
 *
 * 版式（自上而下）：
 * - 220dp 平台色渐变头（#5E5CE6 → #007AFF → #0A2540）：68dp「秋」头像 + 小秋 + 版本行；
 * - L1 统计卡：三列等宽 + 1px 分隔 + 等宽数字（已下载 / 占用空间 / 收件箱，全部真实计数）；
 * - 六张领域卡（2026-10-15 IA，点标题展开子项）：作品库 / 订阅与自动化 / 网络与同步 /
 *   外观与播放 / 存储与备份 / 通用，+ 关于本机静态行；
 * - 本屏无 L3（二级页收敛范式）。
 *
 * 备份中心（2026-10 新媒体备份浮层）不走本页入口，经「网络与同步 → 网盘同步 →
 * 同步情况详情」可达（WebDAV 测试连接通过后显示）。
 */
@Composable
fun MineScreen(mineNav: MineNav) {
    var expandedSection by remember { mutableStateOf<SubMenu?>(null) }

    BackHandler(enabled = expandedSection != null) {
        expandedSection = null
    }

    val statsVm: MineStatsViewModel = viewModel()
    val stats by statsVm.stats.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ===== 身份头部（2026-10-24 重设计）：不透明平台色横幅退役 =====
        // 旧版 220dp #5E5CE6→#007AFF→#0A2540 实底渐变在壁纸舞台上是「贴上去的色块」，
        // 与底缘玻璃卡片的衔接生硬（用户反馈突兀）。新版 = 「浮在舞台上的身份区」：
        // primary 光晕衬底（与 GlassBackground 光斑同语言，色彩氛围保留）+
        // 玻璃环头像（暖色核心保留品牌身份）+ 主题色文字（浅深双主题可读），
        // 与下方统计卡/领域卡同处一个玻璃世界，无边界线。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
            contentAlignment = Alignment.Center,
        ) {
            // primary 径向光晕：身份区的色彩来源，边缘透明融进壁纸（默认圆心=区域中心）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                Color.Transparent
                            ),
                            radius = 620f
                        )
                    )
            )
            Column(
                modifier = Modifier.padding(top = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 玻璃环 + 暖色核心：外圈 L2 玻璃圆接入材质世界，内核保留品牌暖渐变
                GlassSurface(
                    tier = GlassTier.L2,
                    shape = CircleShape,
                    modifier = Modifier.size(84.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFFFD36E), Color(0xFFFF375F))
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "秋",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "小秋",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Edqiu v${BuildConfig.VERSION_NAME} · 本地账户",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.5.sp,
                )
            }
        }

        // ===== 主体：统计卡 + 六领域卡菜单（2026-10-24 常规流式布局，
        // 移除旧 -24dp 上叠 hack——头部不再是不透明横幅，无需压边衔接）=====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatsCard(stats)

            // 心智模型：作品库（看与管）→ 订阅与自动化（让作品自己来）→ 网络与同步（连云端）
            // → 外观与播放（观感）→ 存储与备份（数据放哪）→ 通用（版本维护）
            ExpandableMenuSection(
                icon = Icons.Default.VideoLibrary,
                title = "作品库",
                subtitle = "浏览与管理已下载的内容",
                isExpanded = expandedSection == SubMenu.LIBRARY,
                onToggle = { expandedSection = toggle(expandedSection, SubMenu.LIBRARY) }
            ) {
                SubMenuItemRow(Icons.Default.Download, "下载中心", "正在下载与已完成的作品", mineNav.openDownloadCenter)
                SubMenuDivider()
                // 2026-10-24：30 天保留期清理已实装（启动时清过期归档），文案恢复承诺
                SubMenuItemRow(Icons.Default.Delete, "回收站", "已删除项目保留 30 天，可随时恢复", mineNav.openTrash)
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Person, "作者中心", "按作者归档浏览与订阅", mineNav.openAuthors)
            }
            ExpandableMenuSection(
                icon = Icons.Default.Bolt,
                title = "订阅与自动化",
                subtitle = "让新作品自己找上门",
                isExpanded = expandedSection == SubMenu.AUTOMATION,
                onToggle = { expandedSection = toggle(expandedSection, SubMenu.AUTOMATION) }
            ) {
                SubMenuItemRow(Icons.Default.NotificationsActive, "作品订阅", "订阅作者主页，新作品自动入库，点按下载") { mineNav.openSubscription() }
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.ContentPaste, "下载行为", "失败重试策略与后台状态核对") { mineNav.openEdqiuSection(XSection.CAPTURE) }
            }
            ExpandableMenuSection(
                icon = Icons.Default.Public,
                title = "网络与同步",
                subtitle = "连接方式与云端备份",
                isExpanded = expandedSection == SubMenu.NETWORK,
                onToggle = { expandedSection = toggle(expandedSection, SubMenu.NETWORK) }
            ) {
                SubMenuItemRow(Icons.Default.Lock, "网络连接", "代理 / Cookie / 受限内容解析") { mineNav.openEdqiuSection(XSection.NETWORK) }
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Sync, "网盘同步", "WebDAV 直连私有云盘，双向同步备份") { mineNav.openEdqiuSection(XSection.WEBDAV) }
            }
            ExpandableMenuSection(
                icon = Icons.Default.AutoAwesome,
                title = "外观与播放",
                subtitle = "界面观感与播放体验",
                isExpanded = expandedSection == SubMenu.LOOK,
                onToggle = { expandedSection = toggle(expandedSection, SubMenu.LOOK) }
            ) {
                SubMenuItemRow(Icons.Default.AutoAwesome, "外观质感", "主题颜色 / 玻璃与底栏 / 显示 / 触感") { mineNav.openEdqiuSection(XSection.APPEARANCE) }
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.SmartDisplay, "播放体验", "双播放器 / 滑动手势 / 退场定位") { mineNav.openEdqiuSection(XSection.PLAYBACK) }
            }
            ExpandableMenuSection(
                icon = Icons.Default.Save,
                title = "存储与备份",
                subtitle = "数据放在哪、怎么保",
                isExpanded = expandedSection == SubMenu.STORAGE,
                onToggle = { expandedSection = toggle(expandedSection, SubMenu.STORAGE) }
            ) {
                SubMenuItemRow(Icons.Default.Save, "存储路径", "下载存储 · 监控目录 · 数据备份") { mineNav.openEdqiuSection(XSection.STORAGE) }
                SubMenuDivider()
                SubMenuItemRow(Icons.Default.Backup, "备份与恢复", "收件箱与回收站数据导入导出") { mineNav.openEdqiuSection(XSection.BACKUP) }
            }
            ExpandableMenuSection(
                icon = Icons.Default.Settings,
                title = "通用",
                subtitle = "版本更新与维护工具",
                isExpanded = expandedSection == SubMenu.GENERAL,
                onToggle = { expandedSection = toggle(expandedSection, SubMenu.GENERAL) }
            ) {
                SubMenuItemRow(Icons.Default.Settings, "更新与工具", "版本更新、日志与调试工具") { mineNav.openEdqiuSection(XSection.UPDATE) }
            }
            StaticMenuRow(
                icon = Icons.Default.Info,
                title = "关于软件",
                subtitle = "使用指南、常见问题 · v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                onClick = { mineNav.openEdqiuSection(XSection.ABOUT) }
            )

            // 悬浮 Tab 栏净空
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/** 展开态切换：再点一次同一张卡收起，点别的卡换展开（手风琴）。 */
private fun toggle(current: SubMenu?, target: SubMenu): SubMenu? =
    if (current == target) null else target

/** L1 统计卡：三列等宽 + 1px 分隔线 + 等宽数字。 */
@Composable
private fun StatsCard(stats: MineStatsViewModel.Stats) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(
                value = stats.downloadedCount.toString(),
                label = "已下载",
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            StatCell(
                value = formatStorage(stats.storageBytes),
                label = "占用空间",
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            StatCell(
                value = stats.inboxCount.toString(),
                label = "收件箱",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 9.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 28.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
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

// ================= 语义色与格式化 =================

/** 占用空间：5.2G / 312M / 128K（设计规范等宽口径，整数位无小数点）。 */
private fun formatStorage(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1fG", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0fM", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0fK", bytes / 1024.0)
    else -> "$bytes"
}
