package com.ed.edqiu.backup.http

import com.ed.edqiu.BuildConfig
import android.util.Log
import com.ed.edqiu.backup.model.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * okhttp 单例封装：百度 / 阿里 JSON API 与流式上传（WebDAV 族沿用 HttpURLConnection）。
 *
 * - 超时统一：connect 20s / read 30s / write 30s（与现有 WebDavSyncService 一致）。
 * - 日志脱敏：不打印 Authorization / Cookie 等敏感头，URL 仅记录 path（去掉 query）。
 * - **频控退避**：HTTP 429 / 5xx（502/503/504 等瞬时故障）自动按响应头退避重试，
 *   优先读标准 `Retry-After`（秒），其次读阿里 `x-retry-after`（毫秒），
 *   均缺失则用默认退避。最多重试 [MAX_HTTP_RETRIES] 次，单次退避上限 [MAX_BACKOFF_MS]。
 *   这解决「百度 listall 超频 / 阿里 429 / 123 429 导致备份卡死」的问题。
 * - 所有方法返回 `Result<T>`，失败为面向用户的中文 [BackupException]。
 */
object HttpClient {

    private const val TAG = "BackupHttpClient"
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val READ_TIMEOUT_MS = 30_000L
    private const val WRITE_TIMEOUT_MS = 30_000L
    private const val USER_AGENT = "Edqiu-Backup/" + BuildConfig.VERSION_NAME + " (Android)"

    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

    /** 频控退避：最多重试次数（含首次请求在内共 MAX_HTTP_RETRIES + 1 次请求）。 */
    private const val MAX_HTTP_RETRIES = 3

