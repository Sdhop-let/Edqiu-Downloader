# XInvox CloudBackup 模块差距分析与路线图

> 本文档基于 `docs/cloud-sync-research.md` 的调研结论，对 XInvox 现有 `com.ed.edqiu.backup` 模块做代码级差距分析，并给出 P0–P2 优先级路线图。
> 代码核实日期：2026-08-15（已读 14 个关键 .kt 文件，结论与计划第 3 节一致）
> 适用版本：Edqiu v1.3.0（versionCode 4，applicationId com.ed.twitterdownload）

---

## 1. 现有模块架构概览（已具备的能力）

XInvox 的 CloudBackup 模块**已经是一个相当完整的骨架**，不是从零开始。核心组件：

| 层 | 类 / 文件 | 职责 | 状态 |
|----|-----------|------|------|
| 抽象层 | `BackupTarget.kt` | Provider 接口（id/displayName/authMode/capabilities/isConfigured/isAuthorized/prepareRemote/exists/uploadFile/testConnection） | ✅ 健全 |
| 注册层 | `ProviderRegistry.kt` | ConcurrentHashMap 注册；`registerWebDavFamily()` 注册 WebDAV/Pan123/CloudDrive2；`detectCloudDrive2()` 本机探测 | ✅ 健全，缺天翼 |
| 凭证层 | `BackupCredentialStore.kt` | `EncryptedCredentialStore` 基于 `EncryptedSharedPreferences`（AES256_GCM MasterKey）；密钥失配降级重建 | ✅ 健全，缺主动刷新调度 |
| 网络层 | `HttpClient.kt` | OkHttp 单例；GET/POST/PUT/HEAD/OPTIONS；超时 20s/30s/30s；`toUserMessage()` 脱敏 | ⚠️ 缺频控/退避 |
| 分片层 | `ChunkedUploader.kt` | 流式 SHA-1；`split` 切分；断点续传（跳过 resumeState 已确认分片）；`ChunkReceiptStore` 落盘 `filesDir/backup_chunks/{taskId}.json` | ✅ 健全 |
| 引擎层 | `BackupEngine.kt` | Mutex 单队列串行；状态机 PENDING→UPLOADING→DONE/FAILED；指数退避 30s/2m/10m；节流落盘 1MB；`TaskOutcome` 密封接口 | ✅ 健全，退避仅用于任务级 |
| 调度层 | `BackupWorker.kt` | `BackupScheduler` 24h 周期 + 立即执行；Constraints(电量/存储不低)；`BackupFiles` 收集（VIDEO/IMAGE 白名单）；前台通知 `backup_progress` | ✅ 健全，缺本地账本 |
| Provider | `BaiduPanTarget.kt` | 设备码授权；固定根 `/apps/Edqiu`；`ensureAccessToken()` 刷新（TOKEN_REFRESH_MARGIN_MS=60s）；三段式 precreate→uploadChunks→createFile | ✅ 接入 |
| Provider | `AliPanTarget.kt` | WebView 截 token；固定根 `/EdqiuBackup`；`callWithAuthRetry(401重试)`；`computeProofCode` 秒传；`listUploadedParts` 续传兜底 | ✅ 接入 |
| Provider | `CloudDrive2Target.kt` | `AuthMode.NONE`；本机 `127.0.0.1:19798/dav` 探测；复用 WebDavEngine | ✅ 接入 |
| Provider | `Pan123Target.kt` | `WEBDAV_CREDENTIAL`；URL 约定 `webdav-{accountId}.pd1.123pan.cn/webdav` | ✅ 接入 |
| Provider | `WebDavBackupTarget.kt` | 复用旧 `CloudSyncPreferences`（serverUrl/username/password/remotePath） | ✅ 接入 |
| API 封装 | `AliPanApi.kt` | openapi.alipan.com 客户端；createFolder/uploadPart/listUploadedParts/completeFile/listFiles；check_name_mode=auto_ignore | ✅ 健全 |
| API 封装 | `BaiduPanApi.kt` | 设备码 OAuth；PCS 三段式；meta/createDir；errnoMessage 映射；固定 /apps/ 路径 | ✅ 健全 |

**结论**：骨架优秀，缺的是"最后一公里"的工程打磨（频控、账本、冲突统一、新 Provider）。

---

