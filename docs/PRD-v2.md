# TwitterDownloader v2 — 产品需求文档 (PRD)

## 项目信息

| 字段 | 值 |
|------|-----|
| Language | 中文 |
| Programming Language | Kotlin / Jetpack Compose / Material 3 |
| Project Name | twitter_downloader_v2 |
| 包名 | com.ed.twitterdownloader |
| 技术栈 | Jetpack Compose + Material 3 + youtubedl-android + Room + fxtwitter API |
| 目标用户 | 个人使用，需通过 Clash 代理访问 Twitter/X |

### 原始需求复述

用户提出 4 个功能增强需求：
1. 视频下方显示推文链接 — 方便识别哪些视频已下载、哪些未下载
2. 解析后给出视频预览框 — 预览框下方保持标题+作者等信息
3. 合并"历史+队列" + 新增"播放"页 — 底部导航改为 首页/下载/播放
4. 播放页点击即播 — 点击视频直接在 app 内播放

---

## 产品目标

**本次迭代的核心目标**：提升下载后的内容管理体验——让用户能快速识别重复下载、预览视频内容、并在应用内一站式播放已下载视频。

---

## 用户故事

### 需求1：视频下方显示推文链接

- **US-1a**: 作为一个频繁下载推文视频的用户，我想在解析结果下方看到原始推文 URL，这样我能一眼判断这个视频是否已经下载过，避免重复下载。
- **US-1b**: 作为一个需要记录视频来源的用户，我想点击推文 URL 一键复制到剪贴板，这样我可以在其他地方引用或分享这个推文链接。

### 需求2：视频预览框

- **US-2a**: 作为一个下载视频前想确认内容的用户，我想在解析后看到一个视频预览区域（含缩略图大图或短片段预览），这样我能确认这就是我想下载的视频。
- **US-2b**: 作为一个快速浏览多个视频的用户，我想在预览框下方同时看到视频标题和作者信息，这样我不需要额外的查找步骤就能确认视频来源。

### 需求3：合并"历史+队列" + 新增"播放"页

- **US-3a**: 作为一个管理下载任务的用户，我想在一个页面同时看到进行中的下载和已完成的历史记录，这样我不用在两个 tab 之间来回切换。
- **US-3b**: 作为一个习惯快速查看下载状态的用户，我想"下载"页上方自动展示正在下载的任务、下方展示已完成的历史，这样页面布局自然反映任务优先级。

### 需求4：播放页点击即播

- **US-4a**: 作为一个想快速回看已下载视频的用户，我想在"播放"页点击任意视频条目就直接在 app 内播放，这样我不需要跳转到外部播放器或额外操作。
- **US-4b**: 作为一个在地铁等无网环境使用 app 的用户，我想播放页只展示本地已下载的视频，这样我离线也能浏览和播放我的视频库。

---

## 需求池

### P0 — Must Have（必须有）

| ID | 需求 | 说明 |
|----|------|------|
| P0-1 | 视频下方显示推文 URL | 在 `VideoInfoCard` 下方新增一行，显示原始推文链接文本；长按/点击可复制到剪贴板 |
| P0-2 | 合并"历史+队列"为"下载"页 | 底部导航从 3 个 tab（首页/队列/历史）改为 3 个 tab（首页/下载/播放）；"下载"页上方展示进行中的任务（原 `DownloadQueueScreen`），下方展示已完成的历史（原 `HistoryScreen`），使用分段标题分隔 |
| P0-3 | 新增"播放"页 tab | 在底部导航新增第三个 tab"播放"，展示已下载视频列表 |
| P0-4 | 播放页内置视频播放 | 点击播放页视频条目，使用 Android `VideoView` 或 ExoPlayer 在 app 内播放，无需跳转外部 |

### P1 — Should Have（应该有）

