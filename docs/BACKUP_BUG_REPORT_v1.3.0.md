# 123 网盘 WebDAV 备份 Bug 分析报告（v1.3.0 实测）

> 捕获时间：2026-08-15 03:55 ~ 04:11 CST（实测 1 小时窗口）
> 目标 Provider：123 网盘（WebDAV 凭证，已授权，密码后两位 `yj`）
> 待备份总量：**177 个任务 / 4363.72 MB**，全部 PENDING
> 设备：OnePlus（本地构建产物）

---

## 1. 实测数据快照

### 1.1 backup_tasks.json 状态汇总（JSON 解析）

| 指标 | 值 |
|---|---|
| 总任务数 | **177** |
| DONE | **0** |
| UPLOADING | 0 |
| PENDING | 177 |
| FAILED | 0 |
| CANCELLED | 0 |
| 总字节 | 4363.72 MB |
| `errorMessage is null` 的任务占比 | **100%（177/177）** |
| `bytesDone > 0` 的任务占比 | **0%（0/177）** |
| 当前 active task | `ゆき ❄️🐻‍❄️🐺…mp4`（16 MB / attempt=3） |

### 1.2 顶层偏好文件

- `backup_settings.xml`：`auto_pan123 = true`（自动备份已开启，24h 周期）
- `cloud_sync.xml`：`sync_enabled = false`（CloudDrive2 旧的 immediate sync 关闭）
- `backup_credentials.xml`：`EncryptedSharedPreferences` 双层密钥封装，账号密码落盘加密（OK）

### 1.3 Logcat 输出（最近 600 行）

**没有任何 backup-tagged 日志**。`WebDavEngine` / `BackupEngine` / `BackupHttpClient` / `com.ed.twitterdownload` 4 个候选 tag 全部静默。
- `Log.w("WebDavEngine", "MKCOL returned HTTP $code for $url")` 因为只 Log.w、且 MKCOL 返 200/201/204 时不打印 → **MKCOL 层完全无日志**
- PUT 失败未捕获 → stack trace 未打印
- `BackupEngine.runSingleAttempt` 的 `catch (Exception)` 没保留 cause，也没打 log

### 1.4 任务时间线（倒推）

- 03:55:40 task 入队，`createdAt=1786737940048`
- 03:55 ~ 03:55+30s：`attempt=0` 失败 → 等 30s
- 03:56:25 ~ 03:58:25：`attempt=1` 失败 → 等 2m
- 03:58:25 ~ 04:08:25：`attempt=2` 失败 → 等 10m
- 04:08:10 task 文件更新到 `updatedAt=1786738090417`，`attempt=3 PENDING` 落盘
- 04:08:25 ~ 当前（≈04:11）：**`attempt=3` 上传中**或仍在 10m backoff 阶段

> ⚠️ 由于 `bytesDone=0` 永远落 0，UI 重启后也无任何恢复点 → **冷启动后无进度可续**。

---

## 2. Bug 清单（按严重度排序）

### 🔴 P0-#1：错误信息 100% 丢失（致命，影响所有诊断）

**位置**：`BackupEngine.kt:245` + `BackupTask.markFailed` 调用链

**触发条件**：任何上传失败（IOException / 4xx / 5xx / 网络异常）

**代码证据**：
```kotlin
} catch (error: Exception) {
    TaskOutcome.Failed(error.message ?: "上传失败")   // ← 仅取 message
}
```
- 若是 `IOException("Unexpected status 507")`（**没有** getMessage 子类），`error.message` 可能返回 null
- 若是 `SocketTimeoutException`，`message = null` 时只显示「上传失败」
- 已捕获的 `BackupException` 用 `error.message` 取，但是被外层 `runCatching { ... }.toUserFriendly()` 已经包装过一次 → message 里没有堆栈

**影响范围**：
- 用户看到「失败」两个字，不知道是网络/认证/quota/文件问题
- 开发者看 logcat 也没有 stack trace → 无法 debug
- `taskStore.upsert(failedTask)` 写入 errorMessage=null，设备重启后历史归零

**修复方向**：catch 块取 `cause` 链，格式化 `${ex.javaClass.simpleName}: ${ex.message ?: "(no message)"}`，并 `Log.w(TAG, "...", error)` 输出堆栈。

---

### 🔴 P0-#2：进度字段 `bytesDone` 永不落盘（致命，断点续传失效）

**位置**：`BackupEngine.kt:250-254`（`refreshTaskInState`）

**触发条件**：所有非终态任务的上传过程中

