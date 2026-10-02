# XInvox UI 液态玻璃重构方案

> 日期：2026-08-17
> 范围：收件箱、主题设置、我的界面
> 技术栈：Android Kotlin / Jetpack Compose / Material 3
> 设计参考：iOS 26 Liquid Glass、Material You / Monet
> 约束：功能零损失、仅调整表现层、下载/捕获/持久化逻辑不动

---

## 1. 执行摘要

基于当前 `adb` 抓取与源码分析，提出三项针对性改进：

1. **收件箱**：保持现有信息架构与排版骨架，将底部 Tab 栏背景升级为**实时模糊**，列表卡片升级为**双层液态玻璃**（L1 + 折射高光）。
2. **主题设置**：完整保留现有的 **Monet 取色、种子色预设、色彩风格/标准、模糊强度、悬浮底栏、液态玻璃开关** 等功能；将液态玻璃效果通过 `CompositionLocal` 与 `GlassSurface` 真正覆盖到**所有页面层级**。
3. **我的界面**：在不删除任何功能入口的前提下，对卡片进行**重新编排与视觉降重**，减少冗余分组、统一入口节奏。

本方案同时吸纳 `frontend-skill` 的克制构图原则（减少无意义卡片、以排版与间距建立层级）与 `react-native-skills` 的移动性能原则（列表虚拟化/稳定回调/GPU 属性动画），映射到 Compose 落地。

---

## 2. adb 抓取结果

### 2.1 设备与包信息

| 项 | 值 |
|---|---|
| 设备 | OnePlus 15（1272×2772，531 dpi，挖孔屏） |
| 包名 | `com.ed.twitterdownload` |
| 当前 Activity | `com.ed.edqiu.MainActivity` |
| 当前可见 Tab | 我的（Settings） |
| 底部 Tab 数量 | 3：收件箱 / 媒体库 / 我的 |

### 2.2 当前"我的"页面布局（adb 坐标）

```
顶部品牌卡        [113,240][1159,413]  — 含 E 徽章、EDQIU LOAD、v1.3.0
内容分组          [60,560][1212,1053]  — 下载中心 / 回收站
下载器分组        [60,1174][1212,2093] — 下载保存 / 网络与认证 / WebDAV 同步 / 更新与工具
收件箱分组        [60,2214][1212,2494] — 外观 / 存储与备份（部分被截断）
网盘备份分组      [60,2501][1212,2707] — 网盘备份（部分被截断）
底部 Tab 栏       [67,2506][1205,2666] — 悬浮胶囊，当前选中"我的"
```

### 2.3 关键观察

- **底部 Tab 栏已使用 `LiquidTabBar`**，但外层 `GlassSurface` 在部分滚动位置下背景模糊不明显，需要增强实时模糊采样。
- **"我的"页面分组过多**：5 个分组导致一屏无法完整展示，最后两个入口被截断。
- **收件箱当前为空态**：代码中 `ListScreen` 已包含 `HeaderPanel` + `InboxToolPanel` + `LinkCard`，但需确认真实列表场景下玻璃质感是否足够。
- **主题设置功能已完整**：`SettingsScreen` 中已包含 Monet 取色、动态颜色、色彩风格/标准、模糊强度、液态玻璃开关等。

---

## 3. 当前代码状态

### 3.1 已具备的良好基础

| 文件 | 现状 |
|---|---|
| `ui/components/GlassSurface.kt` | 已实现 L1/L2/L3 三层玻璃 + 折射高光 + 边缘光 + 暗色适配 |
| `ui/components/LiquidTabBar.kt` | 已实现液态胶囊 Tab 栏 + Spring 弹性动画 |
| `ui/theme/MonetColor.kt` | 已实现 Monet 动态取色 + 6 组种子色预设 + TonalStyle + MonetSpec |
| `ui/theme/Theme.kt` | 已注入 `ThemeEffects.BlurStrength` / `LiquidGlassEnabled` |
| `ui/list/ListScreen.kt` | 已用 `GlassSurface` 重构 HeaderPanel / InboxToolPanel / LinkCard |
| `ui/settings/SettingsScreen.kt` | 已完整实现主题设置（莫奈取色、模糊、液态玻璃开关） |
| `ui/navigation/AppNav.kt` | 已定义"我的"页面 `UnifiedSettingsScreen` 与 `MineNav` |

