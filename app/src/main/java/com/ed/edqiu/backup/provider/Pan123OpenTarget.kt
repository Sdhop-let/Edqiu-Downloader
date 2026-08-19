package com.ed.edqiu.backup.provider

import android.content.Context
import android.util.Log
import com.ed.edqiu.BuildConfig
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.http.toUserMessage
import com.ed.edqiu.backup.model.AuthMode
import com.ed.edqiu.backup.model.BackupCapabilities
import com.ed.edqiu.backup.model.BackupException
import com.ed.edqiu.backup.model.BackupTarget
import com.ed.edqiu.backup.model.ProviderId
import com.ed.edqiu.backup.model.UploadReceipt
import com.ed.edqiu.backup.upload.ChunkedUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 123 网盘官方登录适配器（[BackupTarget] 实现，id = [ProviderId.PAN123_OPEN]）。
 *
 * - 授权方式：[AuthMode.WEBVIEW_TOKEN]（WebView 打开授权页登录，拦截回调 code 换 token）。
 *   与旧 [Pan123Target]（WebDAV 手动填账号密码）不同，本类走官方 OAuth，**登录一次即可备份**。
 * - 上传：开放平台分片（[Pan123Api.createFile] → [Pan123Api.uploadSlice] → [Pan123Api.uploadComplete]）
 *   按服务端返回的 sliceSize 串行分片；etag（文件 md5）命中即秒传（reuse=true）。
 * - 远程根目录：固定目录 `Edqiu`（[BACKUP_FOLDER_NAME]），文件平铺存入。
 * - token：refresh_token 约 90 天、单次有效（刷新后返回新值）；401 自动刷新重试（[ensureAccessToken] + [callWithAuthRetry]）。
 * - 凭证：存 [CredentialStore]（providerId=pan123_open），逻辑 key：refresh_token / access_token / expires_at。
 *
 * 局限：开放平台分片上传不提供「已传分片查询」，中断后重试会整体重传（靠秒传 etag 去重兜底），
 * 大文件上传稳定性弱于百度/阿里的断点续传——这是 123 开放平台能力边界，非实现缺陷。
 */
