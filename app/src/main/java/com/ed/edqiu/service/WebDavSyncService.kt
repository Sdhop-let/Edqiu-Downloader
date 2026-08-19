package com.ed.edqiu.service

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.backup.model.BackupException
import com.ed.edqiu.backup.provider.WebDavCredential
import com.ed.edqiu.backup.provider.WebDavEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 同步服务（薄门面）。
 *
 * T02 改造：MKCOL / HEAD / PUT / URL 构建 / BasicAuth 等核心逻辑已提取到 [WebDavEngine]，
 * 本服务只做「遍历下载目录 + 跳过已存在 + 逐文件上传」的编排，行为与旧版保持一致：
 * - `syncDownloads(context, prefs)` 签名不变，SettingsScreen 旧调用零改动；
 * - 遍历 `getExternalFilesDir(DIRECTORY_DOWNLOADS)` 下受支持的媒体文件；
 * - 先逐级创建远程目录（prefs.remotePath），再对每个文件 HEAD 判断跳过，未存在则 PUT 上传；
 * - 返回 `(新增数, 跳过数)`。
 */
object WebDavSyncService {

    private const val TAG = "WebDavSyncService"

    private val engine = WebDavEngine()

    suspend fun syncDownloads(context: Context, prefs: CloudSyncPreferences): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!prefs.hasWebDavConfig()) {
                throw BackupException("请先填写 WebDAV 地址、账号和密码")
            }

            val credential = WebDavCredential(
                serverUrl = prefs.serverUrl,
                username = prefs.username,
                password = prefs.password,
                remotePath = prefs.remotePath,
            )

            engine.ensureRemoteDirectories(credential).getOrElse { throw it }

            val sourceDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return@runCatching Pair(0, 0)
            if (!sourceDir.exists() || !sourceDir.isDirectory) return@runCatching Pair(0, 0)

            var uploaded = 0
            var skipped = 0
            sourceDir.walkTopDown()
                .filter { MediaFileTypes.isSupportedMediaFile(it) }
                .forEach { file ->
                    val remoteUrl = engine.buildRemoteUrl(credential, file.name)
                    val exists = engine.exists(remoteUrl, credential).getOrElse { throw it }
                    if (exists) {
                        skipped++
                    } else {
                        engine.put(remoteUrl, file, credential).getOrElse { throw it }
                        uploaded++
                    }
                }
            Pair(uploaded, skipped)
        }.onFailure { error ->
            Log.e(TAG, "WebDAV sync failed", error)
        }
    }
}
