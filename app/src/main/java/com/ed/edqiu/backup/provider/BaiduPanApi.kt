package com.ed.edqiu.backup.provider

import com.ed.edqiu.backup.http.HttpClient
import com.ed.edqiu.backup.model.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 百度开放平台 / PCS API 封装（纯 HTTP 客户端，不持有状态）。
 *
 * 覆盖三类接口：
 * 1. **OAuth 设备码授权**（[requestDeviceCode] / [pollDeviceToken] / [refreshToken]）：
 *    官方设备码扫码流程，`access_token` 约 30 天、`refresh_token` 约 10 年。
 * 2. **PCS 分片上传**（[precreate] / [uploadPart] / [createFile]）：
 *    预上传返回 uploadid → superfile2 分片（multipart 二进制）→ create 原子创建。
 * 3. **文件元信息**（[meta] / [createDir]）：用于确保 `/apps/Edqiu` 目录存在与秒跳判断。
 *
 * 固定目录约定：所有上传路径必须以 `/apps/Edqiu` 开头（用户已确认接受，不可改）。
 *
 * 说明：百度 PCS 的 precreate / create / meta 使用 `application/x-www-form-urlencoded`
 * 表单体，superfile2 分片使用 `multipart/form-data` 二进制体——`HttpClient` 仅提供 JSON
 * body（getJson/postJson），故本类直接复用共享的 `HttpClient.client`（okhttp 单例，超时/日志
 * 拦截器一致）自行构造表单与 multipart 请求。
 */
class BaiduPanApi {

    // ---- OAuth 授权 ----

    /**
     * 请求设备码（官方设备码流程第一步）。
     *
     * POST https://openapi.baidu.com/oauth/2.0/device/code
     *
     * @param clientId 百度开放平台应用 client_id
     */
    suspend fun requestDeviceCode(clientId: String): Result<DeviceCodeResponse> = withContext(Dispatchers.IO) {
        runCatching {
            require(clientId.isNotBlank()) { "client_id 不能为空" }
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("response_type", "device_code")
                .add("scope", "basic,netdisk")
                .build()
            val json = executeForJson(Request.Builder().url(OAUTH_DEVICE_CODE_URL).post(form).build())
            val obj = json.jsonObject
            val error = obj["error"]?.jsonPrimitive?.contentOrNull
            if (!error.isNullOrBlank()) {
                val desc = obj["error_description"]?.jsonPrimitive?.contentOrNull
                throw BackupException("百度网盘请求设备码失败：${desc ?: error}")
            }
            HttpClient.json.decodeFromJsonElement(DeviceCodeResponse.serializer(), json)
        }
    }

    /**
     * 设备码轮询换取 token（官方设备码流程第二步）。
     *
     * POST https://openapi.baidu.com/oauth/2.0/token（grant_type=device_token）
     *
     * 成功时 [BaiduTokenResponse.error] 为空且 `access_token` 非空；
     * 等待中（`authorization_pending` / `slow_down`）、过期（`expired_token`）、
     * 拒绝（`access_denied`）等以 `error` 字段返回（HTTP 200），由调用方（授权状态机）解释。
     */
    suspend fun pollDeviceToken(
        clientId: String,
        clientSecret: String,
        deviceCode: String,
    ): Result<BaiduTokenResponse> = withContext(Dispatchers.IO) {
        runCatching {
            require(clientId.isNotBlank()) { "client_id 不能为空" }
            require(deviceCode.isNotBlank()) { "device_code 不能为空" }
            val form = FormBody.Builder()
                .add("grant_type", "device_token")
                .add("code", deviceCode)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build()
            val json = executeForJson(Request.Builder().url(OAUTH_TOKEN_URL).post(form).build())
            HttpClient.json.decodeFromJsonElement(BaiduTokenResponse.serializer(), json)
        }
    }

