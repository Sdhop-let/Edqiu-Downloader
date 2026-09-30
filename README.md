# Edqiu

Android 原生的 X/Twitter 媒体下载器：链接收件箱、媒体下载、本地媒体库与沉浸式播放器一体化。

> 当前版本：**v1.6.9**（versionCode 45）· compileSdk/targetSdk 37（Android 17）· minSdk 24
> 历史曾用名：XInvox / Es Qp / TwitterDownloader（现已合并为 Edqiu 单一应用）

## 功能

- **收件箱**：捕获、整理、搜索 X/Twitter `status` 链接，批量管理并派发下载；失败记录与回收站。
- **下载器**：链接解析、媒体下载、任务管理；外部分享链接直接进入应用下载。
- **媒体库**：本地媒体统一视图，按作者/发帖日期分组，文案/作者/文件名搜索；
  pHash 感知哈希查重（一键清理画面相似项）、发布时间联网补拉、画质升级（自动换更高码率/分辨率版本）。
- **播放器**：
  - 抖音式纵向滑动流，滑动按素材类型分流（视频会话只切视频、图片会话只切图片）；
  - X/Twitter 式页面过渡：媒体面内嵌页面、首帧渲染完成后交接封面，切条零黑帧、比例恒定（视频按下载时原始宽高比播放）；
  - 倍速、静音、横屏、seek、播放列表选片；
  - 订阅作者：发现新作品自动下载。
- **云备份**：WebDAV 媒体备份（指南见 [docs/webdav-cloud-backup-guide](docs/webdav-cloud-backup-guide/webdav-cloud-backup-guide.html)）。
- **界面**：Material 3 + 壁纸动态取色，液态玻璃质感（透明度/磨砂/折射独立可调），悬浮胶囊底栏。

## 下载

前往 [GitHub Releases](https://github.com/Sdhop-let/Edqiu-Downloader/releases) 下载最新 APK：

| 渠道 | 说明 |
|---|---|
| Releases 最新版 | `Edqiu-v<版本号>.apk`，版本号与 [docs/RELEASE_NOTES-1.6.9.md](docs/RELEASE_NOTES-1.6.9.md) 对应 |
| 更新日志 | 每个 Release 附当版变更说明 |

> 安装时如提示未知来源，请确认 APK 来自本仓库的 Releases 页面。

## 构建

要求：JDK 21、Android SDK（compileSdk 37）。

```bash
./gradlew.bat :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

发布流程（构建 → 提交 → tag → GitHub Release）见 [docs/release-automation.md](docs/release-automation.md)。

## 技术栈

- **语言/UI**：Kotlin + Jetpack Compose（Material 3、Compose BOM 2026.09）
- **播放**：Media3 / ExoPlayer 1.5.1（TextureView 渲染，支持容器变形转场）
- **图片**：Coil 2.7（含视频帧提取）
- **数据**：Room + DataStore；手动依赖注入（`di/AppContainer`）
- **许可**：[Apache License 2.0](LICENSE)

## 文档索引（docs/）

| 文档 | 内容 |
|---|---|
| [HANDOFF-2026-09-28.md](docs/HANDOFF-2026-09-28.md) | 架构级变更交接（主题系统、玻璃参数、返回过渡、弹窗体系） |
| [RELEASE_NOTES-1.6.9.md](docs/RELEASE_NOTES-1.6.9.md) | 当前版本更新说明（播放器信息流重构等） |
| [release-automation.md](docs/release-automation.md) | 发版流程（GitHub Release） |
| [OVERVIEW.md](docs/OVERVIEW.md) · [BUG_AUDIT.md](docs/BUG_AUDIT.md) | 历史档案（1.2.0 时期，已标注） |
| [system_design.md](docs/system_design.md) · [ARCHITECTURE-v2.md](docs/ARCHITECTURE-v2.md) | 系统与架构设计 |
| [figma-redesign/](docs/figma-redesign/) | 液态玻璃设计原型（HTML） |
