# Edqiu 变更交接文档（2026-09-28 会话全量）

> 交接范围：本会话在 `D:\Edqiu\repo` 上完成的全部改动。当前版本 **v1.6.6 (versionCode 43)、targetSdk 37、compileSdk 37**。
> 构建命令：`JAVA_HOME="D:\AndroidDev\jdk\jdk21" ./gradlew.bat :app:assembleDebug`（系统 JAVA_HOME 已失效，必须显式指定）。
> 测试设备：PGIM10（ColorOS，Android 14，adb 序列号 cf6220df），改动均已实机安装验证。

---

## 一、架构级重构（接手前必读）

### 1. 主题系统：「单一色源 + 固定派生」（`ui/theme/Theme.kt` 全量重写）
旧方案（强调色种子 × 6 种 TonalStyle × 2 种 MonetSpec 自由派生 + 手绘莫奈渐变背景）经多轮修补无果，已整体废弃。新方案：
- **壁纸取色开**：API 31+ 直接用系统 Monet（`dynamicLight/DarkColorScheme`，不做任何 primary 覆盖）；API 28-30 用 `WallpaperManager.getWallpaperColors` 主色作种子；API <28 回退强调色。
- **壁纸取色关**：强调色作种子。
- **所有路径共用固定派生参数**（TonalSpot + Spec 2021，调用 `colorSchemeFromSeed(seed, dark)` 两参重载）。
- `EdqiuTheme` 签名已移除 `tonalStyle`/`monetSpec` 参数；设置页「色彩风格」「色彩标准」两行已删除（`SettingsRepository` 的对应 flow/key 保留未用，向后兼容）。
- `ui/theme/MonetColor.kt` 引擎保留，仅供上述固定参数调用。

### 2. 背景：`ui/components/GlassSurface.kt` 的 `GlassBackground`（v4 现行版）
- 不再手绘莫奈渐变。结构 = 中性面三段基底（`background → surfaceContainer → surfaceContainerHigh`）+ **primary/tertiary 浓郁色场**（linearGradient 0.58/0.62 与 0.52/0.55 alpha，取色开时即壁纸深色系）+ 左下 primary 补光。
- 演进教训（勿回退）：v2 中性面+容器色光晕 → 用户反馈"背景与白卡片融层"；v3 容器色洗 → 仍与 primaryContainer tint 卡片同色系，"看得眼花"；**v4 用调色板中的饱和深色（primary/tertiary）铺色场，靠明度差保证图底关系**——卡片是浅色磨砂面板，背景是深色色场，结构上不可能融合。

### 3. 玻璃参数体系：三滑块独立（`ui/theme/ThemeEffects.kt` 重写）
- `ThemeEffects.GlassTransparency`（0=实 1=透）/ `GlassFrostStrength`（磨砂）/ `GlassRefractionStrength`（折射，仅液态玻璃模式）。
- 旧 `BlurStrength` 已删；旧「模糊开关」（`blurEnabled`）UI 已删（repo key 保留未用），滑块常开。
- 消费点：
  - **真折射路径** `LiquidGlass.kt` → `RealGlassOverlay`：表面色 alpha 由透明度驱动（浅色 0.58→0.16，深色 0.62→0.26，Dark 风格 0.80→0.52）；blur = (4+16·frost)dp；lens 位移 = 原值 ×(0.2+1.4·refraction)。效果链固定顺序 vibrancy → blur → lens（库文档规定）。
  - **卡片路径** `GlassSurface.kt`：底色 alpha ×(1.30−0.75·transparency)；磨砂白雾 (0.08+0.26·frost)/(0.03+0.10·frost)；液态玻璃关闭（普通态）：`surfaceContainerHigh` alpha 0.96−0.41·transparency + 柔光梯度 (0.04+0.12·frost)。
- 背景柔化 blurRadius = frost²×64dp（AppNav）。
- 库能力边界（已用 javap 验证）：`io.github.kyant0:backdrop:2.0.1` 仅有 vibrancy/blur/lens（+opacity/colorControls），**无 glassNormal**。

### 4. 返回过渡：「手势方向滑动」全局统一（预测性返回已整体删除）
- `PredictiveBackHandler`、backProgress、graphicsLayer 逐帧形变已从两个导航器**全部删除**（用户判定卡顿且不属过渡动画；合成兜底动画与 NavHost 转场叠加是卡顿根源）。
- 现行方案：`BackHandler(enabled = predictiveBack设置 && 可返回)` → 单一动画链路 = NavHost 转场：
  - 返回（popExit）：当前页**沿手势方向（右）滑出全屏 + 渐透明**（slide 300ms + fade 280ms）；上一层自左侧 1/4 屏视差滑入 + 淡入（340/320ms）。
  - 进入（enter）：新页自右侧 1/3 滑入 + 淡入；旧页向左 1/5 视差退让。
  - 内层 Tab 切换保留方向感知滑动（it/4）；内层非页签二级页已改为同款方向滑动。
