package com.ed.edqiu.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
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

private enum class SubMenu { CONTENT, DOWNLOADER, INBOX }

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
        onBackup = mineNav.openBackupCenter,
        onPath = { mineNav.openDownloaderSection(DlSection.PATH) },
        onNetwork = { mineNav.openDownloaderSection(DlSection.NETWORK) },
        onWebdav = { mineNav.openDownloaderSection(DlSection.WEBDAV) },
        onUpdate = { mineNav.openDownloaderSection(DlSection.UPDATE) },
        onAppearance = { mineNav.openEdqiuSection(XSection.APPEARANCE) },
        onStorageBackup = { mineNav.openEdqiuSection(XSection.BACKUP) },
        onCapture = { mineNav.openEdqiuSection(XSection.CAPTURE) },
        onAbout = { mineNav.openEdqiuSection(XSection.ABOUT) }
    )
}

@Composable
private fun MineMainView(
    expandedSection: SubMenu?,
    onToggleSection: (SubMenu) -> Unit,
    onNavigateToTheme: () -> Unit,
    onDownloadCenter: () -> Unit,
    onTrash: () -> Unit,
    onBackup: () -> Unit,
    onPath: () -> Unit,
    onNetwork: () -> Unit,
    onWebdav: () -> Unit,
    onUpdate: () -> Unit,
    onAppearance: () -> Unit,
    onStorageBackup: () -> Unit,
    onCapture: () -> Unit,
    onAbout: () -> Unit
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
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
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

                ExpandableMenuSection(
                    icon = Icons.Default.Download,
                    title = "内容管理",
                    subtitle = "下载中心、回收站",
                    count = 2,
                    isExpanded = expandedSection == SubMenu.CONTENT,
                    onToggle = { onToggleSection(SubMenu.CONTENT) }
                ) {
                    SubMenuItemRow(Icons.Default.Download, "下载中心", "查看正在下载与已完成的内容", onDownloadCenter)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.Delete, "回收站", "30 天内可恢复已删除项目", onTrash)
                }

                ExpandableMenuSection(
                    icon = Icons.Default.Settings,
                    title = "下载器设置",
                    subtitle = "下载保存、网络认证、WebDAV、更新",
                    count = 4,
                    isExpanded = expandedSection == SubMenu.DOWNLOADER,
                    onToggle = { onToggleSection(SubMenu.DOWNLOADER) }
                ) {
                    SubMenuItemRow(Icons.Default.Save, "下载保存", "存储目录、命名规则与文件格式", onPath)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.Lock, "网络与认证", "代理、Cookie、账号登录", onNetwork)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.Sync, "WebDAV 同步", "远程服务器配置与自动同步", onWebdav)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.Settings, "更新与工具", "版本更新、日志与调试工具", onUpdate)
                }

                ExpandableMenuSection(
                    icon = Icons.Default.AutoAwesome,
                    title = "收件箱设置",
                    subtitle = "外观、存储备份、捕获同步、关于",
                    count = 4,
                    isExpanded = expandedSection == SubMenu.INBOX,
                    onToggle = { onToggleSection(SubMenu.INBOX) }
                ) {
                    SubMenuItemRow(Icons.Default.AutoAwesome, "外观", "莫奈取色、液态玻璃、界面缩放", onAppearance)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.Backup, "存储与备份", "数据库清理、导出导入", onStorageBackup)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.ContentPaste, "捕获与同步", "剪贴板、无障碍、后台更新", onCapture)
                    SubMenuDivider()
                    SubMenuItemRow(Icons.Default.Info, "关于与诊断", "版本信息、反馈、隐私政策", onAbout)
                }
            }
        }

        AppInfoCard()

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun ProfileCard() {
    GlassSurface(
        tier = GlassTier.L2,
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("E", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(
                modifier = Modifier
                    .padding(start = 13.dp)
                    .weight(1f)
            ) {
                Text(
                    "Edqiu 专属用户",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "本地使用 · 无需登录",
                    fontSize = 11.sp,
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
                .clickable(onClick = onToggle)
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

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeIn(),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeOut()
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
            .clickable(onClick = onClick)
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

@Composable
private fun AppInfoCard() {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(11.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("E", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(
                modifier = Modifier
                    .padding(start = 11.dp)
                    .weight(1f)
            ) {
                Text("EDQIU LOAD", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                Text("高效下载 · 智能捕获", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "v${BuildConfig.VERSION_NAME}",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
