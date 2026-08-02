# XInvox 缺陷审计

> 审计日期：2026-07-11  
> 范围：当前项目全部 Kotlin、Manifest、Gradle 配置与文档；项目目录不包含 Git 元数据，因此不是基于提交差异的审查。  
> 状态：以下 10 项均已通过跨文件调用路径或独立复核确认。

## 严重级别说明

- **高**：可能批量破坏状态、造成永久不重试或明显的数据一致性问题。
- **中**：在可达的并发、权限、生命周期或外部服务异常下造成错误行为。
- **低**：主要影响性能、反馈时延或长期可维护性。

## 1. 扫描失败会被当作目录为空

**级别：高**

位置：

- `app/src/main/java/com/ed/xinvox/data/repository/DownloadMonitor.kt:38`
- `app/src/main/java/com/ed/xinvox/data/repository/SavedLinkRepository.kt:127`

`DownloadMonitor.scan()` 对未配置 URI、Provider 查询异常、SAF 授权失效和目录遍历异常统一返回 `emptyMap()`。`refreshStatuses()` 把该结果视为一次成功的完整扫描，并将所有未命中的 `DOWNLOADED` 记录改回 `PENDING`，同时清除 `filePath` 和 `downloadedAt`。

复现场景：下载器 Provider 临时不可用或 SAF 授权被撤销后执行前台刷新或后台同步；媒体文件仍存在，但全部已下载记录可能被回退。

建议：返回显式的 `ScanResult.Success(items)` / `ScanResult.Failure(error)`；只有成功扫描才允许执行“文件消失”回退。

## 2. 到期下载重试没有持久化执行者

**级别：高**

位置：

- `app/src/main/java/com/ed/xinvox/background/DownloadSyncWorker.kt:22`
- `app/src/main/java/com/ed/xinvox/ui/list/ListViewModel.kt:128`
- `app/src/main/java/com/ed/xinvox/data/repository/SavedLinkRepository.kt:118`

失败记录会持久化 `nextRetryAt`，但后台 Worker 只刷新文件状态和缺失元数据，不调用 `retryDueDownloads()`。短期重试依赖 `viewModelScope` 内的 `delay` 协程；列表页面销毁或进程终止后该任务消失。

复现场景：下载器启动失败后，用户在 30 秒或 2 分钟退避期内离开页面或系统终止进程；后续 WorkManager 运行也不会消费该失败项，直到用户再次进入列表触发刷新。

建议：使用基于数据库状态的唯一 WorkManager 任务消费到期项，或为每个 tweetId 安排可恢复的唯一任务；移除 ViewModel 递归计时器。

## 3. 下载请求缺少原子认领，可能重复派发

**级别：高**

位置：

- `app/src/main/java/com/ed/xinvox/data/repository/SavedLinkRepository.kt:67`
- `app/src/main/java/com/ed/xinvox/data/db/SavedLinkDao.kt:101`
- `app/src/main/java/com/ed/xinvox/ui/list/ListViewModel.kt:128`

`requestDownload()` 按“读取记录→启动外部 Activity→更新 Room”执行，没有条件更新、互斥或事务式认领。多个调用可读取相同 `attemptCount` 并同时启动下载器；失败更新还可能用旧快照覆盖较新的尝试次数。每次失败创建的延迟任务也没有按 tweetId 去重或取消。

复现场景：快速双击下载、批量任务与手动请求重叠，或旧自动重试在用户已经重试后到期；同一推文会被多次交给下载器。

建议：增加明确的 `DISPATCHING`/租约状态，使用带当前状态和版本条件的 DAO 更新原子认领记录；同一 tweetId 只允许一个有效任务。

## 4. 请求已派发但实际下载失败时可能永久停在 PENDING

**级别：高**

位置：

- `app/src/main/java/com/ed/xinvox/data/repository/DownloaderClient.kt:29`
- `app/src/main/java/com/ed/xinvox/data/db/SavedLinkDao.kt:101`
- `app/src/main/java/com/ed/xinvox/data/db/SavedLinkDao.kt:30`

只要 `resolveActivity()` 和 `startActivity()` 成功，记录就会变为 `PENDING`、增加尝试次数并清除 `nextRetryAt`。应用没有下载器接收确认、派发超时或最终失败回写；自动重试查询只选择 `FAILED`。

复现场景：下载器 Activity 启动后崩溃、忽略 Intent、内部下载失败或未生成文件；扫描持续找不到文件，但记录不会转为 `FAILED`，自动重试永远不会处理它。

建议：定义“已派发”状态及超时时间；超过合理期限仍未扫描到文件时转为可重试失败。更理想的方案是下载器返回接收和最终结果。

## 5. 远端 null 元数据会覆盖已有有效值

**级别：中**

位置：

- `app/src/main/java/com/ed/xinvox/data/repository/SavedLinkRepository.kt:172`
- `app/src/main/java/com/ed/xinvox/data/db/SavedLinkDao.kt:82`

`TweetMeta` 的作者、文案和缩略图字段均可为空。`fetchAndApplyMetadata()` 将远端结果直接传给 `applyMeta()`，而 SQL 无条件覆盖全部列。

复现场景：sidecar 已提供作者或缩略图，但记录因另一个字段缺失而进入远端重试；fxtwitter 响应缺少部分字段，已有非空值被写成 `null`。

建议：在 SQL 使用 `COALESCE(:value, existing_column)`，或仓库先读取现值并逐字段合并；同时定义来源优先级。

