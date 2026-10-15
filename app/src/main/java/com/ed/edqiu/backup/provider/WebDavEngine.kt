package com.ed.edqiu.backup.provider

import android.util.Base64
import android.util.Log
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.backup.http.toUserMessage
import com.ed.edqiu.backup.model.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import com.ed.edqiu.backup.http.runCatchingNotCancelled

/**
 * WebDAV 核心引擎。
 *
 * 使用 OkHttp 替代 HttpURLConnection——后者拒绝 MKCOL 等 WebDAV 扩展方法
 * （Android 的 HttpURLConnection.setRequestMethod 仅允许标准 HTTP 方法）。
 *
 * 被以下模块复用：
 * - `WebDavSyncService`（「立即同步」双向同步薄门面）
 * - `WebDavAutoBackupWorker`（定时自动同步）
 * - `MediaBackupViewModel`（同步情况详情对账）
 *
 * 2026-10 统一 WebDAV 通道整改（借鉴成熟开源实现）：
 * - **鉴权**：默认 Basic 预授权（与 sardine-android 同策略）；服务器 401 挑战
 *   `WWW-Authenticate: Digest` 时自动降级 Digest（MD5 / MD5-sess，qop=auth 兼容）——
 *   群晖 / QNAP / nginx dav 模块等自建服务默认 Digest，仅 Basic 会直接报「认证失败」；
 * - **重试**：瞬态故障（网络 IO / 5xx / 429 / 408）指数退避重试 1s / 2s / 4s
 *   （rclone `--low-level-retries` 同款策略）；401/403/404 等配置性错误 fail-fast
 *   不重试（DAVx5 指数退避管理器的「永久错误」分类）；
 * - **断电续传**：[putResumable] 把整文件上传改为 `.edqiu-part` 暂存 + PATCH 追加
 *   续传探测 + 尺寸复核 + MOVE 原子落盘——断电/断网后重试只传剩余字节（服务器支持
 *   PATCH 追加时），且任何时刻云端都不会出现半截「成品」文件；服务器不支持
 *   PATCH/MOVE 时自动降级整传（旧行为）；
 * - **下载**：[get] 支持 HTTP Range 断点续传（206 续传 / 200 重下）；
 * - **目录列举**：[listDirectory] PROPFIND Depth:1（双向同步的远端清单来源）；
 * - **HTTPS**：自签名证书容忍开关（[allowInsecureTls]，NAS 自建场景），默认关闭走系统信任链。
 *
 * 传输层超时统一 connect 20s / read 30s / write 30s。
 * 所有方法返回 `Result<T>`，失败为面向用户的中文 [BackupException]，不含敏感字段（密码打码）。
 */
