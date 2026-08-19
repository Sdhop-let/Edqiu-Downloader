# Bug: BackupWorker 前台服务无类型导致启动闪退（InvalidForegroundServiceTypeException）

> Status: FIXED
> Mode: default
> Severity: blocker
> Author: Edqiu
> Last updated: 2026-08-19

## Symptom
应用启动即闪退，无法进入主界面。设备 logcat crash 缓冲区报：

```
android.app.InvalidForegroundServiceTypeException:
Starting FGS with type none callerApp=... targetSDK=35 has been prohibited
```

## Expected
应用正常启动；自动备份任务在后台执行时显示前台通知，不崩溃。

## Reproduction
- 触发条件：设备上 `backup_settings.xml` 中 `auto_webdav = true`（自动备份已开启）→ 调度了 `BackupWorker`（24h 周期 + 启动时执行）
- 复现步骤：`adb shell am start -n com.ed.twitterdownload/com.ed.edqiu.MainActivity`
- 复现稳定性：修复前 2/2 稳定崩溃；修复后 0/2 崩溃

## Hypotheses & diagnosis
| # | Hypothesis | Verdict | Evidence |
|---|---|---|---|
| H1 | `BackupWorker` 调用 `setForeground()` 时 `ForegroundInfo` 未指定 FGS 类型，targetSDK 35 禁止 type=none 启动前台服务 | confirmed (root cause) | 崩溃栈 `SystemForegroundService$Api31Impl.startForeground` → `Service.startForeground`；源码 `BackupWorker.kt:287` 用两参构造 `ForegroundInfo(id, notification)`，类型默认 0 |
| H2 | Manifest 未声明 FGS 类型 / 权限 | eliminated | Manifest 已声明 `SystemForegroundService` 的 `android:foregroundServiceType="dataSync"` 与 `FOREGROUND_SERVICE_DATA_SYNC` 权限；问题在 `ForegroundInfo` 显式传 type=0 |

## Root cause
`BackupWorker.doWork()` 调用 `setForeground()` 让 WorkManager 启动 `SystemForegroundService`。`createForegroundInfo()` 使用 `ForegroundInfo(id, notification)` 两参构造，`foregroundServiceType` 默认为 0（none）。WorkManager 2.9.0 的 `SystemForegroundService$Api31Impl` 以 type=0 显式调用 `Service.startForeground(id, notification, 0)`。应用 targetSdk=35，Android 14+ 禁止以 type=none 启动前台服务 → 抛 `InvalidForegroundServiceTypeException` → 进程崩溃。

因果链：`auto_webdav=true` → `BackupScheduler` 调度 `BackupWorker` → 应用启动时 WorkManager 执行 → `setForeground(ForegroundInfo(type=0))` → 系统拒绝 → 闪退。崩溃后 WorkManager 将任务标记为可重试，下次启动再次执行 → 每次启动都闪退。

## Fix
- 改动文件：`app/src/main/java/com/ed/edqiu/backup/BackupWorker.kt:287-292`
- 一句话改了什么：`ForegroundInfo` 改用三参构造，显式指定 `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`（与 Manifest 声明的 `dataSync` 类型、`FOREGROUND_SERVICE_DATA_SYNC` 权限一致）
- 代码 diff 摘要：
```kotlin
// 修复前
ForegroundInfo(NOTIFICATION_ID, buildNotification(title, progress))
// 修复后
ForegroundInfo(
    NOTIFICATION_ID,
    buildNotification(title, progress),
    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
)
```

## Verification
- V-1: 修复前 `am start` → 2/2 崩溃（17:36、17:37 两次复现）
- V-2: 修复后 `am start` → 0 崩溃，进程存活，`topResumedActivity=MainActivity`
- V-3: 临时触发 `BackupWorker`（debug 一次性任务）→ WorkManager 数据库显示 `state=2 (SUCCEEDED), run_attempt_count=1`，无崩溃
- V-4: 临时调试代码已完全还原（`git diff` 无残留），干净版本重新构建安装后启动正常

## Regression test
设备级验证（Android 崩溃无法用纯 JVM 单测覆盖）：
- 触发条件：`auto_webdav=true` + 启动应用
- 期望：应用正常启动，`BackupWorker` 执行 SUCCEEDED，无 `InvalidForegroundServiceTypeException`

## Pattern analysis
搜索仓库内其他 `setForeground` / `ForegroundInfo` 用法：

| 搜索方式 | 命中数 | 是否本次同类隐患 |
|---|---|---|
| `rg "setForeground"` | 1 处（BackupWorker.kt:248） | 是（本次已修） |
| `rg "ForegroundInfo"` | 2 处（BackupWorker.kt 导入 + 构造） | 是（本次已修） |

其余 WorkManager worker（`WebDavAutoBackupWorker` / `HistoryBackupWorker` / `DownloadSyncWorker`）均不调用 `setForeground()`，无同类隐患。

## Open questions / Follow-ups
- 设备上 `auto_webdav = true` 的自动备份仍处于开启状态，`BackupWorker` 周期任务会在下次到期时正常执行（现在不会再崩溃）。若用户不再需要旧版网盘备份，可在「我的 → 网盘备份」关闭自动备份，或由 `WebDavAutoBackupWorker`（新实现，不依赖前台服务）接管。
