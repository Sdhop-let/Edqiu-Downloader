package com.ed.edqiu.service

import android.content.Context
import android.util.Log
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 下载后网盘联动（2026-10-17：预下载功能整体退役，仅保留此能力）。
 *
 * 用户下载（收件箱批量/单条/订阅作品点按下载）完成后调用 [syncAfterDownloads]：
 * 对当前选中网盘跑一轮双向同步——远端已有 HEAD 跳过（幂等），新文件断点续传上传，
 * 云端独有文件回补本地。WebDAV 未启用/未验证时静默跳过。
 */
object DownloadCloudSync {

    private const val TAG = "DownloadCloudSync"

    /** 下载完成后的网盘联动（fire-and-forget，调用方不关心结果）。 */
    suspend fun syncAfterDownloads(context: Context) = withContext(Dispatchers.IO) {
        runCatching { syncOnce(context) }
            .onFailure { Log.w(TAG, "下载后网盘联动失败：${it.message}") }
        Unit
    }

    private suspend fun syncOnce(context: Context): Boolean {
        val cloudSyncPreferences = CloudSyncPreferences(context)
        val account = cloudSyncPreferences.currentAccount()
        if (!cloudSyncPreferences.enabled || !account.verified || !account.isComplete) {
            Log.i(TAG, "WebDAV 未启用/未验证，跳过联动")
            return false
        }
        return WebDavSyncService.syncDownloads(context, cloudSyncPreferences).fold(
            onSuccess = { r ->
                Log.i(
                    TAG,
                    "联动完成：上传 ${r.uploaded}，下载 ${r.downloaded}，跳过 ${r.skippedExisting}，冲突 ${r.conflicts}"
                )
                r.conflicts == 0
            },
            onFailure = { e ->
                Log.w(TAG, "联动失败：${e.message}")
                false
            },
        )
    }
}
