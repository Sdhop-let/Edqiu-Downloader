# Edqiu

Android 原生的 X/Twitter 媒体下载器：链接收件箱、媒体下载、本地媒体库与沉浸式播放器一体化，并支持把下载历史与媒体直连备份到你的网盘。

> 当前版本：**v1.8.0**（versionCode 48）· compileSdk/targetSdk 37（Android 17）· minSdk 24 · 仅 arm64-v8a
> 历史曾用名：XInvox / Es Qp / TwitterDownloader（现已合并为 Edqiu 单一应用）

[![Download](https://img.shields.io/badge/下载-GitHub_Releases-2563EB)](../../releases/latest)
[![Release CI](https://img.shields.io/badge/发版-推送_tag_自动构建-2EA043)](.github/workflows/release.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-F0B429)](LICENSE)

---

## 下载安装

前往 [**Releases 页面**](../../releases/latest) 下载 `Edqiu-vX.X.X.apk`：

1. 下载 APK（约 84MB，arm64-v8a，适用绝大多数现代手机）——**已安装旧版的用户建议走应用内
   「检查更新」**：弹窗内可直接查看更新内容，并提供**国内镜像直连 / GitHub 官方直连**双下载源；
2. 允许「安装未知来源应用」（仅首次）；
3. 覆盖安装即可，数据全部保留。

> 系统要求：Android 7.0（API 24）及以上。应用不含广告、不要求账号、不上传任何数据到开发者的服务器——备份目标是你自己的网盘。

## 功能

### 收件箱
- 从 X/Twitter 分享面板、选中文本、手动粘贴三种方式捕获 `status` 链接；
- 链接整理、搜索、批量管理；失败记录与回收站（删除可撤销）；
- 可选自动预下载（攒批触发）与作者订阅（发现新作品自动下载）。

### 下载器
- 三层引擎链自动降级：FXTwitter 直连（最快）→ yt-dlp（稳，支持 Cookie 登录态）→ 第三方 API 兜底（可选）；
- 引擎熔断与单推文亲和：连续失败的引擎临时跳过，避免每次下载先撞一遍必死层；
- 最高画质优先：检测到 HLS 高码率流时自动转 yt-dlp 拉 1080p+；断点续传、取消即停；
- 可选代理（HTTP/SOCKS5，自动检测本机代理端口）与 Cookie 配置（Keystore 加密存储）。

### 媒体库
- 本地媒体统一视图，按作者/发布日期分组；文案、作者、文件名搜索；
- pHash 感知哈希查重，一键清理画面相似项；
- 发布时间联网补拉、画质升级（自动换更高码率/分辨率版本）。

### 播放器
- 抖音式纵向滑动流，滑动按素材类型分流（视频会话只切视频、图片会话只切图片）；
- X/Twitter 式页面过渡：媒体面内嵌页面、首帧渲染完成后交接封面，切条零黑帧；
- **媒体框比例跟手渐变**：翻页时媒体框按前后两条视频的真实比例连续形变，形变期
  裁切补位无黑边，落定立即起播；多次滑动后返回媒体库精准定位到当前视频卡片；
- 倍速、静音、横屏、seek、播放列表选片；右缘跟手拖拽返回。

### 云备份
- **云备份统一入口在「WebDAV 同步」页**：WebDAV 直连同步配置 + 「网盘备份中心」
  （百度 / 123 / 阿里 / 自定义 WebDAV 直连备份的授权、备份范围与任务状态）+ 同步情况一览；
- 分片上传 + 断点续传 + 秒传（阿里）+ 远端删除对账；手动同步挂应用级任务，
  退出页面不中断，回到页面补看结果；
- 下载历史 JSON 备份/恢复（SHA-256 校验），支持 WebDAV 与本地公共目录；
- WebDAV 配置指南：[docs/webdav-cloud-backup-guide](docs/webdav-cloud-backup-guide/webdav-cloud-backup-guide.html)。

### 界面
- Material 3 + 壁纸动态取色（Monet），液态玻璃质感（透明度/磨砂/折射独立可调）；
- 悬浮胶囊底栏、跟手返回转场、深色模式跟随系统；
- 开屏显式「跳过」入口；首次安装三步引导（Cookie/代理可跳过）带错峰入场动画与
  跟手指示器；
- **应用内检查更新**：更新弹窗展示更新内容与双下载源（国内镜像直连 / GitHub 官方直连）。

## 从源码构建

```bash
# 环境：JDK 17+（推荐 21）、Android SDK Platform 37
git clone https://github.com/Sdhop-let/Edqiu-Downloader.git
cd Edqiu-Downloader

# Debug 构建
JAVA_HOME="<你的JDK21路径>" ./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release 构建（需在仓库根目录自备 keystore.properties，格式见下；该文件已被 .gitignore 排除，永不入库）
JAVA_HOME="<你的JDK21路径>" ./gradlew :app:assembleRelease
```

`keystore.properties` 格式：

```properties
storeFile=<keystore 文件路径>
storePassword=<keystore 口令>
keyAlias=<别名>
keyPassword=<别名口令>
```

### 发版流程（维护者）

推送 `v*` tag（如 `v1.7.0`）→ GitHub Actions 自动构建 APK → 创建 Release 并上传 `Edqiu-<tag>.apk`，
Release 说明自动取 `docs/` 下最新的 `RELEASE_NOTES-*.md`。详见 [docs/release-automation.md](docs/release-automation.md)。
网盘备份的应用资质（百度/阿里/123 的 client_id/secret）通过 `gradle.properties` 注入 BuildConfig，缺省留空时对应功能提示"未配置"。

## 项目结构

```
app/src/main/java/com/ed/edqiu/
├── background/     # WorkManager 后台任务（下载同步/持久队列/收件箱备份）
├── backup/         # 网盘直连备份：引擎、分片上传、5 家网盘适配器、HTTP 层
├── capture/        # 分享/选中文本捕获入口
├── data/           # Room 双库（下载历史 + 收件箱/回收站/备份台账）、偏好、仓库层
├── di/             # 手写依赖容器（AppContainer，无 Hilt）
├── downloader/     # FXTwitter 引擎、第三方 API 引擎、引擎健康度（熔断/亲和）
├── domain/         # 推文链接/ID 提取
├── predownload/    # 自动预下载协调器
├── provider/       # 跨应用媒体读取 Provider（同签名校验）
├── service/        # yt-dlp 封装、直连下载器、目录扫描、画质升级、订阅轮询
├── ui/             # Compose 界面（收件箱/播放器/媒体库/备份中心/设置/引导）
└── viewmodel/      # ViewModel（手动 ViewModelFactory 注入）
```

另见 [docs/ARCHITECTURE-v2.md](docs/ARCHITECTURE-v2.md)（架构说明）、[docs/system_design.md](docs/system_design.md)。

## 隐私与权限

| 权限 | 用途 |
|---|---|
| INTERNET | 下载媒体、解析推文、备份上传 |
| FOREGROUND_SERVICE (DATA_SYNC) | 大批量备份的前台进度（Android 15 有 6 小时限档，已做时间预算） |
| POST_NOTIFICATIONS | 备份/下载进度通知（Android 13+ 运行时申请） |
| MANAGE_EXTERNAL_STORAGE | 仅用于把备份写入你指定的公共目录（可选授权，不授权不影响核心功能） |
| REQUEST_INSTALL_PACKAGES | 应用内更新（从本项目 GitHub Releases 下载 APK） |

- Cookie、网盘凭证等全部登录态使用 AndroidKeyStore + AES/GCM 加密存储，且已排除出系统云备份/换机迁移；
- 无任何统计/埋点 SDK；网络请求仅面向 X/FXTwitter/你配置的网盘与代理。

## 已知限制

- **16KB 内存页机型**（部分 Android 15+ 新机）：上游 ffmpeg 依赖库未适配对齐，yt-dlp 合流/HLS 升级暂不可用；FXTwitter 直连链路不受影响（详见 `app/build.gradle.kts` 注释，等待上游修复）；
- 分发方式为 GitHub Releases 侧载：Google Play 的审核政策与自更新通道、全文件权限冲突，故不上架 Play；
- 仅支持 arm64-v8a 设备（x86 模拟器不适用）。

## 文档索引

| 文档 | 说明 |
|---|---|
| [docs/release-automation.md](docs/release-automation.md) | 发版流程（tag → CI → Release） |
| [docs/RELEASE_NOTES-1.8.0.md](docs/RELEASE_NOTES-1.8.0.md) | **本版更新内容**（玻璃外观参数化：描边/亮边/描边色/压暗） |
| [docs/RELEASE_NOTES-1.7.0.md](docs/RELEASE_NOTES-1.7.0.md) | 上版更新内容（备份中心整合/播放器比例/更新弹窗等，历史） |
| [docs/RELEASE_NOTES-1.6.9.1.md](docs/RELEASE_NOTES-1.6.9.1.md) | 上版修复清单（50+ 项，历史） |
| [docs/ARCHITECTURE-v2.md](docs/ARCHITECTURE-v2.md) / [docs/system_design.md](docs/system_design.md) | 架构与系统设计 |
| [docs/webdav-cloud-backup-guide/](docs/webdav-cloud-backup-guide/webdav-cloud-backup-guide.html) | WebDAV 备份图文指南 |
| [docs/archive/](docs/archive/) | 历史文档归档（XInvox 时代审计/PRD/旧版说明） |
| [docs/figma-redesign/](docs/figma-redesign/) | 液态玻璃设计原型（HTML） |

## 致谢

- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)（yt-dlp/ffmpeg Android 封装）
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（液态玻璃 Backdrop）
- [materialkolor](https://github.com/jordond/materialkolor)（动态取色）
- [Titanic](https://github.com/romainpiel/Titanic)（开屏水波动画）
- FXTwitter API、yt-dlp 项目

## License

[Apache License 2.0](LICENSE)
