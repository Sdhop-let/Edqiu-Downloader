# WebDAV 同步标准化方案（2026-10）

> 范围：设置页「WebDAV 同步」+ 同步情况详情（MediaBackup 对账页）。
> 结论先行：**WebDAV 是唯一的同步通道**；百度（设备码）/ 阿里（WebView token）/ 123（OAuth）
> 三条厂商私有 API 通道已整体移除——它们依赖构建期厂商资质（`BAIDU_CLIENT_ID` 等），
> 资质缺失必然同步失败，属于结构性风险而非可修复的 bug。
>
> 2026-10 第二批（双向同步）：**同步语义由单向备份升级为双向同步**（上传 + 下载），
> 核心铁律「本地文件永不被覆盖」；>2GB 视频断电续传（`.edqiu-part` 暂存 + PATCH 追加 +
> MOVE 原子落盘 / GET Range 续传）；HTTPS 自签证书容忍开关；城通网盘免费 5TB 可用 WebDAV。
>
> 2026-10 第三批（本页即全部）：**网盘备份中心（BackupEngine 队列 / 多网盘适配器 / 账本 /
> 任务存储）整体删除**——WebDAV 同步页即唯一配置与同步入口；**每网盘独立账号配置**
> （`cfg_{pid}_*` 键，切预设互不覆盖）；**保存语义 = 测试连接成功才落盘，失败不保存**
> （600ms 去抖自动落盘移除）；「同步情况详情」改为轻量对账页（本地 × PROPFIND 远端逐文件
> 比对，无引擎依赖），仅测试通过后显示。

## 1. 选型理由（参考对象与取舍）

调研了 GitHub 上三套成熟实现，各取其一臂之力，**不引入新依赖**：

