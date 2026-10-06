package com.ed.edqiu.data.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 元数据来源：
 *  1) fxtwitter 公开 API（无需登录）实时解析推文作者/文案/封面；
 *  2) 下载器在输出目录写入的 .meta.json（已下载后补全）。
 *
 * 两路获取均「尽力而为」：任何失败返回 null，绝不阻塞捕获流程。
 */
class MetadataFetcher {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 从 fxtwitter 拉取推文元数据。 */
    suspend fun fetchFromTwitter(tweetId: String): TweetMeta? = withContext(Dispatchers.IO) {
        // 2026-10 整改：连接 finally disconnect + 非 200 分支关闭 errorStream
        //（旧实现连接不释放，补拉批量 300 条 × 8 并发时 socket 压力被放大）
        val conn = (URL("https://api.fxtwitter.com/status/$tweetId").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Edqiu/1.0")
        }
        try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseTwitterResponse(body)
            } else {
                runCatching { conn.errorStream?.close() }
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 解析下载器输出的 .meta.json 文本。 */
    fun parseDownloaderMeta(jsonText: String?): DownloaderMeta? {
        if (jsonText.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<DownloaderMeta>(jsonText) }.getOrNull()
    }

    fun parseTwitterResponse(body: String): TweetMeta? {
        return runCatching {
            val resp = json.decodeFromString<FxResponse>(body)
            if (resp.code == 200 && resp.tweet != null) {
                val t = resp.tweet
                val screenName = t.author?.screenName?.trimStart('@')
                // 2026-10-02 批次B：主媒体显示宽高（视频优先，缺失再取图片）
                val dims = firstMediaDimensions(t.media)
                TweetMeta(
                    tweetId = t.id,
                    authorId = screenName?.takeIf { it.isNotBlank() }?.let { "@$it" },
                    authorName = t.author?.name,
                    caption = t.text,
                    avatarUrl = t.author?.avatarUrl,
                    thumbnailUrl = firstMediaThumbnail(t.media),
                    authorBio = t.author?.description?.takeIf { it.isNotBlank() },
                    // FXTwitter 的 created_timestamp 为 epoch 秒
                    publishedAt = t.createdTimestamp?.takeIf { it > 0L }?.let { it * 1000L },
                    mediaWidth = dims?.first,
                    mediaHeight = dims?.second
                )
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * 2026-10-02 批次B：从推文首条媒体提取显示宽高（px）。
     * 视频取 media.videos[0]（外层无 width/height 时回退其 videos[] 变体子数组），
     * 图片取 media.photos[0].width/height；宽或高 ≤0 视为无效，返回 null 靠回填 Worker 兜底。
     */
    private fun firstMediaDimensions(media: FxMedia?): Pair<Int, Int>? {
        if (media == null) return null
        media.videos?.firstPositiveDims(deepIntoVariants = true)?.let { return it }
        return media.photos?.firstPositiveDims(deepIntoVariants = false)
    }

    private fun JsonArray.firstPositiveDims(deepIntoVariants: Boolean): Pair<Int, Int>? {
        for (element in this) {
            val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
            obj.positiveDims()?.let { return it }
            if (deepIntoVariants) {
                // FXTwitter 视频条目的变体子数组：videos[].width/height（实际分辨率）
                (obj["videos"] as? JsonArray)?.firstPositiveDims(deepIntoVariants = false)?.let { return it }
            }
        }
        return null
    }

    private fun JsonObject.positiveDims(): Pair<Int, Int>? {
        // runCatching：个别字段类型异常（非数值）只降级为缺失，不拖垮整条 TweetMeta 解析
        val w = runCatching { this["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull() ?: 0
        val h = runCatching { this["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull() ?: 0
        return if (w > 0 && h > 0) w to h else null
    }

    private fun firstMediaThumbnail(media: FxMedia?): String? {
        if (media == null) return null

        firstUrlFromArrays(
            media.videos,
            media.rawVideos,
            keys = arrayOf("thumbnail_url", "thumbnail", "thumb", "poster")
        )?.let { return it }

        return firstUrlFromArrays(
            media.photos,
            media.images,
            media.all,
            keys = arrayOf("image_url", "media_url_https", "media_url", "url", "thumbnail_url", "thumbnail", "thumb")
        )
    }

    private fun firstUrlFromArrays(vararg arrays: JsonArray?, keys: Array<String>): String? {
        arrays.filterNotNull().forEach { array ->
            for (element in array) {
                element.directStringUrl()?.let { return it }
                val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
                keys.firstNotNullOfOrNull { key ->
                    obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                }?.let { return it }
            }
        }
        return null
    }

    private fun JsonElement.directStringUrl(): String? =
        runCatching { jsonPrimitive.contentOrNull }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    // ---- fxtwitter 响应结构（只取需要的字段，其余忽略）----
    @Serializable
    private data class FxResponse(
        val code: Int = 0,
        val tweet: FxTweet? = null
    )

    @Serializable
    private data class FxTweet(
        val id: String = "",
        val text: String? = null,
        val author: FxAuthor? = null,
        val media: FxMedia? = null,
        @SerialName("created_timestamp") val createdTimestamp: Long? = null
    )

    @Serializable
    private data class FxAuthor(
        val name: String? = null,
        @SerialName("screen_name")
        val screenName: String? = null,
        @SerialName("avatar_url")
        val avatarUrl: String? = null,
        val description: String? = null
    )

    @Serializable
    private data class FxMedia(
        val videos: JsonArray? = null,
        @SerialName("rawVideos")
        val rawVideos: JsonArray? = null,
        val photos: JsonArray? = null,
        val images: JsonArray? = null,
        val all: JsonArray? = null
    )

    /** 下载器写入的 .meta.json 结构（字段来自下载器源码约定）。 */
    @Serializable
    data class DownloaderMeta(
        val tweetId: String? = null,
        val url: String? = null,
        val title: String? = null,
        val thumbnail: String? = null,
        val uploader: String? = null,
        val authorName: String? = null,
        /** 推文发布时间（epoch ms，2026-09-15 媒体库/作者页按发布时间排序）。 */
        val publishedAt: Long? = null
    )
}