    /**
     * 用 refresh_token 刷新 access_token。
     *
     * POST https://openapi.baidu.com/oauth/2.0/token（grant_type=refresh_token）
     */
    suspend fun refreshToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String,
    ): Result<BaiduTokenResponse> = withContext(Dispatchers.IO) {
        runCatching {
            require(clientId.isNotBlank()) { "client_id 不能为空" }
            require(refreshToken.isNotBlank()) { "refresh_token 不能为空" }
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build()
            val json = executeForJson(Request.Builder().url(OAUTH_TOKEN_URL).post(form).build())
            HttpClient.json.decodeFromJsonElement(BaiduTokenResponse.serializer(), json)
        }
    }

    // ---- PCS 分片上传 ----

    /**
     * 预上传：创建/复用 superfile 会话，返回 uploadid。
     *
     * POST https://pan.baidu.com/rest/2.0/xpan/file?method=precreate
     *
     * @param blockList 本地计算的各分片 SHA-1（十六进制小写），JSON 数组字符串
     */
    suspend fun precreate(
        accessToken: String,
        path: String,
        size: Long,
        blockList: List<String>,
    ): Result<PrecreateResponse> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            require(path.startsWith("/apps/")) { "百度网盘路径必须以 /apps/ 开头" }
            require(size >= 0L) { "文件大小不能为负" }
            val blockListJson = blockList.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
            val form = FormBody.Builder()
                .add("path", path)
                .add("size", size.toString())
                .add("isdir", "0")
                .add("autoinit", "1")
                .add("block_list", blockListJson)
                .build()
            val json = executeForJson(buildPanRequest(METHOD_PRECREATE, accessToken, form))
            val errno = json.jsonObject["errno"]?.jsonPrimitive?.longOrNull ?: -1L
            if (errno != 0L) throw BackupException(errnoMessage(errno))
            HttpClient.json.decodeFromJsonElement(PrecreateResponse.serializer(), json)
        }
    }

    /**
     * 上传单个分片（superfile2），返回服务端确认的 md5（作为分片收据供 create 使用）。
     *
     * POST https://d.pcs.baidu.com/rest/2.0/xpan/file?method=superfile2&type=tmpfile
     *
     * @param partSeq 分片序号，从 0 开始
     * @param bytes   分片二进制内容（≤32MB，来自 [com.ed.edqiu.backup.upload.Chunk.readBytes]）
     */
    suspend fun uploadPart(
        accessToken: String,
        path: String,
        uploadId: String,
        partSeq: Int,
        bytes: ByteArray,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            require(uploadId.isNotBlank()) { "uploadid 不能为空" }
            require(partSeq >= 0) { "分片序号不能为负" }
            val url = HttpUrl.Builder()
                .scheme("https")
                .host(PCS_API_HOST)
                .addPathSegments("rest/2.0/xpan/file")
                .addQueryParameter("method", "superfile2")
                .addQueryParameter("type", "tmpfile")
                .addQueryParameter("access_token", accessToken)
                .addQueryParameter("path", path)
                .addQueryParameter("uploadid", uploadId)
                .addQueryParameter("partseq", partSeq.toString())
                .build()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "part$partSeq",
                    bytes.toRequestBody(OCTET_STREAM_MEDIA_TYPE),
                )
                .build()
            val json = executeForJson(Request.Builder().url(url).post(body).build())
            val obj = json.jsonObject
            val errno = obj["errno"]?.jsonPrimitive?.longOrNull
                ?: obj["error_code"]?.jsonPrimitive?.longOrNull
                ?: 0L
            if (errno != 0L) throw BackupException(errnoMessage(errno))
            obj["md5"]?.jsonPrimitive?.contentOrNull
                ?: throw BackupException("百度网盘分片上传响应缺少 md5")
        }
    }

    /**
     * 创建文件（superfile 最后一步，原子操作）。
     *
     * POST https://pan.baidu.com/rest/2.0/xpan/file?method=create
     *
     * @param blockList 各分片上传返回的 md5（按分片序号升序）
     */
    suspend fun createFile(
        accessToken: String,
        path: String,
        size: Long,
        uploadId: String,
        blockList: List<String>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            require(uploadId.isNotBlank()) { "uploadid 不能为空" }
            val blockListJson = blockList.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
            val form = FormBody.Builder()
                .add("path", path)
                .add("size", size.toString())
                .add("isdir", "0")
                .add("uploadid", uploadId)
                .add("block_list", blockListJson)
                .build()
            val json = executeForJson(buildPanRequest(METHOD_CREATE, accessToken, form))
            val errno = json.jsonObject["errno"]?.jsonPrimitive?.longOrNull ?: -1L
            if (errno != 0L) throw BackupException(errnoMessage(errno))
        }
    }

    /** 创建目录（确保 /apps/Edqiu 存在用）。 */
    suspend fun createDir(accessToken: String, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            require(path.startsWith("/apps/")) { "百度网盘路径必须以 /apps/ 开头" }
            val form = FormBody.Builder()
                .add("path", path)
                .add("size", "0")
                .add("isdir", "1")
                .build()
            val json = executeForJson(buildPanRequest(METHOD_CREATE, accessToken, form))
            val errno = json.jsonObject["errno"]?.jsonPrimitive?.longOrNull ?: -1L
            if (errno != 0L) throw BackupException(errnoMessage(errno))
        }
    }

    /**
     * 查询路径元信息。
     *
     * GET https://pan.baidu.com/rest/2.0/xpan/file?method=meta
     *
     * @return 存在时返回首个条目 JSON；不存在（errno -9 / 31066）返回 null；其余错误返回 [Result.failure]
     */
    suspend fun meta(accessToken: String, path: String): Result<JsonElement?> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "access_token 不能为空" }
            val url = HttpUrl.Builder()
                .scheme("https")
                .host(PAN_API_HOST)
                .addPathSegments("rest/2.0/xpan/file")
                .addQueryParameter("method", "meta")
                .addQueryParameter("access_token", accessToken)
                .addQueryParameter("path", path)
                .build()
            val json = executeForJson(Request.Builder().url(url).get().build())
            val errno = json.jsonObject["errno"]?.jsonPrimitive?.longOrNull ?: 0L
            when (errno) {
                0L -> json.jsonObject["list"]?.jsonArray?.firstOrNull()
                ERRNO_PATH_NOT_FOUND, ERRNO_FILE_NOT_FOUND -> null
                else -> throw BackupException(errnoMessage(errno))
            }
        }
    }

    // ---- 内部工具 ----

    private fun buildPanRequest(method: String, accessToken: String, body: RequestBody): Request {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(PAN_API_HOST)
            .addPathSegments("rest/2.0/xpan/file")
            .addQueryParameter("method", method)
            .addQueryParameter("access_token", accessToken)
            .build()
        return Request.Builder().url(url).post(body).build()
    }

    private suspend fun executeForJson(request: Request): JsonElement = withContext(Dispatchers.IO) {
        HttpClient.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw BackupException("百度网盘请求失败（HTTP ${response.code}）")
            }
            if (text.isBlank()) {
                throw BackupException("百度网盘返回空响应")
            }
            HttpClient.json.parseToJsonElement(text)
        }
    }

    private fun errnoMessage(errno: Long): String = when (errno) {
        0L -> "成功"
        -6L -> "百度网盘身份验证失败，请重新授权"
        -7L -> "文件或目录名错误"
        -9L -> "目标文件或目录已存在"
        -10L -> "文件大小超限"
        -33L -> "文件名不合法"
        404L -> "上传会话不存在或已失效，请重新上传"
        31061L -> "文件名不合法"
        31066L -> "文件不存在"
        else -> "百度网盘返回错误（errno=$errno）"
    }

    private companion object {
        const val OAUTH_BASE_URL = "https://openapi.baidu.com/oauth/2.0"
        const val OAUTH_DEVICE_CODE_URL = "$OAUTH_BASE_URL/device/code"
        const val OAUTH_TOKEN_URL = "$OAUTH_BASE_URL/token"
        const val PAN_API_HOST = "pan.baidu.com"
        const val PCS_API_HOST = "d.pcs.baidu.com"

        const val METHOD_PRECREATE = "precreate"
        const val METHOD_CREATE = "create"

        const val ERRNO_PATH_NOT_FOUND = -9L
        const val ERRNO_FILE_NOT_FOUND = 31066L

        val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

/** 设备码响应（openapi.baidu.com/oauth/2.0/device/code）。 */
@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    @SerialName("qrcode_url") val qrcodeUrl: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0L,
    val interval: Long = 5L,
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
)

/** OAuth token 响应（device_token / refresh_token 共用；错误时 error 字段非空）。 */
@Serializable
data class BaiduTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0L,
    val scope: String = "",
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
)

/** 预上传响应（method=precreate）。 */
@Serializable
data class PrecreateResponse(
    val uploadid: String = "",
    val block_list: List<Int> = emptyList(),
    val errno: Int = 0,
)
