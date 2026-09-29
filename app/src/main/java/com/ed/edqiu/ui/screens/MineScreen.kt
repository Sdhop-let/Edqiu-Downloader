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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
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

        GlassSurface(
            tier = GlassTier.L1,
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = "设置",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.06.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                // ① 内容管理（2026-09-28 二次重组）：只放「看与管内容」的入口
                ExpandableMenuSection(
                    icon = Icons.Default.Download,
                    title = "内容管理",
                    subtitle = "浏览与管理已下载的媒体内容",
                    count = 3,
                    isExpanded = expandedSection == SubMenu.CONTENT,
                    onToggle = { onToggleSection(SubMenu.CONTENT) }
                ) {
                    SubMenuItemRow(Icons.Default.Download, "下载中心", "查看正在下载与已完成的内容", onDownloadCenter)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.Delete, "回收站", "已删除项目保留 30 天，可随时恢复", onTrash)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.Person, "作者作品", "按作者归档浏览已下载作品", onAuthors)
                }

                // ② 下载器（二次重组）：只放下载引擎自身的网络、管道与行为，
                //    云端同步（WebDAV/备份中心）移入「存储与维护」
                ExpandableMenuSection(
                    icon = Icons.Default.Settings,
                    title = "下载器",
                    subtitle = "下载网络、自动下载管道与重试行为",
                    count = 3,
                    isExpanded = expandedSection == SubMenu.DOWNLOADER,
                    onToggle = { onToggleSection(SubMenu.DOWNLOADER) }
                ) {
                    SubMenuItemRow(Icons.Default.Lock, "网络与认证", "代理、Cookie 与受限内容解析", onNetwork)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.Download, "预下载", "攒批自动下载、作品订阅与网盘联动", onPreDownload)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.ContentPaste, "下载行为", "失败自动重试与后台状态核对", onCapture)
                }

                // ③ 外观（二次重组）：全局界面观感，独立成组
                ExpandableMenuSection(
                    icon = Icons.Default.AutoAwesome,
                    title = "外观",
                    subtitle = "主题颜色、玻璃质感、显示比例与手势触感",
                    count = 1,
                    isExpanded = expandedSection == SubMenu.APPEARANCE,
                    onToggle = { onToggleSection(SubMenu.APPEARANCE) }
                ) {
                    SubMenuItemRow(Icons.Default.AutoAwesome, "外观设置", "颜色 / 玻璃与底栏 / 显示 / 手势与触感", onAppearance)
                }

                // ④ 存储与维护（二次重组）：文件与数据「存哪、怎么备份、怎么维护」；
                //    原「收件箱设置」解散——外观独立成组，存储/备份归此组，下载行为归下载器
                ExpandableMenuSection(
                    icon = Icons.Default.Save,
                    title = "存储与维护",
                    subtitle = "存储路径、数据与媒体备份、版本更新与工具",
                    count = 5,
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
            }
        }

        // 底部 AppInfoCard 已删除（2026-09-28 判定冗余）：版本/构建号并入顶部身份块，
        // "高效下载 · 智能捕获" 标语随捕获功能下线而过时，纯装饰无操作价值

        Spacer(modifier = Modifier.height(80.dp))
    }
}

/**
 * 顶部身份块（2026-09-28 三轮：正式优雅化）。
 *
 * 设计语言收敛为"一张安静的身份卡"：
 * - 头像：56dp 圆角方块 + 主色渐变 + 半透明白描边（精致感），字母字重从 ExtraBold 降为 Bold；
 * - 身份行：标题用 Bold + 轻微字距，替代双 chip 的状态改为「状态点 + 一行说明」，
 *   去掉碎片化的徽标堆叠；
 * - 右侧：版本 + 构建号纵向小字（原底部 AppInfoCard 的信息价值并入此处，
 *   AppInfoCard 已判定冗余删除——标语过时、版本重复、纯装饰无操作）。
 */
@Composable
private fun ProfileCard() {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
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
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.2.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        "本地账户 · 数据仅存于本机",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Build ${BuildConfig.VERSION_CODE}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ExpandableMenuSection(
    icon: ImageVector,
    title: String,
    subtitle: String,
    count: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevron"
    )

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pressableNoRipple { onToggle() }
                .padding(vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                count.toString(),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
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
                    .padding(start = 42.dp, top = 2.dp, bottom = 6.dp)
            ) {
                content()
            }
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

