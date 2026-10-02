# XInvox 界面重新设计

> 日期：2026-07-12
> 范围：UI 全面改版（视觉风格 + 导航结构 + 卡片密度 + 操作流程 + 现代组件 + 反馈机制）
> 技术栈：Kotlin 2.0.21 / Jetpack Compose / Material 3 / Coil

## 1. 背景与动机

XInvox 当前 UI 偏「工具型 / 表单化」：信息密度高、按钮多、视觉风格统一但偏朴素。重新设计旨在解决四个痛点：

1. 视觉风格过时平淡，缺乏品牌感
2. 信息密度太高，卡片拥挤、设置页冗长
3. 核心操作流程（下载/重试/批量）路径不顺畅
4. 缺少现代组件和动效（BottomNav / FAB / Snackbar / 骨架屏）

## 2. 设计约束

| 维度 | 决策 |
|---|---|
| 动机 | 全面改版（视觉 + 密度 + 流程 + 现代组件） |
| 视觉风格 | 现代极简（Material You / iOS 风） |
| 导航 | 底部导航栏（收件箱 / 回收站 / 设置） |
| 卡片密度 | 精简卡片（作者 + 文案预览 + 状态 + 时间） |
| 卡片交互 | 点击进详情 + 长按 BottomSheet 菜单 |
| 配色 | 保留深蓝 `#2F4C8F`，净化背景，状态色克制 |
| 技术方案 | 方案 A：Material 3 现代化 |

## 3. 主题与色彩

### 3.1 色彩 token

```kotlin
// 主色（深蓝，保持）
val Primary = Color(0xFF2F4C8F)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFE4E9F4)

// 中性面（净化后）
val Background = Color(0xFFFAFAFA)        // 更干净的灰白（原 #F6F7F9）
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFF5F5F5)    // 更淡（原 #EEF1F5）
val OnSurface = Color(0xFF1A1C1E)
val OnSurfaceVariant = Color(0xFF6B7280)  // 更柔和（原 #5B6168）
val Outline = Color(0xFFE5E7EB)           // 更细的分割线（原 #D8DCE3）

// 状态色（降级为点缀，仅用于小圆点和文字）
val StatusPending = Color(0xFF9CA3AF)     // 灰（原 #8A9099）
val StatusDownloaded = Color(0xFF10B981)  // 绿（原 #2E9E5B）
val StatusFailed = Color(0xFFEF4444)      // 红（原 #D9534F）
```

### 3.2 排版

保持现有层级但字重从 SemiBold 降为 Medium，减少视觉噪音：

| Token | 字号 | 字重 | 行高 |
|---|---|---|---|
| titleLarge | 20sp | Medium（原 SemiBold） | 26sp |
| titleMedium | 16sp | Medium（原 SemiBold） | 22sp |
| bodyMedium | 14sp | Normal | 20sp |
| labelMedium | 12sp | Medium | 16sp |
| labelSmall | 11sp | Normal | 14sp |

### 3.3 形状 token

新增 `Shape.kt` 统一圆角：

```kotlin
val Shapes = Shapes(
    small = RoundedCornerShape(8.dp),    // 圆点、徽标
    medium = RoundedCornerShape(12.dp),  // 卡片
    large = RoundedCornerShape(16.dp)    // 顶栏、底栏
)
```

### 3.4 暗色主题

保持现有暗色方案，同步更新背景和状态色：

```kotlin
background = Color(0xFF121417)
surface = Color(0xFF1A1D21)
surfaceVariant = Color(0xFF23272C)
// 状态色在暗色下提高亮度
StatusPending = Color(0xFFB0B7C0)
StatusDownloaded = Color(0xFF34D399)
StatusFailed = Color(0xFFF87171)
```

## 4. 导航与整体结构

### 4.1 底部导航

使用 M3 `NavigationBar`，三个 Tab：

- **收件箱**：列表页，含 FAB 粘贴捕获
- **回收站**：历史记录页
- **设置**：设置页