| ID | 需求 | 说明 |
|----|------|------|
| P1-1 | 视频预览框（缩略图大图） | 解析成功后，`VideoInfoCard` 改为纵向布局：上方展示视频缩略图大图（16:9 宽幅），下方显示标题、作者、时长等信息 |
| P1-2 | 播放页视频条目交互 | 播放页每个视频条目保留缩略图+标题+作者布局，与历史页类似但增加播放按钮图标，点击即播 |
| P1-3 | 推文 URL 可点击复制 | 推文 URL 行显示为可交互文本，点击时自动复制并弹出 Toast/Snackbar 提示"已复制链接" |

### P2 — Nice to Have（锦上添花）

| ID | 需求 | 说明 |
|----|------|------|
| P2-1 | 视频预览框短片段播放 | 在缩略图大图上叠加一个播放按钮，点击后在预览区域内播放视频前几秒片段（需要 ExoPlayer） |
| P2-2 | 下载页分段折叠 | "下载"页进行中区域在无活跃任务时自动折叠/隐藏，节省空间 |
| P2-3 | 播放页搜索/排序 | 播放页增加按日期/作者排序，或关键词搜索功能 |

---

## UI 设计要点

### 需求1：视频下方显示推文链接

**当前 `VideoInfoCard` 结构**：横向 Row — [缩略图 80dp | 标题+作者+时长+画质数]

**改动**：在 `VideoInfoCard` Card 内部 Column 末尾新增一行推文 URL：

```
┌──────────────────────────────────┐
│ [缩略图 80dp]  标题              │
│                @uploader · 02:30 │
│                可用画质: 3 种     │
│                x.com/user/12345  │  ← 新增：推文URL，蓝色链接样式
└──────────────────────────────────┘
```

- URL 文本使用 `MaterialTheme.colorScheme.primary` 链接色
- 单行显示，超长时 `TextOverflow.Ellipsis` 截断
- 点击 URL → 复制到剪贴板 + Snackbar 提示"已复制链接"
- 数据来源：`VideoInfo.url`（已在数据模型中存在）

### 需求2：视频预览框

**改动**：`VideoInfoCard` 从横向布局改为纵向布局：

```
┌──────────────────────────────────┐
│ ┌──────────────────────────────┐ │
│ │                              │ │  ← 缩略图大图 (16:9 宽幅)
│ │      视频缩略图预览          │ │     宽度 fillMaxWidth
│ │                              │ │     高度按比例约 180dp
│ └──────────────────────────────┘ │
│ 视频标题                         │
│ @uploader · 02:30 · 可用画质: 3  │
│ x.com/user/12345                 │  ← 推文URL（需求1）
└──────────────────────────────────┘
```

- 缩略图使用 `ContentScale.Crop` + `RoundedCornerShape(12.dp)`
- 缩略图区域可叠加播放图标（P2-1 短片段预览的占位）
- 下方信息区域保持标题、作者、时长、画质数、推文URL

### 需求3：合并"历史+队列"为"下载"页

**当前底部导航**：首页 / 队列 / 历史（3 tab）

**新底部导航**：首页 / 下载 / 播放（3 tab）

```
┌──────────────────────────────────┐
│ 下载                             │  ← TopAppBar 标题
├──────────────────────────────────┤
│ ── 正在下载 ──                   │  ← 分段标题（有活跃任务时显示）
│ [DownloadProgressItem] ...       │  ← 原队列内容
│                                  │
│ ── 已完成 ──                     │  ← 分段标题
│ [HistoryItem] ...                │  ← 原历史内容
│                                  │
└──────────────────────────────────┤
│ 首页  |  下载  |  播放           │  ← 新底部导航
└──────────────────────────────────┘
```

- "下载"页使用 `LazyColumn`，先渲染活跃任务段，再渲染已完成段
- 无活跃任务时，"正在下载"段可隐藏或折叠（P2-2）
- TopAppBar actions：保留"清理已完成"按钮 + "清空历史"按钮

### 需求4：播放页

**播放页布局**（类似历史页但增加播放交互）：

