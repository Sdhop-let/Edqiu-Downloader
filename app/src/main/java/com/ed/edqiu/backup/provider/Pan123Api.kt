package com.ed.edqiu.backup.provider

import android.util.Log
import com.ed.edqiu.backup.http.HttpClient
import com.ed.edqiu.backup.http.toUserMessage
import com.ed.edqiu.backup.model.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 123 网盘开放平台 OpenAPI 客户端（open-api.123pan.com）。
 *
 * 纯 HTTP + JSON 编解码，不持有任何凭证状态；token 获取 / 刷新 / 持久化由 [Pan123OpenTarget] 编排。
 *
 * 覆盖能力：
 * - **OAuth 2.0 授权码**（[buildAuthorizeUrl] / [exchangeCode] / [refreshToken]）：
 *   用户登录授权 → 授权码换 access_token + refresh_token（refresh 单次有效，约 90 天）。
 * - **分片上传 + 秒传**（[createFile] / [uploadSlice] / [uploadComplete]）：
 *   etag（文件 md5）命中即秒传（reuse=true），否则按 sliceSize 分片串行上传后合并。
 * - **目录 / 列表**（[mkdir] / [listFiles]）：确保备份目录存在与 exists 判断。
 * - **用户信息**（[userInfo]）：testConnection 校验授权有效性。
 *
 * 公共请求头：`Authorization: Bearer <token>` + `Platform: open_platform`。
 * 业务响应统一 `{ code, message, data }`；code=0 成功，code=429 限流（此处做退避重试）。
 */
class Pan123Api {

    /** 构造授权页 URL（OAuth 授权码流程第一步，官方授权地址 yun.123pan.com/auth）。 */
    fun buildAuthorizeUrl(clientId: String, redirectUri: String, state: String): String {
        return HttpUrl.Builder()
            .scheme("https")
            .host(AUTHORIZE_HOST)
            .addPathSegments("auth")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", DEFAULT_SCOPE)
            .addQueryParameter("state", state)
            .build()
            .toString()
    }

    /** 授权码换取 access_token + refresh_token。 */
    suspend fun exchangeCode(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
    ): Result<Pan123TokenBundle> = withContext(Dispatchers.IO) {
        runCatching {
            require(clientId.isNotBlank()) { "client_id 不能为空" }
            require(code.isNotBlank()) { "授权码不能为空" }
            val url = accessTokenUrl(
                clientId = clientId,
                clientSecret = clientSecret,
                grantType = "authorization_code",
                code = code,
                refreshToken = null,
                redirectUri = redirectUri,
            )
            val json = executeForJson {
                Request.Builder().url(url)
                    .post(ByteArray(0).toRequestBody(null))
                    .header(HEADER_PLATFORM, PLATFORM_VALUE)
                    .build()
            }
            parseTokenBundle(json)
        }.mapError("换取 123 网盘登录凭证")
    }

