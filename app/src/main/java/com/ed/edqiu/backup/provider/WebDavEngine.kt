package com.ed.edqiu.backup.provider

import android.util.Base64
import android.util.Log
import com.ed.twitterdownloader.data.model.MediaFileTypes
import com.ed.edqiu.backup.http.toUserMessage
import com.ed.edqiu.backup.model.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WebDAV 核心引擎。
 *
 * 从旧版 `WebDavSyncService` 提取（MKCOL / HEAD / PUT / OPTIONS + BasicAuth + 目录逐级创建 +
 * URL 构建 + 路径段编码），行为保持兼容，被以下模块复用：
 * - [WebDavBackupTarget]（自定义 WebDAV，高级）
 * - [Pan123Target]（123 网盘）
 * - [CloudDrive2Target]（CloudDrive2 本机 WebDAV）
 * - `WebDavSyncService`（旧版「立即同步」薄门面）
 *
 * 传输层保留 HttpURLConnection（架构文档 1.2），超时统一 connect 20s / read 30s。
 * 所有方法返回 `Result<T>`，失败为面向用户的中文 [BackupException]，不含敏感字段（密码打码）。
 */
class WebDavEngine {

    companion object {
        private const val TAG = "WebDavEngine"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val USER_AGENT = "Edqiu-WebDAV/1.0"
        private const val BUFFER_SIZE = 64 * 1024

        /** MKCOL 视为「目录已存在/创建成功」的状态码（宽容处理，与旧版一致）。 */
        private val MKCOL_OK_CODES = setOf(
            HttpURLConnection.HTTP_CREATED,      // 201
            HttpURLConnection.HTTP_OK,           // 200
            HttpURLConnection.HTTP_NO_CONTENT,   // 204
            HttpURLConnection.HTTP_BAD_METHOD,   // 405：目录已存在时部分服务器返回
        )
    }

    /**
     * 拼接远程 URL：baseUrl 去尾部斜杠，path 按 `/` 分段逐段编码。
     *
     * @param baseUrl 服务器地址（如 `https://dav.example.com`）
     * @param path    相对路径（如 `Edqiu/foo.mp4`，允许带空格/中文）
     */
    fun buildUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
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
        val connection = openConnection(url, "MKCOL", credential)
        try {
            val code = connection.responseCode
            if (code !in MKCOL_OK_CODES) {
                Log.w(TAG, "MKCOL returned HTTP $code for $url")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** HEAD 判断远程文件是否存在（2xx 视为存在；网络异常按失败返回）。 */
    suspend fun exists(url: String, credential: WebDavCredential): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = openConnection(url, "HEAD", credential)
            try {
                val code = connection.responseCode
                code in 200..299
            } finally {
                connection.disconnect()
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
            val connection = openConnection(url, "PUT", credential).apply {
                doOutput = true
                setRequestProperty("Content-Type", MediaFileTypes.mimeTypeForExtension(file.extension))
                setRequestProperty("Content-Length", file.length().toString())
            }
            try {
                connection.outputStream.use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            progress(written)
                        }
                    }
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw BackupException("WebDAV 上传失败 HTTP $code：${file.name}")
                }
                Unit
            } finally {
                connection.disconnect()
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
            val connection = openConnection(url, "OPTIONS", credential)
            try {
                val code = connection.responseCode
                when {
                    code in 200..399 -> true
                    code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN ->
                        throw BackupException("WebDAV 认证失败（HTTP $code），请检查账号与密码")
                    else -> throw BackupException("WebDAV 服务不可用（HTTP $code）")
                }
            } finally {
                connection.disconnect()
            }
        }.toUserFriendly()
    }

    private fun openConnection(url: String, method: String, credential: WebDavCredential): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", basicAuth(credential.username, credential.password))
            setRequestProperty("User-Agent", USER_AGENT)
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