### 4.2 路由结构

```
XInvoxApp
└─ XInvoxTheme
   └─ Scaffold(bottomBar = NavigationBar, snackbarHost = SnackbarHost)
      └─ NavHost(startDestination = INBOX)
         ├─ inbox     → ListScreen (含 FAB)
         ├─ trash     → HistoryScreen
         ├─ settings  → SettingsScreen
         └─ detail/{tweetId} → DetailScreen (push, 无底栏)
```

关键变化：
- 设置不再是列表页的 push，直接成为底部 Tab
- 回收站不再是设置的 push，直接成为底部 Tab
- 详情页隐藏底栏，全屏沉浸
- `SnackbarHost` 挂载在顶层 Scaffold，通过 `SnackbarController` 共享

### 4.3 列表页顶栏简化

**改版后**：
```
[收件箱              批量]
[3 条未下载              ]
```

- 标题简化为「收件箱」
- 副标题改为「N 条未下载」动态计数
- 设置图标移除（已是底栏 Tab）
- 刷新按钮移除（改下拉刷新）
- 「批量」文字按钮保留

## 5. 列表页与卡片设计

### 5.1 列表页布局

```
┌─────────────────────────────────┐
│ 收件箱                  批量     │  ← TopAppBar
│ 3 条未下载                      │
├─────────────────────────────────┤
│ 🔍 搜索作者、文案或链接    排序▾ │  ← 搜索栏
├─────────────────────────────────┤
│  全部  未下载  已下载  失败      │  ← FilterTabs（更细）
├─────────────────────────────────┤
│  ┌─────────────────────────┐    │
│  │ ● @author  复制于 2 小时前│   │  ← 精简卡片
│  │ 文案预览最多两行…        │   │
│  └─────────────────────────┘    │
├─────────────────────────────────┤
│                          [📋]   │  ← FAB 粘贴捕获
├─────────────────────────────────┤
│  📥 收件箱   ♻️ 回收站  ⚙️ 设置 │  ← 底栏
└─────────────────────────────────┘
```

### 5.2 精简卡片

**现状**：头像 + 作者/文案 + 状态徽标/时间 + 分隔线 + 完整链接灰底块 + 三等分按钮，纵向约 200dp。

**改版后**：

```
┌─────────────────────────────────────┐
│ ●  @authorId                        │
│    文案预览最多两行，超出省略号…      │
│                            未下载 · 2h│
└─────────────────────────────────────┘
```

| 元素 | 处理 |
|---|---|
| 状态 | 8dp 小圆点（绿/灰/红），放在作者名左侧 |
| 头像 | 移除（精简卡片，元数据缺失时反而突兀） |
| 作者 | `@authorId`，14sp Medium |
| 文案 | 最多 2 行，14sp Normal，`onSurfaceVariant` 色 |
| 时间 | 右下角，11sp，`onSurfaceVariant`，相对时间格式 |
| 完整链接 | 移除（收到详情页） |
| 操作按钮 | 移除（长按弹出 BottomSheet） |
| 分隔线 | 移除（卡片间用 8dp 间距） |
| 卡片样式 | M3 `Card` filled 变体，12dp 圆角，无阴影 |

卡片高度：约 72-80dp（仅作者 + 文案 + 时间），一屏从约 3-4 条 → 约 7-8 条。

### 5.3 长按 BottomSheet 菜单

```
┌─────────────────────────────────┐
│  @authorId · 未下载             │
├─────────────────────────────────┤
│  📋  复制链接                    │
│  ⬇️  下载 / 重试                 │
│  🗑️  删除                        │
└─────────────────────────────────┘
```

- 使用 M3 `ModalBottomSheet`
- 顶部显示当前作者和状态作为上下文
- 三个 `ListItem`，每个一行图标 + 文字
- 失败状态额外显示「失败原因」行

### 5.4 空状态

