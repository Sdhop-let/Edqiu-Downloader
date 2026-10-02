package com.ed.edqiu.backup.provider

import android.content.Context
import android.util.Log
import com.ed.edqiu.backup.auth.BaiduAuthProgress
import com.ed.edqiu.backup.auth.BaiduDeviceCodeAuth
import com.ed.edqiu.backup.auth.BaiduTokenBundle
import com.ed.edqiu.backup.auth.DeviceCodeSession
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.model.AuthMode
import com.ed.edqiu.backup.model.BackupCapabilities
import com.ed.edqiu.backup.model.BackupException
import com.ed.edqiu.backup.model.BackupTarget
import com.ed.edqiu.backup.model.BackupTask
import com.ed.edqiu.backup.model.ProviderId
import com.ed.edqiu.backup.model.UploadReceipt
import com.ed.edqiu.backup.upload.BaiduChunkSizePolicy
import com.ed.edqiu.backup.upload.ChunkReceiptStore
import com.ed.edqiu.backup.upload.ChunkedUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import com.ed.edqiu.backup.http.runCatchingNotCancelled

/**
 * 百度网盘适配器：设备码扫码授权 + PCS superfile2 分片上传（断点续传）。
 *
 * - 授权：设备码（[AuthMode.DEVICE_CODE_QR]），凭证加密存储于 [CredentialStore]
 *   （key：`access_token` / `refresh_token` / `expires_at`，namespace `baidu`）。
 * - 上传：固定目录 `/apps/Edqiu`（`capabilities.fixedRemoteRoot`，用户不可选根目录）；
 *   分片策略见 [BaiduChunkSizePolicy]（默认 4MB，>4GB 自动升 32MB）。
 * - 续传：分片收据经 [ChunkReceiptStore] 落盘（`filesDir/backup_chunks/{taskId}.json`），
 *   重试时已确认分片跳过；`create` 为原子最后一步。
 *
 * @param context         用于构造 [ChunkReceiptStore]
 * @param credentialStore 加密凭证存储
 */
