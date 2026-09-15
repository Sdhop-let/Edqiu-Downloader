# XInvox 项目概览

> Twitter/X 链接收件箱与外部下载器控制面  
> 文档更新：2026-07-11 ｜ 应用版本：1.2.0（versionCode 3）｜ 数据库版本：3

## 1. 项目定位

XInvox 是一个 Android 原生应用，用于捕获、整理和管理 Twitter/X `status` 链接，并跟踪对应媒体文件的下载状态。

- XInvox 负责：链接捕获、tweet ID 去重、元数据补全、搜索与筛选、下载请求派发、状态对账、失败记录、回收站和 JSON 备份。
- 实际媒体下载由外部应用 `TwitterDownloader` 完成。
- XInvox 接受有效的 Twitter/X `status` 链接，但不会在保存前验证推文一定包含视频或媒体。

## 2. 技术栈与版本

- Kotlin 2.0.21
- Jetpack Compose + Material 3
- Room 2.6.1（数据库版本 3）
- DataStore Preferences 1.1.1
- WorkManager 2.9.0
- Kotlin Coroutines 1.9.0
- Kotlin Serialization 1.7.1
- Android Gradle Plugin 8.5.2
- Gradle Wrapper 8.13
- Java / JVM 17
- `minSdk = 24`
- `compileSdk = 34`
- `targetSdk = 34`

发布新版本前应核对当前应用商店的目标 API 要求，并按需要升级 `compileSdk`、`targetSdk` 和相关依赖。

## 3. 功能清单

### 链接捕获

- 列表页手动粘贴并捕获。
- App 回到前台时检查系统剪贴板。
- 接收系统 `ACTION_SEND` 文本分享。
- 接收系统 `ACTION_PROCESS_TEXT` 文本处理。
- 可选无障碍服务：在受支持应用出现“复制”类事件后读取当前剪贴板。
- 统一规范化为 `https://x.com/i/status/{tweetId}`。
- Room 使用 `tweetId` 主键和 `OnConflictStrategy.IGNORE` 原子去重。

无障碍入口当前只接受固定包名白名单，并依赖有限的中英文复制标签；其他浏览器、语言、自绘菜单或不同事件实现可能无法触发。

### 列表与详情

- 按捕获时间显示记录。
- 搜索、状态筛选与排序。
- 显示作者、文案、缩略图、保存时间、下载状态和失败信息。
- 单条复制、下载、删除。
- 批量复制、下载和删除。
- 详情页手动刷新元数据及下载状态。

### 下载与状态

- 通过显式 Intent 调用 `TwitterDownloader`。
- 记录派发次数、最近尝试时间、错误信息和下次重试时间。
- 扫描下载器 ContentProvider 或用户授权的 SAF 目录。
- 根据媒体文件名或 sidecar `.meta.json` 提取 tweet ID。
- 文件命中后将 Room 记录标记为 `DOWNLOADED` 并写入文件路径和时间。

### 回收站

- 删除活动记录前写入 `deleted_link_history`。
- 支持单条或批量恢复。
- 支持永久删除历史记录。
- 数据库 2→3 迁移负责创建回收站表及索引。

### 备份与恢复

- 手动导出和导入 JSON。
- 备份格式版本为 1。
- 使用 SHA-256 校验备份 payload。
- 支持合并导入和替换导入。
- 替换导入前在 `noBackupFilesDir/pre_restore_backups` 保存安全快照，保留最近 3 份。
- 可选每日自动备份，默认保留 7 份。
- 活动记录恢复时统一重置为 `PENDING`，并清除文件路径、下载时间和失败重试状态；恢复后需要重新扫描下载目录。
- 回收站记录会保留备份中的历史状态字段。

## 4. 系统架构

```text
Compose UI
  └─ ViewModel
      └─ Repository / Coordinator
          ├─ Room
          │   ├─ saved_links
          │   └─ deleted_link_history
          ├─ DataStore Preferences
          ├─ MetadataFetcher → api.fxtwitter.com
          ├─ DownloaderClient → TwitterDownloader Intent
          ├─ DownloadMonitor → ContentProvider / SAF
          └─ WorkManager → 状态同步 / 自动备份
```

`XInvoxApplication` 创建 `AppContainer`，以手动依赖注入方式向 ViewModel、Activity、Service 和 Worker 提供数据库及仓库实例。

### 数据权威范围

- Room 是收件箱、元数据、失败状态、重试信息和回收站的 UI 数据源。
- 下载器 Provider 或 SAF 目录是媒体文件是否存在的权威来源。
- 扫描结果会写回 Room；因此扫描结果必须能够区分“成功但未找到文件”和“扫描失败”。当前实现尚未正确区分这两种情况，详见 `BUG_AUDIT.md`。

## 5. 核心数据流

### 捕获流程