## 2. 差距清单（代码核实结论）

### 2.1 已确认缺口（按影响排序）

| # | 缺口 | 证据（文件:行/逻辑） | 影响 | 优先级 |
|---|------|----------------------|------|--------|
| G1 | **无天翼云盘 Provider** | `ProviderRegistry.kt` 无天翼注册；`BackupTarget.kt` ProviderId 常量无天翼 | 错过国内唯一官方友好的大厂盘 | **P0** |
| G2 | **HttpClient 无频控/退避** | `HttpClient.kt` 仅超时+连接重试，无 429/`x-retry-after` 处理 | 百度 listall 超频 31034、阿里 429 会卡死备份 | **P0/P1** |
| G3 | **无本地同步账本** | `BackupWorker.BackupFiles` 每次重新收集+判断 exists；无 `cloud_file_id`/`mtime` 表 | 每次全量扫描，无法增量，重复 exists 网络调用 | **P1** |
| G4 | **CredentialStore 无主动刷新调度** | `BackupCredentialStore.kt` 仅响应式 `ensureAccessToken()`；无 `isExpired()` 定时刷新 | token 静默过期导致备份失败 | **P1** |
| G5 | **冲突语义未统一** | 百度 `rtype`、阿里 `check_name_mode=auto_ignore` 各写各的；无集中枚举 | 多 Provider 行为不一致 | **P2** |
| G6 | **百度秒传未接 slice-md5** | `BaiduPanApi.kt` 走完整上传，无 `slice-md5`/`content-md5` 秒传 | 重复文件浪费流量 | **P1** |
| G7 | **阿里新用户无法授权引导缺失** | `AliPanTarget.kt` 无"暂停申请"提示；UI 无限制文案 | 新用户困惑 | **P1** |
| G8 | **S3 兼容通道缺失** | ProviderId 无 S3；无 `S3Target` | 错过最通用对象存储 | **P0（可选）** |
| G9 | **大文件轻量指纹缺失** | `ChunkedUploader.computeSha1` 全文件 SHA-1；无"前 1KB 预哈希"快速跳过 | 大视频每次全量算哈希耗时 | **P2** |

### 2.2 已具备（无需重复造轮子）

- 分片上传框架（`ChunkedUploader` + `ChunkReceiptStore`）—— 直接复用，新 Provider 只需实现 `uploadFile` 适配。
- 凭证加密存储（`EncryptedCredentialStore`）—— 新 Provider 直接调 `save/read/clear`。
- 任务状态机与退避（`BackupEngine`）—— 任务级退避已健全，只需补"请求级"频控（G2）。
- Provider 注册机制（`ProviderRegistry`）—— 新增天翼/S3 只需 `register()`。

---

## 3. 优先级路线图

### P0 — 补强最稳通道（1–2 周）

**P0-1：新增 `TianYiPanTarget`（天翼云盘）**
- 文件：`app/src/main/java/com/ed/edqiu/backup/provider/TianYiPanTarget.kt`
- 注册：`ProviderRegistry.kt` 加 `registerTianYi()`；`BackupTarget.kt` 加 `ProviderId.TIANYI`
- 授权：OAuth 2.0 授权码（参考阿里 PKCE 流，但天翼用标准授权码 + redirect_uri）
- 上传：分片 + 秒传（天翼支持 `rapidupload` 接口）
- 复用：`ChunkedUploader` + `EncryptedCredentialStore` + `BackupEngine`

**P0-2：打磨 WebDAV / CloudDrive2 稳定性**
- `HttpClient.kt` 加统一超时与错误映射（G2 部分）
- `CloudDrive2Target.detectCloudDrive2()` 增加失败重试与超时

**P0-3（可选）：新增 `S3Target`（S3 兼容）**
- 文件：`app/src/main/java/com/ed/edqiu/backup/provider/S3Target.kt`
- 用 AWS SDK for Android 或最小 HTTP 签名（V4）
- 兼容 MinIO / 阿里 OSS / 腾讯 COS / 七牛

### P1 — 工程打磨（2–3 周）

**P1-1：HttpClient 频控与退避（G2）**
- 新增 `RateLimiter` 或扩展 `HttpClient`：识别 429 / `x-retry-after` / 百度 31034
- 百度 `listall` 限速窗口（≤8–10 次/分钟）
- 阿里按 `x-retry-after` 退避

