# Edqiu · X/Twitter 视频下载与媒体管理

> 原生 Android 客户端：把 X/Twitter 视频保存到本地，并用抖音式竖滑流播放器管理你的媒体库。
> 曾用名 / 内部代号：XInvox · Es Qp · TwitterDownloader

![Release](https://img.shields.io/badge/version-1.5.1-blue) ![Platform](https://img.shields.io/badge/platform-Android%2016%2B%20arm64--v8a-green) ![License](https://img.shields.io/badge/license-Apache--2.0-lightgrey) ![Tech](https://img.shields.io/badge/Kotlin%20·%20Compose%20·%20Material%203-7F52FF)

## ✨ 功能特性

### 📥 下载引擎（三通道容灾）
- **剪贴板自动识别** X/Twitter 链接，按 tweet ID 去重，支持系统分享入队
- **FXTwitter 直链** → **第三方解析 API** → **yt-dlp 兜底**（Chaquopy 嵌入 Python 3.12）三层下载通道
- **下载引擎自愈**（v1.5.1）：三层全挂时进程内自动重建 yt-dlp 引擎并清熔断，无需重启 App
- **WorkManager 持久队列**（v1.5.1）：进程被杀后下载任务自动续跑
- HLS 高码率流检测与画质升级重下载（MediaQualityUpgrader）
- 推文失效自动标记 DELETED 终态；失败任务自动重试（≤3 次）

### 🎬 播放器
- **X 风格竖滑流**沉浸播放：覆盖层头像徽章行、底部中央操作钮、1.5s 自动沉浸
- **邻条预载**（v1.5.1）：media3 8s preload + `seekTo(index)` 切条，上滑即播
- 画质升级、倍速、seek、手势调节；仅播放页锁横屏
- 缩放策略：竖屏/方视频 FIT 无损，横屏视频竖屏智能 ZOOM

### 🗂 媒体库
- 按「作者 + 发布日」自动分组，推文发布时间倒序，失效记录垫底
- **文本搜索**（v1.5.1）：标题 / 作者 / 文案全文检索
- **pHash 视觉查重**（v1.5.1）：DCT 64bit，汉明距离 ≤4 判重
- 推文发布时间自动回填（PublishedAtBackfiller，FAST/MEDIUM/SLOW 动态调速）
- 头部统计卡即筛选器：数据可视化优先的低学习成本布局

### 📡 订阅与自动化
- **作者订阅**（v1.5.1）：25 分钟轮询作者主页，新作自动入库下载；播放页 ⋮ 一键订阅，设置页统一管理
- 收件箱/详情页后台下载（globalIoScope，退出页面不中断）
- WebDAV / 阿里云盘 / 123 云盘云备份，本地完整备份恢复

### 🎨 界面
- Material 3 + Monet 2026 柔和蓝设计系统
- iOS 26 Liquid Glass 风格：透明底部导航、玻璃胶囊反馈（与 Snackbar 互斥清场）
- X / Instagram / Bluesky 语义色贯穿链接卡片

## 📥 下载安装

前往 **[Releases Latest](https://github.com/Sdhop-let/Edqiu-Downloader/releases/latest)** 下载 APK。

- 架构：arm64-v8a（仅真机，Android 16/17+）
- debug 签名：同签名可直接覆盖安装；release 版需先卸载旧版
- 历史版本（含 MD5 校验值）见 [CHANGELOG.md](CHANGELOG.md) 与 [`releases/`](releases/) 目录

## 🔨 从源码构建

```bash
# 环境要求：JDK 21 · Android SDK (platform 35) · Android Studio AI-261+
git clone https://github.com/Sdhop-let/Edqiu-Downloader.git
cd Edqiu-Downloader
./gradlew assembleDebug        # 产物: app/build/outputs/apk/debug/app-debug.apk
```

- 仅保留 `arm64-v8a` ABI（ffmpeg / yt-dlp 二进制完整保留）
- 依赖解析走 settings.gradle.kts 内配置的镜像，无特殊私有源

## 🗂 仓库结构

```
Edqiu-Downloader/
├── app/                        # 应用源码（单模块）
│   └── src/main/java/com/ed/edqiu/
│       ├── background/         # WorkManager 持久队列 / 后台同步
│       ├── downloader/         # 三通道下载引擎与自愈
│       ├── service/            # pHash 查重 · 订阅轮询 · 画质升级 · 时间回填
│       ├── data/               # Room 数据库 / 仓库 / 偏好
│       └── ui/                 # Compose 界面（播放器 / 媒体库 / 收件箱 / 设置）
├── docs/                       # 架构与历史文档（OVERVIEW / BUG_AUDIT / 版本笔记）
├── releases/                   # 按版本归类的发布说明（v1.4.23 → v1.5.1 …）
├── scripts/                    # 构建 / 发布辅助脚本
├── tools/                      # UI 审计脚本
├── figma-redesign/             # 设计原型（Liquid Glass）
└── CHANGELOG.md                # 全版本时间线（版本号 · code · 日期 · MD5）
```

## 📄 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/OVERVIEW.md](docs/OVERVIEW.md) | 产品概览与设计决策 |
| [docs/BUG_AUDIT.md](docs/BUG_AUDIT.md) | 历史缺陷审计报告 |
| [docs/ARCHITECTURE-v2.md](docs/ARCHITECTURE-v2.md) | v2 架构设计 |
| [CHANGELOG.md](CHANGELOG.md) | 全版本发布时间线 |
| [releases/v1.5.1](releases/v1.5.1/RELEASE-NOTES.md) | 最新版本详细说明 |

## ⚖️ 免责声明

本项目仅供学习与技术研究。下载内容的版权归原作者所有，请勿用于商业用途或侵犯他人权益；使用者需遵守所在地法律法规及平台服务条款。

## License

[Apache-2.0](LICENSE)