```text
外部文本
  → TweetIdExtractor 解析并规范化 URL
  → Room insert IGNORE 去重
  → 尝试从 fxtwitter 获取元数据
  → 返回 Added / Duplicate / Invalid
```

数据库插入发生在网络请求之前，因此元数据服务失败不会丢失已保存链接；但当前 `capture()` 会等待网络请求结束后才返回，可能延迟分享 Activity 关闭和用户反馈。

### 下载流程

```text
用户请求下载
  → 读取 SavedLink
  → DownloaderClient 启动外部 Activity
  → 启动成功：标记 PENDING 并增加 attemptCount
  → 启动失败：标记 FAILED，写入 lastError / nextRetryAt
```

重试策略：

- 最大尝试次数：3 次。
- 第 1 次失败后：约 30 秒重试。
- 第 2 次失败后：约 2 分钟重试。
- 第 3 次失败后：不再自动安排下一次重试。
- 手动重试达到上限的记录时会先清零失败状态。

当前短期定时重试依赖列表 ViewModel 的内存协程，后台 Worker 未消费到期的 `nextRetryAt`；进程退出或页面销毁后不能保证自动重试，详见 `BUG_AUDIT.md`。

### 状态对账流程

```text
Provider / SAF 扫描
  → 收集媒体文件和 .meta.json
  → 按媒体完整相对路径配对 sidecar
  → 优先读取 sidecar tweetId，失败则从文件名提取
  → 每个 tweetId 选择扫描遇到的第一个媒体文件
  → 更新 Room 状态及可用元数据
```

下载时间实际取自文件 `lastModified`；若无有效值，则使用扫描时的当前时间，不代表下载器明确报告的完成时间。

## 6. TwitterDownloader 集成契约

### 下载命令 Intent

- 包名：`com.ed.twitterdownloader`
- Activity：`com.ed.twitterdownloader.MainActivity`
- Action：`com.ed.twitterdownloader.action.DOWNLOAD_TWEET`
- Extra：`com.ed.twitterdownloader.extra.TWEET_URL`
- Intent data：规范化后的 `https://x.com/i/status/{tweetId}`

XInvox 当前以 `resolveActivity()` 和 `startActivity()` 成功作为“请求已派发”的判断依据，下载器没有返回接收确认或最终下载结果。

### 文件 Provider

默认监控 URI：

```text
content://com.ed.twitterdownloader.media/files
```

预期 Provider authority 和权限：

```text
authority: com.ed.twitterdownloader.media
permission: com.ed.twitterdownloader.permission.READ_DOWNLOAD_MEDIA
```

查询列：

- `relative_path`
- `OpenableColumns.DISPLAY_NAME`
- `last_modified`

单文件读取 URI：

```text
content://com.ed.twitterdownloader.media/file/{relativePath}
```

### Sidecar 契约

媒体文件：

```text
path/name.ext
```

元数据文件：

```text
path/name.ext.meta.json
```

读取字段：

- `tweetId`
- `url`
- `title`
- `thumbnail`
- `uploader`
- `authorName`

上述包名、Activity、Action、Extra、authority、权限和列名目前均为硬编码；两端修改契约时必须同步更新，当前没有版本协商或能力探测。

## 7. 设置与后台任务

DataStore 保存：

- 监控目录 URI；默认使用下载器 Provider URI。
- 自动捕获开关；默认开启。
- 自动重试开关；默认开启。
- 后台同步开关；默认开启。
- 自动备份目录、开关和最近执行结果。

后台同步：

- 开启时立即安排一次同步。
- 之后由 WorkManager 以最短 15 分钟周期运行。
- Worker 当前执行下载目录对账和最多 5 条缺失元数据补全。
- WorkManager 周期不是精确计时，实际执行时间受系统调度影响。

自动备份：

- 开启时安排一次即时备份和 24 小时周期任务。
- 要求设备电量和存储空间不低。
- 每次应用冷启动读取到已开启设置时，当前实现会再次替换即时备份任务。

## 8. 权限与隐私

Manifest 权限：

- `android.permission.INTERNET`
- `com.ed.twitterdownloader.permission.READ_DOWNLOAD_MEDIA`

可选能力：

- SAF 持久 URI 授权，用于自定义下载监控目录和自动备份目录。
- 无障碍服务，仅在允许的应用中匹配复制类事件后读取当前系统剪贴板。

数据外发：

- 元数据补全会把 tweet ID 请求发送至 `https://api.fxtwitter.com/status/{tweetId}`。
- JSON 备份包含保存链接、作者、文案、缩略图 URL、状态、失败信息及回收站历史。

系统备份：

- Manifest 当前设置 `android:allowBackup="true"`，但未配置专用 `dataExtractionRules` 或 `fullBackupContent`。
- Android 系统备份与应用内 JSON 备份是两套不同机制；发布前应明确是否允许系统备份 Room 和 DataStore，并排除不可跨设备复用的 SAF URI 等设置。

