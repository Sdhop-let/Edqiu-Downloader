# XInvox UI 重构 · Compose 实现代码

> 对应设计原型：`figma-redesign/prototype-md-2026-08-17.html`  
> 目标：将收件箱、我的、主题设置三屏重构为 Liquid Glass × Monet 风格，并适配一加 15 修长比例。

## 1. 项目技术栈确认

- **UI 框架**：Jetpack Compose + Material3
- **包名**：`com.ed.edqiu`
- **主要源码目录**：`app/src/main/java/com/ed/edqiu/`
- **已有基础组件**：
  - `ui/components/GlassSurface.kt` — 毛玻璃表面
  - `ui/components/LiquidTabBar.kt` — 液态玻璃底栏
  - `ui/theme/MonetColor.kt` — Monet 取色
  - `ui/theme/Theme.kt` — 主题入口
  - `ui/theme/ThemeEffects.kt` — 液态玻璃开关/模糊强度
- **需要改造/新增**：
  - `ui/list/ListScreen.kt` — 收件箱主界面
  - `ui/settings/SettingsScreen.kt` — 我的 / 主题设置（按二级菜单拆分）
  - `ui/components/LiquidTabBar.kt` — 悬浮胶囊 Tab Bar
  - `ui/theme/Shape.kt` / `Type.kt` — 圆角与字重微调

---

## 2. 设计 token 建议

在 `ui/theme/Color.kt` 中新增语义色，避免业务层硬编码：

```kotlin
// 状态色已在项目中，新增玻璃/中性辅助色
val GlassHighlight = Color(0xFFFFFFFF)
val GlassShadow    = Color(0xFF0F172A)

// 如需区分三层玻璃透明度，可直接使用 GlassTier 枚举
```

在 `ui/theme/Shape.kt` 中统一圆角：

```kotlin
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
```

---

## 3. GlassSurface 微调

当前 `GlassSurface` 已较完整，主要按原型增强：

1. **L1/L2/L3 透明度**：与原型对齐
   - L1 `bgAlpha = 0.55f`
   - L2 `bgAlpha = 0.68f`
   - L3 `bgAlpha = 0.78f`
2. **blur 强度**：读取 `ThemeEffects.BlurStrength.current` 后映射到 `Modifier.blur()`。
3. **底部反光**：保留，但让底部更干净（避免形成脏色块）。
4. **边缘描边**：在 `liquidGlass = true` 时，顶部高光 alpha 提升到 `0.50f`（浅色主题）。

建议修改 `GlassSurface.kt` 中 `GlassTier` 的默认值为：

```kotlin
enum class GlassTier(val bgAlpha: Float, val edgeAlpha: Float) {
    L1(0.55f, 0.42f),
    L2(0.68f, 0.52f),
    L3(0.78f, 0.58f)
}
```

---

## 4. LiquidTabBar 改造：悬浮胶囊

目标效果：
- Tab Bar 外壳完全透明，仅选中胶囊有玻璃背景。
- 胶囊尺寸与单个 Tab 按钮完全贴合。
- 高度 50dp，bottom 8dp，圆角 16dp。

替换 `LiquidTabBar.kt` 中 glass 分支的外壳实现：