class WebDavEngine(
    /** 允许自签名/自建 CA 证书（容忍一切 TLS 证书），仅建议内网 NAS 使用；默认 false。 */
    private val allowInsecureTls: () -> Boolean = { false },
) {

    companion object {
        private const val TAG = "WebDavEngine"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val READ_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 30_000L
        private const val USER_AGENT = "Edqiu-WebDAV/1.0"
        private const val BUFFER_SIZE = 64 * 1024

        /** 断点续传暂存后缀：成品文件永不经由此形态出现（断电安全的关键）。 */
        const val PART_SUFFIX = ".edqiu-part"

        /** MKCOL 视为「目录已存在/创建成功」的状态码（宽容处理，与旧版一致）。 */
        private val MKCOL_OK_CODES = setOf(
            201,  // HTTP_CREATED
            200,  // HTTP_OK
            204,  // HTTP_NO_CONTENT
            405,  // HTTP_BAD_METHOD：目录已存在时部分服务器返回
        )

        /** 安全写入文件名前缀（去掉 query / 截断超长），用于日志不暴露敏感。 */
        private fun safeUrlForLog(url: String): String = url.take(160)
    }

    /** 标准客户端（系统信任链）。 */
    private val client: OkHttpClient by lazy { buildClient(trustAll = false) }

    /** 自签容忍客户端（仅当 allowInsecureTls() 为 true 时启用，懒加载）。 */
    private val insecureClient: OkHttpClient by lazy { buildClient(trustAll = true) }

    private fun currentClient(): OkHttpClient =
        if (runCatching { allowInsecureTls() }.getOrDefault(false)) insecureClient else client

    private fun buildClient(trustAll: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            // 连接池保活 90s（默认 5s）：批量同步时文件间不再反复 TLS 握手——
            // 「上一文件很快、下一个卡一下」的主要客源之一
            .connectionPool(okhttp3.ConnectionPool(5, 90_000L, TimeUnit.MILLISECONDS))
        if (trustAll) {
            val trustManager = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    /**
     * 拼接远程 URL：baseUrl 去尾部斜杠，path 按 `/` 分段逐段编码。
     *
     * @param baseUrl 服务器地址（如 `https://dav.example.com`）
     * @param path    相对路径（如 `Edqiu/foo.mp4`，允许带空格/中文）
     */
    fun buildUrl(baseUrl: String, path: String): String {
        val trimmedBase = baseUrl.trim().trimEnd('/')
        // 用户常漏写协议前缀：无 scheme 时默认按 HTTPS 处理，避免 OkHttp 报 "Expected URL scheme"
        val base = if (trimmedBase.startsWith("http://", ignoreCase = true) ||
            trimmedBase.startsWith("https://", ignoreCase = true)
        ) trimmedBase else "https://$trimmedBase"
        val cleanPath = path.trim('/').split('/').joinToString("/") { encodePathSegment(it) }
        return if (cleanPath.isBlank()) base else "$base/$cleanPath"
    }

    /**
     * 构建远程文件 URL：`serverUrl + "/" + remotePath(根目录) + "/" + fileName`。
     *
     * @param fileName 远程文件名（或相对根目录的子路径）
     */
    fun buildRemoteUrl(credential: WebDavCredential, fileName: String): String {
        val path = listOf(credential.remotePath, fileName)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return buildUrl(credential.serverUrl, path)
    }

    /** 对单个路径段做 URL 编码（空格转 `%20`，与旧版一致，兼容中文路径）。 */
    fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** 生成 Basic Auth 请求头；空账号密码也生成合法头（适配 CD2 匿名场景）。 */
    fun basicAuth(username: String, password: String): String {
        val token = "$username:$password"
        return "Basic " + Base64.encodeToString(token.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun buildRequest(
        url: String,
        method: String,
        credential: WebDavCredential,
        body: RequestBody? = null,
    ): Request {
        val effectiveBody = when {
            body != null -> body
            method == "GET" || method == "HEAD" || method == "OPTIONS" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        return Request.Builder()
            .url(url)
            .method(method, effectiveBody)
            .header("Authorization", basicAuth(credential.username, credential.password))
            .header("User-Agent", USER_AGENT)
            .build()
    }

    /**
     * 逐级创建远程目录（MKCOL），目录已存在不报错。
     * 例如 remotePath=`Edqiu/Sub` 会依次创建 `/Edqiu` 与 `/Edqiu/Sub`。
     */
    suspend fun ensureRemoteDirectories(credential: WebDavCredential): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val parts = credential.remotePath.split('/').filter { it.isNotBlank() }
            var current = ""
            parts.forEach { part ->
                current = if (current.isBlank()) part else "$current/$part"
                val url = buildUrl(credential.serverUrl, current)
                mkcolInternal(url, credential)
            }
            Unit
        }.toUserFriendly()
    }

    /** 创建单个远程目录（MKCOL），宽容处理「已存在」（405/200/204）。 */
    suspend fun mkcol(url: String, credential: WebDavCredential): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled { mkcolInternal(url, credential) }.toUserFriendly()
    }

    private fun mkcolInternal(url: String, credential: WebDavCredential) {
        val request = buildRequest(url, "MKCOL", credential)
        executeWithDigestFallback(request, credential).use { response ->
            val code = response.code
            if (code in MKCOL_OK_CODES) {
                if (code == 405) {
                    Log.i(TAG, "MKCOL 405（目录已存在）for ${safeUrlForLog(url)}")
                } else {
                    Log.i(TAG, "MKCOL OK $code for ${safeUrlForLog(url)}")
                }
                return
            }
            val body = runCatchingNotCancelled { response.body?.string()?.take(300) }.getOrNull()
            when {
                code == 401 || code == 403 -> {
                    Log.w(TAG, "MKCOL 鉴权失败 HTTP $code for ${safeUrlForLog(url)}")
                    throw BackupException("WebDAV 认证失败（HTTP $code），请检查账号与密码/应用密码")
                }
                code == 409 -> {
                    // 父目录缺失：按路径逐级创建时不应出现，出现说明服务器路径语义异常
                    Log.w(TAG, "MKCOL 父目录缺失 HTTP 409 for ${safeUrlForLog(url)}")
                    throw BackupException("WebDAV 创建目录失败：父目录不存在（HTTP 409），请检查远程目录设置")
                }
                code == 507 -> {
                    Log.w(TAG, "MKCOL 存储空间不足 HTTP 507 for ${safeUrlForLog(url)}")
                    throw BackupException("WebDAV 存储空间不足（HTTP 507）")
                }
                code in 500..599 -> {
                    Log.w(TAG, "MKCOL 服务端错误 HTTP $code for ${safeUrlForLog(url)} body=$body")
                    throw BackupException("WebDAV 服务端错误（HTTP $code），请稍后重试")
                }
                else -> {
                    // 其余非标准状态码：部分服务器对已存在目录返回 301 等变体，宽容放行但留痕
                    Log.w(TAG, "MKCOL 非标准 HTTP $code for ${safeUrlForLog(url)} body=$body（放行，由后续 PUT 兜底）")
                }
            }
        }
    }

    /**
     * HEAD 判断远程文件是否存在（布尔语义，供旧调用方）。
     * 只有 404 才视为「不存在」；5xx/其他 4xx 视为「状态未知」直接报错——
     * 服务端瞬时故障就可能用旧内容覆盖掉远端较新的文件。
     */
    suspend fun exists(
        url: String,
        credential: WebDavCredential,
        expectedSize: Long = -1L,
    ): Result<Boolean> = headState(url, credential, expectedSize).map { it != RemoteFileState.MISSING }

    /**
     * HEAD 三元状态判定（**单文件场景**；批量上传请改用一次 [listDirectory] 后内存比对——
     * 城通等网盘对高频 HEAD 会限流/返回异常状态，实测 349 文件连发 HEAD 触发
     * 「无法确认远程文件状态」）。
     *
     * - [RemoteFileState.MATCHES]：远端存在且（给了 expectedSize 时）尺寸一致 → 两端已同步；
     * - [RemoteFileState.MISSING]：404 → 远端缺失，可安全上传；
     * - [RemoteFileState.SIZE_DIFFERS]：远端存在但尺寸不一致 → **冲突**（可能是两端各自
     *   演化过，也可能是历史半截上传），调用方必须先保护一侧再写另一侧；
     * - 408/429/5xx 瞬态 → 指数退避重试至多 2 次（1s/2s）后仍失败才报「状态未知」；
     * - 401/403/405 等配置语义 → 立即报错（不重试）。
     */
    suspend fun headState(
        url: String,
        credential: WebDavCredential,
        expectedSize: Long = -1L,
    ): Result<RemoteFileState> = withContext(Dispatchers.IO) {
        var lastCode = -1
        repeat(3) { attempt ->
            if (attempt > 0) {
                val backoffMs = RETRY_BACKOFF_MS[(attempt - 1).coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
                Log.i(TAG, "HEAD 状态未知 HTTP $lastCode，退避 ${backoffMs}ms 后第 $attempt/2 次重试：${safeUrlForLog(url)}")
                delay(backoffMs)
            }
            val result = runCatchingNotCancelled {
                val request = buildRequest(url, "HEAD", credential)
                executeWithDigestFallback(request, credential).use { response ->
                    val code = response.code
                    lastCode = code
                    when {
                        code in 200..299 -> {
                            val remoteLength = response.header("Content-Length")?.toLongOrNull()
                            if (expectedSize > 0 && remoteLength != null && remoteLength != expectedSize) {
                                Log.i(TAG, "HEAD size mismatch (remote=$remoteLength, expected=$expectedSize) for ${safeUrlForLog(url)} → SIZE_DIFFERS")
                                RemoteFileState.SIZE_DIFFERS
                            } else {
                                RemoteFileState.MATCHES
                            }
                        }
                        code == 401 || code == 403 -> {
                            Log.w(TAG, "HEAD 鉴权失败 HTTP $code for ${safeUrlForLog(url)}")
                            throw BackupException("WebDAV 认证失败（HTTP $code），请检查账号与密码")
                        }
                        code == 404 -> RemoteFileState.MISSING
                        else -> {
                            Log.w(TAG, "HEAD non-2xx HTTP $code for ${safeUrlForLog(url)}（状态未知，拒绝写入以防覆盖）")
                            throw BackupException("无法确认远程文件状态（HTTP $code），已暂停上传以防覆盖远端文件")
                        }
                    }
                }
            }
            val error = result.exceptionOrNull()
            if (error == null) return@withContext result
            // 仅瞬态（408/429/5xx，包装为 WebDavTransferException）继续退避；其余立即返回
            val transient = error is WebDavTransferException && isRetryableTransferError(error)
            if (!transient) return@withContext result
        }
        Result.failure(BackupException("无法确认远程文件状态（HTTP $lastCode），已暂停写入以防覆盖远端文件，请稍后重试"))
    }

    /**
     * 连通性探测：OPTIONS 到服务器地址（设置页「测试连接」入口）。
     *
     * - 2xx/3xx → 服务可用且认证通过；
     * - 405 → 部分服务器不允许 OPTIONS，但能响应说明服务可达，视为可达；
     * - 401/403 → 认证失败（抛中文错误提示检查账号密码）；
     * - 其他 → 服务不可用。
     */
    suspend fun probe(url: String, credential: WebDavCredential): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val request = buildRequest(url, "OPTIONS", credential)
            executeWithDigestFallback(request, credential).use { response ->
                val code = response.code
                when {
                    code in 200..399 -> {
                        Log.i(TAG, "PROBE OK $code for ${safeUrlForLog(url)}")
                        true
                    }
                    code == 405 -> {
                        // 部分服务器不允许 OPTIONS，但能响应说明服务可达；认证留给后续 MKCOL/PUT 校验
                        Log.i(TAG, "PROBE 405（服务器不支持 OPTIONS，视为可达）for ${safeUrlForLog(url)}")
                        true
                    }
                    code == 401 || code == 403 -> {
                        Log.w(TAG, "PROBE 鉴权失败 HTTP $code for ${safeUrlForLog(url)}")
                        throw BackupException("WebDAV 认证失败（HTTP $code），请检查账号与密码")
                    }
                    else -> {
                        Log.w(TAG, "PROBE 服务不可用 HTTP $code for ${safeUrlForLog(url)}")
                        throw BackupException("WebDAV 服务不可用（HTTP $code）")
                    }
                }
            }
        }.toUserFriendly()
    }

    /**
     * PUT 整文件上传（单次尝试原语，断点续传编排见 [putResumable]）。
     *
     * @param url        远程完整 URL
     * @param file       本地文件
     * @param credential 连接凭证（用于 BasicAuth）
     * @param progress   按已写入字节数回调（0..文件大小），默认空实现
     */
    suspend fun put(
        url: String,
        file: File,
        credential: WebDavCredential,
        progress: (Long) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            require(file.isFile) { "本地文件不存在：${file.name}" }
            val size = file.length()
            val body = StreamingFileRequestBody(file, size, progress)
            val request = buildRequest(url, "PUT", credential, body)
            val startedAt = System.currentTimeMillis()
            executeWithDigestFallback(request, credential).use { response ->
                val code = response.code
                val elapsed = System.currentTimeMillis() - startedAt
                if (code !in 200..299) {
                    val body = runCatchingNotCancelled { response.body?.string()?.take(300) }.getOrNull()
                    Log.w(TAG, "PUT failed HTTP $code (size=$size B, ${elapsed}ms) for ${safeUrlForLog(url)} body=$body")
                    val reason = when (code) {
                        401, 403 -> "认证失败，请检查账号与密码"
                        404, 409 -> "远程目录不存在，请检查远程目录设置"
                        507 -> "存储空间不足"
                        in 500..599 -> "服务端错误，请稍后重试"
                        else -> "服务器返回 $code"
                    }
                    // 携带状态码：瞬态重试据此区分「瞬态可重试」与「配置错误 fail-fast」
                    throw WebDavTransferException(code, "WebDAV 上传失败（$reason）：${file.name}")
                }
                val speedKbps = if (elapsed > 0) (size * 8 / elapsed) else 0L
                Log.i(TAG, "PUT OK $code (size=$size B, ${elapsed}ms, ~${speedKbps} kbps) for ${safeUrlForLog(url)}")
                Unit
            }
        }.toUserFriendly()
    }

    /**
     * 断电续传整文件上传（2026-10 双向同步核心新增，>2GB 视频安全网）。
     *
     * 流程（借鉴 rclone 的 partial+rename 落盘模式 + dufs/lighttpd 的 PATCH 追加语义）：
     * 1. 全程只写 `目标.edqiu-part` 暂存文件——断电/断网任何时刻，云端都**不会**出现
     *    半截成品文件（旧直接 PUT 会被网盘客户端当完整视频播放）；
     * 2. **每一轮尝试都重新探测暂存现状**（2026-10 修复「89% 重头」）：PUT 中途网络闪断后，
     *    服务器往往已流式写入部分字节——重试先 HEAD 暂存实际大小，支持 PATCH 追加就只传
     *    剩余部分，进度从断点继续而非归零；
     * 3. 追加后 HEAD 复核总尺寸（不匹配即弃用半成品整传，杜绝脏数据固化）；
     * 4. 原子落盘：WebDAV MOVE 把暂存文件改名成成品（覆盖语义）；服务器不支持
     *    MOVE 时降级为整传到成品 URL（旧行为，靠 HEAD 尺寸校验兜底）；
     * 5. PATCH/MOVE 兼容性按引擎实例记忆（`patchUnsupported` / `moveUnsupported`），
     *    不支持的服务器后续文件直接走最快路径，不做无谓探测——**不支持 PATCH 的服务器
     *    （如城通实测）中断后无法续传只能整传，这是 WebDAV 协议限制，非本实现缺陷**。
     *
     * @param url 成品文件目标 URL
     * @param progress 按已写入总字节数回调（0..file.length()；续传时含已有偏移）
     */
    suspend fun putResumable(
        url: String,
        file: File,
        credential: WebDavCredential,
        maxAttempts: Int = 3,
        progress: (Long) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            require(file.isFile) { "本地文件不存在：${file.name}" }
            val size = file.length()
            val partUrl = "$url$PART_SUFFIX"

            // MOVE 兼容性已判负：直接整传成品 URL（旧语义，HEAD 尺寸校验兜底）
            if (moveUnsupported.get()) {
                putWithTransientRetry(url, file, credential, progress, maxAttempts).getOrThrow()
                return@runCatchingNotCancelled
            }

            var attempt = 0
            var lastError: BackupException? = null
            while (attempt < maxAttempts) {
                if (attempt > 0) {
                    val backoffMs = RETRY_BACKOFF_MS[(attempt - 1).coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
                    Log.i(TAG, "PUT 瞬态失败，退避 ${backoffMs}ms 后第 $attempt/${maxAttempts - 1} 次重试（先探测断点续传）：${file.name}")
                    delay(backoffMs)
                }
                when (val staged = stagePart(partUrl, file, size, credential, progress)) {
                    is StagedResult.Ok -> {
                        // 落盘前终检：暂存尺寸 ≠ 本地尺寸（续传/整传任何环节的意外）→ 拒绝固化
                        if (headLength(partUrl, credential) != size) {
                            throw BackupException("上传校验失败：远端暂存尺寸与本地不一致，已放弃落盘以防脏数据（${file.name}）")
                        }
                        // 原子落盘：MOVE 暂存 → 成品
                        val moved = move(partUrl, url, credential, overwrite = true)
                        if (moved.isSuccess) {
                            Log.i(TAG, "PUT Resumable DONE (size=$size B)：${file.name}")
                            return@runCatchingNotCancelled
                        }
                        // MOVE 不可用（405/501 等）：记忆后降级整传成品 URL
                        if (!moveUnsupported.getAndSet(true)) {
                            Log.w(TAG, "服务器不支持 MOVE（${moved.exceptionOrNull()?.message}），降级整传成品 URL")
                        }
                        putWithTransientRetry(url, file, credential, progress, maxAttempts).getOrThrow()
                        delete(partUrl, credential) // best-effort 清理暂存
                        return@runCatchingNotCancelled
                    }
                    is StagedResult.Paused -> throw staged.signal
                    is StagedResult.Fatal -> throw staged.error
                    is StagedResult.Retryable -> {
                        lastError = staged.error
                        attempt++
                    }
                }
            }
            throw lastError ?: BackupException("WebDAV 上传失败：${file.name}")
        }.toUserFriendly()
    }

    /**
     * 单轮暂存动作：探测断点 → PATCH 续传 / 整传 PUT → 尺寸复核。
     * 结果四态：完成 / 用户暂停 / 瞬态可重试（.part 尽量保留）/ 配置错误 fatal。
     */
    private suspend fun stagePart(
        partUrl: String,
        file: File,
        size: Long,
        credential: WebDavCredential,
        progress: (Long) -> Unit,
    ): StagedResult = withContext(Dispatchers.IO) {
        var partLen = headLength(partUrl, credential)
        if (partLen > size) {
            Log.w(TAG, "PART 大于成品（$partLen > $size），弃用：${safeUrlForLog(partUrl)}")
            delete(partUrl, credential)
            partLen = 0L
        }
        if (partLen == size && size > 0) {
            // 上轮已传完但未来得及 MOVE（断电/断网窗口）：零重传直接可落盘
            Log.i(TAG, "PART 已完整（$size B），跳过传输直接 MOVE：${file.name}")
            return@withContext StagedResult.Ok
        }
        if (partLen in 1 until size && !patchUnsupported.get()) {
            when (val outcome = patchAppend(partUrl, file, partLen, credential) { done ->
                progress(partLen + done) // 断点续传：进度从已有字节继续而非归零
            }) {
                is PatchOutcome.Resumed -> {
                    // 追加后必须复核：服务器若未按预期追加，绝不把脏半成品落成成品
                    val newLen = headLength(partUrl, credential)
                    return@withContext if (newLen == size) {
                        Log.i(TAG, "PATCH 续传成功（$partLen B → $newLen B）：${file.name}")
                        StagedResult.Ok
                    } else {
                        Log.w(TAG, "PATCH 追加后尺寸不符（$newLen ≠ $size），弃用半成品整传：${file.name}")
                        delete(partUrl, credential)
                        StagedResult.Retryable(BackupException("PATCH 追加后尺寸不符，已弃用半成品整传（${file.name}）"))
                    }
                }
                is PatchOutcome.Unsupported -> {
                    if (!patchUnsupported.getAndSet(true)) {
                        Log.i(TAG, "服务器不支持 PATCH 追加（全变体非 2xx），本会话降级整传+MOVE（中断后无法续传）")
                    }
                    // fall-through：改用整传 PUT
                }
                is PatchOutcome.Failed -> {
                    // 网络失败：.part 保留（断点仍在），下一轮重试重新探测续传
                    Log.w(TAG, "PATCH 续传网络失败，保留半成品待下轮探测：${file.name}", outcome.error)
                    return@withContext StagedResult.Retryable(
                        BackupException(outcome.error.message ?: "PATCH 续传网络失败", outcome.error)
                    )
                }
            }
        }
        // 整传 PUT（partLen==0 / PATCH 被禁 / Unsupported 落到这里）
        val result = put(partUrl, file, credential, progress)
        val error = result.exceptionOrNull() ?: return@withContext StagedResult.Ok
        when {
            WebDavSyncPausedSignal.inChain(error) -> StagedResult.Paused(WebDavSyncPausedSignal())
            isRetryableTransferError(error) -> StagedResult.Retryable(BackupException(error.toUserMessage(), error))
            else -> StagedResult.Fatal(BackupException(error.toUserMessage(), error))
        }
    }

    private sealed interface StagedResult {
        /** 暂存已完成（尺寸已复核一致），可进入终检 + MOVE。 */
        data object Ok : StagedResult

        /** 用户主动暂停（.part 保留）。 */
        data class Paused(val signal: WebDavSyncPausedSignal) : StagedResult

        /** 瞬态失败（网络/5xx），可退避重试。 */
        data class Retryable(val error: BackupException) : StagedResult

        /** 配置性错误（认证/目录/容量），重试无意义。 */
        data class Fatal(val error: BackupException) : StagedResult
    }

    /** PATCH / MOVE 兼容性记忆（引擎实例级）。 */
    private val patchUnsupported = java.util.concurrent.atomic.AtomicBoolean(false)
    private val moveUnsupported = java.util.concurrent.atomic.AtomicBoolean(false)

    /** PUT + 瞬态退避重试（rclone low-level-retry 同款；供 [putResumable] 内部使用）。 */
    private suspend fun putWithTransientRetry(
        url: String,
        file: File,
        credential: WebDavCredential,
        progress: (Long) -> Unit,
        maxAttempts: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) {
                val backoffMs = RETRY_BACKOFF_MS[(attempt - 1).coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
                Log.i(TAG, "PUT 瞬态失败退避 ${backoffMs}ms 后第 $attempt/${maxAttempts - 1} 次重试：${file.name}")
                delay(backoffMs)
            }
            val result = put(url, file, credential, progress)
            val error = result.exceptionOrNull()
            if (error == null) return@withContext Result.success(Unit)
            lastError = error
            if (!isRetryableTransferError(error)) return@withContext result // 配置性错误：fail-fast
        }
        Result.failure(lastError ?: BackupException("WebDAV 上传失败：${file.name}"))
    }

    /** HEAD 取远端 Content-Length；404/5xx/任何异常按 0 处理（无续传基础，宁可整传）。 */
    private suspend fun headLength(url: String, credential: WebDavCredential): Long = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val request = buildRequest(url, "HEAD", credential)
            executeWithDigestFallback(request, credential).use { response ->
                if (response.code in 200..299) {
                    response.header("Content-Length")?.toLongOrNull() ?: 0L
                } else {
                    0L
                }
            }
        }.getOrDefault(0L)
    }

    /**
     * PATCH 追加续传：把 file 的 [fromOffset..末尾] 字节追加到远端 part 资源。
     *
     * 双头探测：先发标准 Content-Range 头（bytes a-b，总长未知占位符省略），
     * 非 2xx 再试 dufs 风格 X-Update-Range: append（各只试一次，兼容性结果由调用方记忆）。
     * 追加结果**不做正确性承诺**（部分服务器语义不明），调用方必须 HEAD 复核尺寸。
     */
    private suspend fun patchAppend(
        url: String,
        file: File,
        fromOffset: Long,
        credential: WebDavCredential,
        progress: (Long) -> Unit,
    ): PatchOutcome = withContext(Dispatchers.IO) {
        val size = file.length()
        val remaining = size - fromOffset
        if (remaining <= 0) return@withContext PatchOutcome.Failed(BackupException("无待续传字节"))
        val variants = listOf(
            arrayOf("Content-Range" to "bytes $fromOffset-${size - 1}/*"),
            arrayOf("X-Update-Range" to "append"),
        )
        for ((index, headers) in variants.withIndex()) {
            val offsetBase = if (index == 0) fromOffset else 0L // 第二种变体语义未验证，进度从 0 计
            val request = Request.Builder()
                .url(url)
                .method("PATCH", RangeFileRequestBody(file, fromOffset, remaining) { done ->
                    progress(offsetBase + done)
                })
                .header("Content-Type", "application/offset+octet-stream")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("Authorization", basicAuth(credential.username, credential.password))
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                executeWithDigestFallback(request, credential).use { response ->
                    if (response.code in 200..299) {
                        return@withContext PatchOutcome.Resumed(
                            response.header("Content-Length")?.toLongOrNull() ?: -1L
                        )
                    }
                    // 非 2xx：本变体不兼容，试下一变体
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withContext PatchOutcome.Failed(BackupException(error.toUserMessage(), error))
            }
        }
        PatchOutcome.Unsupported
    }

    private sealed interface PatchOutcome {
        /** 追加请求已被服务器接受（尺寸仍需调用方复核）。 */
        data class Resumed(val remoteLength: Long) : PatchOutcome

        /** 服务器明确不支持 PATCH 追加（全变体非 2xx），调用方应记忆并整传。 */
        data object Unsupported : PatchOutcome

        /** 网络层失败（可重试场景）。 */
        data class Failed(val error: BackupException) : PatchOutcome
    }

    /**
     * GET 流式下载（HTTP Range 断点续传）。
     *
     * `targetFile` 已有内容时发 `Range: bytes=N-`：206 → 追加续传；200 → 服务器不支持
     * Range，清零重下。进度按累计总字节回调（0..远端总长）。
     * 调用方应写 `.part` 本地暂存名，成功后自行 rename 成品（防本地半截文件）。
     */
    suspend fun get(
        url: String,
        credential: WebDavCredential,
        targetFile: File,
        progress: (Long) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            targetFile.parentFile?.mkdirs()
            val resumeFrom = if (targetFile.exists() && targetFile.isFile) targetFile.length() else 0L
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", basicAuth(credential.username, credential.password))
                .header("User-Agent", USER_AGENT)
            if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")
            executeWithDigestFallback(builder.build(), credential).use { response ->
                val appendFrom = when {
                    response.code == 206 -> resumeFrom
                    response.code in 200..299 -> 0L
                    response.code == 401 || response.code == 403 ->
                        throw BackupException("WebDAV 认证失败（HTTP ${response.code}），请检查账号与密码")
                    response.code == 404 -> throw BackupException("远程文件不存在：${targetFile.name}")
                    else -> throw BackupException("WebDAV 下载失败（HTTP ${response.code}）")
                }
                var written = appendFrom
                response.body?.byteStream()?.use { input ->
                    java.io.RandomAccessFile(targetFile, "rw").use { raf ->
                        if (appendFrom == 0L) raf.setLength(0)
                        raf.seek(appendFrom)
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            raf.write(buffer, 0, n)
                            written += n
                            progress(written)
                        }
                    }
                } ?: throw BackupException("WebDAV 下载失败：响应体为空")
                Log.i(TAG, "GET OK (from=$appendFrom, total=$written B) for ${safeUrlForLog(url)}")
            }
            Unit
        }.toUserFriendly()
    }

    /**
     * PROPFIND 列目录（Depth:1），双向同步的远端清单来源。
     * 207 解析多状态 XML；404 → 空目录；401/403 → 认证错误；其他 → 列举失败。
     */
    suspend fun listDirectory(
        dirUrl: String,
        credential: WebDavCredential,
    ): Result<List<WebDavRemoteFile>> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>"
            val request = Request.Builder()
                .url(dirUrl)
                .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .header("Authorization", basicAuth(credential.username, credential.password))
                .header("User-Agent", USER_AGENT)
                .build()
            executeWithDigestFallback(request, credential).use { response ->
                when {
                    response.code == 207 -> parseMultiStatus(response.body?.string().orEmpty())
                    response.code == 404 -> emptyList()
                    response.code == 401 || response.code == 403 ->
                        throw BackupException("WebDAV 认证失败（HTTP ${response.code}），请检查账号与密码")
                    else -> throw BackupException("WebDAV 列目录失败（HTTP ${response.code}）")
                }
            }
        }.toUserFriendly()
    }

    /** MOVE 移动/重命名（Destination 传绝对 URL；overwrite=false 用于冲突改名占位）。 */
    suspend fun move(
        srcUrl: String,
        destUrl: String,
        credential: WebDavCredential,
        overwrite: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val request = Request.Builder()
                .url(srcUrl)
                .method("MOVE", ByteArray(0).toRequestBody(null))
                .header("Destination", destUrl)
                .header("Overwrite", if (overwrite) "T" else "F")
                .header("Authorization", basicAuth(credential.username, credential.password))
                .header("User-Agent", USER_AGENT)
                .build()
            executeWithDigestFallback(request, credential).use { response ->
                when {
                    response.code in 200..299 -> Unit
                    response.code == 412 ->
                        throw BackupException("WebDAV 移动失败：目标已存在且禁止覆盖（HTTP 412）")
                    response.code == 405 || response.code == 501 ->
                        throw BackupException("WebDAV 服务器不支持 MOVE（HTTP ${response.code}）")
                    response.code == 401 || response.code == 403 ->
                        throw BackupException("WebDAV 认证失败（HTTP ${response.code}），请检查账号与密码")
                    else -> throw BackupException("WebDAV 移动失败（HTTP ${response.code}）")
                }
            }
        }.toUserFriendly()
    }

    /** DELETE 删除远端资源（404 视为成功；不可用时抛错，调用方 best-effort 消费）。 */
    suspend fun delete(url: String, credential: WebDavCredential): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val request = buildRequest(url, "DELETE", credential)
            executeWithDigestFallback(request, credential).use { response ->
                when {
                    response.code in 200..299 -> Unit
                    response.code == 404 -> Unit // 已不存在 = 删除目标达成
                    response.code == 401 || response.code == 403 ->
                        throw BackupException("WebDAV 认证失败（HTTP ${response.code}）")
                    else -> throw BackupException("WebDAV 删除失败（HTTP ${response.code}）")
                }
            }
        }.toUserFriendly()
    }

    /** 解析 PROPFIND 207 multistatus（命名空间无关，仅匹配 localName）。 */
    private fun parseMultiStatus(xml: String): List<WebDavRemoteFile> {
        val result = mutableListOf<WebDavRemoteFile>()
        runCatchingNotCancelled {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(java.io.StringReader(xml))
            var inResponse = false
            var href: String? = null
            var size = -1L
            var mtime = 0L
            var isDir = false
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "response" -> {
                            inResponse = true; href = null; size = -1L; mtime = 0L; isDir = false
                        }
                        "href" -> if (inResponse) href = parser.nextText()
                        "getcontentlength" -> if (inResponse) size = parser.nextText().trim().toLongOrNull() ?: -1L
                        "getlastmodified" -> if (inResponse) mtime = parseHttpDate(parser.nextText())
                        "collection" -> if (inResponse) isDir = true
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                        "response" -> {
                            inResponse = false
                            val name = href?.let { remoteNameFromHref(it) }
                            if (!isDir && !name.isNullOrBlank() && !name.endsWith(PART_SUFFIX)) {
                                result += WebDavRemoteFile(name, size, mtime, isDir)
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }.onFailure { error ->
            Log.w(TAG, "PROPFIND XML 解析失败（按空目录处理）：${error.message}")
        }
        return result
    }

    /** 从 DAV:href 提取文件名（兼容绝对 URL 与相对路径，URL 解码）。 */
    private fun remoteNameFromHref(href: String): String {
        val clean = href.substringBefore('?').trimEnd('/')
        return android.net.Uri.decode(clean.substringAfterLast('/'))
    }

    /** RFC 1123 日期（Last-Modified）解析；失败返回 0。 */
    private fun parseHttpDate(value: String): Long = runCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .parse(value.trim())?.time ?: 0L
    }.getOrDefault(0L)

    /** 瞬态退避序列（毫秒），rclone low-level-retry 同量级。 */
    private val RETRY_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)

    /** 判定是否瞬态可重试错误：网络 IO / 408 请求超时 / 429 限流 / 5xx 服务端抖动。 */
    private fun isRetryableTransferError(error: Throwable): Boolean = when {
        error is WebDavTransferException ->
            error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        error.cause is java.io.IOException -> true      // 网络抖动（toUserMessage 包装后的 BackupException）
        error is java.io.IOException -> true
        else -> false
    }

    /**
     * 统一执行入口：先按 Basic 预授权发请求；若服务器 401 且挑战
     * `WWW-Authenticate: Digest`，则计算 Digest Authorization 重放一次。
     *
     * 借鉴 sardine-android / okhttp-digest 的「Basic 预授权 + Digest 应答」双通道：
     * 群晖 / QNAP / nginx ngx_dav / lighttpd 等自建 WebDAV 默认 Digest，
     * 旧实现只有 Basic，probe 直接报「认证失败」但账号密码其实正确。
     * 非 Digest 挑战（或挑战参数不全无法构造应答）时维持原语义返回 401 响应。
     */
    private fun executeWithDigestFallback(request: Request, credential: WebDavCredential): okhttp3.Response {
        val httpClient = currentClient()
        val response = httpClient.newCall(request).execute()
        if (response.code != 401) return response
        val challengeHeader = response.header("WWW-Authenticate") ?: return response
        if (!challengeHeader.startsWith("Digest", ignoreCase = true)) return response
        val challenge = parseDigestChallenge(challengeHeader) ?: run {
            Log.w(TAG, "Digest 挑战参数解析失败，维持 401 语义：${challengeHeader.take(120)}")
            return response
        }
        // 消费 body 释放连接供重放复用（keep-alive）
        runCatchingNotCancelled { response.body?.string() }
        response.close()
        val digestValue = digestAuthorization(challenge, request.url, request.method, credential)
        if (digestValue == null) {
            Log.w(TAG, "Digest 挑战不支持（算法/qop 不兼容），按原请求重放")
            return httpClient.newCall(request).execute()
        }
        Log.i(TAG, "Basic 预授权 401 → Digest 应答重放（realm=${challenge["realm"]?.take(48)}）")
        val retryRequest = request.newBuilder()
            .header("Authorization", digestValue)
            .build()
        return httpClient.newCall(retryRequest).execute()
    }

    // ================= Digest 鉴权（RFC 2617/7616 MD5 族） =================

    /** 解析 `WWW-Authenticate: Digest k="v", k=v` 挑战为参数表；结构异常返回 null。 */
    private fun parseDigestChallenge(header: String): Map<String, String>? {
        val body = header.substringAfter(':', "").trim()
        if (body.isBlank()) return null
        val params = mutableMapOf<String, String>()
        // 逐段扫描：key=value 或 key="value"（值内允许转义引号）
        val regex = Regex("(\\w+)=(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,\\s]+))")
        regex.findAll(body).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = (match.groupValues[2].ifEmpty { match.groupValues[3] }).replace("\\\"", "\"")
            params[key] = value
        }
        return if (params.containsKey("nonce")) params else null
    }

    /**
     * 构造 Digest Authorization 头。
     * 支持 algorithm=MD5（默认）/ MD5-sess，qop=auth 或无 qop；其余算法返回 null 交给上层维持 401。
     */
    private fun digestAuthorization(
        challenge: Map<String, String>,
        url: okhttp3.HttpUrl,
        method: String,
        credential: WebDavCredential,
    ): String? {
        val realm = challenge["realm"] ?: return null
        val nonce = challenge["nonce"] ?: return null
        val qop = challenge["qop"]?.split(',')?.map { it.trim().lowercase() }
        // 只实现 qop=auth（auth-int 需要读 body hash，WebDAV 大文件场景不现实）
        if (qop != null && "auth" !in qop) return null
        val algorithm = challenge["algorithm"]?.lowercase() ?: "md5"
        if (algorithm != "md5" && algorithm != "md5-sess") return null

        val cnonce = newCnonce()
        val nc = "00000001"
        val uri = url.encodedPath + (url.query?.let { "?$it" } ?: "")
        val username = credential.username
        val password = credential.password

        val ha1Base = md5Hex("$username:$realm:$password")
        val ha1 = if (algorithm == "md5-sess") md5Hex("$ha1Base:$nonce:$cnonce") else ha1Base
        val ha2 = md5Hex("$method:$uri")
        val response = if (qop != null) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }

        return buildString {
            append("Digest username=\"").append(escapeDigest(username)).append("\", ")
            append("realm=\"").append(escapeDigest(realm)).append("\", ")
            append("nonce=\"").append(escapeDigest(nonce)).append("\", ")
            append("uri=\"").append(uri).append("\", ")
            append("response=\"").append(response).append('"')
            challenge["opaque"]?.let { append(", opaque=\"").append(escapeDigest(it)).append('"') }
            if (qop != null) {
                append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
            }
            if (algorithm != "md5") append(", algorithm=").append(challenge["algorithm"])
        }
    }

    /** Digest 值转义：引号与反斜杠（RFC 7616 quoted-string 规则）。 */
    private fun escapeDigest(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun newCnonce(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 流式文件上传 RequestBody，按写入字节数回调进度。 */
    private class StreamingFileRequestBody(
        private val file: File,
        private val size: Long,
        private val progress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = MediaFileTypes.mimeTypeForExtension(file.extension).toMediaType()
        override fun contentLength(): Long = size
        override fun writeTo(sink: BufferedSink) {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    progress(written)
                }
            }
        }
    }

    /** 区间流式 RequestBody：只发送 file 的 [offset, offset+length) 区间（PATCH 追加续传用）。 */
    private class RangeFileRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val progress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = "application/offset+octet-stream".toMediaType()
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                var remaining = length
                while (remaining > 0) {
                    val read = raf.read(buffer, 0, buffer.size.toLong().coerceAtMost(remaining).toInt())
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    remaining -= read
                    progress(written)
                }
            }
        }
    }
}