- **播放器**（AppNavigation 的 AnimatedVisibility）：自右滑入/滑出 + 短淡出（SurfaceView 仅可平移，scale/长 alpha 会撕裂；淡出保持 160ms）。
- **开屏→主界面**（MainActivity setContent）：硬切改 Crossfade(320ms)。
- 设置行更名：「预测性返回手势」→「返回手势」（关闭 = 返回由系统接管）。
- 注意：Android 14 设备 `predictive_back_animate` 开发者选项**不再需要**（无进度依赖）。

### 5. 弹窗体系：`ui/components/SmoothDialog.kt`（新文件）
- `AnimatedDialog`：Dialog 包装，入场 alpha+scale(0.92→1) 220ms、退场缩小淡出 160ms 后才回调 onDismissRequest。
- `SmoothAlertDialog`：M3 AlertDialog 同参 API（title/text/icon/confirmButton/dismissButton/containerColor/shape），内部走 AnimatedDialog。**注意 material3 没有 ProvideContentColor**，用 `LocalContentColor` + `CompositionLocalProvider`。
- `FeedbackDialog`：操作结果统一弹窗（按 FeedbackKind 定标题：操作成功/操作失败/提示，单按钮「知道了」）——**用户明确偏好弹窗反馈，不要用 Snackbar/内嵌通知条**。
- 全应用 14 处 AlertDialog + BaiduAuthDialog 的裸 Dialog + 9 处 InlineFeedbackBar 内嵌通知条均已替换；`InlineFeedbackBar` 组件保留但已无调用点。

---

## 二、功能变更明细（按文件）

### 新增文件
| 文件 | 内容 |
|---|---|
| `ui/components/SmoothDialog.kt` | AnimatedDialog / SmoothAlertDialog / FeedbackDialog |
| `docs/`（本文件） | 交接文档 |

### 删除文件/功能
| 目标 | 说明 |
|---|---|
| `clipboard/ClipboardCapture.kt`、`capture/EdqiuAccessibilityService.kt`、`capture/CapturePackagePolicy.kt`、`res/xml/accessibility_service_config.xml` | 剪贴板捕获整体移除（自动读剪贴板 + 无障碍复制捕获）。**保留**：手动粘贴捕获弹窗、HomeScreen 读取剪贴板按钮、CaptureIntentActivity 分享/PROCESS_TEXT 捕获（不受影响，已向用户确认） |
| manifest 无障碍服务声明、strings.xml accessibility 两条 | 同上清理 |
| `system/DeviceCapabilityReader.kt` | 仅剩输入法诊断被「关于与诊断」使用，随之删除 |
| 「关于与诊断」区块（ui/settings） | 版本信息与更新工具重复、输入法诊断失效；导入语义提示移至「备份与恢复」导入按钮旁 |
| 下载器设置「下载保存」卡片（screens/SettingsScreen） | 迁入「存储路径」页 |
| 底部 `AppInfoCard`（MineScreen） | 判定冗余（版本重复/标语过时/零操作）；版本+构建号并入顶部身份块 |

### 修改文件（按职责分组）
**导航/骨架**
- `ui/navigation/AppNav.kt`：density 双缩放（界面缩放 0.5-1.0 乘入 density）；玻璃三参数注入；BackHandler + 方向滑动过渡；删除预测返回。
- `navigation/AppNavigation.kt`：悬浮底栏关闭时回退标准 M3 NavigationBar（修"卡死设置页"）；`selectTab` 统一入口；迷你播放条三态避让；返回过渡同上；播放器过渡。

**设置系统**
- `data/preferences/SettingsRepository.kt`：删 autoCapture；新增 `DEFAULT_BACKUP_DIR="/storage/emulated/0/edqiu"`（backupDirUriFlow 默认值）、`glass_transparency`/`refraction_intensity`；displayScale coerce 0.5-1.0。
- `ui/settings/SettingsScreen.kt`（改动最大）：
  - 新增 `XSection.STORAGE`「存储路径」页：① 下载存储（DownloadPathPreferences，自下载器设置迁入）② 监控目录（含 detectMonitorDirectory 检测按钮：只读统计视频/图片数，SAF 目录不落 cache）③ 数据备份（含所有文件访问权限门控 checkStorageAccess + 跳系统设置）。
  - `GroupCard` 支持 title+description；主题页四组（颜色/玻璃与底栏/显示/手势与触感）；「备份与恢复」三分组+空状态提示；「下载行为」瘦身。
  - 玻璃质感三滑块；壁纸取色/强调色行（色彩风格/标准已删）。
  - `uriToDisplayPath`/`normalizeMonitorPath`/`detectMonitorDirectory`/`scanMediaTree`/`checkStorageAccess` 文件级助手。
- `ui/screens/SettingsScreen.kt`（下载器设置）：PATH 卡删除；FeedbackDialog；进入「更新与工具」自动检查更新。

**我的页**
- `ui/screens/MineScreen.kt`：ProfileCard 三轮迭代（现：56dp 渐变方头像+白描边、状态点行"本地账户 · 数据仅存于本机"、v{ver}+Build{n}）；设置卡四组严格分类（① 内容管理 3 ② 下载器 3：网络认证/预下载/下载行为 ③ 外观 1：外观设置 ④ 存储与维护 5：存储路径/备份与恢复/网盘备份中心/WebDAV 同步/更新与工具）；SubMenu 枚举 CONTENT/DOWNLOADER/APPEARANCE/STORAGE；**网盘备份中心原本是无入口孤儿路由，已挂入④**。