## 6. 缺失元数据队列存在确定性饥饿

**级别：中**

位置：

- `app/src/main/java/com/ed/xinvox/data/db/SavedLinkDao.kt:42`
- `app/src/main/java/com/ed/xinvox/data/repository/SavedLinkRepository.kt:160`

查询始终按 `saved_at DESC` 取前 5 条，失败后不更新任何参与排序或过滤的字段，也没有游标、失败次数、退避时间或永久失败标记。

复现场景：最新五条推文被删除、受保护或持续无法解析；每次刷新都重试同一批，第六条及更老的可成功记录永远得不到处理。

建议：增加元数据重试次数和 `nextMetadataRetryAt`，按到期时间轮转；或者使用游标/分页并限制受控并发。

## 7. SAF 持久授权失败后仍保存 URI 并显示成功

**级别：中**

位置：

- `app/src/main/java/com/ed/xinvox/ui/settings/SettingsScreen.kt:112`
- `app/src/main/java/com/ed/xinvox/data/preferences/SettingsRepository.kt:45`

设置页面用 `runCatching` 吞掉 `takePersistableUriPermission()` 的失败，随后仍保存 URI 并提示目录设置成功。

复现场景：Provider 未授予请求的全部权限或不支持持久授权；当前进程可能暂时能访问，但重启后监控或备份失败，设置页仍显示已保存目录。

建议：根据 Activity 返回的实际 flags 请求持久权限；授权失败时不得保存 URI，并向用户显示可恢复的错误信息。

## 8. 无障碍捕获在持久化完成前写入抑制标记

**级别：中**

位置：

- `app/src/main/java/com/ed/xinvox/capture/XInvoxAccessibilityService.kt:51`

服务在调用 `coordinator.capture()` 前更新 `lastCapturedTweetId` 和 `lastCapturedAt`，并忽略 `CaptureResult`。数据库写入异常、协程取消或进程终止时不会回滚标记。

复现场景：首次捕获时 Room insert 抛错；用户在 10 秒内再次复制相同链接，服务直接命中抑制条件并返回，不再尝试持久化，也没有失败反馈。

建议：只在 `Added` 或 `Duplicate` 后设置抑制标记；捕获异常时允许重试，并记录可诊断日志。

## 9. 捕获完成路径同步等待网络元数据

**级别：中**

位置：

- `app/src/main/java/com/ed/xinvox/data/repository/SavedLinkRepository.kt:48`
- `app/src/main/java/com/ed/xinvox/data/metadata/MetadataFetcher.kt:24`
- `app/src/main/java/com/ed/xinvox/capture/CaptureIntentActivity.kt:41`

Room insert 成功后，`capture()` 仍等待 fxtwitter 请求完成才返回 `Added`。连接和读取超时各为 10 秒；分享 Activity 也要等调用返回后才结束。

复现场景：网络缓慢时记录已经保存，但分享页面长时间不关闭；调用者作用域在等待期间取消时，用户可能得不到完成反馈。

建议：插入成功后立即返回；将元数据补全放入应用级任务或 WorkManager。不要吞掉 `CancellationException`。

## 10. 备份操作忙状态不是原子的

**级别：中**

位置：

- `app/src/main/java/com/ed/xinvox/ui/settings/BackupViewModel.kt:116`

`runBusy()` 先检查 `busy`，再启动 IO 协程，并在协程内部设置 `busy = true`。两个快速调用可在任一协程置位前同时通过检查；任一任务结束后还会无条件清除忙状态。

复现场景：快速双击立即备份、合并导入或确认替换；两项操作并发执行，较快任务先把界面恢复为可操作状态，而另一项仍在运行。

底层 `HistoryBackupRepository` 的 Mutex 能串行化部分写操作，但不能保证 ViewModel 预览、读取和界面状态的一致互斥。

建议：使用 ViewModel 级 `Mutex.tryLock()`，或在启动协程前同步完成原子状态占用；仅由持有者在 `finally` 中释放。

## 其他风险与测试建议

以下项目未计入上述 10 个主要缺陷，但建议后续处理：

- SAF 扫描会递归物化整个目录树，没有深度和文件数边界。
- 同一 tweetId 的多个媒体文件按扫描枚举中的第一项选择，结果不稳定。
- `targetSdk = 34`，发布前需核对当前应用商店要求。
- `android:allowBackup="true"` 未配置系统备份排除规则。
- Gradle Wrapper 未配置 `distributionSha256Sum`。
- Room 已启用 schema 导出，但项目中未见已提交的 schema 文件。
- 缺少数据库 2→3 迁移测试。
- 缺少扫描失败、并发下载、重试恢复、备份并发和元数据合并测试。

## 建议回归测试矩阵

1. Provider 正常空目录、Provider 异常、SAF 授权撤销三种扫描结果必须可区分。
2. 进程在 30 秒重试前被杀死，重新调度后仍应自动执行到期下载。
3. 同一 tweetId 的 10 个并发请求只能派发一次。
4. 派发后未生成文件，超过超时必须进入可重试状态。
5. 远端部分字段为 null 时不得清除本地非空元数据。
6. 前五条元数据永久失败时，第六条仍能在有限轮次内处理。
7. SAF 持久授权失败时不得写入 DataStore。
8. 无障碍捕获 insert 失败后再次复制必须重试。
9. 元数据服务超时不应延迟链接保存完成反馈。
10. 多个备份/恢复按钮并发触发时只能有一个操作进入执行区。
