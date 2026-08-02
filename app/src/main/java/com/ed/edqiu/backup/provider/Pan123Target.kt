package com.ed.edqiu.backup.provider

import android.content.Context
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.model.AuthMode
import com.ed.edqiu.backup.model.BackupCapabilities
import com.ed.edqiu.backup.model.BackupException
import com.ed.edqiu.backup.model.BackupTarget
import com.ed.edqiu.backup.model.ProviderId
import com.ed.edqiu.backup.model.UploadReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 123 网盘适配器。
 *
 * 授权：WebDAV 账号密码（账号为手机号/邮箱，密码为 123 网盘网页端生成的 WebDAV 应用密码）。
 *
 * **URL 约定**：`https://webdav-{accountId}.pd1.123pan.cn/webdav`；
 * accountId 从加密凭证读取（逻辑 key `account_id`）；若用户粘贴了完整地址（含 `://`）则直接使用。
 * 凭证存 [CredentialStore]（逻辑 key：`server_url` / `username` / `password` / `account_id` / `remote_path`）。
 *
 * **远程路径约定**：[uploadFile] / [exists] 的 `remotePath` 为相对根目录（默认 `Edqiu`）的路径，
 * 通常传文件名即可。
 */
class Pan123Target(
    context: Context,
    private val credentialStore: CredentialStore,
    private val engine: WebDavEngine = WebDavEngine(),
) : BackupTarget {

    private val appContext: Context = context.applicationContext

    override val id: String = ProviderId.PAN123
    override val displayName: String = "123网盘"
    override val authMode: AuthMode = AuthMode.WEBDAV_CREDENTIAL
    override val capabilities: BackupCapabilities = BackupCapabilities(
        supportsChunked = false,
        supportsRapidUpload = false,
        defaultChunkSize = 0L,
        fixedRemoteRoot = null,
        maxRetry = 5,
    )

    companion object {
        /** 123 网盘远程根目录（架构文档 8：123/WebDAV/CD2 用 remotePath，默认 Edqiu）。 */
        const val DEFAULT_REMOTE_ROOT = "Edqiu"

        /** 由 accountId 构建标准 123 网盘 WebDAV 地址；accountId 为空返回空串。 */
        fun buildServerUrl(accountId: String): String {
            val id = accountId.trim()
            return if (id.isBlank()) "" else "https://webdav-$id.pd1.123pan.cn/webdav"
        }
    }

    private fun readCreds(): Map<String, String> = credentialStore.read(ProviderId.PAN123)

    /** 解析服务器地址：优先用户粘贴的完整地址（含 `://`）；否则用 accountId 拼标准地址。 */
    private fun resolveServerUrl(): String {
        val stored = readCreds()["server_url"].orEmpty().trim()
        if (stored.isNotBlank() && stored.contains("://")) return stored
        return buildServerUrl(readCreds()["account_id"].orEmpty())
    }

    private fun credential(): WebDavCredential {
        val creds = readCreds()
        return WebDavCredential(
            serverUrl = resolveServerUrl(),
            username = creds["username"].orEmpty(),
            password = creds["password"].orEmpty(),
            remotePath = creds["remote_path"].orEmpty().ifBlank { DEFAULT_REMOTE_ROOT },
        )
    }

    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        val creds = readCreds()
        creds["username"].orEmpty().isNotBlank() &&
            creds["password"].orEmpty().isNotBlank() &&
            resolveServerUrl().isNotBlank()
    }

    override suspend fun isAuthorized(): Boolean = withContext(Dispatchers.IO) {
        val creds = readCreds()
        creds["username"].orEmpty().isNotBlank() &&
            creds["password"].orEmpty().isNotBlank() &&
            resolveServerUrl().isNotBlank()
    }

    override suspend fun prepareRemote(): Result<Unit> = engine.ensureRemoteDirectories(credential())

    override suspend fun exists(remotePath: String): Result<Boolean> {
        val credential = credential()
        val url = engine.buildRemoteUrl(credential, remotePath)
        return engine.exists(url, credential)
    }

    override suspend fun uploadFile(
        local: File,
        remotePath: String,
        progress: (Float) -> Unit,
    ): Result<UploadReceipt> {
        val credential = credential()
        val url = engine.buildRemoteUrl(credential, remotePath)
        val size = local.length()
        return engine.put(url, local, credential) { bytes ->
            val p = if (size <= 0L) 0f else (bytes.toFloat() / size.toFloat()).coerceIn(0f, 1f)
            progress(p)
        }.map {
            UploadReceipt(
                remotePath = joinRemotePath(credential.remotePath, remotePath),
                size = size,
            )
        }
    }

    override suspend fun testConnection(): Result<Unit> {
        val serverUrl = resolveServerUrl()
        if (serverUrl.isBlank()) {
            return Result.failure(
                BackupException("123 网盘尚未配置：请填写账号、WebDAV 密码或 accountId")
            )
        }
        return engine.probe(serverUrl, credential()).map { Unit }
    }

    private fun joinRemotePath(root: String, relative: String): String =
        listOf(root, relative).filter { it.isNotBlank() }.joinToString("/")
}