```
┌─────────────────────────────────┐
│          📥                     │
│      收件箱为空                  │
│  复制推文链接后回到此处即可捕获   │
│         [📋 粘贴链接]            │
└─────────────────────────────────┘
```

### 5.5 批量模式

- 顶栏右侧「批量」变为「完成」
- 卡片左侧出现复选框（替换状态圆点位置）
- 底部出现批量操作栏（取代 FAB）：
```
┌─────────────────────────────────┐
│ 已选 3 条   全选  复制 下载 删除 │
└─────────────────────────────────┘
```

### 5.6 骨架屏

列表首次加载时显示 3-5 个灰色卡片占位，shimmer 动画。

## 6. 详情页设计

### 6.1 布局

```
┌─────────────────────────────────┐
│ ←                       ↻ 刷新   │  ← TopAppBar
├─────────────────────────────────┤
│ ●  @authorId                    │
│    authorName                   │
│                                 │
│ ┌─────────────────────────────┐ │
│ │      缩略图（如有）          │ │  ← 16:9
│ └─────────────────────────────┘ │
│                                 │
│ 文案完整内容                     │
│                                 │
│ ─────────────────────────       │
│                                 │
│ 📋 复制时间      2 小时前        │
│ ⬇️ 下载时间      1 小时前        │
│ ⏱ 最近尝试      30 分钟前        │
│ 🔢 尝试次数      2              │
│ ⚠️ 失败原因      下载器未启动     │
│ 🔄 自动重试      2 分钟后        │
│                                 │
│ ─────────────────────────       │
│                                 │
│ 完整链接                         │
│ ┌─────────────────────────────┐ │
│ │ https://x.com/i/status/... │ │  ← 等宽，可选取
│ └─────────────────────────────┘ │
├─────────────────────────────────┤
│ [  打开下载器  ]  [📋] [📂]      │  ← 底部固定操作栏
└─────────────────────────────────┘
```

### 6.2 关键变化

| 区域 | 改动 |
|---|---|
| 头部 | 状态圆点 + `@authorId` + `authorName`，更克制 |
| 缩略图 | 新增。如有 `thumbnailUrl`，用 Coil 加载 16:9 图，圆角 12dp；无则不显示（不占位） |
| 文案 | 完整展示，不限制行数 |
| 信息区 | 改为带左侧图标的列表项，`⏱/⬇️/📋/🔢/⚠️/🔄` 图标 + 标签 + 值 |
| 完整链接 | 包裹在 `surfaceVariant` 圆角块中，等宽字体 |
| 底部操作栏 | 固定在底部。主按钮（打开下载器/重试）占主位，次按钮（复制/打开文件）为图标按钮 |
| 刷新 | 顶栏右侧图标按钮 |

### 6.3 信息区显示规则

| 字段 | 显示条件 |
|---|---|
| 复制时间 | 始终显示 |
| 下载时间 | `status == DOWNLOADED && downloadedAt != null` |
| 最近尝试 | `lastAttemptAt != null` |
| 尝试次数 | `attemptCount > 0` |
| 失败原因 | `!lastError.isNullOrBlank()` |
| 自动重试 | `nextRetryAt != null` |

### 6.4 缩略图加载策略

- 依赖 `SavedLink.thumbnailUrl` 字段（来自 fxtwitter 元数据或 sidecar）
- 用 Coil `AsyncImage`，`crossfade = true`
- 加载中显示 `surfaceVariant` 占位色块
- 加载失败静默隐藏，不显示错误图标
- 点击缩略图可放大查看（可选，后续增强）

## 7. 回收站页设计

### 7.1 布局

```
┌─────────────────────────────────┐
│ ←  回收站                        │  ← TopAppBar
├─────────────────────────────────┤
│  ┌─────────────────────────┐    │
│  │ ☐  @authorId            │    │  ← 复选框 + 作者
│  │    文案预览最多两行…      │    │
│  │    删除于 2 小时前        │    │
│  └─────────────────────────┘    │
├─────────────────────────────────┤
│  📥 收件箱   ♻️ 回收站  ⚙️ 设置 │  ← 底栏
└─────────────────────────────────┘
```

