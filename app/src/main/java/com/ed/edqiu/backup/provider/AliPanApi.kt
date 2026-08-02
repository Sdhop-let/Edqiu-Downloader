package com.ed.edqiu.backup.provider

import com.ed.edqiu.backup.http.HttpClient
import com.ed.edqiu.backup.http.toUserMessage
import com.ed.edqiu.backup.model.BackupException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 阿里云盘 openapi 客户端（openapi.alipan.com）。
 *
 * 职责边界：**只做 HTTP 与 JSON 编解码，不持有任何凭证状态**。
 * 每个方法显式传入 access_token / refresh_token，token 的获取、刷新、持久化由
 * [AliPanTarget] 编排（`ensureAccessToken()` + 401 自动重试）。
 *
 * 覆盖能力（对应架构文档 N14 / 阿里时序图）：
 * - [accessToken]：refresh_token 换取 access_token + 新 refresh_token + expires_in（OAuth 2.0）
 * - [getDriveInfo]：获取 default_drive_id（上传前必需）
 * - [createFile]：创建文件（type=file，带 sha1 content_hash + proof_code 秒传 / 预签名分片地址）
 * - [createFolder]：创建目录（type=folder，check_name_mode=refuse，重名走 AlreadyExist 由调用方兜底）
 * - [uploadPart]：分片 PUT 到预签名 URL
 * - [listUploadedParts]：拉取服务端已上传分片（续传兜底，本地收据丢失时使用）
 * - [completeFile]：完成分片上传（原子操作）
 * - [listFiles]：列目录（exists() / 找备份目录用）
 *
 * 失败约定：所有方法返回 `Result<T>`，错误消息面向用户、中文、不含 token 等敏感字段。
 */