    /** 可退避重试的状态码：429 限流 + 瞬时 5xx（网关/服务抖动）。 */
    private val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)

    /** 默认退避（响应头缺失时），毫秒。 */
    private const val DEFAULT_BACKOFF_MS = 1_000L

    /** 单次退避上限，防止服务端返回超大 Retry-After 把备份挂起。 */
    private const val MAX_BACKOFF_MS = 60_000L

    /** 敏感请求头，日志中一律跳过（token / 密码脱敏）。 */
    private val SENSITIVE_HEADERS = setOf("Authorization", "Cookie", "Set-Cookie", "X-Auth-Token")

    /** kotlinx-serialization 实例（忽略未知字段，供各 API 客户端复用）。 */
    val json: Json = Json { ignoreUnknownKeys = true }

    /** 全局共享 OkHttpClient。 */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(SensitiveLoggingInterceptor)
            .build()
    }

    /** GET 并解析 JSON。 */
    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching {
            executeForJson { buildRequest(url = url, headers = headers, method = "GET", body = null) }
        }
    }

    /** POST JSON 并解析响应 JSON。 */
    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<JsonElement> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = body.toRequestBody(JSON_MEDIA_TYPE.toMediaType())
            executeForJson { buildRequest(url = url, headers = headers, method = "POST", body = requestBody) }
        }
    }

    /** PUT 字节流，[progress] 按已写入字节数回调（用于分片上传 / 预签名 PUT）。返回 HTTP 状态码。 */
    suspend fun putStream(
        url: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
        progress: (Long) -> Unit = {},
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            executeWithBackoff {
                val body = ProgressRequestBody(bytes, progress)
                buildRequest(url = url, headers = headers, method = "PUT", body = body)
            }.use { response ->
                val code = response.code
                if (code !in 200..299) {
                    throw BackupException("上传失败（HTTP $code）")
                }
                code
            }
        }
    }

    /** HEAD 请求，返回 HTTP 状态码（不抛错，由调用方判断 404=不存在等语义）。 */
    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            executeWithBackoff { buildRequest(url = url, headers = headers, method = "HEAD", body = null) }
                .use { it.code }
        }
    }

    /** OPTIONS 请求，返回 HTTP 状态码（WebDAV 探测 / CD2 探测用，不抛错）。 */
    suspend fun options(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            executeWithBackoff { buildRequest(url = url, headers = headers, method = "OPTIONS", body = null) }
                .use { it.code }
        }
    }

    private suspend fun executeForJson(buildRequest: () -> Request): JsonElement {
        executeWithBackoff(buildRequest).use { response ->
            if (!response.isSuccessful) {
                throw BackupException("请求失败（HTTP ${response.code}）")
            }
            val text = response.body?.string() ?: "{}"
            return json.parseToJsonElement(text)
        }
    }

    /**
     * 带频控退避的请求执行。
     *
     * 遇到可重试状态码（429 / 5xx）时，读取响应头计算退避时长，关闭响应后 [delay] 再重试；
     * 最多重试 [MAX_HTTP_RETRIES] 次。返回最终的 [Response]（由调用方 `use` 关闭）。
     *
     * 注意：上传类请求（[putStream]）重试时会重新构造 body，progress 回调从 0 重新累计——
     * 分片 ≤ 10MB，影响可忽略；WebDAV 整文件 PUT 走 [WebDavEngine]（HttpURLConnection），不经过此处。
     */
    private suspend fun executeWithBackoff(buildRequest: () -> Request): Response {
        var attempt = 0
        while (true) {
            val response = client.newCall(buildRequest()).execute()
            val code = response.code
            if (code !in RETRYABLE_STATUS_CODES || attempt >= MAX_HTTP_RETRIES) {
                return response
            }
            val backoffMs = retryDelayMs(response)
            response.close()
            attempt++
            Log.w(TAG, "HTTP $code 触发频控退避，${backoffMs}ms 后第 $attempt/$MAX_HTTP_RETRIES 次重试")
            delay(backoffMs)
        }
    }

    /** 计算退避时长：优先 Retry-After（秒）→ x-retry-after（毫秒/秒）→ 默认值。 */
    private fun retryDelayMs(response: Response): Long {
        response.header("Retry-After")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toLongOrNull()?.let { seconds ->
                return (seconds * 1000L).coerceIn(0L, MAX_BACKOFF_MS)
            }
            // HTTP-date 格式（如 "Wed, 21 Oct 2026 07:28:00 GMT"）不解析，落到默认值
        }
        response.header("x-retry-after")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toLongOrNull()?.let { value ->
                // 阿里云盘 x-retry-after 为毫秒；部分服务为秒。>10_000 视为毫秒，否则视为秒。
                val ms = if (value > 10_000L) value else value * 1000L
                return ms.coerceIn(0L, MAX_BACKOFF_MS)
            }
        }
        return DEFAULT_BACKOFF_MS
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        method: String,
        body: RequestBody?,
    ): Request {
        val builder = Request.Builder().url(url)
        // GET / HEAD 允许 null body；其余方法补空 body 防止 okhttp 报错
        val requestBody = when {
            body != null -> body
            method == "GET" || method == "HEAD" || method == "OPTIONS" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        builder.method(method, requestBody)
        builder.header("User-Agent", USER_AGENT)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    /** 日志拦截器：脱敏敏感头、只记录 URL path、统计耗时。 */
    private object SensitiveLoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val start = System.currentTimeMillis()
            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - start
            val safeUrl = request.url.toString().substringBefore('?')
            val safeHeaders = request.headers
                .filterNot { SENSITIVE_HEADERS.contains(it.first) }
                .joinToString { "${it.first}=${it.second}" }
            Log.d(TAG, "${request.method} $safeUrl -> ${response.code} (${duration}ms) [$safeHeaders]")
            return response
        }
    }

    /** 按写入字节数上报进度的 RequestBody。 */
    private class ProgressRequestBody(
        private val bytes: ByteArray,
        private val progress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): okhttp3.MediaType? = null

        override fun contentLength(): Long = bytes.size.toLong()

        override fun writeTo(sink: BufferedSink) {
            var written = 0
            while (written < bytes.size) {
                val count = minOf(BUFFER_SIZE, bytes.size - written)
                sink.write(bytes, written, count)
                written += count
                progress(written.toLong())
            }
            if (bytes.isEmpty()) {
                progress(0L)
            }
        }

        private companion object {
            const val BUFFER_SIZE = 8 * 1024
        }
    }
}

/** 将底层异常转换为面向用户的中文消息（供各层复用，如 `exceptionOrNull()?.toUserMessage()`）。 */
fun Throwable.toUserMessage(): String = when (this) {
    is BackupException -> message ?: "操作失败，请重试"
    is UnknownHostException -> "无法连接服务器，请检查网络后重试"
    is SocketTimeoutException -> "网络请求超时，请检查网络后重试"
    // 2026-08-31 adb 实测：断网时 OkHttp 对 123pan WebDAV 表现为 SSLHandshakeException/EOFException
    //（TLS 握手阶段连接被断），原始 message 是英文（如 "connection closed"），对用户无意义——统一转成中文提示
    is java.net.ConnectException -> "无法连接服务器，请检查网络后重试"
    is javax.net.ssl.SSLException -> "安全连接失败，请检查网络后重试"
    is java.io.EOFException -> "网络连接被中断，请检查网络后重试"
    is java.io.IOException -> if (message?.contains("closed", ignoreCase = true) == true ||
        message?.contains("reset", ignoreCase = true) == true
    ) {
        "网络连接中断，请检查网络后重试"
    } else {
        "网络错误：${message ?: "未知错误"}"
    }
    else -> message ?: "操作失败，请重试"
}
