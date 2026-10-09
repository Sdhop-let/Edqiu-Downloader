# Edqiu

**把 X / Twitter 的图片与视频存到本机，并直连备份到你的网盘。**

Android 原生（Jetpack Compose），无广告、无账号、无埋点——数据只存在你自己的手机和网盘里。

[**下载 v1.8.0**](../../releases/latest)　·　[更新说明](docs/CHANGELOG.md)　·　[文档](#文档)

[![Release CI](https://img.shields.io/badge/发版-推送_tag_自动构建-2EA043)](.github/workflows/release.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-F0B429)](LICENSE)

> 当前版本 v1.8.0（versionCode 48）· Android 7.0+（API 24）· 仅 arm64-v8a
> 历史曾用名：XInvox / EsQp / TwitterDownloader（现已合并为 Edqiu）

---

## 核心能力

| | |
|---|---|
| **收件箱** | 分享面板 / 选中文本 / 手动粘贴三种方式捕获推文链接；整理、搜索、批量管理、回收站（删除可撤销） |
| **下载器** | FXTwitter → yt-dlp → 第三方 API 三层引擎自动降级；引擎熔断避免反复撞必死层；HLS 高码率自动转 1080p+；断点续传 |
| **媒体库** | 按作者 / 日期分组的本地媒体视图；pHash 感知哈希查重；发布时间联网补拉、画质升级 |
| **播放器** | 抖音式纵向滑动流，按素材类型分流；媒体框比例跟手形变、切条零黑帧；倍速 / 横屏 / seek / 播放列表 |
| **云备份** | 百度 / 123 / 阿里 / 自定义 WebDAV 直连备份；分片上传 + 断点续传 + 秒传；下载历史 JSON 备份（SHA-256 校验） |
| **界面** | Material 3 + 壁纸动态取色（Monet）；液态玻璃质感（透明度 / 磨砂 / 折射 / 描边 / 亮边 / 压暗可调）；悬浮胶囊底栏 |

## 安装

1. 从 [Releases](../../releases/latest) 下载 `Edqiu-vX.X.X.apk`（约 71 MB）；
2. 允许「安装未知来源应用」（仅首次）；
3. 覆盖安装，数据全部保留。

**已装旧版**可直接走应用内「检查更新」：弹窗内查看更新内容，并提供国内镜像 / GitHub 官方双下载源。

## 从源码构建

```bash
# 环境：JDK 21、Android SDK Platform 37
git clone https://github.com/Sdhop-let/Edqiu-Downloader.git && cd Edqiu-Downloader

JAVA_HOME="<JDK21 路径>" ./gradlew :app:assembleDebug    # 产物 app/build/outputs/apk/debug/
JAVA_HOME="<JDK21 路径>" ./gradlew :app:assembleRelease  # 需自备 keystore.properties（见下，已被 .gitignore 排除）
```

`keystore.properties` 格式：`storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项。
百度 / 阿里 / 123 的应用资质通过 `gradle.properties` 注入，留空时对应功能提示「未配置」。

发版（维护者）：推送 `v*` tag → GitHub Actions 自动构建并创建 Release，说明自动取 `docs/RELEASE_NOTES-<版本>.md`。
详见 [docs/release-automation.md](docs/release-automation.md)。

## 隐私与权限

| 权限 | 用途 |
|---|---|
| INTERNET | 下载媒体、解析推文、备份上传 |
| FOREGROUND_SERVICE (DATA_SYNC) | 大批量备份的前台进度 |
| POST_NOTIFICATIONS | 下载 / 备份进度通知（Android 13+ 运行时申请） |
| MANAGE_EXTERNAL_STORAGE | 仅用于把备份写入你指定的公共目录（可选，不授权不影响核心功能） |
| REQUEST_INSTALL_PACKAGES | 应用内更新（从本项目 Releases 下载 APK） |

Cookie、网盘凭证等登录态使用 AndroidKeyStore + AES/GCM 加密存储，且已排除出系统云备份与换机迁移；
无任何统计 / 埋点 SDK，网络请求仅面向 X / FXTwitter / 你配置的网盘与代理。

## 已知限制

- **16KB 内存页机型**（部分 Android 15+ 新机）：上游 ffmpeg 依赖未适配对齐，yt-dlp 合流 / HLS 升级暂不可用，
  FXTwitter 直连链路不受影响（等待上游修复，详见 `app/build.gradle.kts` 注释）；
- 仅通过 GitHub Releases 侧载分发（Google Play 的审核政策与自更新通道、全文件权限冲突），故不上架 Play；
- 仅支持 arm64-v8a 设备。

## 文档

| 文档 | 说明 |
|---|---|
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | **全部版本历史**（v1.3.0 起，含本版） |
| [docs/RELEASE_NOTES-1.8.0.md](docs/RELEASE_NOTES-1.8.0.md) | 当前版本完整说明 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构说明（技术栈、模块划分、主要链路） |
| [docs/release-automation.md](docs/release-automation.md) | 发版流程 |
| [docs/webdav-cloud-backup-guide/](docs/webdav-cloud-backup-guide/webdav-cloud-backup-guide.html) | WebDAV 备份图文指南 |
| [docs/figma-redesign/](docs/figma-redesign/) | 液态玻璃设计原型（HTML） |
| [docs/archive/](docs/archive/) | 历史文档归档 |

## 致谢

- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)（yt-dlp / ffmpeg Android 封装）
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（液态玻璃 Backdrop）
- [materialkolor](https://github.com/jordond/materialkolor)（动态取色）
- [Titanic](https://github.com/romainpiel/Titanic)（开屏水波动画）
- FXTwitter API、yt-dlp 项目

## License

[Apache License 2.0](LICENSE)
