package com.ed.edqiu.service

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object YoutubeDLService {

    private const val TAG = "YoutubeDLService"

    /**
     * 进度停滞判定（2026-11-07 审计修复）：yt-dlp 的 execute 是阻塞调用，协程取消无法
     * 中断它——慢速/半开连接下可数小时无进展，任务永久卡 DOWNLOADING（retryDueDownloads
     * 只捞 FAILED 捞不到）、订阅看门狗的 withTimeoutOrNull 也等不到阻塞返回。
     * 看门狗按「距上次进度回调的间隔」强杀进程，阻塞的 execute 随进程死亡立即返回。
     */
    private const val STALL_TIMEOUT_MS = 15 * 60_000L
    private const val STALL_CHECK_INTERVAL_MS = 60_000L

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
        processId: String? = null,
        expectedStreams: Int = 2,
        /** true = 解析全部产物行（裸 status URL 对多视频推文是 playlist，逐个落位）。 */
        multiVideo: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val pid = processId ?: UUID.randomUUID().toString()
            val progressTracker = YtdlpProgressTracker(expectedStreams)
            // P1 审计修复（2026-11-21）：输出模板加 [%(id)s]。旧模板 {uploader}/{title}.ext
            // 无推文 id——yt-dlp 标题 = 作者 + 文案前 72 字符，同作者两条文案开头相同的推文
            // （模板句/GIF 常见）触发标题碰撞：第二条命中 "has already been downloaded"
            // 不下载任何内容，旧文件还被改名挂到新推文规约名下（内容错配 + 旧路径悬空）。
            // id 进文件名后碰撞消失；extractFilePath 同步校验 "已存在" 行的身份。
            val outputPath = if (playlistIndex != null) {
                "$outputDir/%(uploader)s/%(title)s_%(playlist_index)s_[%(id)s].%(ext)s"
            } else {
                "$outputDir/%(uploader)s/%(title)s_[%(id)s].%(ext)s"
            }
            // 从 URL 提取推文 id（.../status/{id} 或 .../status/{id}/video/{n}），
            // 供 extractFilePath 校验 "has already been downloaded" 行确属本推文
            val expectedTweetId = url.substringAfterLast("/status/", "")
                .substringBefore('/').takeIf { it.matches(Regex("\\d+")) }

            val request = YoutubeDLRequest(url).apply {
                addOption("-f", formatId)
                addOption("-o", outputPath)
                addOption("--newline")
                addOption("--progress-template", YtdlpProgressTracker.PROGRESS_TEMPLATE)
                // 2026-11-11 提速：X 视频 HLS 自适应流是数百个分片，yt-dlp 默认串行拉取
                // （一次 1 个 fragment），高延迟代理线路上吞吐被 RTT 串行化——这是
                // 「网络良好但下载极慢」的主因之一。开 8 并发分片，网页播放器同款策略。
                addOption("--concurrent-fragments", "8")
                addOption("--socket-timeout", "30") // 单个 socket 操作 30s 失败，防半开连接永久挂起
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

            val lastProgressAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            // 看门狗：进度停滞超阈值强杀进程（阻塞的 execute 随之返回，任务可进 FAILED 被
            // retryDueDownloads 捞回；协程取消杀不死阻塞调用，必须杀进程）
            val watchdog = launch {
                while (true) {
                    delay(STALL_CHECK_INTERVAL_MS)
                    val idleMs = System.currentTimeMillis() - lastProgressAt.get()
                    if (idleMs > STALL_TIMEOUT_MS) {
                        Log.w(TAG, "yt-dlp 进度停滞 ${idleMs / 60000}min，强杀进程防永久挂起（pid=$pid）")
                        runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
                        return@launch
                    }
                }
            }
            val result = try {
                YoutubeDL.getInstance().execute(
                    request,
                    pid,
                    { progress, eta, line ->
                        lastProgressAt.set(System.currentTimeMillis())
                        onProgress?.invoke(progressTracker.update(progress, line), eta)
                    }
                )
            } finally {
                watchdog.cancel()
            }

            // Check for errors
            if (result.err.isNotBlank() && result.out.isBlank()) {
                return@withContext Result.failure(Exception(result.err.trim()))
            }

            // Extract the actual file path from yt-dlp output
            val filePaths = if (multiVideo) {
                extractAllFilePaths(result.out, outputDir, expectedTweetId)
            } else {
                listOfNotNull(extractFilePath(result.out, outputDir, expectedTweetId))
            }
            if (filePaths.isEmpty()) {
                return@withContext Result.failure(
                    Exception("下载完成但无法从 yt-dlp 输出确认产物路径，请重试或反馈日志")
                )
            }
            // 单路径直接返回；多视频以 \n 连接（路径不含换行符），调用方按行拆分逐个落位
            Result.success(filePaths.joinToString("\n"))
        } catch (e: Exception) {
            Log.e(TAG, "downloadVideo error", e)
            Result.failure(translateLinkerFailure(e))
        }
    }

    /**
     * 视频格式实证探测（2026-11-11 HLS 路由 B1 漏报兜底）：
     * `--dump-single-json` 让 yt-dlp 解析 x.com 页面拿**全部真实格式**（渐进 mp4 各档 +
     * HLS 自适应各档，每档带一手 height/fps/tbr），只探测不下载。
     *
     * 用途：FXTwitter 的 hasHls 是字符串级弱信号（URL 含 .m3u8 才置真，漏报高发）——
     * 漏报时此处兜底实证：探测到的最高 video 档显著高于渐进 mp4 基准（60fps/更高分辨率）
     * 才路由 yt-dlp，否则直下 mp4 零损耗。
     */
    suspend fun probeVideoFormats(
        url: String,
        proxyUrl: String? = null,
        cookieFilePath: String? = null,
    ): Result<org.json.JSONObject> = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-warnings")
                addOption("--socket-timeout", "30")
                if (proxyUrl != null) addOption("--proxy", proxyUrl)
                if (cookieFilePath != null) addOption("--cookies", cookieFilePath)
            }
            // 探测应秒级完成；仍挂看门狗防半开连接挂死订阅/下载队列（同 getFlatPlaylistIds 模式）
            val pid = UUID.randomUUID().toString()
            val startedAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val watchdog = launch {
                while (true) {
                    delay(STALL_CHECK_INTERVAL_MS)
                    val idleMs = System.currentTimeMillis() - startedAt.get()
                    if (idleMs > STALL_TIMEOUT_MS) {
                        Log.w(TAG, "格式探测停滞 ${idleMs / 60000}min，强杀进程（pid=$pid）")
                        runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
                        return@launch
                    }
                }
            }
            val result = try {
                YoutubeDL.getInstance().execute(request, pid)
            } finally {
                watchdog.cancel()
            }
            Result.success(parseDumpJson(result.out))
        } catch (e: Exception) {
            Log.e(TAG, "probeVideoFormats error for $url", e)
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
     *
     * 2026-11-11 真机实测修复：更新器（库内 YoutubeDLUpdater → URL.openStream → jackson）
     * **不走 App 显式代理**，直连 GitHub —— 墙内 TLS 握手被重置（实测
     * `SSLHandshakeException: connection closed`，用户 Clash 分应用模式不覆盖此连接）。
     * 注入 JVM 级代理属性让 openStream 走 App 配置的代理（Clash），finally 恢复原值。
     * 注入窗口内无参 openConnection() 的直连路径会短暂跟随代理——更新是低频单发操作，
     * 窗口数秒，可接受。
     */
    suspend fun updateYoutubeDL(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val proxySettings = com.ed.edqiu.data.preferences.ProxyPreferences(context).getProxySettings()
        val props: Map<String, String>? = if (proxySettings.enabled) {
            if (proxySettings.type == com.ed.edqiu.data.model.ProxySettings.TYPE_SOCKS5) {
                mapOf(
                    "socksProxyHost" to proxySettings.host,
                    "socksProxyPort" to proxySettings.port.toString(),
                )
            } else {
                mapOf(
                    "http.proxyHost" to proxySettings.host,
                    "http.proxyPort" to proxySettings.port.toString(),
                    "https.proxyHost" to proxySettings.host,
                    "https.proxyPort" to proxySettings.port.toString(),
                )
            }
        } else null
        val previous = props?.mapValues { System.getProperty(it.key) }
        try {
            props?.forEach { (k, v) -> System.setProperty(k, v) }
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
        } finally {
            previous?.forEach { (k, v) ->
                if (v == null) System.clearProperty(k) else System.setProperty(k, v)
            }
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
                addOption("--socket-timeout", "30")
                if (proxyUrl != null) addOption("--proxy", proxyUrl)
                if (cookieFilePath != null) addOption("--cookies", cookieFilePath)
            }
            // 2026-11-07 审计修复：execute 传自动 pid（此前 null → 不可被 cancelDownload）
            // + 停滞看门狗——SubscriptionManager 的 15min 超时对阻塞 execute 无效，
            // 挂死时 pollInFlight 永不复位、订阅轮询整个会话停摆；现在进程强杀后
            // execute 立即抛错返回，finally 得以执行
            val pid = UUID.randomUUID().toString()
            val startedAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val watchdog = launch {
                while (true) {
                    delay(STALL_CHECK_INTERVAL_MS)
                    val idleMs = System.currentTimeMillis() - startedAt.get()
                    if (idleMs > STALL_TIMEOUT_MS) {
                        Log.w(TAG, "yt-dlp flat-playlist 停滞 ${idleMs / 60000}min，强杀进程（pid=$pid）")
                        runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
                        return@launch
                    }
                }
            }
            val result = try {
                YoutubeDL.getInstance().execute(request, pid)
            } finally {
                watchdog.cancel()
            }
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
     *
     * P1 审计修复（2026-11-21）：expectedTweetId 非空时，"has already been downloaded"
     * 行必须包含该推文 id（模板产物名含 [id]）才采纳——旧实现无身份校验，同作者标题
     * 碰撞时会把旧推文的已存在文件直接当作本推文产物返回。
     */
    private fun extractFilePath(output: String, baseDir: String, expectedTweetId: String? = null): String? {
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
                val path = trimmed.substringAfter("[download] ").substringBefore(" has already").trim()
                if (expectedTweetId != null && !path.contains("[$expectedTweetId]")) continue
                return path
            }
        }
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[download] Destination:")) {
                return trimmed.removePrefix("[download] Destination:").trim()
            }
        }
        // 2026-11-07 审计修复：三段解析都不命中时返回 null（调用方按失败处理）——
        // 旧实现兜底返回必然不存在的 "$baseDir/downloaded_video.mp4"，下游照样写
        // sidecar、置 COMPLETED，随后状态机把它降回 PENDING，反复横跳
        return null
    }

    /**
     * 多视频模式（2026-11-21 审计修复）：解析 yt-dlp 输出的全部产物路径。
     * 裸 status URL 对多视频推文是 playlist——yt-dlp 逐条下载并逐条输出 Merger 行，
     * 旧实现只认首条导致其余视频滞留孤儿目录。优先级与 [extractFilePath] 一致：
     * 全部 [Merger] 行 → 全部 "already downloaded" 行（带 id 校验）→ 首个 Destination。
     */
    private fun extractAllFilePaths(output: String, baseDir: String, expectedTweetId: String?): List<String> {
        val lines = output.lines().map { it.trim() }
        val merged = lines.filter { it.startsWith("[Merger] Merging formats into") }
            .map { it.removePrefix("[Merger] Merging formats into").trim().removeSurrounding("\"") }
        if (merged.isNotEmpty()) return merged
        val already = lines.mapNotNull { trimmed ->
            if (!trimmed.contains("has already been downloaded")) return@mapNotNull null
            val path = trimmed.substringAfter("[download] ").substringBefore(" has already").trim()
            if (expectedTweetId != null && !path.contains("[$expectedTweetId]")) null else path
        }
        if (already.isNotEmpty()) return already
        val destination = lines.firstOrNull { it.startsWith("[download] Destination:") }
            ?.removePrefix("[download] Destination:")?.trim()
        return listOfNotNull(destination)
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
        val cookieFile = File.createTempFile("twitter-", ".txt", cookieDir)

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

        // 文件只归本次调用所有，返回路径前写完；调用方 finally 只删除自己的文件。
        try {
            cookieFile.writeText(content)
        } catch (error: Exception) {
            cookieFile.delete()
            throw error
        }
        // 2026-10-24 审计：cookie 文件路径属敏感信息，仅 debug 构建记录
        if (com.ed.edqiu.BuildConfig.DEBUG) {
            Log.d(TAG, "Cookie file written to: ${cookieFile.absolutePath}")
        }
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

