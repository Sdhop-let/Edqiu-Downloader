package com.ed.twitterdownloader.service

import android.util.Log
import com.ed.twitterdownloader.data.model.MediaType
import com.ed.twitterdownloader.data.model.VideoFormat
import com.ed.twitterdownloader.data.model.VideoInfo
import com.ed.twitterdownloader.data.model.VideoInfo.FormatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale

/**
 * Resolves Twitter/X media info via the fxtwitter API.
 * This approach does NOT require user authentication 鈥?fxtwitter provides
 * public tweet metadata including direct video and image URLs.
 *
 * API format: https://api.fxtwitter.com/Twitter/status/{tweetId}
 * The username part "Twitter" is a placeholder 鈥?only the tweet ID matters.
 */
object FXTwitterResolver {

    private const val TAG = "FXTwitterResolver"
    private const val API_BASE = "https://api.fxtwitter.com/Twitter/status/"
    private val videoTypes = setOf("video", "gif", "animated_gif")
    private val imageTypes = setOf("photo", "image")

    /** Regex to extract tweet ID from any Twitter/X URL format. */
    private val TWEET_ID_REGEX = Regex("/status/(\\d+)")

    /**
     * Extract the numeric tweet ID from a Twitter/X URL.
     * Handles both formats: x.com/username/status/123 and x.com/i/status/123
     */
    fun extractTweetId(url: String): String? {
        return TWEET_ID_REGEX.find(url)?.groupValues?.get(1)
    }

    /**
     * Resolve video/image metadata via fxtwitter API.
     * @param url Original Twitter/X URL
     * @param proxyUrl Optional HTTP proxy (e.g. "http://127.0.0.1:7890") for Clash/V2Ray
     */
    suspend fun resolveVideoInfo(url: String, proxyUrl: String?): Result<VideoInfo> =
        withContext(Dispatchers.IO) {
            try {
                val tweetId = extractTweetId(url)
                if (tweetId == null) {
                    return@withContext Result.failure(Exception("鏃犳硶浠庨摼鎺ユ彁鍙栨帹鏂嘔D"))
                }

                val apiUrl = "$API_BASE$tweetId"
                Log.d(TAG, "Fetching fxtwitter API: $apiUrl")

                val jsonStr = fetchJson(apiUrl, proxyUrl)
                val root = JSONObject(jsonStr)

                // Check API response code
                val code = root.optInt("code", -1)
                if (code != 200) {
                    val message = root.optString("message", "Unknown error")
                    return@withContext Result.failure(Exception("fxtwitter API 閿欒 ($code): $message"))
                }

                val tweet = root.getJSONObject("tweet")
                val author = tweet.getJSONObject("author")
                val mediaItems = extractMediaItems(root)
                if (mediaItems.isEmpty()) {
                    return@withContext Result.failure(Exception("推文中没有可下载图片或视频"))
                }

                val formats = mediaItems.mapIndexedNotNull { index, item ->
                    item.toFormat(index + 1)
                }

                if (formats.isEmpty()) {
                    return@withContext Result.failure(Exception("娌℃湁鍙笅杞界殑濯掍綋鏍煎紡"))
                }

                val videoInfo = VideoInfo(
                    url = url,
                    title = tweet.optString("text", "Twitter Media").take(80).ifBlank { "Twitter Media" },
                    thumbnail = mediaItems.firstNotNullOfOrNull { it.thumbnail ?: it.url } ?: author.optString("avatar_url", ""),
                    duration = mediaItems.filter { it.mediaType == MediaType.VIDEO }.maxOfOrNull { it.duration } ?: 0L,
                    uploader = author.optString("screen_name", "unknown"),
                    formats = formats,
                    formatMode = FormatMode.MEDIA_ITEMS
                )

                Log.d(TAG, "fxtwitter resolved: ${videoInfo.formats.size} media item(s)")
                Result.success(videoInfo)
            } catch (e: Exception) {
                Log.e(TAG, "fxtwitter resolve error", e)
                Result.failure(e)
            }
        }

    /**
     * Fetch JSON from fxtwitter API with optional proxy support.
     */
    private fun fetchJson(urlStr: String, proxyUrl: String?): String {
        val proxy = parseProxy(proxyUrl)
        val connection = if (proxy != null) {
            URL(urlStr).openConnection(proxy) as HttpURLConnection
        } else {
            URL(urlStr).openConnection() as HttpURLConnection
        }

        connection.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Edqiu/1.0")
            connectTimeout = 15000
            readTimeout = 15000
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode from fxtwitter API")
            }

            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse proxy URL string into a java.net.Proxy object.
     * Format: "http://host:port"
     */
    private fun parseProxy(proxyUrl: String?): Proxy? {
        if (proxyUrl == null) return null
        try {
            val withoutProtocol = proxyUrl.removePrefix("http://")
            val parts = withoutProtocol.split(":")
            if (parts.size != 2) return null
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: return null
            return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse proxy URL: $proxyUrl", e)
            return null
        }
    }

