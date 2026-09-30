package com.ed.edqiu.data.metadata

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 推文预览图本地缓存（2026-09-30 v1.6.8）。
 *
 * 分享入库拿到封面 URL 后当场把图片落盘（covers/<tweetId>.<ext>），
 * 收件箱与媒体库此后直接读本地文件渲染——用户下次进入 App 不再出现
 * 「预览图需要二次同步/联网补拉」的空窗（离线也能看到封面）。
 *
 * 设计约束：
 * - 幂等：已有本地文件直接返回，不重复下载；
 * - 尽力而为：下载失败/超时返回 null，调用方回退远程 URL，绝不阻塞保存/下载主流程；
 * - 目录位于 App 专属存储（getExternalFilesDir），不进媒体库扫描根目录，不会被当成媒体收录。
 */
object CoverStore {

    private const val TAG = "CoverStore"
    private const val DIR_NAME = "covers"
    private val KNOWN_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "gif")
    /** localCoverPath 的探测顺序（jpeg 归一为 jpg 存储，这里仍保留 jpeg 兼容旧文件）。 */
    private val SCAN_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "gif")

    fun coversDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)

    /** 已落盘的本地封面路径；不存在返回 null。 */
    fun localCoverPath(context: Context, tweetId: String): String? {
        if (tweetId.isBlank()) return null
        val dir = coversDir(context)
        SCAN_EXTENSIONS.forEach { ext ->
            val file = File(dir, "$tweetId.$ext")
            if (file.exists() && file.length() > 0L) return file.absolutePath
        }
        return null
    }

    /**
     * 把远程封面下载到本地并返回本地路径（已有本地文件时幂等直返）。
     * 超时 [timeoutMs] 内未完成即放弃（返回 null），保证分享保存页 8s 元数据窗口不被吃满。
     */
    suspend fun ensureLocalCover(
        context: Context,
        tweetId: String,
        remoteUrl: String?,
        timeoutMs: Long = 5_000L
    ): String? {
        if (tweetId.isBlank()) return null
        val appContext = context.applicationContext
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                localCoverPath(appContext, tweetId)?.let { return@withContext it }
                if (remoteUrl.isNullOrBlank() || !remoteUrl.startsWith("http")) {
                    return@withContext null
                }
                val ext = remoteUrl.substringBefore('?')
                    .substringAfterLast('.', missingDelimiterValue = "jpg")
                    .lowercase()
                    .takeIf { it in KNOWN_EXTENSIONS }
                    ?.let { if (it == "jpeg") "jpg" else it }
                    ?: "jpg"
                val dir = coversDir(appContext)
                dir.mkdirs()
                val target = File(dir, "$tweetId.$ext")
                val part = File(dir, "$tweetId.$ext.part")
                runCatching {
                    val connection =
                        (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4_000
                            readTimeout = 4_000
                            setRequestProperty("User-Agent", "Edqiu/1.0")
                        }
                    try {
                        if (connection.responseCode != 200) {
                            error("HTTP ${connection.responseCode}")
                        }
                        connection.inputStream.use { input ->
                            part.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (part.length() <= 0L) error("empty cover body")
                        if (!part.renameTo(target)) {
                            part.copyTo(target, overwrite = true)
                            part.delete()
                        }
                        target.absolutePath
                    } finally {
                        connection.disconnect()
                    }
                }.onFailure {
                    Log.w(TAG, "cover download failed for $tweetId: ${it.message}")
                    part.delete()
                }.getOrNull()
            }
        }
    }

    /** 清理全部本地封面（预留：备份恢复/数据重置场景可调用）。 */
    fun clearAll(context: Context) {
        runCatching { coversDir(context).deleteRecursively() }
    }
}