**备份/同步**
- `ui/backup/MediaBackupViewModel.kt`：`MediaUploadState.QUEUED` 拆分（PENDING≠上传中）；统计口径随 Tab（scopeItems，飞出不减账面）；`_justCompleted`/`_dismissed` 簿记（rebuild 时 diff 上一快照，页面首建不触发）；rebuildMutex 防并发；`dismissCompleted()`。
- `ui/backup/MediaBackupScreen.kt`：完成条目 380ms 左滑飞出（graphicsLayer 平移+淡出）→ onDismissed 后 filteredItems 排除 + `Modifier.animateItem()` 收拢；FeedbackDialog。
- 备份目录默认写入 `/storage/emulated/0/edqiu`（首次备份自动 mkdirs）；manifest 增 `MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE maxSdk=29`、`requestLegacyExternalStorage`。

**更新检查**
- `service/AppUpdateService.kt`：检查源改为 `https://api.github.com/repos/Sdhop-let/Edqiu-Downloader/releases/latest`；语义化版本逐段比较；APK 取第一个 .apk 资产直链；404（仓库无 Release）视为无更新；下载/安装/FileProvider 链路未变。
- 进入「更新与工具」自动检查 + 既有 SmoothAlertDialog 弹窗提醒。

**其他**
- `ui/list/ListViewModel.kt`+`ListScreen.kt`：剪贴板自动捕获链路删除（保留手动 captureText）；`CaptureFeedback.Empty` 删除。
- `app/build.gradle.kts`：targetSdk 35→37、versionCode 43、versionName 1.6.6（附四项适配核查注释）。
- `res/values/themes.xml`：仅注释更新。

---

## 三、用户偏好与协作约定（接手者必读）

1. **中文交流**；消息是简短的 bug/优化清单，每条都要落实。
2. **操作结果必须用弹窗**（FeedbackDialog/SmoothAlertDialog），不用 Snackbar/内嵌通知条。
3. **过渡动画 = 手势方向滑动 + 淡入淡出**，拒绝整屏缩放；"丝滑 = 过渡期间两屏同屏衔接"。
4. 界面命名要与页面内容**同名联动**，分组要严格归类+职责说明（GroupCard title/description）。
5. 对不确定的方案，用户接受 AskUserQuestion 给组合选项。
6. 每轮改动后：`gradlew assembleDebug` → `adb install -r` → 冷启动 + `logcat AndroidRuntime:E` 验证。

## 四、已知坑与注意事项

1. **JAVA_HOME**：系统值失效，构建必须 `JAVA_HOME="D:\AndroidDev\jdk\jdk21"`；`build_push.sh` 内写死的 `C:/temp/jdk21` 需同样修后再用。
2. **ColorOS 离屏白框**：玻璃组件**永不**加 `Modifier.blur`（v1.4.8 事故，磨砂用渐变模拟）；真折射只走 backdrop 库（Android 13+）。
3. **backdrop 库 2.0.1** 无 glassNormal；效果顺序 vibrancy→blur→lens 固定。
4. **Android 14+ 预测性返回（2026-09-29 跟手版）**：manifest `enableOnBackInvokedCallback="true"`（必须为 true）+ **App 层禁止放普通 BackHandler**（AppNav/AppNavigation 原两处已删——普通回调抢 dispatcher 栈顶会废掉 NavHost 内置 seek）。**ColorOS 专属**：`adb shell settings put secure oplus_third_part_apps_predictive_back 0` 必须执行（默认 1 时系统给第三方 app 强制叠加窗口缩放预览动画，盖住 App 跟手转场，即"缩放卡片返回"）；该开关持久化但恢复出厂/OTA 可能重置。转场验证须抽原尺寸帧 + `input swipe` 模拟手势（BACK 键走不到手势路径、缩略图看不出缩放）。
5. **更新检查直连 api.github.com**，不走 App 内下载代理；设备无法直连 GitHub 时检查会失败（用户已知，可选后续：接代理）。
6. **16KB 内存页**：全部 .so 已验证 0x4000 对齐（`.zip.so` 是数据包非 ELF）；更换 ffmpeg/python 原生库时需复验。
7. Kotlin 字符串模板坑：`"$label可读"` 会把中文并入标识符，必须 `${label}可读`。
8. python 批量改代码时 `insert(anchor)` 的 addition 必须以 anchor 结尾，否则锚点重复（本次踩过三次）。
9. 版本比较按 GitHub tag 语义化解析；发布新版本 = 在 Sdhop-let/Edqiu-Downloader 发 Release 并附 .apk 资产即可，无需改 App。

## 五、遗留/可选后续

- 下载中心与回收站的合并精简方案已评估未实施（结论：回收站必须保留；下载中心保留，可后续收进收件箱"任务"入口）。
- 更新检查走 App 代理（用户未确认需求）。
- 旧仓库静态 JSON 检查源已废弃，可通知下游。