/**
 * WebDAV 连接凭证（与具体网盘无关）。
 *
 * @property serverUrl WebDAV 服务器地址（如 `https://dav.example.com`）
 * @property username  账号（CD2 可留空）
 * @property password  密码 / WebDAV 应用密码（敏感，日志中一律脱敏）
 * @property remotePath 远程根目录（相对服务器根，如 `Edqiu`），上传文件都放在该目录下
 */
data class WebDavCredential(
    val serverUrl: String,
    val username: String = "",
    val password: String = "",
    val remotePath: String = "Edqiu",
)

/**
 * 携带 HTTP 状态码的 WebDAV 传输失败异常。
 *
 * 消息仍为面向用户的中文（继承 [BackupException] 契约）；状态码供
 * [WebDavEngine] 瞬态重试区分「瞬态可重试（408/429/5xx）」与
 * 「配置错误 fail-fast（401/403/404/507 等）」。
 */
class WebDavTransferException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : BackupException(message, cause)

/**
 * 用户主动暂停的同步中断信号（进度回调内抛出，打断正在进行的 PUT）。
 *
 * 不是错误：`.edqiu-part` 暂存保留，恢复后从断点续传；瞬态重试与对账
 * 捕获链通过类型识别它（异常链任一层为本类型即视为暂停），不计入失败。
 */
