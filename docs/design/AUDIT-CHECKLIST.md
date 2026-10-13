# Edqiu 设计验收清单（AUDIT-CHECKLIST）

> 用途：每轮版本发布前对照勾选，防止设计系统被局部改动悄悄破坏。
> 来源：iOS 26 Liquid Glass 设计总纲精简版 + 本项目已定案事实（v1.6.x–v1.9.0 迭代沉淀）。
> 维护：设计系统令牌变更时同步更新本文件。

## 0. 令牌速查（单一事实来源）

| 令牌 | 位置 | 要点 |
|---|---|---|
| 颜色 | `ui/theme/FixedScheme.kt` | 固定 iOS 中性双主题（浅 #FFFFFF/#F2F2F7 族、深 #000000/#1C1C1E 族），禁 Monet/壁纸取色；强调色 iOS 系统调色板 12 色，DEFAULT 0xFF007AFF |
| 状态色 | `ui/theme/Color.kt` | 浅 #C93400/#1F8A3D/#D70015，深 #FF9F0A/#30D158/#FF453A |
| 圆角 | `ui/theme/Shape.kt` | 8/12/16/20/28dp 五档 |
| 动画 | `ui/anim/Motion.kt` | Micro 140 / Control 150 / Panel 160 / PanelIn 220 / Screen 300 / Player 430·460 / OverlayExit 400；新动画先查表，禁止新增第 17 种 tween |
| 玻璃 | `GlassSurface.kt` + `LiquidGlass.kt` | GlassTier L1–L3 + edgeLight 内描边；<Android 13 渐变模拟降级；玻璃组件必须在 layerBackdrop 捕获链之外 |
| 反馈 | `SmoothDialog.kt` | 操作结果一律弹窗（FeedbackDialog/SmoothAlertDialog），禁 Snackbar/内嵌通知条；底部胶囊仅状态类瞬时反馈 |

## 1. 视觉一致性

- [ ] 全应用无默认样式的 M3 控件（TopAppBar/BottomAppBar/Card/Checkbox/Switch/Slider/TextField 均为主题化定制或设计系统组件）
- [ ] 输入场使用 `EdqiuTextField`（iOS 填充式：surfaceContainerLow 底 + 12dp 圆角 + 聚焦 primary 描边 + label 上置）
- [ ] 多选勾选使用 `CheckCircle`（iOS 勾选圆圈，Role.Checkbox 语义）而非 M3 Checkbox
- [ ] 开关使用 `DynamicSwitch`，滑杆使用 `EdqiuSlider`，分段/筛选使用 `PillChip`
- [ ] 玻璃层级可辨：L1 卡片 / L2 底栏与操作条 / L3 强调浮层，透明度与 edgeLight 系数不同
- [ ] 卡片圆角/间距/边框同族一致；阴影仅 CapsuleFeedback 两处豁免（用户定案保留）
- [ ] 硬编码颜色为零（Accent/Ink/Muted 之类旧常量不得复活；文案灰用 onSurfaceVariant）
- [ ] 深浅两主题下文本对比度可读（检查容器 alpha 叠加后的实际色）

## 2. 动画与交互

- [ ] 页面转场语义分场景：tab/进入=方向感知滑动+淡变（320/280ms）；pop=纯位移视差（400ms）；播放页=容器变形（430/460ms），不得混用
- [ ] 跟手返回三件套完好（manifest 开关 + 内层普通 BackHandler + overrideActivityTransition 清 ColorOS 缩放卡片）
- [ ] 播放器为 TextureView（`player_view_texture.xml`），禁止改回 SurfaceView
- [ ] ReduceMotion 生效：转场/按压缩放降级，反馈类微动画保留
- [ ] 按压反馈统一走 `pressableNoRipple`（0.97 spring），无涟漪残留
- [ ] 动画不阻塞操作、不积压、结束落在准确终态；下载/备份进度不被装饰动画延迟

## 3. 工程完整性

- [ ] 业务逻辑零改动：下载引擎/Room/DataStore/备份适配器/Intent 链路未被 UI 重构触碰
- [ ] 外观设置参数（透明/磨砂/折射/描边/亮边/压暗等）调节后真实界面立即生效
- [ ] 无假按钮、假页面、静态演示进度；无新增死代码文件
- [ ] 列表 Key 正确、滚动不闪烁不卡顿；玻璃渲染不在高频滚动路径重复初始化
- [ ] Android 7.0+ 可运行；玻璃在低版本平滑降级

## 4. 构建与真机

- [ ] `gradlew.bat :app:assembleDebug` 通过
- [ ] 真机冒烟：收件箱/下载中心/媒体库/播放/设置/备份六页走查 + 深浅主题各一遍
- [ ] 字体缩放 1.3x 抽查：外观页微标签、底栏标签、大标题折叠不重叠
- [ ] 推真机前执行备份铁律（APK + 源码快照，各留 5 份）

## 5. 审计记录

| 日期 | 范围 | 结论 |
|---|---|---|
| 2026-10-13 | 全 ui/ 静态走查 | 无默认 M3 残留；清理死文件 ×3（SettingGroup/HistoryVideoItem/DownloadProgressItem）与死导入 ×2；云备份域输入框迁移 EdqiuTextField、多选迁移 CheckCircle；存量 22 处 OutlinedTextField（设置 17/引导 4/列表 1）待分批迁移 |
