package com.ed.edqiu.data.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        try {
            val url = URL("https://api.fxtwitter.com/status/$tweetId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Edqiu/1.0")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseTwitterResponse(body)
            } else {
                null
            }
        } catch (_: Exception) {
            null
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
                TweetMeta(
                    tweetId = t.id,
                    authorId = screenName?.takeIf { it.isNotBlank() }?.let { "@$it" },
                    authorName = t.author?.name,
                    caption = t.text,
                    avatarUrl = t.author?.avatarUrl,
                    thumbnailUrl = firstMediaThumbnail(t.media)
                )
            } else {
                null
            }
        }.getOrNull()
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
        val media: FxMedia? = null
    )

    @Serializable
    private data class FxAuthor(
        val name: String? = null,
        @SerialName("screen_name")
        val screenName: String? = null,
        @SerialName("avatar_url")
        val avatarUrl: String? = null
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
        val authorName: String? = null
    )
}