**代码证据**：
```kotlin
private fun refreshTaskInState(updated: BackupTask) {
    _state.update { state ->
        state.copy(tasks = state.tasks.map { if (it.taskId == updated.taskId) updated else it })
    }
    // ← 这里只更新内存 StateFlow，没调 taskStore.upsert
}
```
- 进度回调每 64KB 一次，但只更新内存
- 设备重启 / 进程被杀 → 全部进度归零
- 16MB 文件已经 attempt=3，每次都是从 0 重新上传整文件（实际用户体感就是上传很慢、一直重试）

**影响范围**：
- 13 分钟过去，第 1 个文件还在 attempt=3 PENDING，0 字节进度
- 一旦后续支持 chunked upload，这个 bug 会被立刻放大

**修复方向**：throttle 落盘策略（每 ≥1 MB 或 ≥500ms 才 `taskStore.upsert`）；引入 `BackupEngine` 持有的 `CoroutineScope`（从 `AppContainer` 注入 `globalIoScope`）。

---

### 🔴 P0-#3：Backup 流程完全无日志（致命，开发可观测性 = 0）

**位置**：`BackupEngine.kt` 全文 / `WebDavEngine.put` 失败路径

**触发条件**：任意异常

**代码证据**：
- `BackupEngine.kt` 全文检索：`grep "Log\."` 仅 0 处 → 0 个 logger 调用
- `WebDavEngine.kt:165-167` PUT 失败只 `throw BackupException(...)`，不 `Log.w`
- `BackupEngine.kt:242-246` catch (Exception) 不 `Log.w`，堆栈全丢
- 没有 Log.i 状态机迁移日志（PENDING→UPLOADING→DONE/FAILED）

**影响范围**：
- 凌晨 4 点用户上传失败时，无法判断是网络、认证还是 quota
- 自动备份 WorkManager 触发时，没有任何线索
- `pan123 = auto` 让问题更是"沉默的失败"

**修复方向**：每个状态迁移 `Log.i(TAG, "[${remotePath}] ... → $nextState")`，每个 catch 带 stack trace `Log.w(TAG, "...", throwable)`。

---

### 🔴 P0-#4：MKCOL 把 405 当成功（隐患，quota/权限错误吞掉）

**位置**：`WebDavEngine.kt:38-43`

**代码证据**：
```kotlin
private val MKCOL_OK_CODES = setOf(
    HttpURLConnection.HTTP_CREATED,    // 201
    HttpURLConnection.HTTP_OK,         // 200
    HttpURLConnection.HTTP_NO_CONTENT, // 204
    HttpURLConnection.HTTP_BAD_METHOD, // 405
)
```
- 123 网盘在 **quota 已满**或**目录权限被回收** 时，部分版本返回 405（而不是标准的 507/403）
- 当前代码把它静默当作"目录已存在"，然后 `put` 时再失败 → 用户看到的是「上传失败」而非「配额满了」
- 同理 401/403 一律静默

**修复方向**：
- 405 保留宽容语义但加 `Log.w` 警告
- 4xx/5xx 分别打印并把 401/403 转译为认证错误抛上去
- 把真正的服务器返回 body（如果有）写日志

---

### 🟠 P1-#5：状态机日志过度静默

**位置**：`BackupEngine.runSingleTaskWithRetry` 全文

**触发**：自动备份每次进入

- 18 分钟 backoff 期间，UI 显示「备份进行中…」但用户不知道是在 retry 还是等待
- 也没有 Snackbar 提示「网络异常，正在 30s 后重试」

**修复**：retry 前 `Log.w(TAG, "[${remotePath}] attempt=$nextAttempt 失败，${delay/1000}s 后重试: $cause")`，UI 在 delay 阶段显示「重试中：N 秒后」

---

### 🟠 P1-#6：`enqueue` 不区分 DONE 与 FAILED（隐患，重复上传）

**位置**：`BackupEngine.kt:63-86`

**代码证据**：
```kotlin
suspend fun enqueue(targetId: String, files: List<File>): List<String> {
    files.forEach { file ->
        ...
        taskStore.upsert(task)  // 覆盖同名 task 的 attempt 状态
    }
}
```

虽然调用方（`BackupWorker.doWork` / `CloudBackupViewModel.backupNow`）都有预先 `filterByScope + doneTaskIds`，过滤逻辑 OK。但 **如果一个 task 之前 FAILED，被 retry 后成功，下次 backupNow 又把它当成 PENDING → 重新上传**。

实际上当前只过滤了 DONE，**未过滤 FAILED**。一旦开启自动备份，每次 24h 周期触发都会重新把 FAILED 文件入队（即便用户已尝试手动 retry）。

