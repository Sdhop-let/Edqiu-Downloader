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
import com.ed.edqiu.backup.model.BackupTask
import com.ed.edqiu.backup.model.ProviderId
import com.ed.edqiu.backup.model.UploadReceipt
import com.ed.edqiu.backup.upload.AliyunChunkSizePolicy
import com.ed.edqiu.backup.upload.Chunk
import com.ed.edqiu.backup.upload.ChunkReceiptStore
import com.ed.edqiu.backup.upload.ChunkedUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.security.MessageDigest

/**
 * 阿里云盘适配器（[BackupTarget] 实现，id = [ProviderId.ALIYUN]）。
 *
 * - 授权方式：[AuthMode.WEBVIEW_TOKEN]（WebView 登录截取 refresh_token，失败手动粘贴兜底）
 * - 上传：openapi 分片（默认 10MB，上限 10000 片 ≈ 97GB）+ sha1 秒传 + 断点续传
 *   （本地 [ChunkReceiptStore] 收据 + 服务端 [AliPanApi.listUploadedParts] 双通道恢复）
 * - 远程根目录：固定 `/EdqiuBackup`（[BACKUP_FOLDER_NAME]），文件平铺存入该目录
 * - token：refresh_token 约 90 天，每次 401 自动刷新并持久化（[ensureAccessToken] + [callWithAuthRetry]）
 * - 凭证：存 [CredentialStore]（providerId=aliyun），逻辑 key：refresh_token / access_token / expires_at
 *
 * 构造说明（T05 接线用）：`AliPanTarget(context, credentialStore)`，其余参数可注入便于测试。
 */
