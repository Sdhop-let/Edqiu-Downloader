package com.ed.edqiu.backup.model

import java.io.File

/**
 * 网盘直连备份：统一上传抽象。
 *
 * 一个网盘 = 一个适配器 = 一个 [BackupTarget] 实现。
 * 所有长操作均为 `suspend` 并在 `Dispatchers.IO` 上执行；
 * 所有方法返回 `Result<T>`，失败信息面向用户、中文、不含敏感字段（token/密码一律 `***` 脱敏）。
 *
 * @see ProviderId 各适配器的固定 id 常量
 */
interface BackupTarget {

    /** 目标唯一 id，取值见 [ProviderId]（如 "webdav" / "baidu" / "pan123" / "aliyun" / "clouddrive2"）。 */
    val id: String

    /** 用户可见名称（如 "百度网盘"）。 */
    val displayName: String

    /** 授权方式：设备码扫码 / WebDAV 账号密码 / WebView 截取 token / 无需授权。 */
    val authMode: AuthMode

    /** 能力描述：是否分片、是否秒传、分片大小、固定远程根目录、最大重试次数。 */
    val capabilities: BackupCapabilities

    /** 凭证是否已填写（足以发起授权 / 发起上传）。 */
    suspend fun isConfigured(): Boolean

    /** 是否已获得有效 token / 凭据（可能内部会刷新校验）。 */
    suspend fun isAuthorized(): Boolean

    /** 准备远程目录（MKCOL / mkdir / 确保 /apps/Edqiu 存在）。 */
    suspend fun prepareRemote(): Result<Unit>

    /** 判断远程路径是否已存在（用于跳过已备份文件）。 */
    suspend fun exists(remotePath: String): Result<Boolean>

    /**
     * 上传单个文件。
     *
     * @param local      本地文件
     * @param remotePath 远程相对路径（如 "Edqiu/xxx.mp4"）；固定根目录目标可忽略该参数
     * @param progress   进度回调，取值 0..1
     */
    suspend fun uploadFile(local: File, remotePath: String, progress: (Float) -> Unit): Result<UploadReceipt>

    /** 连通性测试（网络可达 + 授权有效）。 */
    suspend fun testConnection(): Result<Unit>
}

/** 授权方式枚举。 */
enum class AuthMode {
    /** 百度：设备码 + WebView 展示二维码 + 轮询换 token。 */
    DEVICE_CODE_QR,

    /** 123 / WebDAV：账号密码（或应用密码）。 */
    WEBDAV_CREDENTIAL,

    /** 阿里：WebView 登录截取 refresh_token（失败走手动粘贴兜底）。 */
    WEBVIEW_TOKEN,

    /** CloudDrive2：本机探测，无需授权。 */
    NONE,
}

/**
 * 目标能力描述。
 *
 * @property supportsChunked    是否支持分片上传
 * @property supportsRapidUpload 是否支持秒传（sha1 命中直接完成）
 * @property defaultChunkSize   默认分片大小（字节）；不支持分片时为 0
 * @property fixedRemoteRoot    固定远程根目录（百度为 "/apps/Edqiu"）；null 表示使用用户 remotePath
 * @property maxRetry           单任务最大重试次数（指数退避 30s / 2m / 10m，超限置 FAILED）
 */
data class BackupCapabilities(
    val supportsChunked: Boolean = false,
    val supportsRapidUpload: Boolean = false,
    val defaultChunkSize: Long = 0L,
    val fixedRemoteRoot: String? = null,
    val maxRetry: Int = 5,
)

/** 单文件上传结果回执。 */
data class UploadReceipt(
    val remotePath: String,
    val size: Long,
    val rapidMatched: Boolean = false,
    val uploadedAt: Long = System.currentTimeMillis(),
)

/**
 * 统一用户可读错误。
 *
 * 所有 provider / engine 失败时抛出或包装为 `Result.failure(BackupException(中文消息))`，
 * `BackupTask.errorMessage` 直接取自该消息，展示给用户且不含敏感字段。
 */
class BackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Provider id 常量（跨文件约定，见架构文档第 8 节）。 */
object ProviderId {
    const val WEBDAV = "webdav"
    const val BAIDU = "baidu"
    const val PAN123 = "pan123"
    const val ALIYUN = "aliyun"
    const val CLOUDDRIVE2 = "clouddrive2"

    /** 全部已知 provider id，用于注册表一致性校验 / UI 枚举。 */
    val all: List<String> = listOf(WEBDAV, BAIDU, PAN123, ALIYUN, CLOUDDRIVE2)
}
