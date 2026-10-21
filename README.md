# Edqiu

**把 X / Twitter 的图片与视频存到本机，并直连备份到你的网盘。**

Android 原生（Jetpack Compose）。无广告、无账号、无埋点——数据只存在你自己的手机和网盘里。

[**下载 v1.9.4**](../../releases/latest)　·　[更新说明](docs/CHANGELOG.md)　·　[文档](#文档)

[![Release CI](https://img.shields.io/badge/发版-推送_tag_自动构建-2EA043)](.github/workflows/release.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-F0B429)](LICENSE)

> v1.9.4（versionCode 53）· Android 7.0+（API 24）· 仅 arm64-v8a
> 曾用名：XInvox / EsQp / TwitterDownloader（已合并为 Edqiu）

---

## 设计原则

- **手动下载是唯一权威路径**——所有自动机制只负责「发现」与「准备」，真正下载永远由你点按触发，后台行为不可能挤占或误伤手动下载。
- **失败必须说人话**——检测与下载失败一律给出具体原因与下一步指引（未走代理 / 代理超时 / 账号被限 / Cookie 失效 / 接口轮换失败……），绝无静默失败。
- **数据只属于你**——无账号、无埋点、无统计 SDK，登录态硬件级加密，网络请求仅面向 X / FXTwitter / 你配置的网盘与代理。

---

## 能力

### 收件箱与下载

| | |
|---|---|
| **收件箱** | 分享面板 / 选中文本 / 手动粘贴捕获推文链接；整理、搜索、批量管理、回收站（删除可撤销） |
| **下载器** | FXTwitter → yt-dlp → 第三方 API 三级引擎自动降级；HLS 高码率自动转 1080p+；断点续传；引擎熔断按发起方隔离——后台路径的失败永不误伤你的手动下载 |
| **媒体库** | 按作者 / 日期分组；pHash 感知哈希查重；发布时间联网补拉、画质升级 |
| **播放器** | 抖音式纵向滑动流，按素材类型分流；媒体框比例跟手形变、切条零黑帧；倍速 / 横屏 / seek |

### 订阅与自动化

| | |
|---|---|
| **作品订阅** | 订阅 X / Bluesky / Instagram 作者，后台轮询检测新作品。**检测到只收录、不自动下载**——新作品进「订阅消息」与收件箱，由你点按后再下载 |
| **X 检测通道** | X GraphQL 直连：guest token 免登录主通道 + 登录 Cookie 兜底，按数据新鲜度自动切换；queryId 走远程清单静默轮换，接口变更无需发版 |
| **起始日期** | 每个订阅可自定义起始日期，只检测该日期之后发布的新帖，历史内容不再涌入 |
| **失败可见** | 检测/下载失败分类透出并附操作指引（未走代理 / 代理超时 / 账号被限 / Cookie 失效 / 接口轮换失败……），订阅消息与检测状态卡常驻显示 |

### 反馈与界面

| | |
|---|---|
| **通知分层** | 带原因的操作结果（批量下载汇总 / 下载失败 / 无效链接）→ 居中弹窗必须确认；瞬时状态（保存成功 / 已复制 / 下载进度 / 删除可撤销）→ 底部玻璃胶囊，悬于 Tab 栏之上不遮挡 |
| **界面** | iOS 26/27 Liquid Glass 设计语言；固定中性配色（浅色清透白 / 深色石墨黑）+ 固定强调色（iOS 绿）；玻璃透明度 / 磨砂 / 折射 / 描边 / 亮边 / 压暗 + 光学三项（光源角度 / 亮度 / 色散）全参数可调；统一组件语言——玻璃卡片 / 填充式输入场 / 勾选圆圈 / 滑杆 / 分段胶囊 / 居中弹窗体系（86% 屏宽大弹窗） |

### 云与数据

| | |
|---|---|
| **网盘同步** | WebDAV 直连任意私有云盘（123网盘 / 城通 / CloudDrive2 / 坚果云 / NAS…）；每网盘独立账号配置；双向同步（本地文件永不覆盖）+ 断点续传；逐文件对账详情（暂停 / 优先 / 冲突改名保留） |
| **数据备份** | 收件箱 / 回收站数据本地 JSON 备份与导入导出（SHA-256 校验） |

---

## 安装

1. 从 [Releases](../../releases/latest) 下载 `Edqiu-vX.X.X.apk`（约 71 MB）；
2. 允许「安装未知来源应用」（仅首次）；
3. 覆盖安装，数据全部保留。

已装旧版可直接走应用内「检查更新」：弹窗内查看更新内容，并提供国内镜像 / GitHub 官方双下载源。

> v1.7.0 之前的版本曾内置「预下载（攒批自动下载）」，v1.9.3 起移除：保存的链接一律在收件箱等待你点按下载，行为更可预期。

---

## 从源码构建

```bash
# 环境：JDK 21、Android SDK Platform 37
git clone https://github.com/Sdhop-let/Edqiu-Downloader.git && cd Edqiu-Downloader

JAVA_HOME="<JDK21>" ./gradlew :app:assembleDebug     # 产物 app/build/outputs/apk/debug/
JAVA_HOME="<JDK21>" ./gradlew :app:assembleRelease   # 需自备 keystore.properties（被 .gitignore 排除）
```

`keystore.properties` 需四项：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`。
百度 / 阿里 / 123 的应用资质经 `gradle.properties` 注入，留空时对应功能提示「未配置」。
`tools/` 下另有若干开发辅助脚本（产物归档、UI 一致性审核），详见 [tools/README.md](tools/README.md)。

**发版（维护者）**：推 `v*` tag → GitHub Actions 自动构建并创建 Release，说明自动取
`docs/RELEASE_NOTES-<版本>.md`。详见 [docs/release-automation.md](docs/release-automation.md)。

**订阅检测接口维护**：`x_api_manifest.json`（仓库根）是 X 检测通道的远程配置源——
X 轮换 GraphQL queryId 后，更新此文件并推送 main 即可热更新所有客户端，无需发版。
客户端在代理可用时每 7 天静默拉取，检测到陈旧签名时按需即时拉取并重试一次。

## 仓库结构

> 根目录下的 `build.gradle.kts`、`settings.gradle.kts`、`gradlew`、`gradle.properties`、`.gitignore`
> 是 Gradle / Android 构建的**硬性约定位置**，必须位于仓库根目录——这是所有 Gradle 项目的标准形态，
> 移入子文件夹会导致无法构建。下表逐一标注每个条目的用途。

### 文件夹

| 目录 | 用途 |
|---|---|
| **app/** | 应用全部源码与资源：Kotlin + Jetpack Compose，`app/src/main/java/com/ed/edqiu/` 下按 `ui/`（页面与组件）、`data/`（数据库与仓库）、`data/xapi/`（X GraphQL 检测客户端）、`downloader/`（三级下载引擎）、`service/`（订阅轮询 / WebDAV 同步 / 网盘联动）、`navigation/`（双层导航）等分包 |
| **docs/** | 项目文档：版本历史（CHANGELOG）、各版本发布说明（RELEASE_NOTES-\*）、架构说明、发版自动化、WebDAV 图文指南；子目录 `design/` 为图标源文件与液态玻璃原型，`archive/` 为历史文档归档 |
| **tools/** | 开发辅助脚本（产物归档、构建推送、UI 一致性审核等），不参与 App 构建，仅本机开发用 |
| **gradle/wrapper/** | Gradle Wrapper 本体（`gradle-wrapper.jar` + `gradle-wrapper.properties`），保证任何人克隆后用统一版本构建 |
| **.github/workflows/** | GitHub Actions 工作流：`release.yml`（推 `v*` tag 自动签名构建并发布 Release） |

### 根目录文件

| 文件 | 用途 |
|---|---|
| **README.md** | 本文件——项目门面页 |
| **x_api_manifest.json** | X 订阅检测的远程配置清单（bearer / queryId）——客户端静默轮换源，改此文件等于热更新全部客户端 |
| **build.gradle.kts** | 根构建脚本：声明插件版本（AGP / Kotlin / KSP），子模块配置在 `app/build.gradle.kts` |
| **settings.gradle.kts** | 工程设置：模块注册、依赖仓库源（google / mavenCentral / 本地构件仓） |
| **gradle.properties** | Gradle 与 Android 构建参数（JVM 内存、AndroidX 开关、非 SDK 依赖注入位） |
| **gradlew** / **gradlew.bat** | Gradle Wrapper 启动器（Linux/Mac 与 Windows 两个入口） |
| **.gitignore** | Git 忽略规则（构建产物、签名文件、本地配置不入库） |
| **LICENSE** | 开源协议：Apache License 2.0 |

## 隐私与权限

| 权限 | 用途 |
|---|---|
| INTERNET | 下载媒体、解析推文、订阅检测、备份上传 |
| FOREGROUND_SERVICE (DATA_SYNC) | 大批量备份的前台进度 |
| POST_NOTIFICATIONS | 下载 / 备份进度通知（Android 13+ 运行时申请） |
| MANAGE_EXTERNAL_STORAGE | 仅用于把备份写入你指定的公共目录（可选，不授权不影响核心功能） |
| REQUEST_INSTALL_PACKAGES | 应用内更新（从本项目 Releases 下载 APK） |

Cookie、网盘凭证等登录态经 AndroidKeyStore + AES/GCM 加密存储，并已排除出系统云备份与换机迁移；
无任何统计 / 埋点 SDK，网络请求仅面向 X / FXTwitter / 你配置的网盘与代理。
订阅检测使用 X 网页版同源的公开接口与游客令牌，不携带任何账号信息（配置 Cookie 后才会以你的身份读取时间线）。

## 已知限制

- **16KB 内存页机型**（部分 Android 15+ 新机）：上游 ffmpeg 依赖未适配对齐，yt-dlp 合流 / HLS 升级暂不可用；
  FXTwitter 直连链路不受影响（等待上游修复）；
- **X 订阅检测**：游客通道对个别账号可能返回降级数据（App 会标注并建议配置 Cookie）；国内网络需在应用内配置代理；
- 仅通过 GitHub Releases 侧载分发（Google Play 的审核政策与自更新通道、全文件权限冲突），不上架 Play；
- 仅支持 arm64-v8a 设备。

## 文档

| 文档 | 说明 |
|---|---|
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | **全部版本历史**（v1.3.0 起） |
| [docs/RELEASE_NOTES-1.9.4.md](docs/RELEASE_NOTES-1.9.4.md) | 当前版本完整说明 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构：技术栈、模块划分、主要链路 |
| [docs/release-automation.md](docs/release-automation.md) | 发版流程 |
| [docs/webdav-cloud-backup-guide/](docs/webdav-cloud-backup-guide/webdav-cloud-backup-guide.html) | WebDAV 备份图文指南 |
| [docs/design/](docs/design/) | 设计资产：图标源文件、液态玻璃原型、设计验收清单（AUDIT-CHECKLIST.md） |
| [docs/archive/](docs/archive/) | 历史文档归档 |

## 致谢

- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)（yt-dlp / ffmpeg Android 封装）
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（液态玻璃 Backdrop）
- [Titanic](https://github.com/romainpiel/Titanic)（开屏水波动画）
- FXTwitter API、yt-dlp 项目

## License

[Apache License 2.0](LICENSE)