class AliPanTarget(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val chunkReceiptStore: ChunkReceiptStore = ChunkReceiptStore(context),
    private val api: AliPanApi = AliPanApi(BuildConfig.ALI_CLIENT_ID, BuildConfig.ALI_CLIENT_SECRET),
    private val uploader: ChunkedUploader = ChunkedUploader(),
) : BackupTarget {

    override val id: String = ProviderId.ALIYUN

    override val displayName: String = "阿里云盘"

    override val authMode: AuthMode = AuthMode.WEBVIEW_TOKEN

    override val capabilities: BackupCapabilities = BackupCapabilities(
        supportsChunked = true,
        supportsRapidUpload = true,
        defaultChunkSize = ALIYUN_CHUNK_SIZE,
        fixedRemoteRoot = "/$BACKUP_FOLDER_NAME",
        maxRetry = 5,
    )

    private val tokenMutex = Mutex()
    private val driveMutex = Mutex()

    @Volatile
    private var cachedDriveId: String? = null

    @Volatile
    private var cachedBackupFolderId: String? = null

    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        !credentialStore.read(id)["refresh_token"].isNullOrBlank()
    }

    override suspend fun isAuthorized(): Boolean = withContext(Dispatchers.IO) {
        val refresh = credentialStore.read(id)["refresh_token"]
        if (refresh.isNullOrBlank()) return@withContext false
        ensureAccessToken().isSuccess
    }

    /**
     * 授权入口：WebView / 手动粘贴拿到 refresh_token 后调用。
     * 立即换取 access_token 校验有效性，成功返回 Unit（凭证已持久化）。
     */
    suspend fun setRefreshToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        val t = token.trim()
        when {
            t.isBlank() -> Result.failure(BackupException("refresh_token 不能为空"))
            t.length < 20 -> Result.failure(BackupException("refresh_token 格式不正确，请检查是否复制完整"))
            else -> {
                credentialStore.save(id, mapOf("refresh_token" to t))
                ensureAccessToken().map { }
            }
        }
    }

    override suspend fun prepareRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        callWithAuthRetry { token ->
            val driveId = getDriveIdCached(token).getOrElse { return@callWithAuthRetry Result.failure(it) }
            ensureBackupFolder(token, driveId).map { }
        }
    }

    override suspend fun exists(remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val name = remotePath.substringAfterLast('/')
        if (name.isBlank()) {
            return@withContext Result.failure(BackupException("远程路径无效：$remotePath"))
        }
        callWithAuthRetry { token ->
            val driveId = getDriveIdCached(token).getOrElse { return@callWithAuthRetry Result.failure(it) }
            val folderId = ensureBackupFolder(token, driveId).getOrElse { return@callWithAuthRetry Result.failure(it) }
            api.listFiles(token, driveId, folderId).map { items ->
                items.any { it.type == "file" && it.name == name }
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
            val sha1 = uploader.computeSha1(local)
            val token = ensureAccessToken().getOrElse { throw it }
            val driveId = getDriveIdCached(token).getOrElse { throw it }
            val folderId = ensureBackupFolder(token, driveId).getOrElse { throw it }

            val chunkSize = AliyunChunkSizePolicy.chunkSizeFor(size)
            // 0 字节文件无分片（阿里分片最小限制 100KB），直接走 complete
            val partCount = if (size == 0L) 0 else uploader.split(local, chunkSize).size
            val proofCode = computeProofCode(token, size, local)

            val create = api.createFile(
                accessToken = token,
                driveId = driveId,
                parentFileId = folderId,
                name = name,
                type = "file",
                size = size,
                contentHash = sha1,
                proofCode = proofCode,
                partCount = partCount,
            ).getOrElse { throw it }

            // 秒传命中 / 同名已存在（auto_ignore）：无需上传分片
            if (create.rapidUpload || create.exist) {
                Log.d(TAG, "秒传命中：$name")
                progress(1f)
                return@withContext Result.success(
                    UploadReceipt(
                        remotePath = "$BACKUP_FOLDER_NAME/$name",
                        size = size,
                        rapidMatched = true,
                    )
                )
            }

            val uploadId = create.uploadId
            if (uploadId.isBlank()) throw BackupException("创建上传会话失败：未返回 upload_id")

            if (partCount == 0) {
                // 0 字节文件：无分片，直接 complete
                api.completeFile(token, driveId, create.fileId, uploadId).getOrElse { throw it }
                progress(1f)
                return@withContext Result.success(
                    UploadReceipt(
                        remotePath = "$BACKUP_FOLDER_NAME/$name",
                        size = size,
                        rapidMatched = false,
                    )
                )
            }

            val chunks = uploader.split(local, chunkSize)
            val partUrlByNumber = create.partInfoList.associate { it.partNumber to it.uploadUrl }
            if (partUrlByNumber.size < chunks.size) {
                throw BackupException("创建上传会话失败：分片上传地址不完整")
            }

            // 断点续传：本地收据 + 服务端已上传分片合并（本地丢失时兜底）
            val taskId = BackupTask.computeId(id, remotePath)
            val resumeState = chunkReceiptStore.load(taskId).toMutableMap()
            val serverParts = api.listUploadedParts(token, driveId, create.fileId, uploadId)
                .getOrDefault(emptyList())
            serverParts.forEach { part ->
                val seq = part.partNumber - 1
                if (seq >= 0 && !resumeState.containsKey(seq)) {
                    resumeState[seq] = part.etag.ifBlank { "part${part.partNumber}" }
                }
            }

            val uploadOne: suspend (Chunk) -> Result<String> = { chunk ->
                val partNum = chunk.seq + 1
                val url = partUrlByNumber[partNum]
                if (url.isNullOrBlank()) {
                    Result.failure(BackupException("分片 $partNum/${chunks.size} 缺少上传地址"))
                } else {
                    api.uploadPart(url, chunk.readBytes()).map { "part$partNum" }
                }
            }
            val onBytes: (Long) -> Unit = { done ->
                if (size > 0L) progress((done.toFloat() / size.toFloat()).coerceIn(0f, 1f))
            }

            uploader.uploadChunks(chunks, uploadOne, resumeState, onBytes).getOrElse { throw it }
            chunkReceiptStore.save(taskId, resumeState).getOrElse { throw it }

            api.completeFile(token, driveId, create.fileId, uploadId).getOrElse { throw it }
            chunkReceiptStore.delete(taskId)
            progress(1f)

            Result.success(
                UploadReceipt(
                    remotePath = "$BACKUP_FOLDER_NAME/$name",
                    size = size,
                    rapidMatched = false,
                )
            )
        } catch (error: Exception) {
            val wrapped = if (error is BackupException) error else BackupException("上传失败：${error.toUserMessage()}", error)
            Log.w(TAG, "上传失败：${wrapped.message}")
            Result.failure(wrapped)
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        callWithAuthRetry { token ->
            getDriveIdCached(token).map { }
        }
    }

    // ---------------- token 管理 ----------------

    /**
     * 确保有未过期的 access_token；缺失/过期时用 refresh_token 自动刷新并持久化。
     * 并发安全（[tokenMutex] 串行化刷新，避免多个上传并发重复换 token）。
     */
    private suspend fun ensureAccessToken(): Result<String> = tokenMutex.withLock {
        val creds = credentialStore.read(id)
        val refreshToken = creds["refresh_token"]
        if (refreshToken.isNullOrBlank()) {
            return@withLock Result.failure(BackupException("未配置阿里云盘，请先登录授权"))
        }
        val accessToken = creds["access_token"]
        val expiresAt = creds["expires_at"]?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (!accessToken.isNullOrBlank() && expiresAt > now + TOKEN_EXPIRY_MARGIN_MS) {
            return@withLock Result.success(accessToken)
        }

        Log.d(TAG, "access_token 缺失或过期，使用 refresh_token 刷新")
        api.accessToken(refreshToken).fold(
            onSuccess = { bundle ->
                val newAccess = bundle.accessToken
                if (newAccess.isBlank()) {
                    Result.failure(BackupException("刷新令牌返回为空，请重新登录阿里云盘"))
                } else {
                    val newExpiresAt = System.currentTimeMillis() + (bundle.expiresIn * 1000L).coerceAtLeast(60_000L)
                    credentialStore.save(
                        id,
                        mapOf(
                            "access_token" to newAccess,
                            "refresh_token" to bundle.refreshToken.ifBlank { refreshToken },
                            "expires_at" to newExpiresAt.toString(),
                        ),
                    )
                    Result.success(newAccess)
                }
            },
            onFailure = { error ->
                Log.w(TAG, "刷新 access_token 失败：${error.message}")
                Result.failure(error)
            },
        )
    }

    /**
     * 带 401 自动重试的 openapi 调用：先确保 token，调用失败且错误含 401 时
     * 强制刷新一次 token 并重试（应对 token 中途过期 / 服务器端失效）。
     */
    private suspend fun <T> callWithAuthRetry(block: suspend (String) -> Result<T>): Result<T> {
        val token = ensureAccessToken().getOrElse { return Result.failure(it) }
        val first = block(token)
        if (first.isSuccess) return first
        val message = first.exceptionOrNull()?.message.orEmpty()
        if (!message.contains("401")) return first

        Log.d(TAG, "请求返回 401，自动刷新 token 后重试一次")
        val newToken = ensureAccessToken().getOrElse { return first }
        return block(newToken)
    }

    // ---------------- 目录 / drive 管理 ----------------

    private suspend fun getDriveIdCached(token: String): Result<String> = driveMutex.withLock {
        cachedDriveId?.let { return@withLock Result.success(it) }
        api.getDriveInfo(token).fold(
            onSuccess = { info ->
                val driveId = info.defaultDriveId.ifBlank { info.resourceDriveId }
                if (driveId.isBlank()) {
                    Result.failure(BackupException("获取阿里云盘空间信息失败：未返回 drive_id"))
                } else {
                    cachedDriveId = driveId
                    Result.success(driveId)
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    /**
     * 确保 /EdqiuBackup 目录存在并返回其 file_id（结果缓存）。
     * 创建失败（多为 refuse 模式下 AlreadyExist）→ 列根目录查找同名文件夹兜底。
     */
    private suspend fun ensureBackupFolder(token: String, driveId: String): Result<String> {
        cachedBackupFolderId?.let { return Result.success(it) }
        return api.createFolder(token, driveId, "root", BACKUP_FOLDER_NAME).fold(
            onSuccess = { item ->
                if (item.fileId.isBlank()) {
                    Result.failure(BackupException("创建备份目录失败：未返回目录 id"))
                } else {
                    cachedBackupFolderId = item.fileId
                    Result.success(item.fileId)
                }
            },
            onFailure = { createError ->
                val found = api.listFiles(token, driveId, "root").fold(
                    onSuccess = { items ->
                        items.firstOrNull { it.name == BACKUP_FOLDER_NAME && it.type == "folder" }?.fileId
                    },
                    onFailure = { null },
                )
                if (found.isNullOrBlank()) {
                    Result.failure(createError)
                } else {
                    cachedBackupFolderId = found
                    Result.success(found)
                }
            },
        )
    }

    // ---------------- 秒传 proof_code ----------------

    /**
     * 计算阿里秒传 proof_code（对齐社区实现 alist / pan-client）：
     * `md5(access_token)` 前 16 位 hex 视为 uint64，对文件大小取模得到起始偏移，
     * 读取该偏移起最多 8 字节，再取其 SHA-1（hex）。
     * 计算失败返回空串，秒传自动退化为普通分片上传（不影响主流程）。
     */
    private fun computeProofCode(accessToken: String, fileSize: Long, file: File): String {
        if (fileSize <= 0L) return ""
        return try {
            val md5 = MessageDigest.getInstance("MD5").digest(accessToken.toByteArray(Charsets.UTF_8))
            val md5Hex = md5.joinToString("") { "%02x".format(it) }
            val offset = BigInteger(md5Hex.take(16), 16).mod(BigInteger.valueOf(fileSize)).toLong()
            val length = minOf(8L, fileSize - offset)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(length.toInt())
                raf.readFully(buffer)
                MessageDigest.getInstance("SHA-1").digest(buffer).joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "计算 proof_code 失败，秒传退化为普通上传", e)
            ""
        }
    }

    companion object {
        /** 备份根目录名（固定，prepareRemote 创建）。 */
        const val BACKUP_FOLDER_NAME = "EdqiuBackup"

        private const val ALIYUN_CHUNK_SIZE = 10L * 1024 * 1024
        private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
        private const val TAG = "AliPanTarget"
    }
}
