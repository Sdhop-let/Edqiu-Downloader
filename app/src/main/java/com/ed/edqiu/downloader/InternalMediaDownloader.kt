package com.ed.edqiu.downloader

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.model.MediaType
import com.ed.edqiu.data.model.ProxySettings
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
class HlsBetterSourceException(val maxMp4Bitrate: Int) :
    Exception("检测到 HLS 高画质流（mp4 最高仅 ${maxMp4Bitrate}bps）")

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

    suspend fun downloadTweet(rawUrl: String, proxy: ProxySettings? = null): Result<List<DownloadedFile>> = withContext(Dispatchers.IO) {
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
            val uploader = authorId?.removePrefix("@") ?: "unknown"
            val caption = tweet.optString("text").takeIf { it.isNotBlank() }
            val publishedAt = parsePublishedAt(tweet)
            val media = extractMedia(tweet)
            if (media.isEmpty()) error("推文中没有可下载的视频或图片")

            // 2026-09-15 源头画质优化：mp4 变体最高码率不足 2Mbps 且存在 HLS 流时，
            // 不落盘低清文件，直接转交 yt-dlp 引擎拉 HLS 最佳画质（1080p+）
            val (maxMp4Bitrate, hasHls) = analyzeVariants(tweet)
            if (hasHls && maxMp4Bitrate < 2_000_000) {
                throw HlsBetterSourceException(maxMp4Bitrate)
            }

            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "Download")
            outputDir.mkdirs()

            val downloaded = mutableListOf<DownloadedFile>()
            media.forEachIndexed { index, item ->
                val mediaIndex = index + 1
                val filename = sanitize("${uploader}_${tweetId}_${mediaIndex}_${item.kind}_${item.quality}.${item.ext}")
                val target = File(outputDir, filename)
                val task = DownloadTask(
                    id = "xinvox_${tweetId}_$mediaIndex",
                    url = normalizedUrl,
                    title = caption ?: normalizedUrl,
                    thumbnail = item.thumbnail ?: item.url,
                    uploader = uploader,
                    formatId = "fx_internal",
                    quality = item.quality,
                    ext = item.ext,
                    mediaType = if (item.kind == "image") MediaType.IMAGE else MediaType.VIDEO,
                    mediaIndex = mediaIndex,
                    status = DownloadStatus.DOWNLOADING
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
                        thumbnail = item.thumbnail ?: item.url,
                        quality = item.quality,
                        mediaIndex = mediaIndex,
                        mediaType = item.kind.uppercase(Locale.ROOT),
                        ext = item.ext,
                        publishedAt = publishedAt
                    )
                    DownloadedFile(
                        tweetId = tweetId,
                        filePath = target.absolutePath,
                        downloadedAt = target.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                        authorId = authorId,
                        authorName = authorName,
                        caption = caption,
                        thumbnailUrl = item.thumbnail ?: item.url,
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
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = error.message ?: "下载失败"
                        )
                    }
                    Log.w(TAG, "Failed to download ${target.name}", error)
                }
            }
            if (downloaded.isEmpty()) error("没有媒体下载成功")
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
                    url = imageUrl,
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
     * 媒体文件下载（2026-09-14 网络路径优化）：
     * 1. 64KB 缓冲流（原为默认 8KB 拷贝，小缓冲是慢速首因之一）；
     * 2. 断点续传：下载写 .part 临时文件，中断后凭 Range 头续传，失败自动重试 2 次；
     * 3. 进度回写 DownloadTaskBus（300ms 节流），下载中列表可见实时进度；
     * 4. 服务器不支持 Range 时（HTTP 200）自动降级为全量重下。
     */
    /** 共享原语（internal）：可断点续传的媒体文件下载，第三方兜底引擎复用。 */
    internal fun downloadFile(url: String, target: File, proxy: ProxySettings?, taskId: String? = null) {
        val part = File(target.parentFile, target.name + ".part")
        val maxAttempts = 2
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val resumedBytes = if (part.exists()) part.length() else 0L
                val proxyObj = proxy?.toJavaProxy()
                val connection = if (proxyObj != null) {
                    URL(url).openConnection(proxyObj) as HttpURLConnection
                } else {
                    // 无代理时必须用无参 openConnection()，传 null 在部分 Android 版本会抛 "proxy can not be null"
                    URL(url).openConnection() as HttpURLConnection
                }
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Edqiu/1.0")
                // 续传请求：只取未下载部分（CDN twimg 普遍支持 Range）
                if (resumedBytes > 0L) {
                    connection.setRequestProperty("Range", "bytes=$resumedBytes-")
                }
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                try {
                    val code = connection.responseCode
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
            } catch (e: Exception) {
                lastError = e
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
        publishedAt: Long? = null
    ) {
        val json = JSONObject()
            .put("url", sourceUrl)
            .put("tweetId", tweetId)
            .put("uploader", uploader)
            .put("authorName", authorName ?: uploader)
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
        private val VIDEO_TYPES = setOf("video", "gif", "animated_gif")
        private val IMAGE_TYPES = setOf("photo", "image")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
    }
}
