package com.ed.edqiu.backup.provider

import android.util.Base64
import android.util.Log
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.backup.http.toUserMessage
import com.ed.edqiu.backup.model.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WebDAV 核心引擎。
 *
 * 使用 OkHttp 替代 HttpURLConnection——后者拒绝 MKCOL 等 WebDAV 扩展方法
 * （Android 的 HttpURLConnection.setRequestMethod 仅允许标准 HTTP 方法）。
 *
 * 被以下模块复用：
 * - [WebDavBackupTarget]（自定义 WebDAV，高级）
 * - [Pan123Target]（123 网盘）
 * - [CloudDrive2Target]（CloudDrive2 本机 WebDAV）
 * - `WebDavSyncService`（旧版「立即同步」薄门面）
 *
 * 传输层超时统一 connect 20s / read 30s / write 30s。
 * 所有方法返回 `Result<T>`，失败为面向用户的中文 [BackupException]，不含敏感字段（密码打码）。
 */
class WebDavEngine {

    companion object {
        private const val TAG = "WebDavEngine"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val READ_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 30_000L
        private const val USER_AGENT = "Edqiu-WebDAV/1.0"
        private const val BUFFER_SIZE = 64 * 1024

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

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
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
     * 与旧版 `WebDavSyncService.buildRemoteUrl` 语义一致：文件始终放在配置的根目录下。
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
        runCatching {
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
        runCatching { mkcolInternal(url, credential) }.toUserFriendly()
    }

    private fun mkcolInternal(url: String, credential: WebDavCredential) {
        val request = buildRequest(url, "MKCOL", credential)
        client.newCall(request).execute().use { response ->
            val code = response.code
            if (code in MKCOL_OK_CODES) {
                if (code == 405) {
                    Log.i(TAG, "MKCOL 405（目录已存在）for ${safeUrlForLog(url)}")
                } else {
                    Log.i(TAG, "MKCOL OK $code for ${safeUrlForLog(url)}")
                }
                return
            }
            val body = runCatching { response.body?.string()?.take(300) }.getOrNull()
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

    /** HEAD 判断远程文件是否存在（2xx 视为存在；404 等视为不存在；401/403 抛认证错误）。 */
    suspend fun exists(url: String, credential: WebDavCredential): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val request = buildRequest(url, "HEAD", credential)
            client.newCall(request).execute().use { response ->
                val code = response.code
                when {
                    code in 200..299 -> true
                    code == 401 || code == 403 -> {
                        Log.w(TAG, "HEAD 鉴权失败 HTTP $code for ${safeUrlForLog(url)}")
                        throw BackupException("WebDAV 认证失败（HTTP $code），请检查账号与密码")
                    }
                    else -> {
                        // 404 → 文件不存在；3xx 跟随重定向后仍非 2xx 归不存在；其余 4xx/5xx 按不存在处理
                        Log.i(TAG, "HEAD non-2xx HTTP $code for ${safeUrlForLog(url)}（按不存在处理）")
                        false
                    }
                }
            }
        }.toUserFriendly()
    }

    /**
     * PUT 整文件上传（WebDAV 族不分片）。
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
        runCatching {
            require(file.isFile) { "本地文件不存在：${file.name}" }
            val size = file.length()
            val body = StreamingFileRequestBody(file, size, progress)
            val request = buildRequest(url, "PUT", credential, body)
            val startedAt = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val code = response.code
                val elapsed = System.currentTimeMillis() - startedAt
                if (code !in 200..299) {
                    val body = runCatching { response.body?.string()?.take(300) }.getOrNull()
                    Log.w(TAG, "PUT failed HTTP $code (size=$size B, ${elapsed}ms) for ${safeUrlForLog(url)} body=$body")
                    val reason = when (code) {
                        401, 403 -> "认证失败，请检查账号与密码"
                        404, 409 -> "远程目录不存在，请检查远程目录设置"
                        507 -> "存储空间不足"
                        in 500..599 -> "服务端错误，请稍后重试"
                        else -> "服务器返回 $code"
                    }
                    throw BackupException("WebDAV 上传失败（$reason）：${file.name}")
                }
                val speedKbps = if (elapsed > 0) (size * 8 / elapsed) else 0L
                Log.i(TAG, "PUT OK $code (size=$size B, ${elapsed}ms, ~${speedKbps} kbps) for ${safeUrlForLog(url)}")
                Unit
            }
        }.toUserFriendly()
    }

    /**
     * 连通性探测：OPTIONS 到服务器地址。
     *
     * - 2xx/3xx → 服务可用且认证通过；
     * - 401/403 → 认证失败（抛中文错误提示检查账号密码）；
     * - 其他 → 服务不可用。
     */
    suspend fun probe(url: String, credential: WebDavCredential): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val request = buildRequest(url, "OPTIONS", credential)
            client.newCall(request).execute().use { response ->
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
