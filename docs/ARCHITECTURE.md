# 架构说明

Edqiu 的整体结构：单模块 Android 应用（`com.ed.Edqiu`），Jetpack Compose + Kotlin，
无 Hilt（手写依赖容器），共约 184 个 Kotlin 文件。

> 本文以**当前源码为准**。早期两份规划文档
> （`ARCHITECTURE-v2.md`、`system_design.md`，包名仍是 `com.ed.twitterdownload*` 的 TwitterDownloader / XInvox 时期）
> 已移入 [`archive/`](archive/)，仅作历史参考，不要据此理解现状。

## 技术栈

| 项 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3（壁纸动态取色 Monet，经 `materialkolor`） |
| 玻璃材质 | `Kyant0/AndroidLiquidGlass`（AGSL 真折射，Android 13+；低版本走自绘回退） |
| 下载 | FXTwitter 直连 / `yt-dlp`（`youtubedl-android`，含 ffmpeg）/ 第三方 API 三级降级 |
| 本地存储 | Room 双库 + DataStore Preferences（`xinvox_settings`） |
| 后台任务 | WorkManager |
| 注入 | 手写 `di/AppContainer`，无 Hilt |
| 密钥 | AndroidKeyStore + AES/GCM（登录态、网盘凭证） |

## 应用入口

- `TwitterDownloaderApp` — `Application`，创建 `AppContainer`、注册后台调度
- `SplashActivity` — LAUNCHER，开屏（含跳过），随后进 `MainActivity`
- `MainActivity` — 单 Activity + Compose 导航宿主
- `capture/CaptureIntentActivity` — 分享面板 / 选中文本的外部入口

## 模块划分

```
app/src/main/java/com/ed/edqiu/
├── ui/           (70)  Compose 界面：screens（收件箱）/ player / detail / list / history /
│                       backup / settings / onboarding / splash / authors / components（玻璃组件）/ theme
├── data/         (49)  本地数据层：db + database（Room 双库）、preferences（DataStore）、
│                       repository、model、metadata、proxy、backup（台账）
├── backup/       (25)  网盘直连备份：引擎、分片上传、各家网盘适配器、HTTP 层
├── service/      (11)  FXTwitter 解析、yt-dlp 封装、直连下载、目录扫描、pHash 查重、
│                       画质升级、发布时间补拉、订阅轮询、WebDAV 同步、应用内更新
├── background/    (7)  WorkManager 后台任务（后台同步 / 持久队列 / 收件箱备份）
├── viewmodel/     (4)  ViewModel（经手动 ViewModelFactory 注入）
├── domain/        (3)  推文链接与 ID 提取
├── downloader/    (3)  FXTwitter 引擎、第三方 API 引擎、引擎健康度（熔断 / 亲和）
├── capture/       (2)  分享 / 选中文本捕获
├── navigation/    (1)  AppNavigation（页面路由）
├── di/            (2)  AppContainer（手写依赖容器）
├── predownload/   (1)  自动预下载协调器
└── provider/      (1)  跨应用媒体读取 Provider（同签名校验）
```

## 主要链路

**捕获 → 收件箱 → 下载 → 媒体库**

```
分享面板 / 选中文本 / 手动粘贴
        └─ capture ─→ data(db) 收件箱条目（含回收站、删除可撤销）
                        └─ downloader（FXTwitter → yt-dlp → 第三方 API 降级）
                                └─ service（下载 / 断点续传 / pHash 查重 / 画质升级）
                                        └─ 本地媒体 → ui/media（媒体库）→ ui/player（沉浸式播放器）
```

**云备份**

```
data（下载历史 / 台账）─→ backup（引擎 + 分片上传 + 各网盘适配器）
                             ├─ 手动：service/WebDavManualSync（应用级任务，离开页面不中断）
                             └─ 自动：background（WorkManager 周期任务）
```

## 相关文档

- [CHANGELOG.md](CHANGELOG.md) — 版本历史
- [release-automation.md](release-automation.md) — 发版流程
- [webdav-cloud-backup-guide/](webdav-cloud-backup-guide/webdav-cloud-backup-guide.html) — WebDAV 备份图文指南
- [design/](design/) — 设计资产：图标源文件、液态玻璃原型（HTML）
- [archive/](archive/) — 历史文档（含早期包名时期的架构规划）