class Pan123OpenTarget(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val api: Pan123Api = Pan123Api(),
    private val uploader: ChunkedUploader = ChunkedUploader(),
    private val clientId: String = BuildConfig.PAN123_CLIENT_ID,
    private val clientSecret: String = BuildConfig.PAN123_CLIENT_SECRET,
    private val redirectUri: String = BuildConfig.PAN123_REDIRECT_URI,
) : BackupTarget {

    override val id: String = ProviderId.PAN123_OPEN

    override val displayName: String = "123网盘"

    override val authMode: AuthMode = AuthMode.WEBVIEW_TOKEN

    override val capabilities: BackupCapabilities = BackupCapabilities(
        supportsChunked = true,
        supportsRapidUpload = true,
        defaultChunkSize = DEFAULT_SLICE_SIZE,
        fixedRemoteRoot = BACKUP_FOLDER_NAME,
        maxRetry = 5,
    )

    private val tokenMutex = Mutex()
    private val folderMutex = Mutex()

    @Volatile
    private var cachedBackupDirId: Long? = null

    /** 应用资质是否已配置（client_id 非空才能发起授权）。 */
    val isClientConfigured: Boolean
        get() = clientId.isNotBlank()

    /** 构造授权页 URL（供 [com.ed.edqiu.backup.auth.Pan123WebViewAuth] 打开）。 */
    fun buildAuthorizeUrl(state: String): String {
        if (clientId.isBlank()) {
            throw BackupException("未配置 123 网盘应用资质（PAN123_CLIENT_ID），请联系开发者配置后使用")
        }
        return api.buildAuthorizeUrl(clientId, redirectUri, state)
    }

    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        isClientConfigured
    }

    override suspend fun isAuthorized(): Boolean = withContext(Dispatchers.IO) {
        val refresh = credentialStore.read(id)["refresh_token"]
        if (refresh.isNullOrBlank()) return@withContext false
        ensureAccessToken().isSuccess
    }

    /**
     * 授权入口：WebView 拦截到授权码后调用。
     * 立即换取 access_token + refresh_token 并持久化。
     */
    suspend fun setAuthCode(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val c = code.trim()
        when {
            c.isBlank() -> Result.failure(BackupException("授权码不能为空"))
            clientId.isBlank() -> Result.failure(BackupException("未配置 123 网盘应用资质（PAN123_CLIENT_ID）"))
            else -> api.exchangeCode(clientId, clientSecret, c, redirectUri).fold(
                onSuccess = { bundle ->
                    if (bundle.accessToken.isBlank()) {
                        Result.failure(BackupException("123 网盘授权失败：未返回 access_token"))
                    } else {
                        persistToken(bundle)
                        cachedBackupDirId = null
                        Result.success(Unit)
                    }
                },
                onFailure = { Result.failure(it) },
            )
        }
    }

    /** 清除已保存凭证。 */
    suspend fun clearAuth(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            credentialStore.clear(id)
            cachedBackupDirId = null
        }
    }

    override suspend fun prepareRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        callWithAuthRetry { token -> ensureBackupFolder(token).map { } }
    }

    override suspend fun exists(remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val name = remotePath.substringAfterLast('/')
        if (name.isBlank()) {
            return@withContext Result.failure(BackupException("远程路径无效：$remotePath"))
        }
        callWithAuthRetry { token ->
            val dirId = ensureBackupFolder(token).getOrElse { return@callWithAuthRetry Result.failure(it) }
            api.listFiles(token, dirId).map { items ->
                items.any { it.type == 0 && it.filename == name }
            }
        }
    }

    override suspend fun uploadFile(
        local: File,
        remotePath: String,
        progress: (Float) -> Unit,
    ): Result<UploadReceipt> = withContext(Dispatchers.IO) {
        try {
            val size = local.length()
            val name = remotePath.substringAfterLast('/').ifBlank { local.name }
            if (name.isBlank()) throw BackupException("远程文件名无效")

            Log.d(TAG, "准备上传 $name (${size}B)")
            val token = ensureAccessToken().getOrElse { throw it }
            val dirId = ensureBackupFolder(token).getOrElse { throw it }

            // etag = 文件 md5，命中即秒传
            val etag = computeMd5(local)
            val create = api.createFile(token, dirId, name, etag, size).getOrElse { throw it }

            if (create.reuse) {
                Log.d(TAG, "秒传命中：$name")
                progress(1f)
                return@withContext Result.success(
                    UploadReceipt(
                        remotePath = "$BACKUP_FOLDER_NAME/$name",
                        size = size,
                        rapidMatched = true,
                        cloudFileId = create.fileId.toString(),
                    )
                )
            }

            val preuploadId = create.preuploadId
            if (preuploadId.isBlank()) throw BackupException("123 网盘创建上传会话失败：未返回 preuploadID")
            val sliceSize = create.sliceSize
            if (sliceSize <= 0L) throw BackupException("123 网盘创建上传会话失败：sliceSize 无效")
            val server = create.servers.firstOrNull()
                ?: throw BackupException("123 网盘创建上传会话失败：未返回上传域名")

            val chunks = uploader.split(local, sliceSize)
            var uploaded = 0L
            chunks.forEach { chunk ->
                val bytes = chunk.readBytes()
                val sliceMd5 = md5(bytes)
                api.uploadSlice(token, server, preuploadId, chunk.seq + 1, sliceMd5, bytes).getOrElse { throw it }
                uploaded += chunk.length
                if (size > 0L) progress((uploaded.toFloat() / size.toFloat()).coerceIn(0f, 1f))
            }

            val fileId = api.uploadComplete(token, server, preuploadId).getOrElse { throw it }
            progress(1f)

            Result.success(
                UploadReceipt(
                    remotePath = "$BACKUP_FOLDER_NAME/$name",
                    size = size,
                    rapidMatched = false,
                    cloudFileId = fileId.toString(),
                )
            )
        } catch (error: Exception) {
            val wrapped = if (error is BackupException) error else BackupException("上传失败：${error.toUserMessage()}", error)
            Log.w(TAG, "上传失败：${wrapped.message}")
            Result.failure(wrapped)
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        callWithAuthRetry { token -> api.userInfo(token).map { } }
    }

    // ---------------- token 管理 ----------------

    private suspend fun ensureAccessToken(): Result<String> = tokenMutex.withLock {
        val creds = credentialStore.read(id)
        val refreshToken = creds[KEY_REFRESH_TOKEN]
        if (refreshToken.isNullOrBlank()) {
            return@withLock Result.failure(BackupException("未登录 123 网盘，请先登录授权"))
        }
        val accessToken = creds[KEY_ACCESS_TOKEN]
        val expiresAt = creds[KEY_EXPIRES_AT]?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (!accessToken.isNullOrBlank() && expiresAt > now + TOKEN_EXPIRY_MARGIN_MS) {
            return@withLock Result.success(accessToken)
        }

        Log.d(TAG, "access_token 缺失或过期，使用 refresh_token 刷新")
        api.refreshToken(clientId, clientSecret, refreshToken).fold(
            onSuccess = { bundle ->
                val newAccess = bundle.accessToken
                if (newAccess.isBlank()) {
                    Result.failure(BackupException("刷新 123 网盘登录状态失败：返回为空"))
                } else {
                    persistToken(bundle)
                    Result.success(newAccess)
                }
            },
            onFailure = { error ->
                Log.w(TAG, "刷新 123 网盘 access_token 失败：${error.message}")
                Result.failure(error)
            },
        )
    }

    private fun persistToken(bundle: Pan123TokenBundle) {
        val newExpiresAt = System.currentTimeMillis() +
            (bundle.expiresIn * 1000L).coerceAtLeast(60_000L)
        credentialStore.save(
            id,
            mapOf(
                KEY_ACCESS_TOKEN to bundle.accessToken,
                KEY_REFRESH_TOKEN to bundle.refreshToken.ifBlank {
                    credentialStore.read(id)[KEY_REFRESH_TOKEN].orEmpty()
                },
                KEY_EXPIRES_AT to newExpiresAt.toString(),
            ),
        )
    }

    /** 带 401 自动重试的 openapi 调用。 */
    private suspend fun <T> callWithAuthRetry(block: suspend (String) -> Result<T>): Result<T> {
        val token = ensureAccessToken().getOrElse { return Result.failure(it) }
        val first = block(token)
        if (first.isSuccess) return first
        val message = first.exceptionOrNull()?.message.orEmpty()
        if (!message.contains("401") && !message.contains("登录已失效")) return first

        Log.d(TAG, "请求返回 401，自动刷新 token 后重试一次")
        val newToken = ensureAccessToken().getOrElse { return first }
        return block(newToken)
    }

    // ---------------- 目录管理 ----------------

    /** 确保备份根目录 Edqiu 存在并返回其目录 id（结果缓存）。 */
    private suspend fun ensureBackupFolder(token: String): Result<Long> = folderMutex.withLock {
        cachedBackupDirId?.let { return@withLock Result.success(it) }
        api.mkdir(token, BACKUP_FOLDER_NAME, 0L).fold(
            onSuccess = { dirId ->
                if (dirId <= 0L) {
                    Result.failure(BackupException("123 网盘创建备份目录失败：未返回目录 id"))
                } else {
                    cachedBackupDirId = dirId
                    Result.success(dirId)
                }
            },
            onFailure = { createError ->
                // 目录可能已存在（mkdir 重名报错）→ 列根目录查找同名文件夹兜底
                val found = api.listFiles(token, 0L).fold(
                    onSuccess = { items ->
                        items.firstOrNull { it.type == 1 && it.filename == BACKUP_FOLDER_NAME }?.fileId
                    },
                    onFailure = { null },
                )
                if (found == null || found <= 0L) {
                    Result.failure(createError)
                } else {
                    cachedBackupDirId = found
                    Result.success(found)
                }
            },
        )
    }

    // ---------------- md5 ----------------

    /** 流式计算整文件 md5（etag 秒传用，十六进制小写）。 */
    private fun computeMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(MD5_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 计算字节数组 md5（分片 sliceMD5 用）。 */
    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 备份根目录名（固定，prepareRemote 创建）。 */
        const val BACKUP_FOLDER_NAME = "Edqiu"

        /** 默认分片大小（服务端返回 sliceSize 为准，此处仅作能力声明）。 */
        private const val DEFAULT_SLICE_SIZE = 16L * 1024 * 1024

        private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
        private const val MD5_BUFFER_SIZE = 64 * 1024

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"

        private const val TAG = "Pan123OpenTarget"
    }
}