### 7.2 关键变化

| 区域 | 改动 |
|---|---|
| 列表项 | 改为 M3 `Card` filled 变体，12dp 圆角，8dp 间距；移除 `HorizontalDivider` |
| 列表项内容 | 复选框 + 作者 + 文案预览（2 行）+ 删除时间，与收件箱卡片视觉一致 |
| 操作栏 | 选中条目时底部出现 `BottomAppBar`：`已选 N · 全选 · 恢复 · 永久删除` |
| 空状态 | 图标 + 文案 + 引导，类似收件箱空状态 |

### 7.3 底部操作栏

```
┌─────────────────────────────────┐
│ 已选 3   全选   ♻️ 恢复   🗑️ 永久 │
└─────────────────────────────────┘
```

- 未选中时：不显示操作栏，列表占满
- 选中时：操作栏从底部滑入（动画）
- 底部导航栏在回收站页保留（因为是主 Tab）
- 操作栏出现在底栏上方，不遮挡导航

## 8. 设置页设计

### 8.1 布局

```
┌─────────────────────────────────┐
│  设置                            │  ← TopAppBar
├─────────────────────────────────┤
│  ┌─────────────────────────┐    │
│  │ 📁 监控目录              >│    │  ← 分组卡片
│  │    Android/data/.../Down │    │
│  │    load                  │    │
│  │    使用默认目录           │    │
│  └─────────────────────────┘    │
│                                 │
│  ┌─────────────────────────┐    │
│  │ 捕获                    │    │  ← 分组标题
│  │                         │    │
│  │ 回到前台检查剪贴板    ◯─│    │  ← Switch
│  │ 仅在 App 回到前台时读取  │    │
│  │                         │    │
│  │ 无障碍自动捕获        ◯─│    │
│  │ 在受支持应用复制时读取   │    │
│  │ 当前：未开启             │    │
│  │ [前往系统设置]           │    │
│  └─────────────────────────┘    │
│                                 │
│  ┌─────────────────────────┐    │
│  │ 下载                    │    │
│  │                         │    │
│  │ 下载失败自动重试      ◯─│    │
│  │ 30秒、2分钟退避重试     │    │
│  │                         │    │
│  │ 后台更新下载状态      ◯─│    │
│  │ 约每15分钟扫描一次      │    │
│  └─────────────────────────┘    │
│                                 │
│  ┌─────────────────────────┐    │
│  │ 备份与恢复               │    │
│  │ 📁 自动备份目录          >│    │
│  │ 每日自动备份          ◯─│    │
│  │ [💾 立即备份]            │    │
│  │ [📤 导出]  [📥 导入]     │    │
│  │ 最近成功：2026-07-11     │    │
│  └─────────────────────────┘    │
│                                 │
│  ┌─────────────────────────┐    │
│  │ 关于                    │    │
│  │ 输入法：Gboard           │    │
│  │ XInvox 1.2.0             │    │
│  └─────────────────────────┘    │
├─────────────────────────────────┤
│  📥 收件箱   ♻️ 回收站  ⚙️ 设置 │
└─────────────────────────────────┘
```

### 8.2 关键变化

| 区域 | 改动 |
|---|---|
| 整体结构 | 改为分组卡片，每组一个 `Card` filled 变体，组间 12dp 间距 |
| 分组 | 4 组：监控目录 / 捕获 / 下载 / 备份与恢复；底部「关于」小组 |
| 回收站入口 | 移除（已是底部 Tab） |
| 分组标题 | 卡片内顶部 14sp Medium `primary` 色小标题 |
| 开关项 | 标题 + 描述 + 右侧 `Switch`，组内无分隔线，靠 12dp 垂直间距区分 |
| 目录项 | 左图标 + 标题/路径 + 右箭头，点击打开选择器 |
| 操作按钮 | 卡片内底部排列，主按钮 filled，次按钮 outlined |

