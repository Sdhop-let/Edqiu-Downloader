# 架构说明

Edqiu 的整体结构：单模块 Android 应用（`com.ed.Edqiu`），Jetpack Compose + Kotlin，
无 Hilt（手写依赖容器），共约 167 个 Kotlin 文件。

> 本文以**当前源码为准**。早期两份规划文档
> （`ARCHITECTURE-v2.md`、`system_design.md`，包名仍是 `com.ed.twitterdownload*` 的 TwitterDownloader / XInvox 时期）
> 已移入 [`archive/`](archive/)，仅作历史参考，不要据此理解现状。

## 技术栈

| 项 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3（壁纸动态取色 Monet，经 `materialkolor`） |
| 玻璃材质 | `Kyant0/AndroidLiquidGlass`（AGSL 真折射，Android 13+；低版本走自绘回退） |
| 下载 | FXTwitter 直连 / `yt-dlp`（`youtubedl-android`，含 ffmpeg）/ 第三方 API 三级降级；下载前格式实证选型（`VideoSourcePolicy`）；引擎独立云控更新（`YtdlpEngineUpdater`，双通道 + LKG 回退） |
| 本地存储 | Room 双库（`AppDatabase` v17 媒体/订阅、`EdqiuDatabase` v12 收件箱/回收站）+ DataStore Preferences（`xinvox_settings`） |
| 后台任务 | WorkManager |
| 注入 | 手写 `di/AppContainer`，无 Hilt |
| 密钥 | AndroidKeyStore + AES/GCM（登录态、网盘凭证） |

## 应用入口

- `EdqiuApplication` — 实际注册的 `Application`（创建 `AppContainer`、后台调度注册、启动期清理）
- `TwitterDownloaderApp` — Application 基类（下载引擎初始化与 16KB 页设备探测）
- `SplashActivity` — LAUNCHER，开屏（含跳过），随后进 `MainActivity`
- `MainActivity` — 单 Activity + Compose 导航宿主
- `capture/CaptureIntentActivity` — 分享面板 / 选中文本的外部入口

## 模块划分

```
app/src/main/java/com/ed/edqiu/
├── ui/           (69)  Compose 界面：screens（收件箱）/ player / detail / list / history /
│                       backup（同步详情）/ settings / onboarding / splash / authors /
│                       subscription（作品订阅）/ privacy（浏览模式门控）/
│                       components（玻璃组件）/ theme
├── data/         (49)  本地数据层：db + database（Room 双库）、preferences（DataStore）、
│                       repository、model、metadata、proxy、backup（收件箱数据备份）
├── backup/       (3)   WebDAV 引擎与凭证（BackupException / http 工具 / WebDavEngine ——
│                       2026-10 网盘备份中心已移除，见 WEBDAV-SYNC.md）
├── service/      (13)  yt-dlp 封装 + 引擎独立更新器（YtdlpEngineUpdater）、直连下载、
│                       目录扫描、pHash 查重、画质升级、发布时间补拉、订阅轮询、
│                       WebDAV 同步、应用内更新
├── background/    (8)  WorkManager 后台任务（后台同步 / 持久队列 / 收件箱备份 / 引擎自更新）
├── viewmodel/     (3)  ViewModel（经手动 ViewModelFactory 注入）
├── domain/        (3)  推文链接与 ID 提取（单链 / 批量）
├── downloader/    (6)  FXTwitter 引擎、第三方 API 引擎、直连下载 + 下载前视频源选型
│                       （VideoSourcePolicy）、多媒体清单续传（TweetMediaManifest）、
│                       引擎健康度（熔断 / 亲和 / 双线隔离）
├── capture/       (4)  分享 / 选中文本捕获、剪贴板解析、t.co 短链展开
├── navigation/    (1)  AppNavigation（页面路由）
├── di/            (2)  AppContainer（手写依赖容器）
└── provider/      (1)  跨应用媒体读取 Provider（同签名校验）
```

## 主要链路

**捕获 → 收件箱 → 下载 → 媒体库**

```
分享面板 / 选中文本 / 手动粘贴
        └─ capture ─→ data(db) 收件箱条目（含回收站、删除可撤销）
                        └─ downloader（FXTwitter → yt-dlp → 第三方 API 降级；
                           下载前 VideoSourcePolicy 格式实证选型 / TweetMediaManifest 续传清单）
                                └─ service（下载 / 断点续传 / pHash 查重 / 画质升级）
                                        └─ 本地媒体 → ui/media（媒体库）→ ui/player（沉浸式播放器）

yt-dlp 引擎独立云控更新（与下载链路解耦）
        background/YtdlpEngineUpdateWorker（每日）或 设置页手动
                └─ service/YtdlpEngineUpdater（双通道拉取：代理直连 → gh-proxy 镜像）
                        └─ 校验 → 落位 → 真机试运行（--version）→ 失败回滚 + 仓库 LKG 清单回退
```

**网盘同步（WebDAV，2026-10 重构）**

```
CloudSyncPreferences（每网盘独立配置 cfg_{pid}_*，测试成功才保存）
        │
        ├─ 手动/自动：service/WebDavSyncService（双向同步：上传对账 + 下载回补）
        │       └── backup/provider/WebDavEngine（PROPFIND 对账 / PUT 断点续传 / Basic+Digest）
        ├─ 应用级运行器：WebDavManualSync（离开页面同步不中断）
        └─ 自动：background/WebDavAutoBackupWorker（WorkManager 周期任务）
```

## 相关文档

- [CHANGELOG.md](CHANGELOG.md) — 版本历史
- [release-automation.md](release-automation.md) — 发版流程
- [webdav-cloud-backup-guide/](webdav-cloud-backup-guide/webdav-cloud-backup-guide.html) — WebDAV 备份图文指南
- [design/](design/) — 设计资产：图标源文件、液态玻璃原型（HTML）
- [archive/](archive/) — 历史文档（含早期包名时期的架构规划）