class BaiduPanTarget(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val api: BaiduPanApi = BaiduPanApi(),
    private val auth: BaiduDeviceCodeAuth = BaiduDeviceCodeAuth(api = api),
    private val uploader: ChunkedUploader = ChunkedUploader(),
) : BackupTarget {

    private val receiptStore: ChunkReceiptStore by lazy { ChunkReceiptStore(context.applicationContext) }

    /** token 刷新互斥：UI 探活/授权流程可与 Worker 队列并发触发刷新，避免竞写凭证（2026-10 整改）。 */
    private val tokenMutex = Mutex()

    override val id: String = ProviderId.BAIDU

    override val displayName: String = "百度网盘"

    override val authMode: AuthMode = AuthMode.DEVICE_CODE_QR

    override val capabilities: BackupCapabilities = BackupCapabilities(
        supportsChunked = true,
        supportsRapidUpload = false,
        defaultChunkSize = DEFAULT_CHUNK_SIZE,
        fixedRemoteRoot = FIXED_ROOT,
        maxRetry = 5,
    )

    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        auth.isConfigured
    }

    override suspend fun isAuthorized(): Boolean = withContext(Dispatchers.IO) {
        val creds = credentialStore.read(ProviderId.BAIDU)
        creds[KEY_ACCESS_TOKEN].isNullOrBlank().not() && creds[KEY_REFRESH_TOKEN].isNullOrBlank().not()
    }

    override suspend fun prepareRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val token = ensureAccessToken()
            val meta = api.meta(token, FIXED_ROOT).getOrElse { throw it }
            if (meta == null) {
                api.createDir(token, FIXED_ROOT).getOrElse { throw it }
            }
        }
    }

    // expectedSize 忽略：百度 createFile 是原子完成协议，远端不存在半截文件
    override suspend fun exists(remotePath: String, expectedSize: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val token = ensureAccessToken()
            val meta = api.meta(token, resolvePath(remotePath)).getOrElse { throw it }
            meta != null
        }
    }

    override suspend fun uploadFile(
        local: File,
        remotePath: String,
        progress: (Float) -> Unit,
    ): Result<UploadReceipt> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val path = resolvePath(remotePath)
            val size = local.length()
            val token = ensureAccessToken()
            val taskId = BackupTask.computeId(id, remotePath)

            // 分片策略 + 切分 + 本地 SHA-1（precreate 秒传/会话校验用）
            val chunkSize = BaiduChunkSizePolicy.chunkSizeFor(size)
            val chunks = uploader.split(local, chunkSize)
            val blockSha1s = chunks.map { computeBlockSha1(it.readBytes()) }

            val precreate = api.precreate(token, path, size, blockSha1s).getOrElse { throw it }
            if (precreate.uploadid.isBlank()) {
                throw BackupException("百度网盘预上传失败：未返回 uploadid")
            }
            val uploadId = precreate.uploadid

            // 恢复上一轮分片收据（2026-10 整改）：仅当「文件指纹 + upload_id」双匹配才允许跳过
            // 已传分片——旧实现只按 taskId 匹配，同名文件内容变化或分片边界变化（跨 4GB 阈值）
            // 时会把新内容当已传分片跳过，产生远端坏文件或永久上传失败。
            val fingerprint = "$size:${local.lastModified()}"
            val savedReceipts = receiptStore.loadMeta(taskId)
            val resumeState = if (savedReceipts != null &&
                savedReceipts.uploadId == uploadId &&
                savedReceipts.fingerprint == fingerprint
            ) {
                Log.d(TAG, "续传：恢复 ${savedReceipts.receipts.size} 个分片收据（指纹/会话匹配）")
                savedReceipts.receipts.toMutableMap()
            } else {
                mutableMapOf()
            }

            // 逐分片上传（跳过 resumeState 中已确认的分片），按字节累计上报进度 0..1
            uploader.uploadChunks(
                chunks = chunks,
                uploader = { chunk ->
                    api.uploadPart(token, path, uploadId, chunk.seq, chunk.readBytes())
                },
                resumeState = resumeState,
                progress = { done ->
                    val ratio = if (size > 0L) {
                        (done.toFloat() / size.toFloat()).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    progress(ratio)
                },
            ).getOrElse { throw it }

            // 分片收据落盘（best-effort）：断点续传用，绑定文件指纹与 upload_id（2026-10 整改），
            // create 前持久化，崩溃后可跳过已确认分片
            receiptStore.save(taskId, resumeState, fingerprint, uploadId)

            // create（原子最后一步）：block_list 用各分片服务端 md5，按 seq 升序
            val md5List = chunks.map { chunk ->
                resumeState[chunk.seq]
                    ?: throw BackupException("分片 ${chunk.seq + 1}/${chunks.size} 缺少上传收据，无法创建文件")
            }
            api.createFile(token, path, size, uploadId, md5List).getOrElse { throw it }

            // 上传完成，清理分片收据
            receiptStore.delete(taskId)
            progress(1f)

            UploadReceipt(remotePath = remotePath, size = size)
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            val token = ensureAccessToken()
            api.meta(token, FIXED_ROOT).getOrElse { throw it }
            Unit
        }
    }

    // ---- 授权编排（供 UI / VM 调用，见 sequence-diagram 场景 1） ----

    /** 发起设备码授权：请求设备码（二维码/验证码）。 */
    suspend fun startDeviceCodeAuth(): Result<DeviceCodeSession> = auth.requestDeviceCode()

    /** 轮询授权结果；成功后返回 token 捆绑包（由调用方调 [saveAuthResult] 落盘）。 */
    suspend fun pollDeviceToken(
        session: DeviceCodeSession,
        onProgress: (BaiduAuthProgress) -> Unit = {},
    ): Result<BaiduTokenBundle> = auth.pollToken(session, onProgress)

    /** 持久化授权结果（access_token / refresh_token / expires_at）。 */
    suspend fun saveAuthResult(bundle: BaiduTokenBundle): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled {
            credentialStore.save(
                ProviderId.BAIDU,
                mapOf(
                    KEY_ACCESS_TOKEN to bundle.accessToken,
                    KEY_REFRESH_TOKEN to bundle.refreshToken,
                    KEY_EXPIRES_AT to bundle.expiresAtMillis.toString(),
                ),
            )
        }
    }

    /** 清除已保存的百度网盘凭证。 */
    suspend fun clearAuth(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNotCancelled { credentialStore.clear(ProviderId.BAIDU) }
    }

    // ---- 内部工具 ----

    /**
     * 校验 access_token 有效性，过期则用 refresh_token 自动刷新并落盘。
     *
     * 未授权或刷新失败时抛 [BackupException]（中文、脱敏）。
     */
    private suspend fun ensureAccessToken(): String = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            val creds = credentialStore.read(ProviderId.BAIDU)
            val accessToken = creds[KEY_ACCESS_TOKEN].orEmpty()
            val refreshToken = creds[KEY_REFRESH_TOKEN].orEmpty()
            if (accessToken.isBlank() || refreshToken.isBlank()) {
                throw BackupException("百度网盘未授权，请先登录百度网盘")
            }
            val expiresAt = creds[KEY_EXPIRES_AT]?.toLongOrNull() ?: 0L
            if (expiresAt > System.currentTimeMillis() + TOKEN_REFRESH_MARGIN_MS) {
                return@withLock accessToken
            }
            val bundle = auth.refresh(refreshToken).getOrElse { throw it }
            credentialStore.save(
                ProviderId.BAIDU,
                mapOf(
                    KEY_ACCESS_TOKEN to bundle.accessToken,
                    KEY_REFRESH_TOKEN to bundle.refreshToken,
                    KEY_EXPIRES_AT to bundle.expiresAtMillis.toString(),
                ),
            )
            bundle.accessToken
        }
    }

    /**
     * 把用户传入的 remotePath 解析为百度完整路径。
     *
     * 固定根目录语义：忽略用户传入的 "Edqiu/" 前缀（架构文档第 8 节），统一落在 `/apps/Edqiu/` 下。
     */
    private fun resolvePath(remotePath: String): String {
        var rel = remotePath.trim().trimStart('/')
        if (rel.startsWith("Edqiu/", ignoreCase = true)) {
            rel = rel.removePrefix("Edqiu/").trimStart('/')
        }
        if (rel.isBlank()) return FIXED_ROOT
        if (rel.startsWith("apps/", ignoreCase = true)) return "/$rel"
        return "$FIXED_ROOT/$rel"
    }

    /** 计算分片字节的 SHA-1（precreate block_list 用，十六进制小写）。 */
    private fun computeBlockSha1(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "BaiduPanTarget"
        const val FIXED_ROOT = "/apps/Edqiu"
        const val DEFAULT_CHUNK_SIZE = 4L * 1024 * 1024

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"

        /** access_token 过期前 60s 即触发刷新，避免边界失效。 */
        const val TOKEN_REFRESH_MARGIN_MS = 60_000L
    }
}
