# Edqiu

**把 X / Twitter 的图片与视频存到本机，并直连备份到你的网盘。**

Android 原生（Jetpack Compose）。无广告、无账号、无埋点——数据只存在你自己的手机和网盘里。

[**下载 v1.9.15**](../../releases/latest)　·　[更新说明](docs/CHANGELOG.md)　·　[文档](#文档)

[![Release CI](https://img.shields.io/badge/发版-推送_tag_自动构建-2EA043)](.github/workflows/release.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-F0B429)](LICENSE)

> v1.9.15（versionCode 64，新签名——v1.9.10 及更早版本须卸载重装，先网盘全量备份）· Android 7.0+（API 24）· 仅 arm64-v8a
> 曾用名：XInvox / EsQp / TwitterDownloader（已合并为 Edqiu）

---

## 设计原则

- **玻璃背后要有真实内容**——液态玻璃的质感来自「折射真实的东西」：背景直接取你的系统壁纸虚化铺底，卡片与底栏对真实照片做磨砂、折射与色散，而不是对着一块均匀色板空转。
- **手动下载是唯一权威路径**——所有自动机制只负责「发现」与「准备」，真正下载永远由你点按触发，后台行为不可能挤占或误伤手动下载。
- **失败必须说人话**——下载失败的原因直接写在卡片上（哪层引擎、什么错误、下一步怎么办），弹窗与卡片双通道透出，绝无静默失败。
- **状态会自愈**——收件箱与媒体库每轮同步互相比对：文件已在媒体库的条目自动回写「已下载」；同一作品重复下载自动合并保留最高画质；文件被外部删除的坏条目扫描即清。
- **删除是有记忆的**——你删掉的作品（含订阅作品）永不自动回来；订阅检测同时查收件箱与回收站，「防删除复活」是全链路铁律。
- **动画皆有始有终**——所有界面动画都是有限时长的一次性编排，应用能随时进入空闲（常驻动画曾引发系统生命周期乱序与画面冻结，此为根治结论）。
- **数据只属于你**——无账号、无埋点、无统计 SDK，登录态硬件级加密，网络请求仅面向 X / FXTwitter / 你配置的网盘与代理。

---

## 能力

### 收件箱与下载

| | |
|---|---|
| **链接捕获** | 四通道共用一条捕获管线：系统分享面板、任意 App 划词、跨 App 下载契约（`DOWNLOAD_TWEET`）、收件箱粘贴（自动读剪贴板，多条链接一键批量入库） |
| **收件箱** | iOS large title 语义：统计总览卡（待处理 / 已完成 / 失败 + **全部下载**与数据同位）随滚动收缩成进度摘要条，筛选行吸顶常驻；批量管理与多选；删除可撤销（撤销按钮直接在玻璃胶囊上） |
| **下载器** | FXTwitter → yt-dlp → 第三方 API 三级引擎自动降级；HLS 直拉（配 Cookie 时 ≤720p 视频下载即拉 1080p+，失败自动回退 mp4）；点按即刻反馈进度（引擎链启动期无空窗）；引擎熔断按发起方隔离——后台路径的失败永不误伤手动下载 |
| **失败可见** | 失败的引擎链详情直接写在卡片上，弹窗同步完整信息；自动重试按退避策略最多 3 次 |
| **媒体库** | **双列封面网格**（大封面 + 时长角标 + 作者/画质行，一屏尽览）；**默认按下载时间排序**——刚下完的在最上，可一键切换按发布时间（作者 · 发布日分组）；筛选计数胶囊固定在标题正下方；pHash 感知哈希查重；画质升级；与收件箱状态互相自愈 |
| **播放器** | 抖音式纵向滑动流，按素材类型分流；媒体框比例跟手形变、切条零黑帧；倍速 / 横屏 / seek；返回自动落位回网格中该卡片并高亮 |

### 订阅

| | |
|---|---|
| **作品订阅** | 订阅 X / Bluesky / Instagram 作者，后台轮询检测。**只收录、不自动下载**——新作品进订阅页（永不混入收件箱，双线隔离），由你点按下载 |
| **只收本人作品** | 转推 / 引推的原创者是别人——全部过滤（X 逐条校验作者、Bluesky 跳过转推），订阅列表里只有你订阅的作者本人的作品 |
| **X 检测通道** | X GraphQL 直连：guest token 免登录主通道 + 登录 Cookie 兜底，按数据新鲜度自动切换；queryId 走远程清单静默轮换，接口变更无需发版 |
| **起始日期** | 每个订阅可自定义起始日期，只检测该日期之后发布的新帖 |
| **失败可见** | 检测失败分类透出并附操作指引（未走代理 / 代理超时 / 账号被限 / Cookie 失效……），订阅消息与检测状态卡常驻显示 |

### 反馈与界面

| | |
|---|---|
| **壁纸舞台** | 液态玻璃直接折射你的系统壁纸：卡片对壁纸做真磨砂 + 真折射 + 棱镜色散；底栏玻璃后透出滚动中的文字（透字）；换壁纸即时热更新（无需重启）；加载失败自动降级色场 |
| **通知分层** | 需要确认的结果 → 86% 屏宽居中弹窗；瞬时状态与可撤销操作 → 底部玻璃胶囊（支持操作按钮，如「撤销」） |
| **界面** | iOS 26/27 Liquid Glass 设计语言；固定中性配色（浅色清透白 / 深色石墨黑）+ 固定强调色（iOS 绿）；玻璃透明度 / 磨砂 / 折射 / 描边 / 亮边 / 压暗 + 光学三项（光源角度 / 亮度 / 色散）全参数可调 |
| **开屏** | 与主界面同一壁纸舞台的液态玻璃开屏：玻璃徽记环光扫掠 + 一次性入场动画；「就绪即走」——引擎就绪提前进入，1.5s 保底 3s 兜底，可随时跳过 |

### 云与数据

| | |
|---|---|
| **网盘同步** | WebDAV 直连任意私有云盘（123网盘 / 城通 / CloudDrive2 / 坚果云 / NAS…）；每网盘独立账号；双向同步（本地文件永不覆盖）+ 断点续传；逐文件对账详情（暂停 / 优先 / 冲突改名保留）；与自动同步撞车时如实提示「已跳过」，不报假成功 |
| **数据备份** | 收件箱 / 回收站数据本地 JSON 备份与导入导出（SHA-256 校验）；回收站 30 天保留期自动清理 |
| **应用内更新** | 检查更新 → 国内镜像 / GitHub 双下载源；安装包完整性自校验（版本 + 签名）；跳去开「未知来源安装」权限返回后**自动继续安装**，同版本包永不重复下载 |

---

## 安装

1. 从 [Releases](../../releases/latest) 下载 `Edqiu-vX.X.X.apk`（约 74 MB）；
2. 允许「安装未知来源应用」（仅首次）；
3. 覆盖安装，数据全部保留。

已装旧版可直接走应用内「检查更新」：弹窗内查看更新内容并选择下载源。

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
| **app/** | 应用全部源码与资源：Kotlin + Jetpack Compose，`app/src/main/java/com/ed/edqiu/` 下按 `ui/`（页面与组件）、`data/`（数据库与仓库）、`data/xapi/`（X GraphQL 检测客户端）、`downloader/`（三级下载引擎）、`service/`（订阅轮询 / WebDAV 同步 / 媒体扫描）、`navigation/`（双层导航）等分包；`app/src/debug/` 为实验性构建源集（当前：液态玻璃渲染实验页，不影响 release） |
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
壁纸舞台读取的是系统壁纸（公开 API，无需任何存储权限），仅在设备本地虚化渲染，不上传。

## 已知限制

- **16KB 内存页机型**（部分 Android 15+ 新机）：上游 ffmpeg 依赖未适配对齐，yt-dlp 合流 / HLS 升级暂不可用；FXTwitter 直连链路不受影响（等待上游修复）；
- **X 订阅检测**：游客通道对个别账号可能返回降级数据（App 会标注并建议配置 Cookie）；国内网络需在应用内配置代理；
- 仅通过 GitHub Releases 侧载分发（Google Play 的审核政策与自更新通道、全文件权限冲突），不上架 Play；
- 仅支持 arm64-v8a 设备。

## 文档

| 文档 | 说明 |
|---|---|
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | **全部版本历史**（v1.3.0 起） |
| [docs/RELEASE_NOTES-1.9.9.md](docs/RELEASE_NOTES-1.9.9.md) | 当前版本完整说明 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构：技术栈、模块划分、主要链路 |
| [docs/release-automation.md](docs/release-automation.md) | 发版流程 |
| [docs/webdav-cloud-backup-guide/](docs/webdav-cloud-backup-guide/webdav-cloud-backup-guide.html) | WebDAV 备份图文指南 |
| [docs/design/](docs/design/) | 设计资产：图标源文件、液态玻璃原型、设计验收清单（AUDIT-CHECKLIST.md） |
| [docs/archive/](docs/archive/) | 历史文档归档 |

## 致谢

- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)（yt-dlp / ffmpeg Android 封装）
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（液态玻璃 Backdrop：真折射 / 磨砂 / 色散）
- FXTwitter API、yt-dlp 项目

## License

[Apache License 2.0](LICENSE)