class AliPanApi(
    private val clientId: String,
    private val clientSecret: String,
) {

    private val json: Json = HttpClient.json

    /**
     * refresh_token 换取 access_token（grant_type=refresh_token）。
     *
     * 响应中的 refresh_token 可能轮换（返回新值），调用方应持久化新值。
     */
    suspend fun accessToken(refreshToken: String): Result<AliTokenBundle> {
        return try {
            if (clientId.isBlank()) {
                throw BackupException("未配置阿里云盘应用资质（ALI_CLIENT_ID），请先在构建配置中填写")
            }
            val body = buildJsonObject {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
                put("client_id", clientId)
                put("client_secret", clientSecret)
            }.toString()
            val res = HttpClient.postJson(OAUTH_TOKEN_URL, body).getOrThrow()
            Result.success(json.decodeFromJsonElement(AliTokenBundle.serializer(), res))
        } catch (e: Exception) {
            Result.failure(BackupException("刷新阿里云盘令牌失败：${e.toUserMessage()}", e))
        }
    }

    /** 获取用户空间信息（default_drive_id 等）。 */
    suspend fun getDriveInfo(accessToken: String): Result<AliDriveInfo> {
        return try {
            val res = HttpClient.postJson(GET_DRIVE_INFO_URL, "{}", bearer(accessToken)).getOrThrow()
            Result.success(json.decodeFromJsonElement(AliDriveInfo.serializer(), res))
        } catch (e: Exception) {
            Result.failure(BackupException("获取阿里云盘空间信息失败：${e.toUserMessage()}", e))
        }
    }

    /**
     * 创建文件 / 目录。
     *
     * @param type "file" 或 "folder"
     * @param contentHash 文件 sha1（秒传用，null 跳过）
     * @param proofCode 秒传校验码（取 access_token 派生的文件内 8 字节 sha1，null 跳过）
     * @param partCount 分片数（type=file 且 >0 时生成 part_info_list）
     *
     * 返回的 [AliCreateFileResult.rapidUpload] / [AliCreateFileResult.exist] 为 true 表示
     * 秒传命中或同名文件已存在（check_name_mode=auto_ignore），无需再走分片上传。
     */
    suspend fun createFile(
        accessToken: String,
        driveId: String,
        parentFileId: String,
        name: String,
        type: String,
        size: Long,
        contentHash: String?,
        proofCode: String?,
        partCount: Int,
    ): Result<AliCreateFileResult> {
        return try {
            val body = buildJsonObject {
                put("drive_id", driveId)
                put("parent_file_id", parentFileId)
                put("name", name)
                put("type", type)
                put("check_name_mode", "auto_ignore")
                if (size >= 0L) put("size", size)
                if (!contentHash.isNullOrBlank()) {
                    put("content_hash", contentHash)
                    put("content_hash_name", "sha1")
                }
                if (!proofCode.isNullOrBlank()) {
                    put("proof_code", proofCode)
                }
                if (type == "file" && partCount > 0) {
                    put(
                        "part_info_list",
                        buildJsonArray {
                            repeat(partCount) { index -> add(buildJsonObject { put("part_number", index + 1) }) }
                        },
                    )
                }
            }.toString()
            val res = HttpClient.postJson(CREATE_FILE_URL, body, bearer(accessToken)).getOrThrow()
            Result.success(json.decodeFromJsonElement(AliCreateFileResult.serializer(), res))
        } catch (e: Exception) {
            Result.failure(BackupException("创建阿里云盘文件失败：${e.toUserMessage()}", e))
        }
    }

    /** 创建目录（type=folder，重名时服务端返回 AlreadyExist，由调用方列目录兜底）。 */
    suspend fun createFolder(
        accessToken: String,
        driveId: String,
        parentFileId: String,
        name: String,
    ): Result<AliFileItem> {
        return try {
            val body = buildJsonObject {
                put("drive_id", driveId)
                put("parent_file_id", parentFileId)
                put("name", name)
                put("type", "folder")
                put("check_name_mode", "refuse")
            }.toString()
            val res = HttpClient.postJson(CREATE_FILE_URL, body, bearer(accessToken)).getOrThrow()
            Result.success(json.decodeFromJsonElement(AliFileItem.serializer(), res))
        } catch (e: Exception) {
            Result.failure(BackupException("创建阿里云盘备份目录失败：${e.toUserMessage()}", e))
        }
    }

    /** 分片 PUT 到预签名 URL。0 字节分片直接视为成功（分片最小限制 100KB，空文件走 0 分片路径）。 */
    suspend fun uploadPart(
        uploadUrl: String,
        bytes: ByteArray,
        progress: (Long) -> Unit = {},
    ): Result<Unit> {
        return try {
            if (bytes.isEmpty()) {
                return Result.success(Unit)
            }
            HttpClient.putStream(uploadUrl, bytes, headers = emptyMap(), progress = progress).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(BackupException("上传阿里云盘分片失败：${e.toUserMessage()}", e))
        }
    }

    /** 拉取服务端已上传分片（断点续传兜底：本地分片收据丢失时，从这里恢复）。 */
    suspend fun listUploadedParts(
        accessToken: String,
        driveId: String,
        fileId: String,
        uploadId: String,
    ): Result<List<AliUploadedPart>> {
        return try {
            val body = buildJsonObject {
                put("drive_id", driveId)
                put("file_id", fileId)
                put("upload_id", uploadId)
            }.toString()
            val res = HttpClient.postJson(LIST_UPLOADED_PARTS_URL, body, bearer(accessToken)).getOrThrow()
            val response = json.decodeFromJsonElement(AliUploadedPartsResponse.serializer(), res)
            Result.success(response.parts)
        } catch (e: Exception) {
            Result.failure(BackupException("查询阿里云盘已上传分片失败：${e.toUserMessage()}", e))
        }
    }

    /** 完成分片上传（原子操作，成功后文件正式可见）。 */
    suspend fun completeFile(
        accessToken: String,
        driveId: String,
        fileId: String,
        uploadId: String,
    ): Result<Unit> {
        return try {
            val body = buildJsonObject {
                put("drive_id", driveId)
                put("file_id", fileId)
                put("upload_id", uploadId)
            }.toString()
            HttpClient.postJson(COMPLETE_FILE_URL, body, bearer(accessToken)).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(BackupException("完成阿里云盘上传失败：${e.toUserMessage()}", e))
        }
    }

    /** 列出目录下的文件（用于 exists() 与查找备份目录）。 */
    suspend fun listFiles(
        accessToken: String,
        driveId: String,
        parentFileId: String,
        limit: Int = MAX_LIST_LIMIT,
    ): Result<List<AliFileItem>> {
        return try {
            require(limit in 1..MAX_LIST_LIMIT) { "limit 超出范围" }
            val body = buildJsonObject {
                put("drive_id", driveId)
                put("parent_file_id", parentFileId)
                put("limit", limit)
            }.toString()
            val res = HttpClient.postJson(LIST_FILES_URL, body, bearer(accessToken)).getOrThrow()
            val response = json.decodeFromJsonElement(AliFileListResponse.serializer(), res)
            Result.success(response.items)
        } catch (e: Exception) {
            Result.failure(BackupException("获取阿里云盘目录列表失败：${e.toUserMessage()}", e))
        }
    }

    private fun bearer(accessToken: String): Map<String, String> =
        mapOf(AUTH_HEADER to "Bearer $accessToken")

    private companion object {
        const val BASE_URL = "https://openapi.alipan.com"
        const val OAUTH_TOKEN_URL = "$BASE_URL/oauth/access_token"
        const val GET_DRIVE_INFO_URL = "$BASE_URL/adrive/v1.0/user/getDriveInfo"
        const val CREATE_FILE_URL = "$BASE_URL/adrive/v1.0/openFile/create"
        const val LIST_UPLOADED_PARTS_URL = "$BASE_URL/adrive/v1.0/openFile/listUploadedParts"
        const val COMPLETE_FILE_URL = "$BASE_URL/adrive/v1.0/openFile/complete"
        const val LIST_FILES_URL = "$BASE_URL/adrive/v1.0/openFile/list"
        const val AUTH_HEADER = "Authorization"
        const val MAX_LIST_LIMIT = 200
    }
}

