package com.ed.edqiu.data.xapi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * X (Twitter) GraphQL 客户端（2026-10-17 v1.9.2 修复：作者订阅 twitter 检测通道重建）。
 *
 * 背景：旧实现把 twitter 检测寄托于 yt-dlp 提取 `x.com/<user>` 用户页，而 yt-dlp
 * **从不支持**用户主页时间线（`Unsupported URL`），失败被静默吞掉 → 订阅永远「暂无新作品」。
 * 本客户端改为直连 X 网页版自用的 GraphQL 接口：
 *
 * 1. [activateGuest]——POST `api.x.com/1.1/guest/activate.json`（public bearer）拿 guest token；
 * 2. [userByScreenName]——`UserByScreenName` 查询拿 rest_id；
 * 3. [userTweets]——`UserTweets` 查询拿时间线推文（rest_id + created_at）。
 *
 * 双通道：
 * - **guest 通道**（免登录、零配置，主力）：[Channel.Guest]；
 * - **cookie 通道**（用户 auth_token/ct0，兜底）：[Channel.Cookie]——guest 数据被 X 降级时兜底。
 *
 * queryId 静默轮换：X 定期轮换 GraphQL queryId，失效签名（`query: not found` 等）会触发
 * 远程 manifest 热更新（[refreshManifest]，仓库 x_api_manifest.json）并重试一次；
 * 重试仍失败才向上抛 [XApiError.STALE_QUERY_ID]（UI 弹窗提示升级 App）。
 *
 * 网络要求：api.x.com / x.com 国内不可直连，调用方应传入代理（ProxySettings.toJavaProxy()）；
 * 无代理时本客户端仍会短超时尝试一次，失败按 [XApiError.NO_PROXY] 归类。
 */
object XApiClient {

    private const val TAG = "XApiClient"

    private const val PREFS = "x_api_config"

    // ============ 内置默认值（manifest 拉取失败时的兜底，2026-10-17 实测可用）============

    /** X 网页内置 public bearer（非账号机密，X 前端 JS 常量）。 */
    private const val DEFAULT_BEARER =
        "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

    private const val DEFAULT_USER_BY_SCREEN_NAME = "sLVLhk0bGj3MVFEKTdax1w"
    private const val DEFAULT_USER_TWEETS = "E3opETHurmVJflFsUBVuUQ"

    /** 远程 manifest（queryId 热更新源）：仓库 main 分支根目录。 */
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/Sdhop-let/Edqiu-Downloader/main/x_api_manifest.json"

    /** manifest 静默刷新有效期：7 天内不重复拉。 */
    private const val MANIFEST_TTL_MS = 7L * 24 * 3600 * 1000

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // ==================== 通道、错误与查询结果 ====================

    sealed interface Channel {
        /** guest 通道：免登录。 */
        data class Guest(val token: String) : Channel

        /** cookie 通道：用户登录态。 */
        data class Cookie(val authToken: String, val ct0: String) : Channel
    }

    /** 面向用户分类的失败原因；文案组装在 [XTimelineDetector]。 */
    enum class XApiError {
        /** 未配置代理（X 国内不可直连）。 */
        NO_PROXY,

        /** 代理延迟过高/无响应。 */
        PROXY_TIMEOUT,

        /** 代理不可达（连接被拒/域名解析失败）。 */
        PROXY_UNREACHABLE,

        /** X 限流（429）。 */
        RATE_LIMITED,

        /** cookie 通道认证失效（401/403，auth_token 过期）。 */
        AUTH_EXPIRED,

        /** queryId 陈旧且静默轮换失败（manifest 拉取失败或新 ID 仍失效）。 */
        STALE_QUERY_ID,

        /** 账号对当前通道不可见（游客降级/不存在/冻结）。 */
        ACCOUNT_UNAVAILABLE,

        /** 响应结构异常（X 改版）。 */
        MALFORMED_RESPONSE,

        /** 其他网络错误。 */
        NETWORK,
    }

    class XApiException(val error: XApiError, detail: String?) : Exception(detail ?: error.name)