### 3.2 需要补齐的 gap

1. **收件箱底部背景实时模糊**：`Scaffold` 容器色透明，`GlassBackground` 已模糊，但 Tab 栏下方的内容在滚动时没有明确的"玻璃下内容透出"效果。
2. **我的页面分组冗余**：5 个分组导致信息密度过高，需要合并/重排。
3. **全局液态玻璃一致性**：部分设置项的 `Card` / `Surface` 仍使用纯色白底（如 `SectionCard` 的 `containerColor = Color.White`），未接入 `GlassSurface`。

---

## 4. 设计改进方案

### 4.1 设计语言：Liquid Glass × Monet

延续 `Glass You` 核心命题：

- **Monet 提供色彩主权**：主色从壁纸/种子色动态提取，中性面跟随变化。
- **Liquid Glass 提供质感主权**：实时模糊、折射高光、边缘光、悬浮层级。

### 4.2 收件箱：保持布局 + 玻璃升级

**保持不变的排版骨架**

```
┌─────────────────────────────────────┐
│  HeaderPanel（标题 + 粘贴/批量按钮）   │  L2 玻璃悬浮卡
├─────────────────────────────────────┤
│  InboxToolPanel                     │  L2 玻璃悬浮卡
│  （搜索 + 排序 + 4 个筛选 chip + 统计） │
├─────────────────────────────────────┤
│                                     │
│  LinkCard 列表                        │  L1 玻璃卡片
│  （缩略图 + 作者 + 状态 pill + 文案）   │
│                                     │
├─────────────────────────────────────┤
│  LiquidTabBar（收件箱/媒体库/我的）    │  L2 玻璃悬浮底栏
└─────────────────────────────────────┘
```

**需要调整的实现细节**

| 区域 | 当前状态 | 改进方向 |
|---|---|---|
| 底部 Tab 栏背景 | `GlassSurface(L2)`，但模糊半径偏小 | 将 `LiquidTabBar` 外层 `GlassSurface` 的 `blurRadius` 与 `GlassBackground` 联动，确保 Tab 栏下方内容实时透出 |
| 列表卡片 | 已用 `GlassSurface(L1)`，elevated=false | 增加顶部镜面高光强度（参考 iOS 26 更亮的 0.5 白色上沿），并保留底部微反光 |
| 空态卡 | `GlassSurface(L1)` | 保持 L1，但将"粘贴链接"按钮改为液态玻璃胶囊按钮，与 Tab 栏语言一致 |
| 返回顶部按钮 | 纯黑 Surface | 改为 L2 玻璃小圆钮，避免与玻璃主题割裂 |
| 批量选择工具条 | `GlassSurface(L3)` + primary 渐变 | 保持 L3，但增加 12dp 下阴影，确保在 Tab 栏上方浮起 |

**iOS 26 Liquid Glass 参考要点**