| 参考项目 | 借鉴点 | 落地位置 |
|---|---|---|
| [sardine-android](https://github.com/thegrizzlylabs/sardine-android)（Android WebDAV 客户端事实标准） | OkHttp 传输层 + 「Basic 预授权、401 Digest 挑战应答」双通道鉴权 | `WebDavEngine.executeWithDigestFallback`：默认 Basic；服务器挑战 `WWW-Authenticate: Digest` 时计算 MD5 / MD5-sess 应答重放一次 |
| [DAVx5](https://github.com/bitfireAT/davx5-ose)（Android CalDAV/CardDAV 同步） | 错误分类学：瞬态错误退避重试、配置性错误 fail-fast；超时统一 | `WebDavEngine.putWithRetry` + `WebDavTransferException`（携带 HTTP 状态码）；401/403/404/507 不重试，408/429/5xx/网络 IO 才重试 |
| [rclone](https://github.com/rclone/rclone)（rsync for cloud） | 低层重试指数退避（1s/2s/4s）；上传前后 HEAD 大小校验防半截文件固化 | 退避序列与 `exists(url, expectedSize)` 尺寸比对（v1.9 已有，保留并文档化） |

**为什么不直接引入 sardine-android 依赖**：其维护节奏放缓（年更）、依赖树包含 junit/ext 库冲突风险；
本项目引擎已稳定运行多个版本（OkHttp 4.x + Kotlin），只补 Digest 与重试两块短板，
30 行内自实现比引入 0.9MB 依赖 + 适配成本更划算。**为什么不是 WebDAV 库 webdav-client-android**：
同类问题，且不支持 MKCOL 逐级建目录的错误分类（405/409/507 语义区分）。

## 2. 架构（2026-10 第三批精简后）

```
设置页 WebDAV 同步卡片（每网盘独立配置表单 + 连接状态面板 + 测试连接/立即同步/自动备份/详情入口）
        │
        ├── WebDavManualSync（应用级运行器：退出页面同步继续，进度回填页面）
        │       └── WebDavSyncService.syncDownloads(onProgress)   ← 读当前选中预设 cfg_{pid}_*
        │               └── WebDavEngine（MKCOL/HEAD 三元判定/PUT 断点续传/GET Range/
        │                                 PROPFIND/MOVE/DELETE/Basic+Digest/自签容忍）
        │
        ├── onOpenMediaBackup → 同步情况详情（MediaBackupScreen，轻量对账）
        │       └── 本地目录扫描 × PROPFIND 远端清单 → 逐文件 已同步/待上传/冲突
        │           （无引擎/账本依赖；立即同步复用 WebDavManualSync，完成后自动重对账）
        │
        └── WebDavAutoBackupWorker（WorkManager 定时自动双向同步）
```

网盘备份中心（BackupEngine 队列 / ProviderRegistry 适配器 / 账本 / 任务存储）已整体删除——
同步幂等由「HEAD 远端尺寸比对」天然保证，不再需要账本；预下载联动直接调 WebDavSyncService。

## 3. 网盘接入配置（预设模板）

入口：设置 → WebDAV 同步 → 网盘预设。**2026-10 每网盘独立配置**：每个预设的地址/账号/
密码/远程目录独立存储（`cfg_{pid}_*` 键），切换预设互不覆盖、互不串号；
**保存语义 = 「测试连接」成功才落盘该网盘配置，失败不保存**（600ms 去抖自动落盘已移除）。
带 ✻ 角标 = WebDAV 功能需要该网盘付费会员（仅提示，不阻断）。

| 预设 | 服务器地址 | 账号 | 密码 | 备注 |
|---|---|---|---|---|
| 123网盘 ✻ | `https://webdav-{账号ID}.pd1.123pan.cn/webdav`（模板占位，或粘贴控制台完整地址） | 手机号/邮箱 | 网页端「第三方挂载 → WebDAV 授权管理 → 添加应用」生成的**应用密码**（只显示一次，勾选读写权限） | 第三方挂载需 VIP |
| 城通网盘 | `https://dav.ctfile.com/ws<用户ID>`（**个人专属地址**，旧版界面左侧「WebDAV」查看，形如 dav.ctfile.com/ws3684788） | 注册邮箱 | 账号密码 | 免费注册即享 5TB 共享空间；免费文件 6 个月无下载可能清理 |
| CloudDrive2 | `http://<内网IP>:19798/dav` | CD2 内配置 | CD2 内配置 | 可把百度/阿里等挂载成本地 WebDAV |
| 移动云盘 | `https://webdav.139.com` | 手机号 | 设置中开启 WebDAV 后生成 | |
| 数据胶囊 | `https://data.cstcloud.cn/webdav` | 中科技云通行证 | 网页端「设置」生成 | 实名免费 20GB，不限速 |
| InfiniCloud | `https://<用户名>.teracloud.jp/dav/` | 用户名 | Apps Password | 免费 25GB |
| 坚果云 | `https://dav.jianguoyun.com/dav/` | 注册邮箱 | 应用密码（第三方应用管理生成） | 免费 3GB |
| 自定义 | 任意 | 任意 | 任意 | NAS / alist / Nextcloud / 群晖等 |

✻ = WebDAV 功能需要该网盘付费会员（仅提示，不阻断）。当前仅 123 网盘标注；城通网盘免费档可用。
真机实测（2026-10-15）：城通免费账号 dav.ctfile.com/ws3684788 鉴权通过并完成 349 文件真实同步。

**已移除的预设**：百度网盘、阿里云盘（无官方 WebDAV，原模板 `http://<内网IP>:…` 属配置不完整误导源）。
此类网盘请用 CloudDrive2 / alist 挂载后走「自定义」。

## 4. 连接状态与同步进度展示

设置页 WebDAV 卡片新增**连接状态面板**（表单下方，风格与全页一致）：

- **连接状态**：未配置 → 已填写待验证 → 正在验证 → 已连接（鉴权通过）/ 上次验证失败（语义色点 + 文案）；
- **地址 / 账号 / 密码**：当前配置回显；密码永不展示，只显示「已保存（不展示）」；
- **鉴权结果**：上次「测试连接」时间 + 通过/失败结论（`lastVerifiedAt` 持久化）；
- **同步进度**：`正在上传/正在下载 i/N · 文件名 · xx%` + 6dp 胶囊进度条（整体进度 = (i-1+单文件进度)/N，
  由应用级运行器 `WebDavManualSync` 实时驱动，退出页面不中断，回来接着看）；
- **错误提示**：最近一次同步失败的中文原因常驻展示（脱敏、无 token），下次同步开始时覆盖；
- **自签证书开关**：面板下方「允许自签名证书（HTTPS 自建 NAS）」开关（默认关）。

反馈弹窗约定不变：操作结果（测试成功/失败、同步完成/失败）仍走 `FeedbackDialog` 弹窗；
状态面板只承担**常驻展示**，不替代弹窗。

## 5. 鉴权处理

- 默认 **Basic 预授权**（每次请求带 `Authorization: Basic base64(user:pass)`，与旧版一致）；
- 服务器返回 401 且 `WWW-Authenticate: Digest` → 解析挑战（realm/nonce/qop/opaque/algorithm），
  计算 **MD5 或 MD5-sess、qop=auth** 应答并重放一次（sardine-android 同策略）；
- `qop=auth-int` 或非 MD5 族算法（如 SHA-256-only 服务器）不支持 → 维持 401 语义报「认证失败」；
- 密码经 `SecretBox`（AndroidKeyStore + AES/GCM）加密落盘，日志与错误信息全链路脱敏。

## 6. 双向同步语义（2026-10 第二批）

同步 = 上传阶段（本地 → 云端）+ 下载阶段（云端 → 本地），**本地文件永不被覆盖**是硬保证：

| 场景 | 上传阶段行为 |
|---|---|
| 远端缺失 | putResumable 断点续传上传 |
| 两端尺寸一致 | 跳过 |
| 同名但尺寸冲突 | 云端旧版本先 MOVE 改名 `xxx.conflict-<时间戳>.ext` 保留（Overwrite:F），再上传本地版；保不住（不支持 MOVE）则放弃该文件——**云端数据不静默丢失，本地版本不被远端污染** |
| HEAD 状态未知（5xx 等） | 中止本轮（防服务端故障时误覆盖） |

| 场景 | 下载阶段行为 |
|---|---|
| 本地不存在 | GET（Range 续传）先写本地 `.edqiu-part`，完成后 rename 成品 |
| 本地已存在 | **一律跳过，绝不覆盖**（即使尺寸不同；差异由上传阶段对账云端） |
| 远端 `.edqiu-part` 暂存 | 不下载（它设备/上次会话的未完成传输） |

备份中心（BackupEngine 队列）已随第三批删除；「立即同步」/ 自动备份 / 预下载联动 /
同步情况详情全部走同一条双向同步链路，语义统一。

## 6.5 断电续传协议（>2GB 视频）

**上传**（`WebDavEngine.putResumable`，三个 WebDAV 适配器 + 立即同步共用）：

1. 全程只写远端 `目标.edqiu-part` 暂存——任何时刻断电，云端都不会出现半截成品；
2. 续传探测：HEAD 暂存尺寸 → 服务器支持 PATCH 追加时只传剩余字节（双头探测：
   先 `Content-Range: bytes a-b/*`，不识别再试 dufs 风格 `X-Update-Range: append`）；
   追加后 HEAD 复核总尺寸，不符即弃用半成品整传（防脏数据固化）；
3. 原子落盘：MOVE 暂存 → 成品（覆盖语义）；暂存已传完整但未落盘（断电窗口）→ 零重传直接 MOVE；
4. 降级链：不支持 PATCH → 整传 .part + MOVE（仍断电安全）；不支持 MOVE → 整传成品 URL
   （旧行为，exists 尺寸校验兜底）。兼容性按引擎实例记忆，不做无谓重复探测；
5. 瞬态失败（408/429/5xx/网络 IO）1s/2s/4s 退避重试至多 3 次；配置性错误 fail-fast。

**下载**（`WebDavEngine.get`）：本地 `.edqiu-part` 已有内容时发 `Range: bytes=N-`——
206 续传 / 200 重下；中断保留 .part 现场，下次同步从断点继续。

## 7. HTTPS 引擎（2026-10 第二批）

- **自签名证书容忍开关**（`allow_insecure_tls`，默认关）：设置页 WebDAV 卡片新增开关行，
  开启后引擎换用不校验证书链与主机名的客户端——自建 NAS 的 https 自签场景；
- **明文 http 放行**（Manifest `usesCleartextTraffic="true"`）：targetSdk 28+ 默认禁明文，
  会把 CloudDrive2 / alist / 群晖的 `http://192.168.x.x` 局域网地址全部拒掉；
  本 App 服务器地址完全由用户填写、解析 API 均为 https，必须放行。
  ⚠️ http 明文含 Basic Auth 凭证，公网服务仍建议 https；
- 缺协议前缀的地址默认按 https 处理（已有行为）。

## 8. 配置项清单（CloudSyncPreferences，`cloud_sync` prefs）

| key | 说明 |
|---|---|
| `sync_enabled` | WebDAV 同步总开关 |
| `webdav_provider_id` | 当前选中预设 id（123pan/ctfile/clouddrive2/migu/cstcloud/infinicloud/jianguoyun/custom） |
| `cfg_{pid}_server_url` / `cfg_{pid}_username` / `cfg_{pid}_password`（加密） | **每网盘独立**连接三元组（2026-10 第三批，替代旧全局键） |
| `cfg_{pid}_remote_path` | 每网盘独立远程根目录 |
| `cfg_{pid}_verified` / `cfg_{pid}_verified_at` | 每网盘独立鉴权结论 + 上次验证通过时间（驱动详情入口与状态面板） |
| `per_provider_migrated` | 旧全局配置一次性迁移标记（迁入迁移时的选中预设命名空间） |
| `allow_insecure_tls` | 自签名证书容忍开关（自建 NAS https 场景，默认关） |
| `auto_backup_enabled` / `auto_backup_days` | 定时自动双向同步（1/3/7 天） |
| `last_sync_time` / `sync_file_count` | 同步历史统计（全局） |

旧全局键 `webdav_server_url` / `webdav_username` / `webdav_password` / `webdav_remote_path` /
`connection_verified` / `last_verified_at` 只读保留作迁移源，代码不再写入。

## 9. 权限要求

| 权限 | 用途 | 现状 |
|---|---|---|
| `INTERNET` | WebDAV 传输 | 已声明 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 自动备份前台服务（Android 14+ 类型化） | 已声明（BackupWorker） |
| `POST_NOTIFICATIONS` | 备份进度通知（Android 13+ 运行时） | 已声明并请求 |
| WorkManager 约束 | 网络连通 + 电池非低 (+ 存储非低) | 已配置 |

WebDAV 方案不引入任何新权限；相比厂商直连**减少了** OAuth 回调 scheme、WebView 授权页、
设备码轮询等敏感面。

## 10. 风险与兼容性限制

1. **Digest 兼容面**：仅实现 MD5 / MD5-sess + qop=auth。SHA-256-only 服务器（RFC 7616 新式）
   不支持，表现为「认证失败」——主流自建服务（群晖/QNAP/nginx dav）默认 MD5，影响面小。
2. **双向同步冲突语义**：本地永不被覆盖（下载阶段本地已存在一律跳过）；同名不同尺寸冲突时
   本地版胜出上传，云端旧版本尽力改名 `conflict-<时间戳>` 保留——保不住（服务器不支持 MOVE）
   则放弃该文件并计入冲突数。无三方合并/时间戳择新（如需请用 Syncthing）。
3. **断点续传兼容矩阵**：`.edqiu-part` 暂存 + MOVE 原子落盘 = 全服务器断电安全；
   PATCH 追加续传（省流量）仅部分服务器支持（dufs 风格 `X-Update-Range: append` /
   `Content-Range`），不支持时自动降级整传（不丢正确性，只费流量）。追加后强制 HEAD
   尺寸复核，服务器语义不明也不会固化脏数据。
4. **123 网盘应用密码只显示一次**：丢失需重新生成；本 App 不缓存明文，只存加密值。
5. **存量数据迁移**：曾选百度/阿里/123 OAuth 的用户，备份中心选中项自动回退「自定义 WebDAV」；
   预下载联动的失效 id 自动回退 WebDAV（未配置则跳过联动，不报错）。历史任务/账本中的
   旧 provider 记录保留但不再执行。
6. **明文 HTTP 地址**：Manifest 已放行明文（`usesCleartextTraffic="true"`，否则 targetSdk 28+
   会拒掉所有 `http://192.168.x.x` 局域网地址）；http 明文含 Basic Auth 凭证，公网服务
   建议 HTTPS + 自签开关组合。引擎对缺 scheme 的地址默认按 HTTPS 处理。
7. **自签证书开关的安全代价**：开启 `allow_insecure_tls` 后不校验证书链与主机名，
   中间人可截获凭证——仅限内网可信环境。

## 11. 改动文件索引（2026-10）

**第一批（统一通道）**：
- 移除：`backup/auth/*`、`backup/upload/ChunkedUploader.kt`、
  `backup/provider/{BaiduPan*,AliPan*,Pan123Open*,Pan123Api}.kt`、
  `ui/backup/{BaiduAuthDialog,AliAuthDialog,Pan123AuthDialog}.kt`、
  build 配置中的全部厂商资质项（app/build.gradle.kts + gradle.properties）
- 重构：`CloudBackupViewModel/Screen`（仅 WebDAV 族）、`WebDavProviderSelector`（预设重建 + ✻ 角标）、
  `SettingsScreen` WebDAV 卡片（连接状态面板）
- 清理：`backup/http/HttpClient.kt` 收缩为工具函数（runCatchingNotCancelled / toUserMessage）

**第二批（双向同步 + 断电续传 + HTTPS）**：
- `WebDavEngine`：+`putResumable`（.part 暂存/PATCH 追加探测/MOVE 原子落盘/尺寸复核）、
  +`get`（Range 续传）、+`listDirectory`（PROPFIND 207 解析）、+`move`/`delete`、
  +`headState` 三元判定（MATCHES/MISSING/SIZE_DIFFERS）、+自签证书容忍双客户端；
  putWithRetry 收编为私有 putWithTransientRetry
- `WebDavSyncService`：单向 → **双向**（上传 + 下载两阶段，冲突云端改名保留，
  结果 `WebDavSyncResult` 含 uploaded/downloaded/skipped/conflicts）
- `WebDavManualSync` / `WebDavAutoBackupScheduler`：适配新结果与分阶段进度
- 三个 WebDAV 适配器：`uploadFile` 全部切到 `putResumable`
- `CloudSyncPreferences`：+`allow_insecure_tls`；`SettingsScreen`：+自签开关行、
  分阶段进度文案、双向结果弹窗文案
- `AndroidManifest`：+`usesCleartextTraffic="true"`（局域网 http 放行）
- `WebDavProviderSelector`：城通网盘预设改为「免费 5TB 可用 WebDAV」

**第三批（本页即全部 + 每网盘独立配置）**：
- 删除：`ui/backup/CloudBackup{Screen,ViewModel}.kt`、`backup/{engine,data,model/BackupTarget.kt,
  BackupWorker.kt,provider/{ProviderRegistry,WebDavBackupTarget,Pan123Target,CloudDrive2Target}}.kt`、
  SettingsScreen「网盘备份中心」入口、`openCloudBackup` 全链路参数；
  EdqiuDatabase v9→v10（DROP backup_ledger 表）
- `CloudSyncPreferences`：每网盘独立配置 `cfg_{pid}_*`（含密码 SecretBox 加密）+
  旧全局键一次性迁移 + `saveAccount` 显式保存 API（仅测试成功调用）
- `SettingsScreen`：切预设加载该网盘独立配置；删除 600ms 去抖自动落盘；
  测试成功保存/失败不保存；「同步情况详情」矩形框入口（connectionVerified 门禁内）
- `MediaBackup{Screen,ViewModel}`：重写为轻量对账页（本地 × PROPFIND 逐文件
  已同步/待上传/冲突，无引擎依赖；立即同步复用应用级运行器，完成自动重对账）
- `WebDavSyncService` / `WebDavAutoBackupWorker` / `PreDownloadManager`：凭证改读
  当前选中预设 `currentAccount()`；预下载联动改调 WebDavSyncService（幂等 HEAD 跳过）
- `WebDavProviderSelector`：城通地址模板 `https://dav.ctfile.com/ws<用户ID>`、
  123 模板改 per-user 域名（真机核对）
- 教训：`rm -rf backup/provider` 误删 WebDavEngine.kt——git checkout 原版 + 重放当日增强
  恢复（恢复时漏了 probe 函数导致一轮编译错，已补）；**批量删除目录前必须核对目录内文件清单**
