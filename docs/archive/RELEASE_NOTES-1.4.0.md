# EsQp（Edqiu）v1.4.0

> 正式命名 **Edqiu**｜包名 `com.ed.Edqiu`｜versionCode 5｜2026-08-19

## 升级说明

本次更新**包名从 `com.ed.twitterdownload` 迁移为 `com.ed.Edqiu`**，需卸载旧版安装新版。应用会自动扫描旧版数据目录（`com.ed.twitterdownload`、`com.ed.edqiu`），无需手动迁移。

## 🆕 新增功能

### 全新 Liquid Glass UI
- **玻璃态设计语言**：`GlassSurface`（L1/L2/L3 阶）覆盖卡片、TabBar、按钮、播放器控件
- **底部导航栏胶囊化**：`LiquidTabBar` 浮动胶囊 + 活跃指示器动画
- **主题增强**：`ThemeEffects`（玻璃背景）、`MonetColor`（动态取色）、`Shape` 圆角令牌体系
- **设置页重构**：`SettingsScreen` 玻璃卡片 + 全新导航结构
- **播放器升级**：`GlassPlayerControls` 玻璃态控制层 + 音量/进度条重新设计

### 网盘备份增强
- **123 网盘直连（OpenAPI）**：`Pan123Api` + `Pan123OpenTarget`，支持官方 OAuth 授权
- **备份账本**：`BackupLedgerDao` / `BackupLedgerEntity` / `BackupLedgerRepository`，记录每次备份的元数据
- **自动备份调度器**：`WebDavAutoBackupScheduler`（WorkManager 周期任务，无需前台服务）

### 新应用图标
- 全新 Edqiu 品牌图标：`ic_launcher_round`（全密度 PNG + Adaptive Icon）

## 🐛 Bug 修复

| Bug | 修复 |
|---|---|
| 启动闪退：`InvalidForegroundServiceTypeException`（BackupWorker） | `ForegroundInfo` 三参构造显式指定 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` |
| 包名迁移后旧 Provider authority 失效 | `DownloadMediaProvider` 保留 `com.ed.twitterdownload.media` / `com.ed.edqiu.media` 兼容 |
| 旧版 Intent action 不兼容 | `DownloadIntentContract` 保留 `com.ed.twitterdownload.extra.TWEET_URL` 兼容读取 |
| Snackbar 显示异常 | `EdqiuSnackbarHost` 自定义 Snackbar 容器 |

## ⚙️ 优化

- **包名规范化**：`com.ed.twitterdownload` → `com.ed.Edqiu`，保留三级旧包名兼容
- **Provider 权限独立**：`com.ed.Edqiu.permission.READ_DOWNLOAD_MEDIA` 签名级权限，旧版兼容保留
- **发布签名**：首次启用 release 签名构建（keystore 本地私有，不提交）
- **目录扫描**：`DirectoryScanner` 同时扫描新包和旧包 Android/data 目录
- **gitignore 加固**：排除 keystore、调试目录、临时数据库文件

## ⏭️ 已知事项

- 百度/阿里 client_id 未配置（gradle.properties 空占位）→ 配置后可在网盘备份启用
- 旧版 `com.ed.twitterdownload` 卸载后，其 Android/data 目录中的下载文件保留，Edqiu 可正常读取