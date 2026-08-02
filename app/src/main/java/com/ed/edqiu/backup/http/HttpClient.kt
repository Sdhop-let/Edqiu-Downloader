package com.ed.edqiu.backup.http

import android.util.Log
import com.ed.edqiu.backup.model.BackupException
import kotlinx.coroutines.Dispatchers
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
 * - 所有方法返回 `Result<T>`，失败为面向用户的中文 [BackupException]。
 */
object HttpClient {

    private const val TAG = "BackupHttpClient"
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val READ_TIMEOUT_MS = 30_000L
    private const val WRITE_TIMEOUT_MS = 30_000L
    private const val USER_AGENT = "Edqiu-Backup/1.2.0 (Android)"

    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

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
            val request = buildRequest(url = url, headers = headers, method = "GET", body = null)
            executeForJson(request)
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
            val request = buildRequest(url = url, headers = headers, method = "POST", body = requestBody)
            executeForJson(request)
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
            val body = ProgressRequestBody(bytes, progress)
            val request = buildRequest(url = url, headers = headers, method = "PUT", body = body)
            client.newCall(request).execute().use { response ->
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
            val request = buildRequest(url = url, headers = headers, method = "HEAD", body = null)
            client.newCall(request).execute().use { it.code }
        }
    }

    /** OPTIONS 请求，返回 HTTP 状态码（WebDAV 探测 / CD2 探测用，不抛错）。 */
    suspend fun options(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val request = buildRequest(url = url, headers = headers, method = "OPTIONS", body = null)
            client.newCall(request).execute().use { it.code }
        }
    }

    private fun executeForJson(request: Request): JsonElement {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw BackupException("请求失败（HTTP ${response.code}）")
            }
            val text = response.body?.string() ?: "{}"
            return json.parseToJsonElement(text)
        }
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
    is SocketTimeoutException -> "网络请求超时，请检查网络后重试"
    is UnknownHostException -> "无法连接服务器，请检查网络后重试"
    is IOException -> "网络错误：${message ?: "未知错误"}"
    else -> message ?: "操作失败，请重试"
}