## 9. 构建与运行

### 环境

- Android Studio
- JDK 17
- Android SDK 34

### 常用命令

在项目根目录执行：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
```

完整功能需要：

1. 安装与上述 Intent / Provider 契约兼容的 `TwitterDownloader`；或在设置中选择可读取的 SAF 目录。
2. 如需无障碍自动捕获，由用户在系统设置中手动启用 XInvox 服务。
3. 如需设备测试，连接模拟器或 Android 设备。

仓库中的旧构建日志记录过 Maven Central TLS 握手失败；当前环境已于 2026-07-11 成功执行 `.\gradlew.bat testDebugUnitTest`，Debug 源码与 JVM 单元测试均通过。设备测试仍需连接模拟器或 Android 设备后另行验证。

## 10. 测试现状

已有测试主要覆盖：

- Twitter/X URL 解析、规范化和文件名 ID 提取。
- 下载重试退避和最大尝试次数。
- 列表搜索与排序。
- Room 1→2 迁移的 JVM SQL 测试和设备迁移测试。

主要缺口：

- Room 2→3 迁移测试。
- 下载器 Intent 契约。
- Provider / SAF 扫描及异常语义。
- Sidecar 解析和多媒体聚合规则。
- 下载状态机、并发派发和跨进程重试。
- 元数据合并、退避和饥饿场景。
- 备份校验、合并/替换恢复和并发操作。
- 回收站事务。
- 捕获白名单、无障碍事件和 Compose UI。
- WorkManager 调度行为。

## 11. 已知问题

详细复现条件和代码位置见 [`BUG_AUDIT.md`](BUG_AUDIT.md)。当前高优先级问题包括：

1. 扫描失败与空目录使用同一返回值，可能把全部已下载记录错误回退为 `PENDING`。
2. 到期下载重试未接入后台 Worker，页面销毁或进程退出后自动重试失效。
3. 下载请求缺少原子认领，并发请求或遗留定时任务可能重复派发。
4. 外部 Activity 成功启动后没有接收确认或超时，下载失败可能永久停在 `PENDING`。
5. 远端元数据的 `null` 字段可能覆盖已有有效数据。
6. 缺失元数据固定处理最新 5 条，永久失败记录会使更老记录饥饿。
7. SAF 持久授权失败后仍可能保存 URI 并显示成功。
8. 无障碍入口在持久化完成前设置抑制标记，失败后可能漏掉再次捕获。
9. 捕获流程同步等待 fxtwitter，可能延迟完成反馈。
10. 备份 ViewModel 的忙状态检查不是原子的，快速操作可能并发执行。

## 12. 目录结构

```text
XInvox/
├─ OVERVIEW.md
├─ BUG_AUDIT.md
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ gradle/wrapper/
└─ app/
   ├─ build.gradle.kts
   └─ src/
      ├─ main/
      │  ├─ AndroidManifest.xml
      │  ├─ java/com/ed/xinvox/
      │  │  ├─ background/    # WorkManager 同步与自动备份
      │  │  ├─ capture/       # 分享、处理文本和无障碍捕获
      │  │  ├─ clipboard/     # 剪贴板读取
      │  │  ├─ data/
      │  │  │  ├─ backup/     # JSON 备份、校验与恢复
      │  │  │  ├─ db/         # Room DAO、迁移和数据库
      │  │  │  ├─ metadata/   # fxtwitter 与 sidecar 元数据
      │  │  │  ├─ model/      # SavedLink、历史和状态模型
      │  │  │  ├─ preferences/# DataStore 设置
      │  │  │  └─ repository/ # 核心业务与下载器集成
      │  │  ├─ di/            # 手动依赖注入
      │  │  ├─ domain/        # URL / tweet ID 解析
      │  │  ├─ system/        # 设备能力读取
      │  │  └─ ui/            # Compose 页面、组件与 ViewModel
      │  └─ res/
      ├─ test/                 # JVM 单元测试
      └─ androidTest/          # Android 设备测试
```

## 13. 建议修复顺序

1. 为扫描结果建立明确的成功/失败类型，失败时禁止修改现有下载状态。
2. 将到期下载重试集中到持久化调度器，并为下载请求增加原子认领和派发超时。
3. 将元数据更新改为逐字段非空合并，并为元数据重试增加退避或轮转。
4. 只有在 SAF 持久授权成功后才保存 URI 和提示成功。
5. 捕获成功后再设置无障碍抑制标记，并让网络元数据补全脱离捕获完成路径。
6. 使用 `Mutex` 或同步状态占用修复备份操作互斥。
7. 补齐状态机、迁移、备份、Provider/SAF 和并发场景测试。