    /** UserTweets 解析出的单条推文（ID + 原始时间串）。 */
    data class XTimelineTweet(val id: String, val createdAtRaw: String?)

    // ==================== 查询入口 ====================

    /** 激活 guest token（每次轮询新鲜激活，几分钟级时效，调用极轻）。 */
    fun activateGuest(proxyUrl: String?): Result<String> = runCatching {
        val request = Request.Builder()
            .url("https://api.x.com/1.1/guest/activate.json")
            .header("Authorization", "Bearer ${bearer()}")
            .header("User-Agent", UA)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val body = execute(request, proxyUrl)
        val token = body.optString("guest_token")
        if (token.isBlank()) throw XApiException(XApiError.MALFORMED_RESPONSE, "guest_token 缺失")
        token
    }

    /** UserByScreenName：screenName → rest_id（userId）。 */
    fun userByScreenName(
        screenName: String,
        channel: Channel,
        proxyUrl: String?,
    ): Result<Long> =
        graphql("UserByScreenName", JSONObject().put("screen_name", screenName), channel, proxyUrl)
            .mapCatching { body ->
                val result = body.optJSONObject("data")?.optJSONObject("user")?.optJSONObject("result")
                    ?: throw XApiException(XApiError.ACCOUNT_UNAVAILABLE, "@$screenName 对当前通道不可见")
                decodeRestId(result.optString("id"))
                    ?: throw XApiException(XApiError.MALFORMED_RESPONSE, "rest_id 解析失败")
            }

    /**
     * UserTweets：拿时间线（count 条，含置顶）。
     * @return 推文列表（保持接口返回顺序；新鲜度由调用方按 createdAtRaw 判断）。
     */
    fun userTweets(
        userId: Long,
        count: Int,
        channel: Channel,
        proxyUrl: String?,
    ): Result<List<XTimelineTweet>> =
        graphql(
            operation = "UserTweets",
            variables = JSONObject()
                .put("userId", userId.toString())
                .put("count", count)
                .put("includePromotedContent", false)
                .put("withQuickPromoteEligibilityTweetFields", true)
                .put("withVoice", true),
            channel = channel,
            proxyUrl = proxyUrl,
        ).mapCatching { body -> parseUserTweets(body) }

    // ==================== GraphQL 传输 + queryId 轮换 ====================

    /**
     * 执行一次 GraphQL 查询；queryId 陈旧时静默刷新 manifest 并重试一次，仍失败抛
     * [XApiException] error=[XApiError.STALE_QUERY_ID]。
     */
    private fun graphql(
        operation: String,
        variables: JSONObject,
        channel: Channel,
        proxyUrl: String?,
    ): Result<JSONObject> {
        val firstQid = queryId(operation)
        val first = tryGet(operation, variables, channel, proxyUrl, firstQid)
        if (first.isSuccess) return first
        val firstErr = first.exceptionOrNull()
        // 仅陈旧签名值得花一次 manifest 请求；网络/限流/认证类失败直接上交
        if (firstErr !is XApiException || firstErr.error != XApiError.STALE_QUERY_ID) {
            return first
        }
        if (!refreshManifest(proxyUrl)) return first
        val rotated = queryId(operation)
        if (rotated == firstQid) return first
        return tryGet(operation, variables, channel, proxyUrl, rotated)
    }

    private fun tryGet(
        operation: String,
        variables: JSONObject,
        channel: Channel,
        proxyUrl: String?,
        queryId: String,
    ): Result<JSONObject> {
        val url = "https://x.com/i/api/graphql/$queryId/$operation" +
            "?variables=${java.net.URLEncoder.encode(variables.toString(), "UTF-8")}"
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${bearer()}")
            .header("User-Agent", UA)
            .header("x-twitter-active-user", "yes")
        when (channel) {
            is Channel.Guest -> builder.header("x-guest-token", channel.token)
            is Channel.Cookie -> {
                builder.header("Cookie", "auth_token=${channel.authToken}; ct0=${channel.ct0}")
                builder.header("x-csrf-token", channel.ct0)
                builder.header("x-twitter-auth-type", "OAuth2Session")
            }
        }
        val request = builder.build()
        return runCatching { execute(request, proxyUrl) }
    }