class WebDavSyncPausedSignal : BackupException("用户已暂停该文件同步") {

    /** 是否在异常链中携带本信号（OkHttp/okio 可能层层包裹）。 */
    companion object {
        fun inChain(error: Throwable?): Boolean =
            generateSequence(error) { it.cause }.any { it is WebDavSyncPausedSignal }
    }
}

/** PROPFIND 列举出的远端目录条目（双向同步下载阶段的数据源）。 */
data class WebDavRemoteFile(
    /** 文件名（已 URL 解码，不含路径）。 */
    val name: String,
    /** 字节大小；服务器未返回时为 -1。 */
    val size: Long,
    /** 最后修改时间（epoch ms）；解析失败为 0。 */
    val lastModified: Long,
    /** 是否目录（目录条目不参与同步）。 */
    val isDir: Boolean,
)

/** HEAD 三元状态：两端一致 / 远端缺失 / 尺寸冲突（本地与远端各自演化过）。 */
enum class RemoteFileState {
    MATCHES,
    MISSING,
    SIZE_DIFFERS,
}

/** 将底层异常统一包装为面向用户的中文 [BackupException]（已是 BackupException 则原样返回）。 */
private fun <T> Result<T>.toUserFriendly(): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { error ->
        if (error is BackupException) {
            Result.failure(error)
        } else {
            Result.failure(BackupException(error.toUserMessage(), error))
        }
    },
)