```kotlin
@Composable
fun LiquidTabBar(
    tabs: List<LiquidTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    liquidGlassEnabled: Boolean = true
) {
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val onSurfaceVariant  = MaterialTheme.colorScheme.onSurfaceVariant
    val dark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    val liquidGlass = liquidGlassEnabled && ThemeEffects.LiquidGlassEnabled.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 16.dp)
    ) {
        val barWidth = maxWidth
        val itemWidth = barWidth / tabs.size

        var pressedIndex by remember { mutableStateOf(-1) }
        val capsuleIndex = if (pressedIndex >= 0) pressedIndex else selectedIndex
        val capsuleTargetX = itemWidth * capsuleIndex
        val capsuleX by animateFloatAsState(
            targetValue = capsuleTargetX.value,
            animationSpec = if (pressedIndex >= 0) tween(50) else spring(dampingRatio = 0.6f, stiffness = 480f),
            label = "capsuleX"
        )

        // 仅胶囊有玻璃背景
        if (liquidGlass) {
            GlassSurface(
                tier = GlassTier.L2,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .offset(x = capsuleX.dp)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 3.dp, vertical = 3.dp)
            ) { }
        } else {
            Box(
                modifier = Modifier
                    .offset(x = capsuleX.dp)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 3.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(primaryContainer)
            )
        }

        // Tab 项
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.12f else 1f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
                    label = "scale$index"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .semantics { role = Role.Tab }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(index) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.scale(iconScale)
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) onPrimaryContainer else onSurfaceVariant,
                            modifier = Modifier.size(21.dp)
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
                            exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 3 }
                        ) {
                            Text(
                                text = tab.label,
                                color = onPrimaryContainer,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 11.sp,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
```

---

## 5. 收件箱屏幕 ListScreen

保持现有 `LazyColumn` 结构，主要把背景、Header、Card、底栏替换为玻璃组件。