```
┌──────────────────────────────────┐
│ 播放                             │  ← TopAppBar 标题
├──────────────────────────────────┤
│ ┌────────────────────────────┐   │
│ │[缩略图] 标题               ▶│   │  ← ▶ 播放图标覆盖在缩略图上
│ │         @uploader · 720p   │   │
│ └────────────────────────────┘   │
│ ┌────────────────────────────┐   │
│ │[缩略图] 标题               ▶│   │
│ │         @uploader · 1080p  │   │
│ └────────────────────────────┘   │
└──────────────────────────────────┤
│ 首页  |  下载  |  播放           │
└──────────────────────────────────┘
```

- 点击任意条目 → 全屏/内嵌视频播放界面
- 播放界面使用 ExoPlayer（推荐）或 Android `VideoView`
- 播放界面布局：顶部返回按钮 + 视频区域 + 底部控制栏（播放/暂停/进度条）
- 数据来源：`DownloadHistoryEntity`，读取 `filePath` 播放本地文件
- 空状态提示："还没有可播放的视频，下载后即可在此播放"

---

## 数据模型变更

### DownloadHistoryEntity（需新增字段）

当前字段缺少推文 URL 和视频时长，需补充：

```kotlin
@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey val id: String,
    val url: String,          // ← 已有，推文URL
    val title: String,
    val thumbnail: String,
    val uploader: String,
    val quality: String,
    val filePath: String,
    val fileSize: Long = 0,
    val duration: Long = 0,   // ← 新增：视频时长(秒)
    val createdAt: Long,
    val completedAt: Long
)
```

- `duration` 字段需要 Room 数据库版本升级（migration 或 fallbackToDestructiveMigration）
- `url` 字段已存在，可直接用于播放页和历史页显示推文链接

### VideoInfo（无需变更）

`url` 字段已存在，可直接用于首页推文链接显示。

---

## 导航结构变更

### 当前

```
Screen.Home  → HomeScreen
Screen.Queue → DownloadQueueScreen
Screen.History → HistoryScreen
Screen.Settings → SettingsScreen (从首页 TopAppBar 进入)
```

### 变更后

```
Screen.Home     → HomeScreen（URL输入/解析/下载）
Screen.Download → DownloadScreen（合并：活跃任务+已完成历史）
Screen.Player   → PlayerScreen（已下载视频列表+内置播放）
Screen.Settings → SettingsScreen（从首页 TopAppBar 进入）
```

- `Screen` sealed class 更新：移除 `Queue` 和 `History`，新增 `Download` 和 `Player`
- `screens` 列表更新为 `[Screen.Home, Screen.Download, Screen.Player]`
- 图标建议：Home=Home, Download=Download, Player=PlayCircle/SmartDisplay

---

## 待确认问题

| # | 问题 | 影响范围 | 建议 |
|---|------|----------|------|
| Q1 | 视频播放器选型：ExoPlayer vs Android MediaPlayer？ | 播放页 | 推荐 ExoPlayer（更好的格式支持、手势控制、进度条）；但会增加依赖体积。用户是否接受？ |
| Q2 | 播放页视频条目是否需要显示推文 URL？ | 播放页 | 建议不显示——播放页重点是播放，推文 URL 在"下载"页历史段已有。但用户可能也想在播放页复制链接？ |
| Q3 | Room 数据库迁移策略？ | 数据模型 | `DownloadHistoryEntity` 新增 `duration` 字段。建议使用 `fallbackToDestructiveMigration`（个人使用app，历史数据丢失可接受）还是写 Migration？ |
| Q4 | 视频预览框的"预览"定义：仅缩略图大图 vs 短片段播放？ | 需求2 | P1 仅做缩略图大图（简单可靠）；P2-1 增加短片段播放（需要 ExoPlayer + 预加载）。用户是否需要短片段播放？ |
| Q5 | "下载"页活跃任务和已完成历史的分界线样式？ | 下载页 | 建议用分段标题（"正在下载"/"已完成"）+ StickyHeader；还是用 Divider + 不同卡片颜色区分？ |
| Q6 | 播放页是否需要从"下载"页跳转？ | 导航 | 用户在"下载"页点击已完成视频，是否可以直接跳转到播放页并自动播放？还是仅通过底部导航切换？ |
