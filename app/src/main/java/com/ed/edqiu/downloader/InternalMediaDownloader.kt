package com.ed.edqiu.downloader

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ed.edqiu.data.metadata.CoverStore
import com.ed.edqiu.data.model.DownloadCancelledException
import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.model.MediaType
import com.ed.edqiu.data.model.ProxySettings
import com.ed.edqiu.data.repository.DownloadCancellation
import com.ed.edqiu.data.repository.DownloadTaskBus
import com.ed.edqiu.domain.TweetIdExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 推文已不存在（被作者删除/私密/未公开/链接失效）。
 * 永久性失败：下载层收到该异常后不再尝试 yt-dlp 回退，
 * 收件箱将其标记为 DELETED（UI 显示「推文不存在」），不再自动重试。
 */
class TweetGoneException(message: String) : Exception(message)

/**
 * 源推文只有低码率 mp4 变体（<2Mbps）但存在 HLS 高画质自适应流。
 * 下载层收到该异常后转交 yt-dlp 引擎拉取 HLS 最佳画质（2026-09-15 源头画质优化：
 * 避免先落盘低清 mp4 再靠后台升级，X 网页清晰而 App 内模糊的根源即在此）。
 */
class HlsBetterSourceException(
    val maxMp4Bitrate: Int,
    // 2026-10-25：携带作者 handle——ytdlpFallback 据此把产物重命名回 App 规约名
    val uploader: String = "unknown",
) : Exception("检测到 HLS 高画质流（mp4 最高仅 ${maxMp4Bitrate}bps）")

/**
 * 引擎一：FXTwitter 直连下载器。
 *
 * 职责：调 FXTwitter API 解析推文元数据 → 选择最高码率变体 → 断点续传落盘 → 写 sidecar。
 * 由 [com.ed.edqiu.data.repository.DownloaderClient] 编排在三层引擎链的第一层；
 * 2026-09-15 P0-1 起 [downloadFile] / [writeSidecar] 同时作为共享原语供
 * 第三方 API 兜底引擎（ThirdPartyApiResolver 链路）复用，故可见性为 internal。
 */
class InternalMediaDownloader(private val context: Context) {

    data class DownloadedFile(
        val tweetId: String,
        val filePath: String,
        val downloadedAt: Long,
        val authorId: String?,
        val authorName: String?,
        val caption: String?,
        val thumbnailUrl: String?,
        /** 推文发布时间（epoch ms，2026-09-15 媒体库按发布时间排序）；解析失败为 null。 */
        val publishedAt: Long? = null
    )

    suspend fun downloadTweet(
        rawUrl: String,
        proxy: ProxySettings? = null,
        // 2026-10-25：false = 禁用 HLS 路由（DownloaderClient 回退直下 mp4 时用，
        // 防止「yt-dlp 拉流失败 → 重试内部 → 又被路由回 yt-dlp」死循环）
        allowHlsRouting: Boolean = true,
        // 2026-11-07 审计修复：true = 只下图片（混合推文视频被 yt-dlp 拿走后补图专用）
        skipVideos: Boolean = false,
        // 2026-11-11：Bus 任务 ID 前缀（默认 xinvox_）。混合推文后台补拉图片传 "bgimg"，
        // 使补拉任务不进收件箱聚合正则——补拉期间（fire-and-forget 5-30s）卡片已由
        // DB 的 DOWNLOADED 状态接管，xinvox 任务在场会把聚合拉回 99% 误导用户
        taskPrefix: String = "xinvox",
    ): Result<List<DownloadedFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val tweetId = TweetIdExtractor.fromUrl(rawUrl)
                ?: error("链接中没有有效的推文 ID")
            val normalizedUrl = "https://x.com/i/status/$tweetId"
            val root = JSONObject(fetchText("$API_BASE$tweetId", proxy))
            val code = root.optInt("code", -1)
            // FXTwitter 对已删除/私密推文返回 code=404：永久失败，直接抛专用异常
            if (code == 404) throw TweetGoneException("推文不存在或已被删除")
            if (code != 200) error(root.optString("message", "FXTwitter API 返回 $code"))

            val tweet = root.getJSONObject("tweet")
            val author = tweet.optJSONObject("author")
            val authorId = author?.optString("screen_name")?.takeIf { it.isNotBlank() }?.let { "@$it" }
            val authorName = author?.optString("name")?.takeIf { it.isNotBlank() }
            // 2026-09-16 播放页真头像：FXTwitter author.avatar_url（与 MetadataFetcher 同源）
            val avatarUrl = author?.optString("avatar_url")?.takeIf { it.isNotBlank() }
            val uploader = authorId?.removePrefix("@") ?: "unknown"
            val caption = tweet.optString("text").takeIf { it.isNotBlank() }
            val publishedAt = parsePublishedAt(tweet)
            val allMedia = extractMedia(tweet)
            val media = if (skipVideos) allMedia.filter { it.kind == "image" } else allMedia
            if (media.isEmpty()) error("推文中没有可下载的视频或图片")

