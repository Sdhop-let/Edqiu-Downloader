package com.ed.edqiu.backup.provider

import android.content.Context
import com.ed.twitterdownloader.data.preferences.CloudSyncPreferences
import com.ed.edqiu.backup.model.AuthMode
import com.ed.edqiu.backup.model.BackupCapabilities
import com.ed.edqiu.backup.model.BackupTarget
import com.ed.edqiu.backup.model.ProviderId
import com.ed.edqiu.backup.model.UploadReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自定义 WebDAV（高级）适配器。
 *
 * 配置来源为现有 [CloudSyncPreferences]（serverUrl / username / password / remotePath），
 * 与旧版 SettingsScreen「立即同步」共用同一份配置，行为兼容。
 *
 * **远程路径约定**：[uploadFile] / [exists] 的 `remotePath` 为相对本目标配置根目录
 * （`CloudSyncPreferences.remotePath`）的路径，通常传文件名即可；[prepareRemote] 会确保根目录层级存在。
 */
class WebDavBackupTarget(
    context: Context,
    private val engine: WebDavEngine = WebDavEngine(),
) : BackupTarget {

    private val appContext: Context = context.applicationContext
    private val prefs: CloudSyncPreferences by lazy { CloudSyncPreferences(appContext) }

    override val id: String = ProviderId.WEBDAV
    override val displayName: String = "自定义 WebDAV（高级）"
    override val authMode: AuthMode = AuthMode.WEBDAV_CREDENTIAL
    override val capabilities: BackupCapabilities = BackupCapabilities(
        supportsChunked = false,
        supportsRapidUpload = false,
        defaultChunkSize = 0L,
        fixedRemoteRoot = null,
        maxRetry = 5,
    )

    private fun credential(): WebDavCredential = WebDavCredential(
        serverUrl = prefs.serverUrl,
        username = prefs.username,
        password = prefs.password,
        remotePath = prefs.remotePath,
    )

    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) { prefs.hasWebDavConfig() }

    override suspend fun isAuthorized(): Boolean = withContext(Dispatchers.IO) { prefs.hasWebDavConfig() }

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
