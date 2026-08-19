# 秋的私有物 v2.2.0 更新日志

## 界面重构

### 底部导航栏调整
- 新增第5个Tab「设置」，从独立页面移入底部导航栏
- Tab顺序调整为：首页(原收件箱) / 次页(原首页) / 下载 / 播放 / 设置
- 首页（收件箱）移除内联设置面板和齿轮图标，设置统一在设置Tab中管理

### 首页（收件箱）UI对齐XInvox
- 标题区：品牌名「秋的私有物」+ 副标题「推特视频链接收件箱」
- 右上角：刷新按钮
- 统计行：未下载数量 + 粘贴并捕获按钮（同行显示）
- 搜索框右侧新增「最新捕获」快捷入口
- 四档过滤标签：全部 / 未下载 / 已下载 / 失败
- 卡片布局：作者头像(AsyncImage加载) + 作者名 + @handle + 完整推文文案 + 状态标签 + 时间 + URL
- 四按钮操作栏：复制 / 去下载(或状态按钮) / 打开(跳转Twitter) / 删除

## 新功能

### 收件箱数据管理
- 新增 `InboxExportService`：JSON格式导出/导入收件箱数据
- 导出备份：将收件箱数据保存为 `inbox_backup/inbox_links.json`
- 从本地恢复：从备份文件恢复收件箱数据（upsert合并）
- WebDAV同步：将收件箱数据同步到WebDAV服务器
- 从WebDAV恢复：从WebDAV下载并导入收件箱数据
- 设置页新增「收件箱数据管理」区块，包含4个操作按钮

### 分享无感保存
- 新增 `ShareReceiverActivity`（透明Activity）
- 从Twitter/X分享到app时，静默保存到收件箱，不跳转app界面
- 使用 `Theme.Translucent.NoTitleBar` + `noHistory` + `taskAffinity=""` 实现完全无感

### 播放界面增强
- 新增「正在播放」指示器：当前播放的条目显示蓝色标记和「正在播放」文字
- 自动滚动：切换到播放Tab时自动滚动到当前播放条目
- 新增扫描按钮：右上角同步图标可扫描本地文件夹（含Android/data目录）
- 扫描范围扩展：下载目录 + Android/data + app外部文件目录

### 作者信息自动获取
- 保存链接时从fxtwitter API自动获取：作者头像、作者名(display name)、作者ID(screen_name)、完整推文文案
- 卡片展示真实头像（Coil AsyncImage）和 @handle

### 启动自动识别代理
- app启动后自动扫描本地代理端口（7890/7897/10809等）并保存配置
- 无需手动进入设置点击「自动识别」

## Bug修复

### 下载完成显示已暂停/0-99%
- `DownloadProgressItem` 对COMPLETED状态强制显示100%进度和绿色「已完成」文字
- `DirectDownloader` 下载完成后额外发送100%进度回调
- `DownloadRepository` 后置操作（sidecar写入/自定义目录复制）改为best-effort，失败不阻塞COMPLETED状态

### 下载成功状态不更新
- `startInboxBatchDownload` 改用successCount/failCount计数器判断下载结果
- 不再依赖history反查（存在URL匹配/时序问题导致状态卡在DOWNLOADING）
- 下载成功立即标记DOWNLOADED，部分成功标记PARTIAL_DOWNLOADED，全部失败标记FAILED

### 清空任务不包含暂停状态
- `clearCompleted()` 内存过滤和DAO `clearFinished()` 查询均补充PAUSED状态

### 已识别链接二次识别
- `resolveLink` 新增状态守卫，仅对SAVED和FAILED状态执行解析
- READY/DOWNLOADED/PARTIAL_DOWNLOADED/DOWNLOADING/RESOLVING状态跳过，避免浪费网络请求

### 播放界面与收件箱重叠渲染
- 新增LaunchedEffect在离开Player路由时清除player状态，防止遮罩残留

## 数据库变更

### 版本 6 → 7 迁移
- 新增字段：`authorName`(作者名)、`authorAvatar`(头像URL)、`fullText`(完整推文文案)
- 所有新字段带默认空字符串，兼容旧数据
- `VideoInfo` 模型同步新增对应字段

## 技术改进

- 移除Compose `weight` modifier的wildcard import冲突（改用BoxWithConstraints计算等宽按钮）
- `OutlinedButton` 添加 `contentPadding = PaddingValues(0.dp)` 解决窄按钮文字截断
- `LazyColumn` 使用 `fillMaxWidth()` 替代 `fillMaxSize()` 避免约束问题
- `showLatest()` 补充 `syncUiState()` 调用确保过滤列表正确更新