            // 2026-11-11 画质策略升级：X 的 HLS 自适应流码率/帧率恒 ≥ 渐进 mp4（网页端清晰、
            // App 内模糊的根源）。旧策略仅「mp4 <2Mbps」才路由 yt-dlp，1080p 档渐进变体
            // （≥2Mbps）永远直下 mp4 错过 HLS 高帧率。现放宽：用户已配置 X Cookie（登录态
            // 可拉最高档 HLS）→ 所有含 HLS 流的视频默认路由 yt-dlp 一次到位；
            // 未配 Cookie 保持保守（无登录态拉不到高档 HLS，直下 mp4 即无登录最佳）。
            // yt-dlp 已开 --concurrent-fragments 8 并发分片，「HLS 慢」不再是理由。
            // 守卫：仅含视频的推文路由（纯图片推文无流可拉）；16KB 页机型 ffmpeg 不可用不路由；
            // 调用方 allowHlsRouting=false 时跳过（回退通道专用）
            val (maxMp4Bitrate, hasHls) = analyzeVariants(tweet)
            val hasVideoMedia = media.any { it.kind != "image" }
            val cookieBoost = runCatching {
                com.ed.edqiu.data.preferences.CookiePreferences(context).hasCookies()
            }.getOrDefault(false)
            val is16kPage = (context.applicationContext as? com.ed.edqiu.TwitterDownloaderApp)
                ?.is16kPageDevice == true
            // 2026-11-07 审计修复：码率解析不到（=0）≠ 低码率——旧实现 0 < 2M 恒真 +
            // cookieBoost 使配 Cookie 用户的所有视频（包括本已高码率的）都绕道 yt-dlp
            // 白跑一遍。现语义反转（Cookie 登录态=画质优先）：hasHls 存在时，
            // 码率已知且低（<2Mbps，含 0=纯 HLS）→ 无 Cookie 也路由；
            // 配了 Cookie → 无条件路由（拉 HLS 最高帧率/码率档）
            val shouldRouteHls = hasVideoMedia && !is16kPage && hasHls &&
                (maxMp4Bitrate <= 1_999_999 || cookieBoost)
            if (allowHlsRouting && shouldRouteHls) {
                throw HlsBetterSourceException(maxMp4Bitrate, uploader)
            }
            // 2026-11-11 B1 漏报兜底：hasHls=false = FXTwitter 漏报高发区（旧逻辑此场景
            // 「低码率+Cookie 盲赌路由」，09-16 定案；高码率+漏报则永远直下 1080p30 mp4，
            // 错过 HLS 60fps / Premium+ 2160p）。主用户 Cookie 恒配置 → 此场景让 yt-dlp
            // 实证探测一次真实格式表（height/fps/tbr 一手值）：存在显著高于渐进 mp4 基准
            // 的 video 档（10% 容差过滤同档噪声）才路由，探测失败/无增益 → 直下 mp4 零损耗。
            if (allowHlsRouting && !shouldRouteHls && hasVideoMedia && !is16kPage && cookieBoost) {
                val probedBestBps = probeYtdlpBestBitrate(normalizedUrl)
                if (probedBestBps != null &&
                    probedBestBps > maxMp4Bitrate * 1.1 &&
                    probedBestBps > 0
                ) {
                    Log.i(
                        TAG,
                        "漏报兜底路由：FXTwitter 未报 m3u8，yt-dlp 实测最高档 $probedBestBps bps " +
                            "> 渐进基准 $maxMp4Bitrate bps，转 yt-dlp 拉 HLS（tweet=$tweetId）"
                    )
                    throw HlsBetterSourceException(maxMp4Bitrate, uploader)
                }
            }

            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "Download")
            outputDir.mkdirs()

            val downloaded = mutableListOf<DownloadedFile>()
            var cancelledAny = false
            // 2026-09-30 v1.6.8 预览图同步落盘：sidecar/收件箱记录的 thumbnail 优先用
            // 本地封面路径（分享时已落盘），媒体库扫描后免联网同步预览图；
            // 本地没有则借本次下载的网络窗口顺手补存一份（4s 超时，失败不影响下载）
            val localCover = CoverStore.localCoverPath(context, tweetId)
                ?: media.firstNotNullOfOrNull { it.thumbnail }
                    ?.takeIf { it.startsWith("http") }
                    ?.let { CoverStore.ensureLocalCover(context, tweetId, it, timeoutMs = 4_000L) }
            media.forEachIndexed { index, item ->
                val mediaIndex = index + 1
                val filename = sanitize("${uploader}_${tweetId}_${mediaIndex}_${item.kind}_${item.quality}.${item.ext}")
                val target = File(outputDir, filename)
                val task = DownloadTask(
                    id = "${taskPrefix}_${tweetId}_$mediaIndex",
                    url = normalizedUrl,
                    title = caption ?: normalizedUrl,
                    thumbnail = localCover ?: item.thumbnail ?: item.url,
                    uploader = uploader,
                    formatId = "fx_internal",
                    quality = item.quality,
                    ext = item.ext,
                    mediaType = if (item.kind == "image") MediaType.IMAGE else MediaType.VIDEO,
                    mediaIndex = mediaIndex,
                    status = DownloadStatus.DOWNLOADING,
                    avatarUrl = avatarUrl ?: "",
                    authorName = authorName ?: ""
                )
                DownloadTaskBus.add(task)

                runCatching {
                    downloadFile(item.url, target, proxy, taskId = task.id)
                    writeSidecar(
                        mediaFile = target,
                        sourceUrl = normalizedUrl,
                        tweetId = tweetId,
                        uploader = uploader,
                        authorName = authorName,
                        caption = caption,
                        thumbnail = localCover ?: item.thumbnail ?: item.url,
                        quality = item.quality,
                        mediaIndex = mediaIndex,
                        mediaType = item.kind.uppercase(Locale.ROOT),
                        ext = item.ext,
                        publishedAt = publishedAt,
                        avatarUrl = avatarUrl
                    )
                    DownloadedFile(
                        tweetId = tweetId,
                        filePath = target.absolutePath,
                        downloadedAt = target.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                        authorId = authorId,
                        authorName = authorName,
                        caption = caption,
                        thumbnailUrl = localCover ?: item.thumbnail ?: item.url,
                        publishedAt = publishedAt
                    )
                }.onSuccess { file ->
                    downloaded += file
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            outputPath = file.filePath,
                            progress = 100f,
                            completedAt = file.downloadedAt
                        )
                    }
                }.onFailure { error ->
                    val cancelled = error is DownloadCancelledException || DownloadCancellation.isCancelled(task.id)
                    if (cancelled) cancelledAny = true
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(
                            status = if (cancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED,
                            errorMessage = if (cancelled) "下载已取消" else (error.message ?: "下载失败")
                        )
                    }
                    Log.w(TAG, "Failed to download ${target.name}", error)
                }
            }
            // 2026-11-07 审计修复：取消语义上抛（旧实现部分取消会被下面的「部分成功」
            // 判定吞成整体成功/失败，触发无谓自动重试）
            if (cancelledAny) throw DownloadCancelledException()
            if (downloaded.isEmpty()) error("没有媒体下载成功")
            // 2026-11-07 审计修复：部分成功不再算整体成功——旧实现只要 ≥1 个文件成功
            // 就返回、状态置 DOWNLOADED，失败索引永不重试。改为抛错走既有 FAILED→重试
            // 链路补齐（重试会重下已成功的文件，量小可接受；换取状态与实际文件一致）
            if (downloaded.size < media.size) {
                error("部分媒体下载失败（${downloaded.size}/${media.size} 成功），将自动重试补齐")
            }
            downloaded
        }
    }

    /**
     * 分析推文全部视频变体：返回 (最高 mp4 码率 bps, 是否存在 HLS 流)。
     * 用于源头画质优化——mp4 不够好且有 HLS 时转 yt-dlp 高画质引擎。
     */
    private fun analyzeVariants(tweet: JSONObject): Pair<Int, Boolean> {
        var maxBitrate = 0
        var hasHls = false
        fun scanArray(array: org.json.JSONArray?) {
            array ?: return
            (0 until array.length()).forEach { i ->
                val item = array.optJSONObject(i) ?: return@forEach
                val variants = item.optJSONArray("variants")
                    ?: item.optJSONObject("video_info")?.optJSONArray("variants")
                    ?: return@forEach
                (0 until variants.length()).forEach { j ->
                    val v = variants.optJSONObject(j) ?: return@forEach
                    val url = v.optString("url", "")
                    when {
                        url.contains(".m3u8") -> hasHls = true
                        url.contains(".mp4") || v.optString("content_type") == "video/mp4" ->
                            maxBitrate = maxOf(maxBitrate, v.optInt("bitrate", 0))
                    }
                }
            }
        }
        val mediaObject = tweet.optJSONObject("media") ?: JSONObject()
        scanArray(mediaObject.optJSONArray("videos"))
        scanArray(mediaObject.optJSONArray("all"))
        scanArray(tweet.optJSONArray("media_extended"))
        tweet.optJSONObject("extended_entities")?.let { scanArray(it.optJSONArray("media")) }
        tweet.optJSONObject("entities")?.let { scanArray(it.optJSONArray("media")) }
        return maxBitrate to hasHls
    }

    /**
     * yt-dlp 实证探测（2026-11-11 B1 漏报兜底）：FXTwitter 报了视频但没报 m3u8 时，
     * 让 yt-dlp 解析一次 x.com 页面拿全部真实格式（`--dump-single-json`，只探测不下载），
     * 返回最高 video 档的码率（bps）。yt-dlp 的 tbr 单位 kbit/s，×1000 换算后与
     * FXTwitter 的 bitrate（bps）口径对齐。
     *
     * 探测失败（网络/解析异常/无 video 格式）返回 null → 调用方直下 mp4 零损耗。
     * Cookie 文件用完即删（同 ytdlpFallback 纪律）。
     */
    private suspend fun probeYtdlpBestBitrate(url: String): Int? {
        val cookiePreferences = com.ed.edqiu.data.preferences.CookiePreferences(context)
        val cookieFile = if (cookiePreferences.hasCookies()) {
            com.ed.edqiu.service.YoutubeDLService.writeCookieFile(
                context, cookiePreferences.getAuthToken(), cookiePreferences.getCt0()
            )
        } else null
        return try {
            val proxyUrl = com.ed.edqiu.data.preferences.ProxyPreferences(context)
                .getProxySettings().toProxyUrl()
            val json = com.ed.edqiu.service.YoutubeDLService.probeVideoFormats(url, proxyUrl, cookieFile)
                .getOrNull() ?: return null
            val formats = json.optJSONArray("formats") ?: return null
            var bestKbps = 0.0
            (0 until formats.length()).forEach { i ->
                val f = formats.optJSONObject(i) ?: return@forEach
                // vcodec=none 是纯音频流，不参与画质对比
                if (f.optString("vcodec", "none") == "none") return@forEach
                bestKbps = maxOf(bestKbps, f.optDouble("tbr", 0.0))
            }
            if (bestKbps <= 0.0) null else (bestKbps * 1000).toInt()
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp 格式探测失败（漏报兜底跳过，直下 mp4）: $url", e)
            null
        } finally {
            cookieFile?.let { runCatching { File(it).delete() } }
        }
    }

    private fun extractMedia(tweet: JSONObject): List<MediaItem> {        val mediaObject = tweet.optJSONObject("media") ?: JSONObject()
        val candidates = buildList {
            addAll(mediaObject.optObjects("videos"))
            addAll(mediaObject.optObjects("photos"))
            addAll(mediaObject.optObjects("images"))
            addAll(mediaObject.optObjects("all"))
            addAll(tweet.optObjects("media_extended"))
            tweet.optJSONObject("extended_entities")?.let { addAll(it.optObjects("media")) }
            tweet.optJSONObject("entities")?.let { addAll(it.optObjects("media")) }
        }
        val seen = mutableSetOf<String>()
        return candidates.mapNotNull { item ->
            val type = item.optString("type", item.optString("media_type"))
            val variants = item.optJSONArray("variants")
                ?: item.optJSONObject("video_info")?.optJSONArray("variants")
            val bestVideo = variants?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it) }
                    .filter { it.optString("content_type") == "video/mp4" || it.optString("url").contains(".mp4") }
                    .maxByOrNull { it.optInt("bitrate", 0) }
            }
            val videoUrl = bestVideo?.optString("url")?.takeIf { it.isNotBlank() }
                ?: item.optString("video_url").takeIf { it.isNotBlank() }
            if ((type in VIDEO_TYPES || bestVideo != null) && !videoUrl.isNullOrBlank()) {
                val key = videoUrl.substringBefore("?")
                if (!seen.add(key)) return@mapNotNull null
                return@mapNotNull MediaItem(
                    url = videoUrl,
                    thumbnail = item.firstString("thumbnail_url", "thumbnail", "thumb"),
                    ext = "mp4",
                    quality = bitrateToQuality(bestVideo?.optInt("bitrate", 0) ?: 0),
                    kind = "video"
                )
            }

            val imageUrl = item.firstString("image_url", "media_url_https", "media_url", "url", "thumbnail_url", "thumbnail")
            if ((type in IMAGE_TYPES || imageUrl?.contains("pbs.twimg.com/media", ignoreCase = true) == true) && !imageUrl.isNullOrBlank()) {
                val key = imageUrl.substringBefore("?")
                if (!seen.add(key)) return@mapNotNull null
                return@mapNotNull MediaItem(
                    // 2026-11-11 源头画质补齐：图片 URL 强制 name=orig（原图，无压缩参数）——
                    // 视频侧已做 HLS 优先路由，图片侧此前的 URL 可能带 CDN 压缩参数，
                    // 源头策略在图片侧是半成品
                    url = toOrigImageUrl(imageUrl),
                    thumbnail = imageUrl,
                    ext = inferImageExtension(imageUrl),
                    quality = "original",
                    kind = "image"
                )
            }
            null
        }
    }

    /**
     * 媒体文件下载（2026-11-11 重构：多线程分段并行）：
     * 1. 磁盘满预检 + 旧版整文件 .part 兼容（升级前残留走原单流续传收尾）；
     * 2. Range 探测：206 响应解析远端总大小，≥8MB 自动拆段并行下载（见 downloadParallel）；
     * 3. 小文件 / 不支持 Range / 探测失败 → 单流路径（64KB 缓冲 + .part 断点续传 + 2 次重试）；
     * 4. 进度统一 300ms 节流回写 DownloadTaskBus。
     */
    /** 共享原语（internal）：媒体文件下载入口，第三方兜底引擎复用。 */
    internal fun downloadFile(url: String, target: File, proxy: ProxySettings?, taskId: String? = null) {
        val dir = target.parentFile
        if (dir != null && dir.usableSpace in 1 until MIN_FREE_SPACE_BYTES) {
            error("存储空间不足（剩余不足 64MB），请清理存储后重试")
        }
        // 旧版整文件 .part（升级前遗留）：按原单流续传逻辑收尾，保持兼容
        if (File(dir, target.name + ".part").exists()) {
            downloadSingleStream(url, target, proxy, taskId)
            return
        }
        val totalBytes = probeRemoteSize(url, proxy)
        if (totalBytes != null && totalBytes >= SPLIT_MIN_BYTES) {
            downloadParallel(url, target, proxy, taskId, totalBytes)
        } else {
            downloadSingleStream(url, target, proxy, taskId)
        }
    }

    /** 构建带代理的 GET 连接（共享原语）：Edqiu UA + 15s 连接 / 30s 读超时 + 跟随重定向。 */
    private fun openConnection(url: String, proxy: ProxySettings?): HttpURLConnection {
        val proxyObj = proxy?.toJavaProxy()
        val connection = if (proxyObj != null) {
            URL(url).openConnection(proxyObj) as HttpURLConnection
        } else {
            // 无代理时必须用无参 openConnection()，传 null 在部分 Android 版本会抛 "proxy can not be null"
            URL(url).openConnection() as HttpURLConnection
        }
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Edqiu/1.0")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        return connection
    }

    /**
     * Range 探测：发 `Range: bytes=0-0`，响应 206 时从 Content-Range 解析远端总大小
     * （分段并行可用）；200（服务器不认 Range）/ 异常 / 未知大小 → 返回 null 走单流。
     * 探测失败不阻断下载，只降级路径。
     */
    private fun probeRemoteSize(url: String, proxy: ProxySettings?): Long? {
        val connection = openConnection(url, proxy)
        return try {
            connection.setRequestProperty("Range", "bytes=0-0")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL ->
                    parseContentRangeTotal(connection.getHeaderField("Content-Range"))
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Range 探测失败，降级单流下载：$url", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /** 从 Content-Range 头（"bytes 0-0/12345"）解析总大小；未知（`*`）或非法返回 null。 */
    private fun parseContentRangeTotal(header: String?): Long? {
        if (header == null) return null
        return header.substringAfterLast('/', "").trim().toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * 多线程分段并行下载（2026-11-11 提速：单 TCP 流经代理吞吐受限，跨境高延迟链路
     * 单流往往只有几百 KB/s；X CDN 支持并发 Range 连接——网页播放器本就多连接拉分片）。
     * - 线程数按文件大小自动分配：8-32MB → 3 段，32-128MB → 4 段，≥128MB → 6 段；
     * - 每段独立连接 + `Range: bytes=start-end` 写独立段文件 `<target>.p<i>`，段级断点续传；
     * - 段响应必须 206（探测过支持 Range）；416 = 段残留越界 → 清段重下；
     * - 任一段 2 次重试后仍失败 → 保留全部段文件抛错，下轮重试只补缺段；
     * - 全部段完成 → 校验段长 → 顺序拼接为 .part → rename 转正（下载完成前 target 不存在，
     *   保持「完成即落位」原子性）；拼接/校验失败删 .part 保留段文件供续传。
     */
    private fun downloadParallel(
        url: String,
        target: File,
        proxy: ProxySettings?,
        taskId: String?,
        totalBytes: Long
    ) {
        val dir = target.parentFile
        val threads = when {
            totalBytes >= 128L * 1024 * 1024 -> 6
            totalBytes >= 32L * 1024 * 1024 -> 4
            else -> 3
        }
        val segSize = totalBytes / threads
        val segFiles = (0 until threads).map { i -> File(dir, target.name + ".p$i") }
        val segments = (0 until threads).map { i ->
            val start = i * segSize
            val end = if (i == threads - 1) totalBytes - 1 else (i + 1) * segSize - 1
            Triple(i, start, end)
        }
        // 续传基线：已有段文件按其长度计入（超长段按段长封顶，写循环内自纠）
        val totalWritten = java.util.concurrent.atomic.AtomicLong(
            segments.sumOf { (_, start, end) ->
                val i = ((start / segSize).toInt()).coerceAtMost(threads - 1)
                segFiles[i].length().coerceAtMost(end - start + 1)
            }
        )
        val lastReportAt = java.util.concurrent.atomic.AtomicLong(0L)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val futures = segments.map { (i, start, end) ->
                pool.submit {
                    downloadSegment(url, segFiles[i], start, end, proxy, taskId, totalWritten, lastReportAt, totalBytes)
                }
            }
            try {
                futures.forEach { it.get() }
            } catch (e: java.util.concurrent.ExecutionException) {
                // 段失败：保留全部段文件抛原异常，交由调用方 FAILED → 自动重试续传补缺
                throw e.cause ?: e
            }
        } finally {
            pool.shutdownNow()
        }

        // 全部段完成：校验段长 → 拼接 → 转正
        segments.forEach { (i, start, end) ->
            val segLen = end - start + 1
            val segFile = segFiles[i]
            if (!segFile.exists() || segFile.length() != segLen) {
                error("分段 ${segFile.name} 不完整（${if (segFile.exists()) segFile.length() else -1}/$segLen）")
            }
        }
        val merged = File(dir, target.name + ".part")
        var mergedOk = false
        try {
            java.io.FileOutputStream(merged).use { out ->
                segFiles.forEach { seg ->
                    seg.inputStream().use { it.copyTo(out, 128 * 1024) }
                }
            }
            if (merged.length() != totalBytes) {
                error("拼接总字节 ${merged.length()} ≠ 远端 $totalBytes")
            }
            if (target.exists()) target.delete()
            if (!merged.renameTo(target)) {
                merged.copyTo(target, overwrite = true)
                merged.delete()
            }
            mergedOk = true
            Log.i(TAG, "分段并行下载完成：${target.name}（$threads 线程，$totalBytes 字节）")
        } finally {
            if (mergedOk) {
                segFiles.forEach { runCatching { it.delete() } }
            } else {
                runCatching { merged.delete() }
            }
        }
    }

    /** 单段下载（段级断点续传 + 2 次重试 + 取消检查），供 downloadParallel 的线程池调用。 */
    private fun downloadSegment(
        url: String,
        segFile: File,
        segStart: Long,
        segEnd: Long,
        proxy: ProxySettings?,
        taskId: String?,
        totalWritten: java.util.concurrent.atomic.AtomicLong,
        lastReportAt: java.util.concurrent.atomic.AtomicLong,
        totalBytes: Long
    ) {
        val segLen = segEnd - segStart + 1
        var lastError: Throwable? = null
        for (attempt in 1..2) {
            try {
                if (segFile.exists() && segFile.length() > segLen) segFile.delete() // 异常残留重下
                val resumed = if (segFile.exists()) segFile.length() else 0L
                if (resumed >= segLen) return // 段已完成（上轮写满）
                val connection = openConnection(url, proxy)
                try {
                    connection.setRequestProperty("Range", "bytes=${segStart + resumed}-$segEnd")
                    val code = connection.responseCode
                    if (code == 416) {
                        // 段起点越界（残留偏移异常）→ 清段重下，耗一次重试额度
                        Log.w(TAG, "HTTP 416 段续传越界（seg=${segStart + resumed}），清段重下：${segFile.name}")
                        segFile.delete()
                        error("HTTP 416 段续传越界")
                    }
                    if (code != HttpURLConnection.HTTP_PARTIAL) {
                        error("分段下载段响应 HTTP $code（必须 206）")
                    }
                    connection.inputStream.use { raw ->
                        java.io.BufferedInputStream(raw, 128 * 1024).use { input ->
                            java.io.FileOutputStream(segFile, true).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                var written = resumed
                                while (written < segLen) {
                                    if (taskId != null && DownloadCancellation.isCancelled(taskId)) {
                                        throw DownloadCancelledException()
                                    }
                                    val want = (segLen - written).coerceAtMost(buffer.size.toLong()).toInt()
                                    val read = input.read(buffer, 0, want)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    written += read
                                    totalWritten.addAndGet(read.toLong())
                                    reportProgress(taskId, totalWritten.get(), totalBytes, lastReportAt)
                                }
                            }
                        }
                    }
                    return
                } finally {
                    connection.disconnect()
                }
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "段下载中断（第 $attempt 次）seg=${segFile.name} 已有 ${segFile.length()}/$segLen 字节，将续传", e)
            }
        }
        throw lastError ?: error("分段下载失败")
    }

    /** 分段并行进度回写：全局 300ms 节流（CAS 抢占式，多段并发只取一个写者）。 */
    private fun reportProgress(
        taskId: String?,
        written: Long,
        totalBytes: Long,
        lastReportAt: java.util.concurrent.atomic.AtomicLong
    ) {
        if (taskId == null || totalBytes <= 0) return
        val now = System.currentTimeMillis()
        val last = lastReportAt.get()
        if (now - last > 300 && lastReportAt.compareAndSet(last, now)) {
            DownloadTaskBus.updateTask(taskId) {
                it.copy(progress = (written.toFloat() / totalBytes * 100f).coerceIn(0f, 99f))
            }
        }
    }

    /** 单流下载（原逻辑保留）：64KB 缓冲 + 整文件 .part 断点续传 + 2 次重试 + 200 降级全量重下。 */
    private fun downloadSingleStream(url: String, target: File, proxy: ProxySettings?, taskId: String?) {
        val part = File(target.parentFile, target.name + ".part")
        val maxAttempts = 2
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val resumedBytes = if (part.exists()) part.length() else 0L
                val connection = openConnection(url, proxy)
                try {
                    // 续传请求：只取未下载部分（CDN twimg 普遍支持 Range）
                    if (resumedBytes > 0L) {
                        connection.setRequestProperty("Range", "bytes=$resumedBytes-")
                    }
                    val code = connection.responseCode
                    // .part ≥ 远端文件（远端被较小版本替换/上轮落位失败残留）→ Range 起点越界
                    // 416，两次重试全 416 永久失败且 .part 永不修复。清零重下（耗一次重试额度）
                    if (code == 416 && resumedBytes > 0L) {
                        Log.w(TAG, "HTTP 416（.part=$resumedBytes ≥ 远端），清零重下：$url")
                        part.delete()
                        error("HTTP 416 断点越界，已清零重下")
                    }
                    val appending = resumedBytes > 0L && code == HttpURLConnection.HTTP_PARTIAL
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        error("HTTP $code 下载失败")
                    }
                    val contentLength = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
                    val startOffset = if (appending) resumedBytes else 0L
                    var written = startOffset
                    var lastReport = 0L
                    connection.inputStream.use { raw ->
                        java.io.BufferedInputStream(raw, 64 * 1024).use { input ->
                            java.io.FileOutputStream(part, appending).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    // 用户取消：中止写入（.part 保留供断点续传），由调用方置 CANCELLED
                                    if (taskId != null && DownloadCancellation.isCancelled(taskId)) {
                                        throw DownloadCancelledException()
                                    }
                                    output.write(buffer, 0, read)
                                    written += read
                                    // 进度节流：每 300ms 回写一次，避免高频重组
                                    val now = System.currentTimeMillis()
                                    if (taskId != null && contentLength > 0 && now - lastReport > 300) {
                                        lastReport = now
                                        DownloadTaskBus.updateTask(taskId) {
                                            it.copy(progress = (written.toFloat() / (startOffset + contentLength) * 100f).coerceIn(0f, 99f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 下载完成：.part 转正
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    return
                } finally {
                    connection.disconnect()
                }
            } catch (e: DownloadCancelledException) {
                // 取消不重试、不覆盖 lastError 语义，直接上抛
                throw e
            } catch (e: Exception) {
                // ENOSPC 翻译为用户可读文案（原文晦涩且每次重试无意义）
                lastError = if (e.message?.contains("ENOSPC", ignoreCase = true) == true ||
                    e.message?.contains("No space left", ignoreCase = true) == true
                ) {
                    IllegalStateException("存储空间不足，写入失败，请清理存储后重试", e)
                } else {
                    e
                }
                Log.w(TAG, "下载中断（第 $attempt 次尝试）url=$url 已有 ${part.length()} 字节，将尝试续传", e)
            }
        }
        throw lastError ?: error("下载失败")
    }

    private fun fetchText(url: String, proxy: ProxySettings?): String {
        val proxyObj = proxy?.toJavaProxy()
        val connection = if (proxyObj != null) {
            URL(url).openConnection(proxyObj) as HttpURLConnection
        } else {
            // 无代理时必须用无参 openConnection()，传 null 在部分 Android 版本会抛 "proxy can not be null"
            URL(url).openConnection() as HttpURLConnection
        }
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Edqiu/1.0")
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                // HTTP 404 = fxtwitter 明确找不到该推文（被删/私密/不存在）
                if (connection.responseCode == 404) {
                    throw TweetGoneException("推文不存在或已被删除")
                }
                error("HTTP ${connection.responseCode} from FXTwitter")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** 共享原语（internal）：写 .meta.json sidecar 供收件箱 DownloadMonitor 配对；第三方兜底引擎复用。 */
    internal fun writeSidecar(
        mediaFile: File,
        sourceUrl: String,
        tweetId: String,
        uploader: String,
        authorName: String?,
        caption: String?,
        thumbnail: String?,
        quality: String,
        mediaIndex: Int,
        mediaType: String,
        ext: String,
        publishedAt: Long? = null,
        avatarUrl: String? = null
    ) {
        val json = JSONObject()
            .put("url", sourceUrl)
            .put("tweetId", tweetId)
            .put("uploader", uploader)
            .put("authorName", authorName ?: uploader)
            .put("avatarUrl", avatarUrl ?: JSONObject.NULL)
            .put("title", caption ?: sourceUrl)
            .put("thumbnail", thumbnail ?: "")
            .put("quality", quality)
            .put("formatId", "fx_internal")
            .put("mediaIndex", mediaIndex)
            .put("mediaType", mediaType)
            .put("ext", ext)
            .put("publishedAt", publishedAt ?: JSONObject.NULL)
        File(mediaFile.absolutePath + META_SUFFIX).writeText(json.toString())
        Log.i(TAG, "Downloaded ${mediaFile.name}")
    }

    /**
     * 解析推文发布时间（epoch ms，2026-09-15 媒体库按发布时间排序）：
     * 优先 FXTwitter 的 `created_timestamp`（epoch 秒），兜底解析 `created_at`（ISO8601）。
     * 解析失败返回 null（sidecar 写 JSON null，UI/排序按「无发布时间」垫底处理）。
     */
    private fun parsePublishedAt(tweet: JSONObject): Long? {
        val tsSeconds = tweet.optLong("created_timestamp", 0L)
        if (tsSeconds > 0L) return tsSeconds * 1000L
        val createdAt = tweet.optString("created_at", "").takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            java.time.OffsetDateTime.parse(createdAt).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.Instant.parse(createdAt).toEpochMilli()
            }.getOrNull()
        }
    }

    private fun JSONObject.optObjects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)
        }
    }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }

    private fun inferImageExtension(url: String): String {
        val format = Regex("[?&]format=([a-zA-Z0-9]+)").find(url)?.groupValues?.getOrNull(1)
        if (format != null && format.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS) return format.lowercase(Locale.ROOT)
        val ext = url.substringBefore("?").substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
        return if (ext in IMAGE_EXTENSIONS) ext else "jpg"
    }

    /** pbs.twimg.com 图片 URL 换 name=orig（原图，无 CDN 压缩参数）；已带 name 则替换。 */
    private fun toOrigImageUrl(url: String): String = when {
        url.contains("name=") -> url.substringBefore("name=") + "name=orig"
        url.contains("?") -> "$url&name=orig"
        else -> "$url?name=orig"
    }

    private fun bitrateToQuality(bitrate: Int): String = when {
        bitrate >= 2_000_000 -> "1080p"
        bitrate >= 832_000 -> "720p"
        bitrate >= 320_000 -> "480p"
        bitrate > 0 -> "360p"
        else -> "best"
    }

    private fun sanitize(name: String): String = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    private data class MediaItem(
        val url: String,
        val thumbnail: String?,
        val ext: String,
        val quality: String,
        val kind: String
    )

    companion object {
        private const val TAG = "InternalMediaDownloader"
        private const val API_BASE = "https://api.fxtwitter.com/Twitter/status/"
        private const val META_SUFFIX = ".meta.json"

        /** 下载前磁盘最低水位（2026-11-07 审计修复：磁盘满预检）。 */
        private const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L

        /** 分段并行下载的文件大小门槛（2026-11-11 提速）：小于此值单流下载（建 3+ 连接不划算）。 */
        private const val SPLIT_MIN_BYTES = 8L * 1024L * 1024L
        private val VIDEO_TYPES = setOf("video", "gif", "animated_gif")
        private val IMAGE_TYPES = setOf("photo", "image")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
    }
}