### 8.3 备份忙状态

- `busy` 时所有备份相关按钮禁用 + 显示顶部 `LinearProgressIndicator`
- 修复 BUG_AUDIT #10 的忙状态原子性问题：使用 `Mutex.tryLock()`

## 9. 反馈、动效与组件细节

### 9.1 Toast → Snackbar

| 反馈类型 | 处理 |
|---|---|
| 捕获结果（已保存/重复/非推文/空） | Snackbar 2 秒，带「查看」action（仅 Added 时跳详情） |
| 操作反馈（复制成功/下载已派发/删除） | Snackbar 2 秒 |
| 错误反馈（下载失败/备份失败） | Snackbar 4 秒，带「重试」action |
| 删除确认 | 不再弹 AlertDialog；改为 Snackbar + 「撤销」action，5 秒内可恢复 |

实现方式：在 `XInvoxApp` 的 `Scaffold` 挂载 `SnackbarHostState`，通过共享 `SnackbarController` 让各 ViewModel 触发。

### 9.2 撤销删除

- 单条删除：立即移除 + 写入回收站，Snackbar 提供撤销
- 批量删除：同上，撤销恢复全部
- 撤销逻辑：从回收站恢复回收件箱
- 5 秒未撤销则确认，无额外提示
- 实现方案：复用回收站，立即写入 `deleted_link_history`，撤销时从回收站恢复

### 9.3 加载状态

| 场景 | 处理 |
|---|---|
| 列表首次加载 | 骨架屏（3-5 个灰色卡片占位，shimmer 动画） |
| 列表刷新 | `PullToRefresh` 下拉，顶部 `LinearProgressIndicator` |
| 详情加载 | 居中 `CircularProgressIndicator` |
| 元数据刷新 | 顶部 `LinearProgressIndicator`，内容可读 |
| 备份/恢复 | 按钮 `CircularProgressIndicator` + 禁用 |

### 9.4 动效

| 场景 | 动效 |
|---|---|
| 页面转场 | 淡入淡出 200ms（NavHost 默认） |
| 详情进入 | 淡入淡出（与页面转场一致）。共享元素转场为可选增强，不作为本次硬性要求 |
| 卡片出现 | `fadeIn() + slideInVertically()` 100ms |
| 状态变化 | `animateColor()` 状态点颜色过渡 200ms |
| 底部操作栏 | `slideInVertically()` 从底部滑入 150ms |
| BottomSheet | M3 默认动画 |
| Snackbar | M3 默认滑入 |

### 9.5 组件清单

| 组件 | 来源 | 用途 |
|---|---|---|
| `NavigationBar` / `NavigationBarItem` | M3 | 底部三 Tab |
| `FloatingActionButton` | M3 | 粘贴捕获 |
| `Card` (filled) | M3 | 卡片容器 |
| `ModalBottomSheet` | M3 | 长按菜单 |
| `Snackbar` + `SnackbarHost` | M3 | 反馈 |
| `PullToRefreshBox` | M3 (material3 1.3+) | 下拉刷新 |
| `LinearProgressIndicator` | M3 | 顶部加载条 |
| `CircularProgressIndicator` | M3 | 居中加载 |
| `Switch` | M3 | 设置开关 |
| `ListItem` | M3 | BottomSheet 和信息区行项 |
| `AsyncImage` | Coil | 缩略图加载 |

### 9.6 依赖变更

在 `app/build.gradle.kts` 添加：

```kotlin
implementation("io.coil-kt:coil-compose:2.7.0")
```

现有 `composeBom = 2024.09.00` 已包含 `material3 1.3.0`，支持 `PullToRefreshBox`。

## 10. 文件结构与改动范围

