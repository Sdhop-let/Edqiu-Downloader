# EsQp（XInvox）v1.3.0 变更记录

> 新代号 **EsQp**（原 XInvox）｜包名 `com.ed.twitterdownload`｜2026-08-02

---

## 🆕 新增功能

### 网盘直连备份（T01-T05 完整交付）
- **5 网盘接入**：百度网盘（官方设备码扫码）、123网盘（WebDAV 应用密码）、阿里云盘（网页登录截 refresh_token）、CloudDrive2（本机检测）、自定义 WebDAV（高级）
- **备份中心 UI**：「我的 → 网盘备份」入口，品牌卡 + 网盘列表 + 授权弹窗 + 备份设置（范围/自动开关）+ 状态区（进度/任务/重试）
- **架构**：BackupTarget 统一抽象 + 加密凭证存储（EncryptedSharedPreferences + Keystore）+ 分片断点续传 + WorkManager 后台任务 + 前台服务通知
- 使用前提：百度/阿里需在 `gradle.properties` 配置 client_id/client_secret；123/CD2/自定义 WebDAV 开箱即用

### 新开屏动画（仅首次安装）
- 新增 `FirstLaunchManager`：SharedPreferences 标记，仅新安装/清数据后显示
- 新增 `LaunchSplash`：0.5s 极简开屏（淡入 150ms + 停留 200ms + 淡出 150ms），品牌蓝底 + 白字 **EsQp**
- 旧 8 秒三阶段开屏（EsQpSplashFlow）废弃

### App 图标
- 全新 EsQp 品牌图标：深蓝渐变底 + 白色 "E" 印章徽章
- 适配 Adaptive Icon（background + foreground 432x432）+ 全密度 PNG

---

## 🐛 Bug 修复

| Bug | 根因 | 修复 |
|---|---|---|
| 图片卡进视频播放器 | playVideo 不分图片/视频喂 ExoPlayer | PlayerScreen isImageView 分流（Coil 查看）+ PlayerViewModel 跳过图片 + 控制层 isImageMode |
| 动态取色不生效 | GlassBackground 用 seed 做 tint 与 ColorScheme 脱钩 | 改为从 ColorScheme.primary 取色 |
| 状态栏黑块 + 顶部下移 | targetSdk 35 强制 edge-to-edge 未适配 | MainActivity enableEdgeToEdge + Scaffold contentWindowInsets=0 |
| 收件箱玻璃卡不靠状态栏 | ListScreen Scaffold 默认含状态栏 inset | Scaffold WindowInsets(0) + GlassSurface statusBarsPadding |
| 视频退出仍后台播放 | 返回不释放 ExoPlayer | BackHandler + DisposableEffect 调 stopPlayer() |
| 强制横屏 | setPlayerFullscreen 设 LANDSCAPE | 删除横屏设置，仅控制系统栏 |
| 开屏后一直蓝屏 | splashDone 状态没加 remember | `remember { mutableStateOf(false) }` |
| 二级菜单返回键与状态栏重合 | Column/LazyColumn 无 statusBarsPadding | 两处 SettingsScreen 加 statusBarsPadding + top=14dp |

---

## ⚙️ 优化

- **targetSdk 34 → 35**（适配 Android 15 edge-to-edge + 前台服务）
- **compose 色彩同源**：GlassBackground tint 统一从 ColorScheme 取，保证动态取色开关真的有效
- **ExoPlayer 生命周期**：stop 不 release（返回可复播），release 仅 ViewModel onCleared
- **布局对齐**：收件箱/媒体库 HeaderPanel 统一从状态栏底开始

---

## 📦 版本

- versionCode: 3 → **4**
- versionName: 1.2.0 → **1.3.0**
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## ⏭️ 待办 / 已知事项

- 百度/阿里 client_id 未配置（gradle.properties 空占位）→ 配置后可启用
- 移动云盘延后（官方个人开发者通道未确认）
- 旧 EsQpSplashFlow.kt 已不再引用，可删除
- App 内部分 "XInvox" 文案未全部改为 EsQp（仅开屏已改）
