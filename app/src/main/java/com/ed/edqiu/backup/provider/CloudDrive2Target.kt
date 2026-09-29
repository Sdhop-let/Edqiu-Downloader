package com.ed.edqiu.backup.provider

import android.content.Context
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.model.AuthMode
import com.ed.edqiu.backup.model.BackupCapabilities
import com.ed.edqiu.backup.model.BackupTarget
import com.ed.edqiu.backup.model.ProviderId
import com.ed.edqiu.backup.model.UploadReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CloudDrive2 适配器（本机 WebDAV）。
 *
 * **授权方式**：NONE（架构文档 1.4：探测 `127.0.0.1:19798`，一键填充，无需用户输入账号密码）。
 * CD2 设置里的账号密码可写入凭证 store（逻辑 key：`server_url` / `username` / `password` / `remote_path`），
 * 留空时按匿名访问处理。
 *
 * **远程路径约定**：[uploadFile] / [exists] 的 `remotePath` 为相对根目录（默认 `Edqiu`）的路径，
 * 通常传文件名即可。
 */
class CloudDrive2Target(
    context: Context,
    private val credentialStore: CredentialStore,
    private val engine: WebDavEngine = WebDavEngine(),
) : BackupTarget {

    private val appContext: Context = context.applicationContext

    override val id: String = ProviderId.CLOUDDRIVE2
    override val displayName: String = "CloudDrive2"
    override val authMode: AuthMode = AuthMode.NONE
    override val capabilities: BackupCapabilities = BackupCapabilities(
        supportsChunked = false,
        supportsRapidUpload = false,
        defaultChunkSize = 0L,
        fixedRemoteRoot = null,
        maxRetry = 5,
    )

    companion object {
        /** CloudDrive2 默认本机 WebDAV 地址。 */
        const val LOCAL_SERVER_URL = "http://127.0.0.1:19798/dav"

        /** 远程根目录（架构文档 8：CD2 用 remotePath，默认 Edqiu）。 */
        const val DEFAULT_REMOTE_ROOT = "Edqiu"
    }

    private fun readCreds(): Map<String, String> = credentialStore.read(ProviderId.CLOUDDRIVE2)

    private fun credential(): WebDavCredential {
        val creds = readCreds()
        return WebDavCredential(
            serverUrl = creds["server_url"].orEmpty().ifBlank { LOCAL_SERVER_URL },
            username = creds["username"].orEmpty(),
            password = creds["password"].orEmpty(),
            remotePath = creds["remote_path"].orEmpty().ifBlank { DEFAULT_REMOTE_ROOT },
        )
    }

    /**
     * 本机探测：`127.0.0.1:19798/dav` 是否可达（OPTIONS 2xx/3xx 视为已安装并运行）。
     */
    suspend fun detectLocal(): Boolean = withContext(Dispatchers.IO) {
        engine.probe(
            LOCAL_SERVER_URL,
            WebDavCredential(serverUrl = LOCAL_SERVER_URL, remotePath = DEFAULT_REMOTE_ROOT),
        ).getOrDefault(false)
    }

    override suspend fun isConfigured(): Boolean = detectLocal()

    override suspend fun isAuthorized(): Boolean = detectLocal()

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
        val credential = credential()
        return engine.probe(credential.serverUrl, credential).map { Unit }
    }

    private fun joinRemotePath(root: String, relative: String): String =
        listOf(root, relative).filter { it.isNotBlank() }.joinToString("/")
}
