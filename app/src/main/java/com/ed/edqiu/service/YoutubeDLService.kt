package com.ed.edqiu.service

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object YoutubeDLService {

    private const val TAG = "YoutubeDLService"

    /**
     * Extract video metadata without downloading.
     * Uses --dump-single-json so playlist/media entries are returned in one JSON object.
     * @param proxyUrl Optional HTTP proxy URL (e.g. "http://127.0.0.1:7890") for Clash/V2Ray.
     * @param cookieFilePath Optional Netscape cookie file path for Twitter auth.
     */
        /**
     * Download video with progress callback.
     * Junkfood02 fork callback signature: (progress: Float, eta: Long, line: String) -> Unit
     * @param proxyUrl Optional HTTP proxy URL (e.g. "http://127.0.0.1:7890") for Clash/V2Ray.
     * @param cookieFilePath Optional Netscape cookie file path for Twitter auth.
     * @param processId Optional process id（取消链路：调用方把 taskId 作为 processId 传入，
     *   即可用 `cancelDownload(processId)` 销毁 yt-dlp 进程；缺省自动生成，不可取消）。
     */
    suspend fun downloadVideo(
        url: String,
        formatId: String,
        outputDir: String,
        onProgress: ((Float, Long) -> Unit)? = null,
        proxyUrl: String? = null,
        cookieFilePath: String? = null,
        playlistIndex: Int? = null,
        processId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val pid = processId ?: UUID.randomUUID().toString()
            val outputPath = if (playlistIndex != null) {
                "$outputDir/%(uploader)s/%(title)s_%(playlist_index)s.%(ext)s"
            } else {
                "$outputDir/%(uploader)s/%(title)s.%(ext)s"
            }

            val request = YoutubeDLRequest(url).apply {
                addOption("-f", formatId)
                addOption("-o", outputPath)
                addOption("--newline")
                if (playlistIndex != null) {
                    addOption("--playlist-items", playlistIndex.toString())
                } else {
                    addOption("--no-playlist")
                }
                if (proxyUrl != null) {
                    addOption("--proxy", proxyUrl)
                }
                if (cookieFilePath != null) {
                    addOption("--cookies", cookieFilePath)
                }
            }

            val result = YoutubeDL.getInstance().execute(
                request,
                pid,
                { progress, eta, _ ->
                    onProgress?.invoke(progress, eta)
                }
            )

            // Check for errors
            if (result.err.isNotBlank() && result.out.isBlank()) {
                return@withContext Result.failure(Exception(result.err.trim()))
            }

            // Extract the actual file path from yt-dlp output
            val filePath = extractFilePath(result.out, outputDir)
            Result.success(filePath)
        } catch (e: Exception) {
            Log.e(TAG, "downloadVideo error", e)
            Result.failure(translateLinkerFailure(e))
        }
    }

    /**
     * Cancel an ongoing download by process ID.
     */
    fun cancelDownload(processId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (e: Exception) {
            Log.e(TAG, "cancelDownload error", e)
        }
    }

    /**
     * Update yt-dlp binary to latest version.
     * No proxy needed 鈥?this only updates the yt-dlp binary itself.
     */
    suspend fun updateYoutubeDL(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                context,
                com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.STABLE
            )
            Result.success(status?.name ?: "Updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "updateYoutubeDL error", e)
            Result.failure(e)
        }
    }

    /**
     * 通用 flat-playlist ID 提取（2026-10 真正作品订阅：多平台检测通道）。
     * 只要 ID 不要媒体元数据（--flat-playlist + --dump-single-json，快且省流量）。
     * @param digitOnly true = 仅保留纯数字 ID（X 推文）；false = 保留原样（IG shortcode 等）。
     * @return ID 列表（新→旧），失败返回 failure（调用方静默跳过本轮）。
     *
     * 注：X 作者页提取已于 2026-10-17 废弃——yt-dlp 不支持 `x.com/<user>` 主页
     * （`Unsupported URL`，从来就不支持），twitter 订阅检测改走 data.xapi.XTimelineDetector。
     */
    suspend fun getFlatPlaylistIds(
        pageUrl: String,
        limit: Int = 8,
        proxyUrl: String? = null,
        cookieFilePath: String? = null,
        digitOnly: Boolean = false,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(pageUrl).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--playlist-items", "1:$limit")
                addOption("--no-warnings")
                if (proxyUrl != null) addOption("--proxy", proxyUrl)
                if (cookieFilePath != null) addOption("--cookies", cookieFilePath)
            }
            val result = YoutubeDL.getInstance().execute(request, null)
            val json = parseDumpJson(result.out)
            val entries = json.optJSONArray("entries")
            val ids = mutableListOf<String>()
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val raw = entry.optString("id")
                    val id = if (digitOnly) raw.takeWhile { it.isDigit() } else raw.trim()
                    if (id.isNotBlank()) ids += id
                }
            }
            if (ids.isEmpty()) {
                Result.failure(Exception("用户页未返回作品（可能改名/私密/无作品）"))
            } else {
                Result.success(ids)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getFlatPlaylistIds error for $pageUrl", e)
            Result.failure(translateLinkerFailure(e))
        }
    }

    /**
     * 16KB 内存页设备（Android 15+ 部分机型）上，ffmpeg 依赖载荷内 libwebp* 等 .so 为
     * 4KB 对齐、libavcodec 直接依赖它们，加载即 linker 失败且报错晦涩难懂——
     * 识别这类失败并翻译为用户可读信息（原异常保留为 cause，日志行为不变）。
     */
    private fun translateLinkerFailure(e: Exception): Exception {
        val msg = generateSequence(e as Throwable) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        val linkerHit = listOf("libavcodec", "libwebp", "dlopen failed", "library not found", "cannot locate")
            .any { msg.contains(it, ignoreCase = true) }
        return if (linkerHit)
            Exception("此设备为 16KB 内存页机型，当前 ffmpeg 依赖库未适配（上游问题），已自动改用直连引擎或稍后重试", e)
        else e
    }

            private fun parseDumpJson(output: String): org.json.JSONObject {
        val trimmed = output.trim()
        if (trimmed.startsWith("{")) return org.json.JSONObject(trimmed)

        val firstJsonLine = output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") && it.endsWith("}") }

        if (firstJsonLine != null) return org.json.JSONObject(firstJsonLine)
        throw IllegalArgumentException("yt-dlp did not return JSON metadata")
    }

        /**
     * 从 yt-dlp 输出提取最终文件路径。
     * 优先级：[Merger]（合并产物）→ "has already been downloaded"（秒传/已存在）→
     * 首个 [download] Destination（单格式下载的最终文件）。
     * 注意合并下载时首个 Destination 是视频单流中间文件（合并后即被 yt-dlp 删除），
     * 旧实现先命中它导致返回已删除路径、下载误判失败（2026-10 修复）。
     */
    private fun extractFilePath(output: String, baseDir: String): String {
        val lines = output.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[Merger] Merging formats into")) {
                return trimmed.removePrefix("[Merger] Merging formats into").trim()
                    .removeSurrounding("\"")
            }
        }
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains("has already been downloaded")) {
                return trimmed.substringAfter("[download] ").substringBefore(" has already").trim()
            }
        }
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[download] Destination:")) {
                return trimmed.removePrefix("[download] Destination:").trim()
            }
        }
        return "$baseDir/downloaded_video.mp4"
    }

    /**
     * Write a Netscape-format cookie file for yt-dlp --cookies option.
     * Required cookies: auth_token and ct0 from Twitter/X.
     * @param context Application context for file storage
     * @param authToken Twitter auth_token cookie value
     * @param ct0 Twitter ct0 (CSRF) cookie value
     * @return Absolute path to the written cookie file
     */
    fun writeCookieFile(context: Context, authToken: String, ct0: String): String {
        val cookieDir = File(context.filesDir, "yt-dlp-cookies")
        cookieDir.mkdirs()
        val cookieFile = File(cookieDir, "twitter_cookies.txt")

        val content = buildString {
            appendLine("# Netscape HTTP Cookie File")
            appendLine("# This is a generated file!  Do not edit.")
            appendLine()
            // Twitter/X cookies 鈥?domain .x.com and .twitter.com
            appendLine(".x.com\tTRUE\t/\tTRUE\t0\tauth_token\t$authToken")
            appendLine(".x.com\tTRUE\t/\tTRUE\t0\tct0\t$ct0")
            appendLine(".twitter.com\tTRUE\t/\tTRUE\t0\tauth_token\t$authToken")
            appendLine(".twitter.com\tTRUE\t/\tTRUE\t0\tct0\t$ct0")
        }

        // 2026-10 整改：原子写（tmp+rename）——并发下载同时触发写入时读者不会读到半截文件
        // 2026-10：tmp 名带时间戳——并发下载同时写 cookie 时不再互踩同一个 tmp
        val tmp = File(cookieDir, cookieFile.name + "." + System.currentTimeMillis() + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(cookieFile)) {
            cookieFile.outputStream().use { output -> tmp.inputStream().use { it.copyTo(output) } }
            tmp.delete()
        }
        Log.d(TAG, "Cookie file written to: ${cookieFile.absolutePath}")
        return cookieFile.absolutePath
    }

    /**
     * 清理全部 yt-dlp cookie 落盘文件（2026-10 整改：cookie 文件含明文 auth_token/ct0，
     * 旧实现常驻 filesDir 从不删除）。两个调用时机：
     *   1. 用户在设置中清除 Cookie 时；
     *   2. Application 启动时（清理上次会话残留——每次下载需要 cookie 时都会重新写入）。
     */
    fun cleanupCookieFiles(context: Context) {
        runCatching {
            val cookieDir = File(context.filesDir, "yt-dlp-cookies")
            cookieDir.listFiles()?.forEach { file ->
                if (file.isFile && file.delete()) {
                    Log.i(TAG, "Deleted stale cookie file: ${file.name}")
                }
            }
        }.onFailure { Log.w(TAG, "cleanupCookieFiles failed", it) }
    }
}

