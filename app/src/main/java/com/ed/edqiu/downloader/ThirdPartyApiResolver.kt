package com.ed.edqiu.downloader

import com.ed.edqiu.data.model.ProxySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 第三方解析 API 兜底引擎（2026-09-15 P0-1 解析链三层冗余第三层）。
 *
 * 定位：FXTwitter 与 yt-dlp 都失败后的最后一道保险（付费 API 通常能覆盖
 * 登录态/风控内容）。**默认关闭**——设置页「网络与认证 → 第三方解析兜底」
 * 填入端点后才启用（[com.ed.edqiu.data.preferences.ThirdPartyApiPreferences]）。
 *
 * 通用契约（容错解析，兼容常见返回形态）：
 * - 端点支持 {id} 占位符（如 `https://api.example.com/twitter/status/{id}`），
 *   未占位则把 tweetId 拼在端点末尾；
 * - 鉴权：`Authorization: Bearer <key>`（Key 留空则不带该头）；
 * - 响应形态：①FXTwitter 兼容形（code=200 + tweet.media）②data.medias/media 数组
 *   ③data.url / url / video_url 单链接。
 *
 * 安全审计约定：接入任何付费 API 前必须人工甄别响应无广告/跟踪注入（项目铁律）。
 */
class ThirdPartyApiResolver {

    data class ThirdPartyMedia(
        val url: String,
        /** "video" | "image" */
        val kind: String,
        val ext: String,
        val quality: String,
        val thumbnail: String?
    )

    suspend fun resolve(
        tweetId: String,
        endpoint: String,
        apiKey: String,
        proxy: ProxySettings?
    ): Result<List<ThirdPartyMedia>> = withContext(Dispatchers.IO) {
        runCatching {
            val apiUrl = if (endpoint.contains("{id}")) {
                endpoint.replace("{id}", tweetId)
            } else {
                endpoint.trimEnd('/') + "/" + tweetId
            }
            val json = JSONObject(fetchJson(apiUrl, apiKey, proxy))
            parseMedias(json).also {
                if (it.isEmpty()) error("第三方 API 未返回可用媒体链接")
            }
        }
    }

    private fun fetchJson(url: String, apiKey: String, proxy: ProxySettings?): String {
        val proxyObj = proxy?.toJavaProxy()
        val connection = if (proxyObj != null) {
            URL(url).openConnection(proxyObj) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
        connection.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Edqiu/1.0")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** 容错解析：覆盖三类常见返回形态，URL 去重（去掉 query 后比对）。 */
    internal fun parseMedias(root: JSONObject): List<ThirdPartyMedia> {
        val out = mutableListOf<ThirdPartyMedia>()
        val seen = mutableSetOf<String>()

        fun add(url: String?, kind: String, thumbnail: String? = null) {
            val clean = url?.trim().takeIf { !it.isNullOrBlank() && it.startsWith("http") } ?: return
            val key = clean.substringBefore("?")
            if (!seen.add(key)) return
            val ext = if (kind == "image") {
                val format = Regex("[?&]format=([a-zA-Z0-9]+)").find(clean)?.groupValues?.getOrNull(1)?.lowercase()
                format?.takeIf { it in IMAGE_EXTS }
                    ?: clean.substringBefore("?").substringAfterLast('.', "jpg").lowercase().takeIf { it in IMAGE_EXTS }
                    ?: "jpg"
            } else "mp4"
            out += ThirdPartyMedia(clean, kind, ext, "best", thumbnail)
        }

        // 形态①：FXTwitter 兼容（code=200 + tweet.media.*）
        val tweet = root.optJSONObject("tweet")
        if (tweet != null && root.optInt("code", -1) == 200) {
            val media = tweet.optJSONObject("media") ?: JSONObject()
            (media.optObjects("videos") + media.optObjects("all")).forEach { item ->
                val best = item.optJSONArray("variants")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                        .filter { it.optString("content_type") == "video/mp4" || it.optString("url").contains(".mp4") }
                        .maxByOrNull { it.optInt("bitrate", 0) }
                }
                add(
                    best?.optString("url")?.takeIf { it.isNotBlank() } ?: item.optStringOrNull("video_url"),
                    "video",
                    item.optStringOrNull("thumbnail_url") ?: item.optStringOrNull("thumbnail")
                )
            }
            (media.optObjects("photos") + media.optObjects("images")).forEach { item ->
                add(item.firstString("image_url", "media_url_https", "media_url", "url"), "image")
            }
            if (out.isNotEmpty()) return out
        }

        // 形态②：data.medias / data.media 数组（常见付费 API 返回）
        val data = root.optJSONObject("data") ?: root
        listOfNotNull(data.optJSONArray("medias"), data.optJSONArray("media")).forEach { array ->
            (0 until array.length()).forEach { i ->
                val item = array.optJSONObject(i) ?: return@forEach
                val type = item.optString("type", item.optString("media_type", "")).lowercase()
                val kind = if (type.contains("photo") || type.contains("image") || type.contains("pic")) "image" else "video"
                add(
                    item.firstString("url", "video_url", "image_url", "media_url_https", "media_url"),
                    kind,
                    item.optStringOrNull("thumbnail") ?: item.optStringOrNull("thumb")
                )
            }
        }
        if (out.isNotEmpty()) return out

        // 形态③：单链接（data.url / url / video_url）
        add(data.firstString("url", "video_url", "videoUrl"), "video", data.optStringOrNull("thumbnail"))
        if (out.isEmpty()) add(root.firstString("url", "video_url", "videoUrl"), "video", root.optStringOrNull("thumbnail"))
        return out
    }

    private fun JSONObject.optObjects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private companion object {
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif")
    }
}