    private fun extractMediaItems(root: JSONObject): List<FXMediaItem> {
        val tweet = root.optJSONObject("tweet") ?: root
        val candidates = mutableListOf<JSONObject>()

        tweet.optJSONObject("media")?.let { media ->
            candidates += media.optJsonObjectList("videos")
            candidates += media.optJsonObjectList("photos")
            candidates += media.optJsonObjectList("images")
            candidates += media.optJsonObjectList("all")
        }

        tweet.optJSONObject("extended_entities")?.let { entities ->
            candidates += entities.optJsonObjectList("media")
        }
        tweet.optJSONObject("entities")?.let { entities ->
            candidates += entities.optJsonObjectList("media")
        }

        candidates += tweet.optJsonObjectList("media_extended")
        candidates += root.optJsonObjectList("media_extended")

        val result = mutableListOf<FXMediaItem>()
        val seen = mutableSetOf<String>()

        candidates.forEach { item ->
            val mediaTypeText = item.optStringOrNull("type") ?: item.optStringOrNull("media_type")
            var isVideo = mediaTypeText in videoTypes
            var videoUrl = item.optStringOrNull("video_url") ?: item.optStringOrNull("source")

            val variants = item.optJSONArray("variants")
                ?: item.optJSONObject("video_info")?.optJSONArray("variants")
            val bestVariant = variants.bestMp4Variant()

            val bitrate = bestVariant?.optInt("bitrate", 0) ?: 0
            if (bestVariant != null) {
                videoUrl = bestVariant.optStringOrNull("url")
                isVideo = true
            }

            if (isVideo && !videoUrl.isNullOrBlank()) {
                val dedupeKey = videoUrl.substringBefore("?")
                if (seen.add(dedupeKey)) {
                    result += FXMediaItem(
                        url = videoUrl,
                        type = mediaTypeText ?: "video",
                        thumbnail = item.optStringOrNull("thumbnail_url")
                            ?: item.optStringOrNull("thumbnail")
                            ?: item.optStringOrNull("thumb"),
                        duration = item.optDouble("duration", 0.0).toLong(),
                        bitrate = bitrate,
                        ext = "mp4",
                        mediaType = MediaType.VIDEO
                    )
                }
                return@forEach
            }

            val imageUrl = item.optStringOrNull("image_url")
                ?: item.optStringOrNull("media_url_https")
                ?: item.optStringOrNull("media_url")
                ?: item.optStringOrNull("url")
                ?: item.optStringOrNull("thumbnail_url")
                ?: item.optStringOrNull("thumbnail")
                ?: item.optStringOrNull("thumb")
            val isImage = mediaTypeText in imageTypes || imageUrl?.contains("pbs.twimg.com/media", ignoreCase = true) == true

            if (isImage && !imageUrl.isNullOrBlank()) {
                val dedupeKey = imageUrl.substringBefore("?")
                if (seen.add(dedupeKey)) {
                    val ext = inferImageExtension(imageUrl)
                    result += FXMediaItem(
                        url = imageUrl,
                        type = mediaTypeText ?: "photo",
                        thumbnail = imageUrl,
                        duration = 0L,
                        bitrate = 0,
                        ext = ext,
                        mediaType = MediaType.IMAGE
                    )
                }
            }
        }

        return result
    }

    private fun FXMediaItem.toFormat(index: Int): VideoFormat? {
        if (url.isBlank()) return null

        return when (mediaType) {
            MediaType.VIDEO -> {
                val qualityLabel = bitrateToQualityLabel(bitrate)
                VideoFormat(
                    formatId = "fx_${index}_${bitrate}",
                    quality = "瑙嗛${index.toString().padStart(2, '0')} 路 $qualityLabel",
                    ext = ext,
                    filesize = 0,
                    vcodec = "h264",
                    acodec = "aac",
                    fps = 0,
                    directUrl = url,
                    thumbnail = thumbnail,
                    mediaIndex = index,
                    isMediaItem = true,
                    mediaType = MediaType.VIDEO
                )
            }
            MediaType.IMAGE -> {
                VideoFormat(
                    formatId = "fx_img_$index",
                    quality = "鍥剧墖${index.toString().padStart(2, '0')} 路 鍘熷浘",
                    ext = ext,
                    filesize = 0,
                    directUrl = url,
                    thumbnail = thumbnail ?: url,
                    mediaIndex = index,
                    isMediaItem = true,
                    mediaType = MediaType.IMAGE
                )
            }
        }
    }

    private fun JSONObject.optJsonObjectList(key: String): List<JSONObject> {
        return optJSONArray(key)?.toJsonObjectList().orEmpty()
    }

    private fun JSONArray?.bestMp4Variant(): JSONObject? {
        if (this == null) return null

        return toJsonObjectList()
            .filter { variant ->
                variant.optStringOrNull("content_type") == "video/mp4" ||
                    ".mp4" in variant.optString("url", "")
            }
            .maxByOrNull { it.optInt("bitrate", 0) }
    }

    private fun JSONArray.toJsonObjectList(): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        for (index in 0 until length()) {
            val obj = optJSONObject(index)
            if (obj != null) {
                items += obj
            } else {
                val directUrl = optString(index, "")
                if (directUrl.isNotBlank()) {
                    items += JSONObject().put("url", directUrl).put("type", "photo")
                }
            }
        }
        return items
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    }

    private fun inferImageExtension(url: String): String {
        val formatMatch = Regex("[?&]format=([a-zA-Z0-9]+)").find(url)
        val formatExt = formatMatch?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
        if (formatExt in setOf("jpg", "jpeg", "png", "webp", "gif")) return formatExt!!

        val cleanPath = url.substringBefore("?").substringBefore("#")
        val pathExt = cleanPath.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
        return if (pathExt in setOf("jpg", "jpeg", "png", "webp", "gif")) pathExt else "jpg"
    }

    /**
     * Map Twitter video bitrate to a human-readable quality label.
     */
    private fun bitrateToQualityLabel(bitrate: Int): String {
        return when {
            bitrate >= 2000000 -> "1080p"
            bitrate >= 832000 -> "720p"
            bitrate >= 320000 -> "480p"
            bitrate > 0 -> "360p"
            else -> "unknown"
        }
    }

    private data class FXMediaItem(
        val url: String,
        val type: String,
        val thumbnail: String?,
        val duration: Long,
        val bitrate: Int,
        val ext: String,
        val mediaType: MediaType
    )
}