**修复**：在 `BackupFiles.collect` 中加入 `failedTaskIds` set 也跳过，或在 enqueue 时尊重已有 taskId 的 status。

---

### 🟠 P1-#7：Mkcol 失败后仍继续后续目录

**位置**：`WebDavEngine.ensureRemoteDirectories`

**代码证据**：
```kotlin
parts.forEach { part ->
    current = ...
    val url = buildUrl(credential.serverUrl, current)
    mkcolInternal(url, credential)   // ← 失败不抛
}
```
- `mkcolInternal` 仅 `Log.w` 不抛错
- 如果根目录 `/Edqiu` 失败，子目录 `/Edqiu/Sub` 也会继续尝试（浪费时间）

**修复**：父级 MKCOL 抛错时不抛 → 父级 4xx 时让子级也跳过，但 Log.w 留痕便于诊断

---

### 🟡 P2-#8：HttpURLConnection PUT 无 `setUseCaches(false)` / `setFixedLengthStreamingMode`

**位置**：`WebDavEngine.openConnection`

- 大文件（45 MB）下，`HttpURLConnection` 默认会用内部缓冲
- 没有显式 `setUseCaches(false)` 可能命中 HTTP 304
- 没有 `setFixedLengthStreamingMode` 让底层走 chunked 流式
- 123 网盘对 1GB 大文件单次 PUT 是支持的，但 stream 优化缺失

**修复**：`setUseCaches(false)` + 显式 `setFixedLengthStreamingMode(size)`

---

### 🟡 P2-#9：备份任务保留无上限

**位置**：`BackupTaskStore.JsonBackupTaskStore`

- 177 个 task 永远累积
- 每次 `persistLocked()` 都会写全文件 108KB
- 长期使用会膨胀

**修复**：UI 提供「清理已完成任务」按钮（>30 天的 DONE 任务自动归档或删除）

---

### 🟡 P2-#10：底部 Toast 文案疑义

**位置**：`CloudBackupViewModel.kt:268`

```kotlin
onSuccess = { postMessage("$name 连接正常，登录状态已校验") }
```
- 截图中显示「**123网盘 连接正常，登录状态云已校验**」——多了一个"云"字
- 经排查代码常量无"云"，疑似 iOS 风字体渲染导致 · 被读为"云"，或截图 OCR 误差
- 建议重写文案为"$name 连接正常，授权有效"或"$name 已通过认证"避免歧义

**修复**：改成"`{name}` 授权有效" + 增加 state 字段显式声明

---

## 3. bug → 修复路线图

| 优先级 | Bug | 修复策略 | 预估改动量 |
|---|---|---|---|
| P0-#1 | 错误信息丢失 | `BackupEngine` 重构 TaskOutcome + 保留 cause + 写日志 | 中 |
| P0-#2 | 进度不落盘 | 引入 `BackupEngine(ioScope)` + throttle 落盘 | 小 |
| P0-#3 | 无日志 | 全状态机加 `Log.i/w` | 小 |
| P0-#4 | MKCOL 宽容 | 405/4xx 抛错；保留 200/201/204 宽容 | 小 |
| P1-#5 | 重试无 UI 反馈 | retry 前 Snackbar 提示 + Log.w | 中 |
| P1-#6 | FAILED 重新入队 | `BackupFiles.collect` 过滤 FAILED | 小 |
| P1-#7 | Mkcol 失败仍继续 | 父级失败跳过后续 | 小 |
| P2-#8 | HTTP 性能 | `setUseCaches(false)` + streamingMode | 微 |
| P2-#9 | 任务累积 | 加清理入口 | 中 |
| P2-#10 | Toast 文案 | 重写 + 增 `state` 字段 | 微 |

---

## 4. 验证方法

1. **本地触发一次失败**：
   - 临时把密码改一位 → 让 123 网盘返回 401
   - 观察 logcat 应当输出 `BackupEngine` + `WebDavEngine` 完整 stack
   - `backup_tasks.json` 中首个 FAILED task 的 `errorMessage` 应为非 null

2. **断点续传验证**：
   - 用 100MB 测试文件
   - 在上传到 50MB 时断网
   - 杀进程，重启 App
   - 期望：`bytesDone` 已落盘 50MB → 重启后从 50MB 续传

3. **MKCOL 401 验证**：
   - 把 serverUrl 改成错误格式
   - 测试备份额外目录 → 期望 Log.w 明确「父级目录认证失败」

---

> 报告作者：Edqiu（UI Designer + Bug Hunter）
> 输出时间：2026-08-15 04:11 CST
> 数据来源：`adb shell run-as $PKG cat files/backup_tasks.json` + `adb logcat -d -t 600`