    /** 统一 execute + 错误归类。 */
    private fun execute(request: Request, proxyUrl: String?): JSONObject {
        val client = OkHttpClient.Builder()
            .connectTimeout(if (proxyUrl != null) 12L else 8L, TimeUnit.SECONDS)
            .readTimeout(20L, TimeUnit.SECONDS)
            .apply { proxyUrl?.let { u -> proxy(toJavaProxy(u)) } }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val bodyText = runCatching { response.body?.string() }.getOrNull().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    throw XApiException(XApiError.AUTH_EXPIRED, "HTTP ${response.code}")
                }
                if (response.code == 429) {
                    throw XApiException(XApiError.RATE_LIMITED, "HTTP 429")
                }
                if (!response.isSuccessful) {
                    throw XApiException(XApiError.MALFORMED_RESPONSE, "HTTP ${response.code}")
                }
                val trimmed = bodyText.trimStart()
                if (!trimmed.startsWith("{")) {
                    // X 风控页（HTML）/ 空响应：无法区分 queryId 失效与风控，按陈旧处理触发一次轮换
                    throw XApiException(XApiError.STALE_QUERY_ID, "非 JSON: ${trimmed.take(80)}")
                }
                val json = runCatching { JSONObject(bodyText) }.getOrElse {
                    throw XApiException(XApiError.MALFORMED_RESPONSE, "JSON 解析失败")
                }
                val errors = json.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    val first = errors.optJSONObject(0)
                    val msg = first?.optString("message").orEmpty()
                    val code = first?.optInt("code", 0)
                    val looksStale = msg.contains("query: not found", true) ||
                        msg.contains("Invalid query", true) ||
                        code == 34 || code == 32
                    if (looksStale) {
                        throw XApiException(XApiError.STALE_QUERY_ID, msg)
                    }
                }
                if (!json.has("data")) {
                    throw XApiException(XApiError.MALFORMED_RESPONSE, "响应无 data 字段")
                }
                return json
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw XApiException(
                if (proxyUrl != null) XApiError.PROXY_TIMEOUT else XApiError.NETWORK,
                "read timeout",
            )
        } catch (e: java.net.UnknownHostException) {
            throw XApiException(
                if (proxyUrl != null) XApiError.PROXY_UNREACHABLE else XApiError.NO_PROXY,
                "unknown host",
            )
        } catch (e: java.net.ConnectException) {
            throw XApiException(
                if (proxyUrl != null) XApiError.PROXY_UNREACHABLE else XApiError.NO_PROXY,
                e.message,
            )
        } catch (e: java.io.IOException) {
            throw XApiException(XApiError.NETWORK, e.message)
        }
    }

    // ==================== 解析 ====================

    private fun parseUserTweets(body: JSONObject): List<XTimelineTweet> {
        val user = body.optJSONObject("data")?.optJSONObject("user")?.optJSONObject("result")
            ?: throw XApiException(XApiError.ACCOUNT_UNAVAILABLE, "user.result 缺失")
        val timeline = user.optJSONObject("timeline")?.optJSONObject("timeline")
            ?: throw XApiException(XApiError.ACCOUNT_UNAVAILABLE, "timeline 缺失")
        val out = mutableListOf<XTimelineTweet>()
        // 注意：Android org.json.JSONArray 不可 for-in（无 iterator()），按索引遍历
        val instructions = timeline.optJSONArray("instructions") ?: JSONArray()
        for (i in 0 until instructions.length()) {
            val instruction = instructions.optJSONObject(i) ?: continue
            val entries = instruction.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val item = entry.optJSONObject("content")?.optJSONObject("itemContent") ?: continue
                if (item.optString("__typename") != "TimelineTweet") continue
                val result = item.optJSONObject("tweet_results")?.optJSONObject("result") ?: continue
                val tweet = result.optJSONObject("tweet") ?: result // TweetWithVisibilityResults 包装
                if (tweet.optString("__typename") != "Tweet") continue
                val id = tweet.optString("rest_id")
                if (id.isBlank()) continue
                out += XTimelineTweet(id = id, tweet.optJSONObject("legacy")?.optString("created_at"))
            }
        }
        if (out.isEmpty()) {
            throw XApiException(XApiError.ACCOUNT_UNAVAILABLE, "时间线为空")
        }
        return out
    }

    /** X rest_id 两种编码：纯数字，或 Base64("User:44196397")。 */
    private fun decodeRestId(raw: String): Long? {
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { return it }
        return runCatching {
            val decoded = String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT))
            decoded.substringAfterLast(':').toLongOrNull()
        }.getOrNull()
    }

    // ==================== manifest 静默轮换 ====================

    /**
     * 拉取远程 manifest 热更新 queryId/bearer 并落 SharedPreferences。
     * @return true = 配置已更新（调用方可重试原查询）。
     */
    fun refreshManifest(proxyUrl: String?): Boolean = runCatching {
        val request = Request.Builder()
            .url(MANIFEST_URL)
            .header("User-Agent", UA)
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(15L, TimeUnit.SECONDS)
            .apply { proxyUrl?.let { u -> proxy(toJavaProxy(u)) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val json = JSONObject(response.body?.string().orEmpty())
            val ids = json.optJSONObject("queryIds") ?: return false
            val userByScreenName = ids.optString("userByScreenName").ifBlank { return false }
            val userTweets = ids.optString("userTweets").ifBlank { return false }
            prefs().edit()
                .putString("bearer", json.optString("bearer").ifBlank { DEFAULT_BEARER })
                .putString("qid_userByScreenName", userByScreenName)
                .putString("qid_userTweets", userTweets)
                .putLong("manifestAt", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "manifest 已热更新：$userByScreenName / $userTweets")
            true
        }
    }.getOrElse {
        Log.w(TAG, "manifest 拉取失败：${it.message}")
        false
    }

    /**
     * 代理开启时的机会性静默升级：manifest 超过 [MANIFEST_TTL_MS] 才拉，失败静默（不弹窗）。
     * 由订阅轮询每轮开头调用一次——用户无感完成 queryId 保鲜。
     */
    fun maybeQuietUpgrade(proxyUrl: String?) {
        if (proxyUrl == null) return
        val last = prefs().getLong("manifestAt", 0L)
        if (System.currentTimeMillis() - last < MANIFEST_TTL_MS) return
        refreshManifest(proxyUrl)
    }

    // ==================== 配置存取 ====================

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /** 进程启动时装配一次（AppContainer.init 调用）。 */
    fun init(context: Context) {
        if (cachedPrefs == null) {
            synchronized(this) {
                if (cachedPrefs == null) {
                    cachedPrefs = context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun prefs(): SharedPreferences = cachedPrefs
        ?: throw IllegalStateException("XApiClient.init(context) 未调用")

    private fun bearer(): String =
        prefs().getString("bearer", null)?.ifBlank { null } ?: DEFAULT_BEARER

    private fun queryId(operation: String): String {
        val key = when (operation) {
            "UserByScreenName" -> "qid_userByScreenName"
            "UserTweets" -> "qid_userTweets"
            else -> return ""
        }
        return prefs().getString(key, null)?.ifBlank { null } ?: defaultQueryId(operation)
    }

    private fun defaultQueryId(operation: String): String = when (operation) {
        "UserByScreenName" -> DEFAULT_USER_BY_SCREEN_NAME
        "UserTweets" -> DEFAULT_USER_TWEETS
        else -> ""
    }

    /** 代理 URL → java.net.Proxy（与 Bluesky 通道同规则；失败返回 null = 直连）。 */
    private fun toJavaProxy(proxyUrl: String): java.net.Proxy? = runCatching {
        val uri = java.net.URI.create(proxyUrl)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else when (uri.scheme) {
            "http", "https" -> 80
            "socks", "socks5" -> 1080
            else -> return null
        }
        val type = if (uri.scheme.startsWith("socks")) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
        java.net.Proxy(type, java.net.InetSocketAddress(host, port))
    }.getOrNull()
}