/** OAuth 换 token 响应（refresh_token 可能轮换）。 */
@Serializable
data class AliTokenBundle(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0L,
    @SerialName("token_type") val tokenType: String = "",
)

/** getDriveInfo 响应。 */
@Serializable
data class AliDriveInfo(
    @SerialName("default_drive_id") val defaultDriveId: String = "",
    @SerialName("resource_drive_id") val resourceDriveId: String = "",
    @SerialName("backup_drive_id") val backupDriveId: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
)

/** create 文件响应。 */
@Serializable
data class AliCreateFileResult(
    @SerialName("file_id") val fileId: String = "",
    @SerialName("upload_id") val uploadId: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("exist") val exist: Boolean = false,
    @SerialName("rapid_upload") val rapidUpload: Boolean = false,
    @SerialName("part_info_list") val partInfoList: List<AliPartInfo> = emptyList(),
)

/** 预签名分片地址。 */
@Serializable
data class AliPartInfo(
    @SerialName("part_number") val partNumber: Int = 0,
    @SerialName("upload_url") val uploadUrl: String = "",
)

/** 服务端已上传分片。 */
@Serializable
data class AliUploadedPart(
    @SerialName("part_number") val partNumber: Int = 0,
    @SerialName("etag") val etag: String = "",
    @SerialName("size") val size: Long = 0L,
)

/** 文件/目录条目（list / createFolder 用）。 */
@Serializable
data class AliFileItem(
    @SerialName("file_id") val fileId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("type") val type: String = "",
)

@Serializable
private data class AliUploadedPartsResponse(
    @SerialName("upload_id") val uploadId: String = "",
    val parts: List<AliUploadedPart> = emptyList(),
)

@Serializable
private data class AliFileListResponse(
    val items: List<AliFileItem> = emptyList(),
    @SerialName("next_marker") val nextMarker: String = "",
)