**P1-2：本地同步账本（G3）**
- 新增 Room 表 `BackupLedger`（path / size / mtime / local_hash / cloud_file_id / state）
- `BackupWorker` 改为"读账本 → 差异收集 → 更新账本"
- 低频云端对账（`list` + mtime 比对）

**P1-3：CredentialStore 刷新调度（G4）**
- `BackupCredentialStore` 加 `isExpired()` / `scheduleRefresh()`
- 或 `BackupWorker` 每次跑前批量 `ensureAccessToken()`

**P1-4：百度秒传 slice-md5（G6）**
- `BaiduPanApi.precreate` 加 `content-md5` + `slice-md5`（前 256KB）
- `ChunkedUploader` 暴露前 256KB 哈希

**P1-5：阿里限制引导文案（G7）**
- `AliPanTarget` 检测"应用未创建"时返回友好错误
- UI 提示"阿里云盘 2025-07 起暂停个人申请，仅支持已有授权用户"

### P2 — 一致性收尾（1 周）

**P2-1：冲突语义统一（G5）**
- `BackupTarget` 或 `BackupEngine` 定义 `ConflictStrategy` 枚举（SKIP_IF_SAME_HASH / RENAME / REFUSE）
- 各 Target 映射自身 API 参数（百度 `rtype`、阿里 `check_name_mode`）

**P2-2：大文件轻量指纹（G9）**
- `ChunkedUploader` 加 `preHash`（前 1KB SHA1）用于快速跳过已存在文件
- 阿里 `pre_hash` 直接复用

---

## 4. 关键文件定位（供执行阶段直接跳转）

| 任务 | 主改文件 | 参考文件 |
|------|----------|----------|
| P0-1 天翼 | `provider/TianYiPanTarget.kt`（新） / `ProviderRegistry.kt` / `model/BackupTarget.kt` | `AliPanTarget.kt` / `BaiduPanTarget.kt` |
| P0-2 WebDAV 打磨 | `provider/CloudDrive2Target.kt` / `HttpClient.kt` | `WebDavBackupTarget.kt` |
| P0-3 S3 | `provider/S3Target.kt`（新） / `ProviderRegistry.kt` | `Pan123Target.kt` |
| P1-1 频控 | `http/HttpClient.kt` | `BackupEngine.kt`（退避参考） |
| P1-2 账本 | `data/BackupLedger.kt`（新） / `BackupWorker.kt` | `BackupCredentialStore.kt` |
| P1-3 刷新 | `data/BackupCredentialStore.kt` | `BaiduPanTarget.ensureAccessToken()` |
| P1-4 百度秒传 | `provider/BaiduPanApi.kt` / `upload/ChunkedUploader.kt` | `AliPanApi.computeProofCode()` |
| P1-5 阿里引导 | `provider/AliPanTarget.kt` + UI 字符串 | — |
| P2-1 冲突 | `model/BackupTarget.kt` / `engine/BackupEngine.kt` | 各 Target exists/uploadFile |
| P2-2 指纹 | `upload/ChunkedUploader.kt` | `AliPanApi.pre_hash` |

---

## 5. 执行原则回顾（与调研报告第 4 节呼应）

1. 引擎与 Provider 解耦 —— 新增云盘不动引擎。
2. 收据落盘先于字节传输 —— `ChunkReceiptStore` 已做对。
3. 频控是引擎职责 —— P1-1 放 HttpClient，不放 Provider。
4. 冲突语义集中定义 —— P2-1 一处枚举。
5. token 生命周期统一管理 —— P1-3 收口到 CredentialStore。

---

## 6. 验收标准（Definition of Done）

- [ ] 天翼云盘可完成"授权 → 上传 → 续传 → 秒传"全链路
- [ ] 百度 listall 超频不再卡死（自动退避）
- [ ] 二次备份耗时 ≈ 0（账本命中，无重复上传）
- [ ] token 过期前自动刷新，备份不失败
- [ ] 多 Provider 冲突行为一致（同名文件统一重命名或跳过）
- [ ] 阿里新用户看到明确限制提示

---

_本文档与 `docs/cloud-sync-research.md` 配套使用。调研报告定义"为什么这么做"，本文档定义"具体改哪里、按什么顺序"。_