- 玻璃材质会**实时折射和反射周围内容**（引用：Apple Support [关于 iOS 26 更新](https://support.apple.com/zh-cn/123075)）。
- 减少透明度时，玻璃更"霜化"；增加对比度时，元素变为黑/白并加对比描边（引用：Apple WWDC [Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/)）。
- 因此 XInvox 的玻璃需要：
  1. 背景必须有色（莫奈渐变），不能是白底；
  2. 玻璃上的文字/按钮使用全不透明 `onSurface`，保证可读性；
  3. 高对比度模式下提供纯黑/纯白降级路径。

### 4.3 主题设置：保持功能 + 全局生效

**必须完整保留的功能**

| 功能 | 当前实现位置 | 说明 |
|---|---|---|
| Monet 动态取色 | `ThemeSettingsSection` → `DynamicSwitch` | API 31+ 跟随系统壁纸 |
| 种子色预设 | `Monet.seedPresets` + `SeedColorSelector` | 6 组预设 |
| 强调色自定义 | `AccentColorPickerDialog` | HSV 三滑块 + 12 预设 |
| 色彩风格 | `TonalStyle` | TonalSpot / Vibrant / Expressive / FruitSalad / Fidelity / Content |
| 色彩标准 | `MonetSpec` | SPEC_2021 / CAM16 |
| 模糊开关/强度 | `blurEnabledFlow` / `blurIntensityFlow` | 0-100% |
| 悬浮底栏 | `floatingTabBarFlow` | 控制 `LiquidTabBar` 显隐 |
| 液态玻璃 | `liquidGlassEnabledFlow` | 全局 `GlassSurface` 开关 |
| 预测性返回 | `predictiveBackFlow` | Android 14+ |
| 界面缩放 | `displayScaleFlow` | 70%-100% |

**全局液态玻璃生效路径**

```
EdqiuApp
  └── CompositionLocalProvider
        ├── ThemeEffects.BlurStrength
        ├── ThemeEffects.LiquidGlassEnabled
        └── GlassBackground(blurRadius = blurIntensity * 40.dp)
              └── Scaffold(containerColor = Transparent)
                    └── NavHost
                          ├── ListScreen      → HeaderPanel/LinkCard 用 GlassSurface
                          ├── MediaLibraryScreen → 需同步接入 GlassSurface
                          ├── UnifiedSettingsScreen → MineGroupCard 用 GlassSurface
                          └── SettingsScreen  → SectionCard/GroupCard 用 GlassSurface
```

**关键落地动作**

1. 将 `SettingsScreen` 中的 `SectionCard`（当前 `containerColor = Color.White`）替换为 `GlassSurface(L1)`。
2. 将 `ThemeSettingsSection` 中的 `ThemePreviewCard`、`GroupCard` 替换为玻璃化容器。
3. 确保 `DynamicSwitch` 在玻璃背景上的轨道/滑块对比度。

### 4.4 我的界面：重新编排

**当前问题**

5 个分组、12 个入口，一屏展示不全，底部入口被截断。

**重新编排方案（3 个分组）**

```
┌─────────────────────────────────────┐
│  品牌卡（EDQIU LOAD + v1.3.0）         │  保持 L2 玻璃卡
├─────────────────────────────────────┤
│  内容管理                            │  L1 玻璃分组卡
│  ├─ 下载中心                         │
│  ├─ 回收站                           │
│  └─ 网盘备份                         │  从独立分组并入
├─────────────────────────────────────┤
│  下载器设置                          │  L1 玻璃分组卡
│  ├─ 下载保存                         │
│  ├─ 网络与认证                       │
│  ├─ WebDAV 同步                      │
│  └─ 更新与工具                       │
├─────────────────────────────────────┤
│  收件箱设置                          │  L1 玻璃分组卡
│  ├─ 外观（莫奈取色/液态玻璃）          │
│  ├─ 存储与备份                        │
│  ├─ 捕获与同步                        │
│  └─ 关于与诊断                        │
├─────────────────────────────────────┤
│  LiquidTabBar                        │  L2 玻璃悬浮底栏
└─────────────────────────────────────┘
```

**编排理由**

| 变更 | 原因 |
|---|---|
| 网盘备份并入"内容管理" | "网盘备份"目前只有 1 个入口，单独成组浪费纵向空间；它与"下载中心/回收站"同属"内容去哪里/从哪里来"语义 |
| 保持 3 个主要分组 | 符合 `frontend-skill` 的"减少无意义卡片"原则，3 个语义块（内容/下载器/收件箱）对应用户心智模型 |
| 每个分组 3-4 个入口 | 保证一屏完整可见，避免截断 |

**视觉降重**

- 分组标题从品牌色块改为**玻璃上的小型标签**（`labelMedium`，primary 色）。
- 入口行图标容器从 `primaryContainer` 实色改为**L2 玻璃圆角**，减少色彩噪音。
- 分组之间间距 14dp，分组内部行间距 2dp（紧凑但不拥挤）。
- 整页底部留出 `96dp` 避让 `LiquidTabBar`。

---

## 5. 技术实现建议

### 5.1 结合 frontend-skill 的 Compose 映射

| frontend-skill 原则 | Compose 落地 |
|---|---|
| 减少无意义卡片 | 能用 layout/spacing 表达层级的地方，不用 `Card`；必须用容器时才用 `GlassSurface` |
| 强排版与间距 | 统一 12dp/16dp/20dp 网格；标题、副标题、入口行严格左对齐 |
| 有限颜色 | 只使用 `MaterialTheme.colorScheme` 的 semantic color + 固定的状态色 |
| 克制动效 | 只保留胶囊切换 Spring、列表项 enter/exit fade、滚动返回顶部按钮 scale |

### 5.2 结合 react-native-skills 的 Compose 映射

| react-native-skills 原则 | Compose 落地 |
|---|---|
| 列表虚拟化 | 使用 `LazyColumn`，已为 `LinkCard` 提供 `key = { it.tweetId }` |
| 稳定回调 | `onClick`/`onLongClick` 通过 ViewModel 方法引用传递，避免 inline lambda 导致 item 重组 |
| GPU 属性动画 | 胶囊位移动画使用 `animateFloatAsState` + `offset { }`（仅 transform）；避免在滚动时触发 `blur` 重新计算 |
| Pressable | `LinkCard` 使用 `combinedClickable`；入口行使用 `clickable` |
| 安全区 | `Scaffold` 已设 `contentWindowInsets = WindowInsets(0)`，各页面自行处理 `statusBarsPadding` / bottom clearance |

### 5.3 实时模糊性能策略

沿用现有 `GlassSurface` 的 Tier 分级思想：

| 场景 | 策略 |
|---|---|
| 列表静止 | `GlassBackground` 使用 `blurRadius = blurIntensity * 40.dp` |
| 列表滚动 | 将 `GlassBackground` 的 blur 降级为预模糊缓存或关闭，避免每帧重算；`GlassSurface` 内部高光/描边不触发重组 |
| Tab 栏 | 使用 `Modifier.graphicsLayer { renderEffect = BlurEffect(...) }` 或 `Modifier.blur()`，跟随主题设置 |
| 低端机 | `liquidGlassEnabled = false` 时，`GlassSurface` 回退为扁平半透明 + 渐变高光 |

### 5.4 待修改文件清单

```
app/src/main/java/com/ed/edqiu/ui/
├─ list/ListScreen.kt
│  └─ 调整：HeaderPanel/InboxToolPanel 玻璃高光强度；空态按钮玻璃化；返回顶部按钮玻璃化
├─ components/GlassSurface.kt
│  └─ 调整：增强 iOS 26 风格镜面高光参数；确保 Tab 栏下方内容透出
├─ components/LiquidTabBar.kt
│  └─ 调整：外层 GlassSurface 实时模糊与背景联动
├─ settings/SettingsScreen.kt
│  └─ 调整：SectionCard、ThemePreviewCard、GroupCard 玻璃化；保持所有 Monet 功能不变
├─ navigation/AppNav.kt
│  └─ 调整：UnifiedSettingsScreen 重新编排为 3 个 MineGroupCard
└─ theme/Theme.kt
   └─ 确认：ThemeEffects 注入已覆盖所有页面（当前已覆盖）
```

---

## 6. 验收标准

1. **adb 复测**：收件箱、我的、主题设置三页截图中，底部 Tab 栏均有明显实时模糊，下方内容可见但柔化。
2. **Monet 功能回归**：切换种子色/动态颜色/色彩风格/色彩标准后，全局主题即时更新，设置页所有开关与选择器正常工作。
3. **我的页面一屏可见**：3 个分组、11 个入口（含版本号）在 2400px 高度设备上一屏完整显示，无截断。
4. **列表性能**：收件箱加载 100 条记录时，滚动帧率 ≥ 55fps（开启 blur 时）或 ≥ 58fps（关闭 blur 时）。
5. **无障碍**：TalkBack 可朗读 Tab 标签、入口行标题/副标题、开关状态。
6. **编译通过**：`./gradlew assembleDebug` 成功。

---

## 7. 附录：adb 命令参考

```bash
# 查看当前 Activity
adb shell dumpsys activity top | findstr "packageName\|mResumedActivity"

# 抓取 UI 层次结构
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml ./audit-ui-auto/window_dump.xml

# 截图
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png ./audit-ui-auto/screen.png
```

---

**文档状态**：方案待评审
**下一步**：确认分组编排后，进入 `ListScreen.kt` / `AppNav.kt` / `SettingsScreen.kt` 落地修改