```
app/src/main/java/com/ed/xinvox/
├─ ui/
│  ├─ theme/
│  │  ├─ Color.kt          [改] 色彩 token 净化
│  │  ├─ Theme.kt          [改] 添加 Scaffold 挂载，无其他变更
│  │  ├─ Type.kt           [改] 字重 SemiBold → Medium
│  │  └─ Shape.kt          [新] 统一圆角 token (8/12/16dp)
│  ├─ components/
│  │  ├─ LinkCard.kt       [改] 精简卡片重写
│  │  ├─ StatusBadge.kt    [改] 徽标 → 8dp 圆点
│  │  ├─ StatusDot.kt      [新] 状态圆点组件
│  │  ├─ SkeletonCard.kt   [新] 骨架屏卡片
│  │  ├─ Shimmer.kt        [新] shimmer Modifier
│  │  └─ EmptyState.kt     [新] 空状态组件
│  ├─ list/
│  │  ├─ ListScreen.kt     [改] 顶栏简化 + FAB + 下拉刷新 + 骨架屏
│  │  └─ ListViewModel.kt  [改] 删除改为软删除 + 撤销状态
│  ├─ detail/
│  │  ├─ DetailScreen.kt   [改] 缩略图 + 信息区图标 + 固定底栏
│  │  └─ DetailViewModel.kt [无改]
│  ├─ history/
│  │  ├─ HistoryScreen.kt  [改] 卡片化 + 底部操作栏
│  │  └─ HistoryViewModel.kt [无改]
│  ├─ settings/
│  │  ├─ SettingsScreen.kt [改] 分组卡片重写
│  │  ├─ BackupViewModel.kt [改] 修复 busy 原子性 (Mutex.tryLock)
│  │  └─ SettingGroup.kt   [新] 分组卡片容器组件
│  ├─ navigation/
│  │  ├─ AppNav.kt         [改] 底部导航 + SnackbarHost
│  │  └─ SnackbarController.kt [新] 全局 Snackbar 控制器
│  └─ util/
│     └─ Actions.kt        [改] 添加相对时间格式化
├─ data/
│  └─ repository/
│     └─ SavedLinkRepository.kt [改] 新增 softDelete + undoDelete
└─ app/build.gradle.kts    [改] 添加 Coil 依赖
```

### 改动统计

| 类型 | 数量 |
|---|---|
| 新增文件 | 7 |
| 修改文件 | 13 |
| 无改文件 | 2 |

### 改动边界

**本次改版范围内**：
- 所有 UI 层（theme / components / list / detail / history / settings / navigation）
- `ListViewModel` 的删除逻辑（软删除 + 撤销）
- `BackupViewModel` 的 busy 互斥修复
- `SavedLinkRepository` 新增软删除方法
- `build.gradle.kts` 添加 Coil

**本次改版范围外**（不触碰）：
- 数据库 schema、DAO、迁移
- 下载器集成、扫描、重试逻辑
- 捕获流程、无障碍服务
- WorkManager、备份仓库
- BUG_AUDIT 中除 #10 外的其他缺陷

## 11. 验收标准

1. 底部导航三 Tab（收件箱 / 回收站 / 设置）可正常切换
2. 列表页卡片高度约 72-80dp，一屏可见 7-8 条
3. 长按卡片弹出 BottomSheet，包含复制/下载/删除
4. 删除后 Snackbar 显示「撤销」action，5 秒内可恢复
5. 详情页显示缩略图（如有 `thumbnailUrl`）
6. 详情页底部固定操作栏，主按钮 + 图标按钮
7. 设置页分为 5 个分组卡片，无 `HorizontalDivider`
8. 所有 Toast 改为 Snackbar
9. 列表首次加载显示骨架屏
10. 下拉刷新功能正常
11. 配色按 spec 净化，深蓝主色保持
12. 字重从 SemiBold 降为 Medium
13. 备份操作 busy 状态原子互斥
14. 暗色主题同步更新
