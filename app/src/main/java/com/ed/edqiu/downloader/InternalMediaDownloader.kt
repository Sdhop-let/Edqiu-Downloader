package com.ed.edqiu.downloader

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ed.twitterdownloader.data.model.DownloadStatus
import com.ed.twitterdownloader.data.model.DownloadTask
import com.ed.twitterdownloader.data.model.MediaType
import com.ed.twitterdownloader.data.repository.DownloadTaskBus
import com.ed.edqiu.domain.TweetIdExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class InternalMediaDownloader(private val context: Context) {

    data class DownloadedFile(
        val tweetId: String,
        val filePath: String,
        val downloadedAt: Long,
        val authorId: String?,
        val authorName: String?,
        val caption: String?,
        val thumbnailUrl: String?
    )

    suspend fun downloadTweet(rawUrl: String): Result<List<DownloadedFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val tweetId = TweetIdExtractor.fromUrl(rawUrl)
                ?: error("链接中没有有效的推文 ID")
            val normalizedUrl = "https://x.com/i/status/$tweetId"
            val root = JSONObject(fetchText("$API_BASE$tweetId"))
            val code = root.optInt("code", -1)
            if (code != 200) error(root.optString("message", "FXTwitter API 返回 $code"))

            val tweet = root.getJSONObject("tweet")
            val author = tweet.optJSONObject("author")
            val authorId = author?.optString("screen_name")?.takeIf { it.isNotBlank() }?.let { "@$it" }
            val authorName = author?.optString("name")?.takeIf { it.isNotBlank() }
            val uploader = authorId?.removePrefix("@") ?: "unknown"
            val caption = tweet.optString("text").takeIf { it.isNotBlank() }
            val media = extractMedia(tweet)
            if (media.isEmpty()) error("推文中没有可下载的视频或图片")

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
                    downloadFile(item.url, target)
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
                        ext = item.ext
                    )
                    DownloadedFile(
                        tweetId = tweetId,
                        filePath = target.absolutePath,
                        downloadedAt = target.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                        authorId = authorId,
                        authorName = authorName,
                        caption = caption,
                        thumbnailUrl = item.thumbnail ?: item.url
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

    private fun extractMedia(tweet: JSONObject): List<MediaItem> {
        val mediaObject = tweet.optJSONObject("media") ?: JSONObject()
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

    private fun downloadFile(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Edqiu/1.0")
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${connection.responseCode} 下载失败")
            }
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Edqiu/1.0")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${connection.responseCode} from FXTwitter")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeSidecar(
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
        ext: String
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
        File(mediaFile.absolutePath + META_SUFFIX).writeText(json.toString())
        Log.i(TAG, "Downloaded ${mediaFile.name}")
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