### 5.1 页面背景

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    // 莫奈色域背景
    GlassBackground(
        seed = MaterialTheme.colorScheme.primary,
        blurRadius = 46.dp
    )

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        // 列表 + 底栏
    }
}
```

### 5.2 Header 区域

```kotlin
@Composable
private fun InboxHeader(
    pendingCount: Int,
    downloadedCount: Int,
    failedCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSortClick: () -> Unit,
    selectedFilter: Filter,
    onFilterChange: (Filter) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = "收件箱",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "捕获链接、下载进度与历史记录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 统计卡片
        GlassSurface(
            tier = GlassTier.L1,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem("待处理", pendingCount.toString())
                MetricItem("已完成", downloadedCount.toString())
                MetricItem("失败", failedCount.toString())
            }
        }

        // 搜索 + 排序
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            GlassSurface(
                tier = GlassTier.L1,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 15.dp)
                ) {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 9.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        ),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text("搜索链接或标题", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                            inner()
                        }
                    )
                }
            }
            GlassSurface(
                tier = GlassTier.L1,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(46.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onSortClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 筛选 chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Filter.entries.forEach { f ->
                val selected = f == selectedFilter
                GlassSurface(
                    tier = if (selected) GlassTier.L2 else GlassTier.L1,
                    shape = RoundedCornerShape(12.dp),
                    elevated = false,
                    modifier = Modifier.clickable { onFilterChange(f) }
                ) {
                    Text(
                        text = f.label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                 else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}
```

### 5.3 LinkCard 玻璃化

如果 `LinkCard.kt` 已存在，建议外层包裹 `GlassSurface(tier = GlassTier.L1, shape = RoundedCornerShape(18.dp))`，内部结构不变。若不存在，核心实现如下：

```kotlin
@Composable
fun LinkCard(
    link: SavedLink,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 缩略图
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // 信息区
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(link.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(link.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadge(status = link.status)
                    Text(link.createdAt, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
```

### 5.4 Scaffold 底部插槽

```kotlin
Scaffold(
    containerColor = Color.Transparent,
    bottomBar = {
        LiquidTabBar(
            tabs = listOf(
                LiquidTab("收件箱", Icons.Outlined.Inbox, Icons.Filled.Inbox),
                LiquidTab("媒体库", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
                LiquidTab("我的", Icons.Outlined.Person, Icons.Filled.Person)
            ),
            selectedIndex = 0,
            onSelect = { /* 切换导航 */ }
        )
    }
) { innerPadding ->
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 0.dp,
            bottom = innerPadding.calculateBottomPadding() + 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { InboxHeader(...) }
        items(links) { link -> LinkCard(link, ...) }
    }
}
```

---

## 6. 我的屏幕 MineScreen（新增）

建议新增 `ui/screens/MineScreen.kt` 或在 `SettingsScreen.kt` 中以二级菜单形式实现。

### 6.1 一级菜单

```kotlin
@Composable
fun MineScreen(
    onNavigateToContent: () -> Unit,
    onNavigateToDownloader: () -> Unit,
    onNavigateToInboxSettings: () -> Unit,
    onNavigateToTheme: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        GlassBackground(seed = MaterialTheme.colorScheme.primary, blurRadius = 42.dp)

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
                modifier = Modifier.padding(top = 12.dp, start = 4.dp)
            )

            // Edqiu 专属用户卡（非登录入口）
            ProfileCard()

            // 设置分组
            GlassSurface(tier = GlassTier.L1, shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "设置",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.06.em,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    MenuRow(
                        icon = Icons.Default.Download,
                        title = "内容管理",
                        subtitle = "下载中心、回收站、网盘备份",
                        count = 3,
                        onClick = onNavigateToContent
                    )
                    MenuRow(
                        icon = Icons.Default.Settings,
                        title = "下载器设置",
                        subtitle = "下载保存、网络认证、WebDAV、更新",
                        count = 4,
                        onClick = onNavigateToDownloader
                    )
                    MenuRow(
                        icon = Icons.Default.AutoAwesome,
                        title = "收件箱设置",
                        subtitle = "外观、存储备份、捕获同步、关于",
                        count = 4,
                        onClick = onNavigateToInboxSettings
                    )
                }
            }

            // 应用信息小卡
            AppInfoCard()

            Spacer(modifier = Modifier.height(80.dp))
        }
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
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    count: Int,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
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
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AppInfoCard() {
    GlassSurface(tier = GlassTier.L1, shape = RoundedCornerShape(18.dp)) {
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
                Text("EDQIU LOAD", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text("高效下载 · 智能捕获", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "v1.3.0",
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
```

### 6.2 二级菜单页

```kotlin
@Composable
fun ContentManagementScreen(onBack: () -> Unit) {
    SubMenuScreen(
        title = "内容管理",
        onBack = onBack,
        items = listOf(
            MenuItem(Icons.Default.Download, "下载中心", "查看正在下载与已完成的内容"),
            MenuItem(Icons.Default.Delete, "回收站", "30 天内可恢复已删除项目"),
            MenuItem(Icons.Default.Cloud, "网盘备份", "WebDAV / 云盘同步与备份")
        )
    )
}

@Composable
fun DownloaderSettingsScreen(onBack: () -> Unit) {
    SubMenuScreen(
        title = "下载器设置",
        onBack = onBack,
        items = listOf(
            MenuItem(Icons.Default.Save, "下载保存", "存储目录、命名规则与文件格式"),
            MenuItem(Icons.Default.Lock, "网络与认证", "代理、Cookie、账号登录"),
            MenuItem(Icons.Default.Sync, "WebDAV 同步", "远程服务器配置与自动同步"),
            MenuItem(Icons.Default.Settings, "更新与工具", "版本更新、日志与调试工具")
        )
    )
}

@Composable
fun InboxSettingsScreen(
    onBack: () -> Unit,
    onNavigateToTheme: () -> Unit
) {
    SubMenuScreen(
        title = "收件箱设置",
        onBack = onBack,
        items = listOf(
            MenuItem(Icons.Default.AutoAwesome, "外观", "莫奈取色、液态玻璃、界面缩放", onClick = onNavigateToTheme),
            MenuItem(Icons.Default.Backup, "存储与备份", "数据库清理、导出导入"),
            MenuItem(Icons.Default.ContentPaste, "捕获与同步", "剪贴板、无障碍、后台更新"),
            MenuItem(Icons.Default.Info, "关于与诊断", "版本信息、反馈、隐私政策")
        )
    )
}

data class MenuItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: (() -> Unit)? = null
)

@Composable
fun SubMenuScreen(
    title: String,
    onBack: () -> Unit,
    items: List<MenuItem>
) {
    Box(modifier = Modifier.fillMaxSize()) {
        GlassBackground(seed = MaterialTheme.colorScheme.primary, blurRadius = 42.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
            ) {
                GlassSurface(
                    tier = GlassTier.L2,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }

            GlassSurface(
                tier = GlassTier.L1,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    items.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.13f),
                                thickness = 1.dp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { item.onClick?.invoke() }
                                .padding(vertical = 9.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .weight(1f)
                            ) {
                                Text(item.title, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
                                Text(item.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
```

---

## 7. 主题设置屏幕 ThemeSettingsScreen

基于现有 `SettingsScreen.kt` 改造，保留 Monet 取色/种子色/风格/标准/液态玻璃开关等所有功能，仅把卡片玻璃化。

### 7.1 预览卡片

```kotlin
@Composable
private fun ThemePreviewCard() {
    GlassSurface(
        tier = GlassTier.L1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Monet × Liquid Glass",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
```

### 7.2 种子色选择器

```kotlin
@Composable
private fun SeedColorRow(
    presets: List<SeedPreset>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        presets.forEachIndexed { index, preset ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(preset.seed)
                    .then(
                        if (selected) Modifier.border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = CircleShape
                        ) else Modifier
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
```

### 7.3 设置行玻璃化

所有设置行外层使用 `GlassSurface(tier = GlassTier.L1, shape = RoundedCornerShape(20.dp))` 包裹，内部保留 `HorizontalDivider` 分隔。

开关继续使用现有 `DynamicSwitch`。

---

## 8. 一加 15 适配

一加 15 屏幕比例约 20:9（1272×2772），修长。Compose 中主要做两件事：

1. **底部安全区**：使用 `WindowInsets.navigationBars` 而不是固定 80dp。
2. **Tab Bar 位置**：`Modifier.navigationBarsPadding()` + 额外 8dp。

```kotlin
val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

Scaffold(
    bottomBar = { LiquidTabBar(...) }
) { innerPadding ->
    LazyColumn(
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 0.dp,
            bottom = innerPadding.calculateBottomPadding() + 8.dp
        )
    ) { ... }
}
```

对于某些固定尺寸（如 50dp Tab Bar、48dp 头像），保持 dp 值不变即可自动按屏幕密度缩放；无需为 2K 屏单独写 px。

---

## 9. 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `ui/components/LiquidTabBar.kt` | 修改 | 悬浮胶囊 Tab Bar |
| `ui/components/GlassSurface.kt` | 微调 | 玻璃层级 alpha/高光 |
| `ui/list/ListScreen.kt` | 修改 | 收件箱玻璃化 |
| `ui/settings/SettingsScreen.kt` | 拆分 | 拆出 MineScreen + ThemeSettingsScreen |
| `ui/screens/MineScreen.kt` | 新增 | 我的页面一级/二级菜单 |
| `ui/theme/Shape.kt` | 修改 | 统一圆角 |
| `ui/theme/ThemeEffects.kt` | 无需改动 | 已存在液态玻璃开关/模糊强度 |
| `ui/theme/MonetColor.kt` | 无需改动 | 已存在种子色/风格/标准 |

---

## 10. 注意事项

1. **dynamicColor 与 seed 色的关系**：当前 `Theme.kt` 在启用 dynamicColor 时用系统 scheme 的中性面，但强调色始终用 `keyColor` 派生。这与原型一致。
2. **液态玻璃开关**：所有玻璃组件读取 `ThemeEffects.LiquidGlassEnabled.current`，关闭时退化为扁平半透明。
3. **深色主题**：`GlassSurface` 已根据 `background.luminance()` 判断 dark 模式，但建议统一使用 `isSystemInDarkTheme()` 或 `ThemeMode`。
4. **性能**：`GlassBackground` 中的大半径 radialGradient 和 `blur()` 在低端机上可能掉帧，可在设置中提供"降低玻璃质量"开关。
5. **导航集成**：二级菜单返回需要与现有 `AppNav.kt` 的返回栈配合。

---

*文档生成时间：2026-08-17*  
*对应原型版本：prototype-md-2026-08-17.html 最终版*