    /** 用 refresh_token 刷新 access_token（返回新的 refresh_token，单次有效）。 */
    suspend fun refreshToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String,
    ): Result<Pan123TokenBundle> = withContext(Dispatchers.IO) {
        runCatching {
            require(refreshToken.isNotBlank()) { "refresh_token 不能为空" }
            val url = accessTokenUrl(
                clientId = clientId,
                clientSecret = clientSecret,
                grantType = "refresh_token",
                code = null,
                refreshToken = refreshToken,
                redirectUri = null,
            )
            val json = executeForJson {
                Request.Builder().url(url)
                    .post(ByteArray(0).toRequestBody(null))
                    .header(HEADER_PLATFORM, PLATFORM_VALUE)
                    .build()
            }
            parseTokenBundle(json)
        }.mapError("刷新 123 网盘登录状态")
    }

    /**
     * 创建上传会话（分片上传第一步；带 etag 秒传）。
     *
     * @return reuse=true 表示秒传命中（无需上传分片，fileId 即云端文件）；否则返回 preuploadId/sliceSize/servers。
     */
    suspend fun createFile(
        accessToken: String,
        parentFileId: Long,
        filename: String,
        etag: String,
        size: Long,
    ): Result<Pan123CreateFileResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            val body = buildJsonBody(
                "parentFileID" to parentFileId,
                "filename" to filename,
                "etag" to etag,
                "size" to size,
                "duplicate" to 1, // 同名冲突：保留两者（加后缀），避免覆盖
            )
            val json = executeForJson {
                Request.Builder()
                    .url("$BASE_UPLOAD_URL/upload/v2/file/create")
                    .post(body)
                    .headers(authHeaders(accessToken))
                    .build()
            }
            val data = dataOf(json)
            Pan123CreateFileResult(
                fileId = data["fileID"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                reuse = data["reuse"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                preuploadId = data["preuploadID"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sliceSize = data["sliceSize"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                servers = data["servers"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
        }.mapError("创建 123 网盘上传会话")
    }

    /**
     * 上传单个分片（multipart，分片序号从 1 开始自增）。
     *
     * @param server 创建文件接口返回的上传域名（servers[0]）
     */
    suspend fun uploadSlice(
        accessToken: String,
        server: String,
        preuploadId: String,
        sliceNo: Int,
        sliceMd5: String,
        bytes: ByteArray,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            require(preuploadId.isNotBlank()) { "preuploadID 不能为空" }
            val base = server.trim().trimEnd('/').ifBlank { BASE_UPLOAD_URL }
            executeForJson {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("preuploadID", preuploadId)
                    .addFormDataPart("sliceNo", sliceNo.toString())
                    .addFormDataPart("sliceMD5", sliceMd5)
                    .addFormDataPart("slice", "slice$sliceNo", bytes.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
                    .build()
                Request.Builder()
                    .url("$base/upload/v2/file/slice")
                    .post(body)
                    .headers(authHeaders(accessToken))
                    .build()
            }
            Unit
        }.mapError("上传 123 网盘分片")
    }

    /**
     * 通知合并分片（最后一步），completed=false 时按 1s 间隔轮询直到完成。
     *
     * @return 上传完成后的 fileID
     */
    suspend fun uploadComplete(
        accessToken: String,
        server: String,
        preuploadId: String,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            require(preuploadId.isNotBlank()) { "preuploadID 不能为空" }
            val base = server.trim().trimEnd('/').ifBlank { BASE_UPLOAD_URL }
            repeat(MAX_COMPLETE_POLLS) {
                val json = executeForJson {
                    val body = buildJsonBody("preuploadID" to preuploadId)
                    Request.Builder()
                        .url("$base/upload/v2/file/upload_complete")
                        .post(body)
                        .headers(authHeaders(accessToken))
                        .build()
                }
                val data = dataOf(json)
                val completed = data["completed"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                if (completed) {
                    return@runCatching data["fileID"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: throw BackupException("123 网盘合并分片响应缺少 fileID")
                }
                delay(1_000L)
            }
            throw BackupException("123 网盘合并分片超时，请重试")
        }.mapError("合并 123 网盘分片")
    }

    /** 创建目录（确保备份根目录存在），返回目录 id。 */
    suspend fun mkdir(
        accessToken: String,
        name: String,
        parentId: Long = 0L,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            val json = executeForJson {
                val body = buildJsonBody("name" to name, "parentID" to parentId)
                Request.Builder()
                    .url("$BASE_UPLOAD_URL/upload/v1/file/mkdir")
                    .post(body)
                    .headers(authHeaders(accessToken))
                    .build()
            }
            val data = dataOf(json)
            data["dirID"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: throw BackupException("123 网盘创建目录响应缺少 dirID")
        }.mapError("创建 123 网盘备份目录")
    }

    /** 列出目录内容（exists 判断用）。 */
    suspend fun listFiles(
        accessToken: String,
        parentFileId: Long,
        limit: Int = MAX_LIST_LIMIT,
    ): Result<List<Pan123FileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            val url = HttpUrl.Builder()
                .scheme("https")
                .host(API_HOST)
                .addPathSegments("api/v2/file/list")
                .addQueryParameter("parentFileId", parentFileId.toString())
                .addQueryParameter("limit", limit.coerceIn(1, MAX_LIST_LIMIT).toString())
                .build()
            val json = executeForJson {
                Request.Builder()
                    .url(url)
                    .get()
                    .headers(authHeaders(accessToken))
                    .build()
            }
            val data = dataOf(json)
            data["fileList"]?.jsonArray?.map { element ->
                val obj = element.jsonObject
                Pan123FileItem(
                    fileId = obj["fileId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    filename = obj["filename"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    type = obj["type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    etag = obj["etag"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.orEmpty()
        }.mapError("获取 123 网盘目录列表")
    }

    /** 用户信息（testConnection 校验授权有效性）。 */
    suspend fun userInfo(accessToken: String): Result<Pan123UserInfo> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            val json = executeForJson {
                Request.Builder()
                    .url("$BASE_API_URL/api/v1/user/info")
                    .get()
                    .headers(authHeaders(accessToken))
                    .build()
            }
            val data = dataOf(json)
            Pan123UserInfo(
                userId = data["userId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                nickname = data["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.mapError("获取 123 网盘用户信息")
    }

    // ---------------- 内部工具 ----------------

    private fun accessTokenUrl(
        clientId: String,
        clientSecret: String,
        grantType: String,
        code: String?,
        refreshToken: String?,
        redirectUri: String?,
    ): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host(API_HOST)
            .addPathSegments("api/v1/oauth2/access_token")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("client_secret", clientSecret)
            .addQueryParameter("grant_type", grantType)
        code?.let { builder.addQueryParameter("code", it) }
        refreshToken?.let { builder.addQueryParameter("refresh_token", it) }
        redirectUri?.let { builder.addQueryParameter("redirect_uri", it) }
        return builder.build()
    }

    private fun authHeaders(accessToken: String): okhttp3.Headers =
        okhttp3.Headers.Builder()
            .add(HEADER_AUTH, "Bearer $accessToken")
            .add(HEADER_PLATFORM, PLATFORM_VALUE)
            .build()

    private fun buildJsonBody(vararg pairs: Pair<String, Any?>): RequestBody {
        val sb = StringBuilder("{")
        pairs.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')
            sb.append('"').append(key).append("\":")
            when (value) {
                null -> sb.append("null")
                is String -> sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                is Boolean -> sb.append(value)
                else -> sb.append(value) // Int / Long 等数值
            }
        }
        sb.append('}')
        return sb.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun dataOf(json: JsonElement): kotlinx.serialization.json.JsonObject =
        json.jsonObject["data"]?.jsonObject ?: json.jsonObject

    /**
     * 带 429 退避的 JSON 请求执行。
     *
     * [buildRequest] 每次重试都重新调用，避免复用已消费的 body（尤其 multipart 分片上传）。
     */
    private suspend fun executeForJson(buildRequest: () -> Request): JsonElement {
        var attempt = 0
        while (true) {
            val response = HttpClient.client.newCall(buildRequest()).execute()
            var shouldRetry = false
            var result: JsonElement? = null
            response.use {
                if (!it.isSuccessful) {
                    throw BackupException("123 网盘请求失败（HTTP ${it.code}）")
                }
                val text = it.body?.string().orEmpty()
                if (text.isBlank()) throw BackupException("123 网盘返回空响应")
                val json = HttpClient.json.parseToJsonElement(text)
                val code = json.jsonObject["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                when {
                    code == 429 && attempt < MAX_RATE_RETRIES -> {
                        attempt++
                        Log.w(TAG, "123 网盘限流（code=429），${RATE_BACKOFF_MS}ms 后第 $attempt/$MAX_RATE_RETRIES 次重试")
                        shouldRetry = true
                    }
                    code != 0 -> {
                        val message = json.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
                        throw BackupException("123 网盘错误（code=$code）：${errnoMessage(code, message)}")
                    }
                    else -> result = json
                }
            }
            if (!shouldRetry) {
                return result ?: throw BackupException("123 网盘返回异常")
            }
            delay(RATE_BACKOFF_MS)
        }
    }

    private fun errnoMessage(code: Int, message: String): String = when (code) {
        401 -> "登录已失效，请重新登录 123 网盘"
        429 -> "请求过于频繁，请稍后重试"
        else -> message.ifBlank { "code=$code" }
    }

    private fun parseTokenBundle(json: JsonElement): Pan123TokenBundle {
        val root = json.jsonObject
        // OAuth 端点可能直接返回字段，也可能用 code/message/data 包裹，兼容两者
        val src = root["data"]?.jsonObject ?: root
        return Pan123TokenBundle(
            tokenType = src["token_type"]?.jsonPrimitive?.contentOrNull ?: "Bearer",
            accessToken = src["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            refreshToken = src["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            expiresIn = src["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            scope = src["scope"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun <T> Result<T>.mapError(prefix: String): Result<T> = fold(
        onSuccess = { Result.success(it) },
        onFailure = { error ->
            val wrapped = if (error is BackupException) error
            else BackupException("${prefix}失败：${error.toUserMessage()}", error)
            Result.failure(wrapped)
        },
    )

    private companion object {
        private const val TAG = "Pan123Api"
        private const val API_HOST = "open-api.123pan.com"
        private const val AUTHORIZE_HOST = "yun.123pan.com"
        private const val BASE_API_URL = "https://$API_HOST"
        private const val BASE_UPLOAD_URL = "https://$API_HOST"

        private const val HEADER_AUTH = "Authorization"
        private const val HEADER_PLATFORM = "Platform"
        private const val PLATFORM_VALUE = "open_platform"

        private const val DEFAULT_SCOPE = "user:base,file:all:read,file:all:write"

        private const val MAX_LIST_LIMIT = 100
        private const val MAX_COMPLETE_POLLS = 60
        private const val MAX_RATE_RETRIES = 3
        private const val RATE_BACKOFF_MS = 1_000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

/** OAuth token 响应（token_type / access_token / refresh_token / expires_in / scope）。 */
@Serializable
data class Pan123TokenBundle(
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0L,
    val scope: String = "",
)

/** 创建文件响应（reuse=true 表示秒传命中）。 */
data class Pan123CreateFileResult(
    val fileId: Long = 0L,
    val reuse: Boolean = false,
    val preuploadId: String = "",
    val sliceSize: Long = 0L,
    val servers: List<String> = emptyList(),
)

/** 目录条目。 */
data class Pan123FileItem(
    val fileId: Long = 0L,
    val filename: String = "",
    val type: Int = 0, // 0-文件 1-文件夹
    val size: Long = 0L,
    val etag: String = "",
)

/** 用户信息。 */
data class Pan123UserInfo(
    val userId: Long = 0L,
    val nickname: String = "",
)
