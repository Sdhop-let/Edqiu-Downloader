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
 * 2026-11-21 云控扩展：同一 manifest 额外承载 `fxHosts`（FXTwitter 兼容 API 主机列表，
 * 下载/元数据/画质升级按序回退）与 `bskyEndpoint`（Bluesky 公共 AppView 根地址）——
 * 上游实例故障时改仓库清单即全体切换，无需发版。校验不过保留原值。
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

    /** FXTwitter 兼容 API 主机（2026-11-21 云控）：下载/元数据/升级器的 API_BASE 主机部分。 */
    private val DEFAULT_FX_HOSTS = listOf("https://api.fxtwitter.com")

    /** Bluesky 公共 AppView 根地址（2026-11-21 云控）：订阅检测的 xrpc 调用前缀。 */
    private const val DEFAULT_BSKY_ENDPOINT = "https://public.api.bsky.app"

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
    data class XTimelineTweet(val id: String, val createdAtRaw: String?, val authorHandle: String? = null)

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
        // 2026-11-07 审计修复：共享连接池——旧实现每请求新建 OkHttpClient，
        // TLS 握手/连接池全部不复用（WebDavEngine 已示范 newBuilder 模式）
        val client = clientFor(proxyUrl)
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
                // 2026-11-07 审计修复：rest_id 可能是 Base64("User:44196397") 编码形态
                // （本文件 UserByScreenName 已做解码、注释明确两种编码）——旧实现取原文，
                // 编码形态下去重键 takeWhile{isDigit} 得空串被静默 continue，订阅整体失效
                val id = decodeRestId(tweet.optString("rest_id"))?.toString() ?: continue
                // 2026-10-24 订阅污染修复：提取推文作者 handle——UserTweets 时间线含转推/引推，
                // 其作者是别人（原创者）；订阅语义=只收订阅作者本人的作品，调用方据此过滤。
                // 结构缺失（字段裁剪的响应）时置 null，调用方保守放行不误杀
                val authorHandle = tweet.optJSONObject("core")
                    ?.optJSONObject("user_results")
                    ?.optJSONObject("result")
                    ?.optJSONObject("legacy")
                    ?.optString("screen_name")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                out += XTimelineTweet(id = id, tweet.optJSONObject("legacy")?.optString("created_at"), authorHandle)
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

    /**
     * 共享基础客户端 + 按请求派生（连接池/线程池复用，代理参数动态下传即时生效）。
     */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8L, TimeUnit.SECONDS)
            .readTimeout(20L, TimeUnit.SECONDS)
            .build()
    }

    private fun clientFor(proxyUrl: String?): OkHttpClient =
        if (proxyUrl == null) {
            baseClient
        } else {
            baseClient.newBuilder()
                .connectTimeout(12L, TimeUnit.SECONDS)
                .proxy(toJavaProxy(proxyUrl))
                .build()
        }

    // ==================== manifest 静默轮换 ====================

    /**
     * 拉取远程 manifest 热更新 queryId/bearer/FX 主机/Bluesky 端点并落 SharedPreferences。
     * @return true = 配置已更新（调用方可重试原查询）。
     */
    fun refreshManifest(proxyUrl: String?): Boolean = runCatching {
        val request = Request.Builder()
            .url(MANIFEST_URL)
            .header("User-Agent", UA)
            .build()
        val client = baseClient.newBuilder()
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
            // 2026-11-07 审计修复：格式白名单校验——旧实现只查非空，垃圾值会覆盖内置
            // 可用默认值且刷新 7 天 TTL（公开仓库被篡改时全体客户端静默失效最长 7 天）
            val qidShape = Regex("^[A-Za-z0-9_-]{10,}$")
            if (!qidShape.matches(userByScreenName) || !qidShape.matches(userTweets)) {
                Log.w(TAG, "manifest 校验失败（queryId 形状异常），放弃写入：$userByScreenName / $userTweets")
                return false
            }
            val bearer = json.optString("bearer").ifBlank { DEFAULT_BEARER }
            if (bearer != DEFAULT_BEARER && !Regex("^[A-Za-z0-9%_-]{30,}$").matches(bearer)) {
                Log.w(TAG, "manifest 校验失败（bearer 形状异常），放弃写入")
                return false
            }
            prefs().edit()
                .putString("bearer", bearer)
                .putString("qid_userByScreenName", userByScreenName)
                .putString("qid_userTweets", userTweets)
                .putLong("manifestAt", System.currentTimeMillis())
                .apply()
            // 2026-11-21 云控扩展：FX 主机列表 + Bluesky 端点（可选字段——清单缺省或
            // 校验不过时保留原值，绝不清空回默认，防误伤已切换的实例）
            validFxHosts(json.optJSONArray("fxHosts"))?.let { hosts ->
                prefs().edit().putString("fxHosts", hosts.joinToString("\n")).apply()
            }
            validBskyEndpoint(json.optString("bskyEndpoint"))?.let { endpoint ->
                prefs().edit().putString("bskyEndpoint", endpoint).apply()
            }
            // 2026-11-21 云控扩展：WebDAV 预设覆盖 + 公告（可选字段，缺省或校验不过保留原值）
            validWebdavPresets(json.optJSONArray("webdavPresets"))?.let { overrides ->
                val arr = org.json.JSONArray()
                overrides.forEach { o ->
                    val e = JSONObject().put("id", o.id)
                    o.name?.let { e.put("name", it) }
                    o.serverUrlTemplate?.let { e.put("serverUrlTemplate", it) }
                    o.description?.let { e.put("description", it) }
                    arr.put(e)
                }
                prefs().edit().putString("webdavPresets", arr.toString()).apply()
            }
            validAnnouncements(json.optJSONArray("announcements"))?.let { list ->
                prefs().edit().putString("announcements", announcementsToJson(list)).apply()
            }
            Log.i(TAG, "manifest 已热更新：$userByScreenName / $userTweets")
            true
        }
    }.getOrElse {
        Log.w(TAG, "manifest 拉取失败：${it.message}")
        false
    }

    /**
     * 代理开启时的机会性静默升级：manifest 超过 TTL 才拉，失败静默（不弹窗）。
     * 由订阅轮询每轮开头调用一次——用户无感完成 queryId 保鲜；
     * FXTwitter 全主机失败时以下降 TTL（1h）触发，加速云端切换实例的生效。
     *
     * 2026-11-21 公告时效修正：7 天 [MANIFEST_TTL_MS] 是 queryId 保鲜的节奏，公告等不起
     * （实测：设备 12:21 装机拉过一次清单，14:26 推公告，7 天 TTL 锁死到 11-28 才能收到）。
     * 与调用方 [ttlMs] 取小：announcements 缓存为空（从未收到过公告）时 1h——新公告
     * 最迟下一轮订阅轮询触达；已有公告缓存回落 24h 天级保鲜。
     */
    fun maybeQuietUpgrade(proxyUrl: String?, ttlMs: Long = MANIFEST_TTL_MS) {
        if (proxyUrl == null) return
        val prefs = prefs()
        val last = prefs.getLong("manifestAt", 0L)
        val announceTtl = if (prefs.getString("announcements", null).isNullOrBlank()) {
            60 * 60_000L // 未收到过公告：1h 加速，新公告最迟下一轮轮询触达
        } else {
            24L * 3600 * 1000 // 已有公告缓存：天级保鲜
        }
        if (System.currentTimeMillis() - last < minOf(ttlMs, announceTtl)) return
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

    // ==================== 云控端点存取（2026-11-21） ====================

    /**
     * FXTwitter 兼容 API 主机列表（云控）：下载引擎一 / 元数据 / 画质升级按序回退。
     * 清单 `fxHosts` 校验通过后写入（`\n` 连接），未写入或为空回退内置默认。
     */
    fun fxHosts(): List<String> =
        prefs().getString("fxHosts", null)?.split('\n')
            ?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_FX_HOSTS

    /** Bluesky 公共 AppView 根地址（云控）：清单 `bskyEndpoint` 校验通过后写入。 */
    fun bskyEndpoint(): String =
        prefs().getString("bskyEndpoint", null)?.ifBlank { null } ?: DEFAULT_BSKY_ENDPOINT

    /**
     * FXTwitter 探针主机（代理测试用，无需 init 装配的轻量读取）：
     * 优先云控主机，装配未完成时回退内置默认。
     */
    fun fxProbeHost(context: Context?): String {
        val stored = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString("fxHosts", null)
        return stored?.split('\n')?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_FX_HOSTS.first()
    }

    /**
     * fxHosts 白名单校验（纯函数，可单测）：https 主机形态（可选端口），1-5 条。
     * 条目缺失/非法整体返回 null（保留原值，不清空回默认）。
     */
    internal fun validFxHosts(raw: org.json.JSONArray?): List<String>? {
        raw ?: return null
        if (raw.length() == 0) return null
        val shape = Regex("^https://[A-Za-z0-9.-]+(:\\d{1,5})?$")
        val hosts = (0 until raw.length()).mapNotNull { raw.optString(it).ifBlank { null } }
        if (hosts.isEmpty() || hosts.size > 5) return null
        return hosts.takeIf { list -> list.all { shape.matches(it) } }
    }

    /** bskyEndpoint 白名单校验（纯函数，可单测）：https 主机形态（可选端口），非法返回 null。 */
    internal fun validBskyEndpoint(raw: String?): String? {
        val s = raw?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        val shape = Regex("^https://[A-Za-z0-9.-]+(:\\d{1,5})?$")
        return s.takeIf { shape.matches(it) }
    }

    // ==================== 云控：WebDAV 预设覆盖 + 公告（2026-11-21） ====================

    /**
     * WebDAV 预设云控覆盖条目：按 [com.ed.edqiu.ui.screens.WebDavProvider.id] 对齐，
     * 提供的字段覆盖内置预设（name/serverUrlTemplate/description，均可选）。
     * 未知 id 由合并层忽略——清单不能凭空创造新服务商（无图标/无历史文案）。
     */
    data class CloudWebDavOverride(
        val id: String,
        val name: String?,
        val serverUrlTemplate: String?,
        val description: String?,
    )

    /** 应用内公告条目：收件箱顶部一次性胶囊展示。 */
    data class Announcement(
        val id: String,
        val severity: String,
        val text: String,
        val url: String?,
        val once: Boolean,
        val minApp: Int?,
        val maxApp: Int?,
    )

    /**
     * webdavPresets 白名单校验（纯函数，可单测）：id 形态 `^[a-z0-9_]{1,32}$`；
     * serverUrlTemplate 须为 scheme:// 形态且无空白/引号（允许 {账号ID}/<内网IP> 类占位符
     * 与中文——CloudDrive2 内网 IP 模板即含中文）；description ≤600 字。整批 ≤20 条。
     * 任何一条非法 → 整体 null（保留原值，防部分写入造成状态分裂）。
     */
    internal fun validWebdavPresets(raw: org.json.JSONArray?): List<CloudWebDavOverride>? {
        raw ?: return null
        if (raw.length() == 0) return null
        if (raw.length() > 20) return null
        val idShape = Regex("^[a-z0-9_]{1,32}$")
        val tplShape = Regex("^https?://[^\\s\"']{1,180}$")
        val out = mutableListOf<CloudWebDavOverride>()
        for (i in 0 until raw.length()) {
            val o = raw.optJSONObject(i) ?: return null
            val id = o.optString("id").trim()
            if (!idShape.matches(id)) return null
            val name = if (o.has("name")) {
                o.optString("name").trim().takeIf { it.isNotEmpty() && it.length <= 20 } ?: return null
            } else null
            val tpl = if (o.has("serverUrlTemplate")) {
                o.optString("serverUrlTemplate").trim().takeIf { tplShape.matches(it) } ?: return null
            } else null
            val desc = if (o.has("description")) {
                o.optString("description").takeIf { it.isNotBlank() && it.length <= 600 } ?: return null
            } else null
            out += CloudWebDavOverride(id, name, tpl, desc)
        }
        return out
    }

    /**
     * announcements 白名单校验（纯函数，可单测）：id 形态 `^[A-Za-z0-9_.-]{1,64}$`；
     * text 1-300 字；url 可选须 https://；severity ∈ {info, warn}（缺省 info）；
     * minApp/maxApp 可选非负 int；once 可选 bool（缺省 true）。整批 ≤10 条。
     * 任何一条非法 → 整体 null（保留原值）。
     */
    internal fun validAnnouncements(raw: org.json.JSONArray?): List<Announcement>? {
        raw ?: return null
        if (raw.length() == 0) return null
        if (raw.length() > 10) return null
        val idShape = Regex("^[A-Za-z0-9_.-]{1,64}$")
        val out = mutableListOf<Announcement>()
        for (i in 0 until raw.length()) {
            val o = raw.optJSONObject(i) ?: return null
            val id = o.optString("id").trim()
            if (!idShape.matches(id)) return null
            val text = o.optString("text").trim()
            if (text.isEmpty() || text.length > 300) return null
            val url = o.optString("url").trim().takeIf { it.isNotBlank() }
            if (url != null && !url.startsWith("https://")) return null
            val severity = o.optString("severity", "info").also {
                if (it != "info" && it != "warn") return null
            }
            val minApp = if (o.has("minApp")) o.optInt("minApp", -1).takeIf { it >= 0 } ?: return null else null
            val maxApp = if (o.has("maxApp")) o.optInt("maxApp", -1).takeIf { it >= 0 } ?: return null else null
            out += Announcement(id, severity, text, url, o.optBoolean("once", true), minApp, maxApp)
        }
        return out
    }

    /** 公告落盘为规范化 JSON（与清单原文字解耦，读取侧只认规范化形态）。 */
    private fun announcementsToJson(list: List<Announcement>): String {
        val arr = org.json.JSONArray()
        list.forEach { a ->
            val o = JSONObject().put("id", a.id).put("severity", a.severity)
                .put("text", a.text).put("once", a.once)
            a.url?.let { o.put("url", it) }
            a.minApp?.let { o.put("minApp", it) }
            a.maxApp?.let { o.put("maxApp", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    private fun parseAnnouncementsJson(raw: String?): List<Announcement> = runCatching {
        val arr = org.json.JSONArray(raw ?: return emptyList())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Announcement(
                id = id,
                severity = o.optString("severity", "info"),
                text = o.optString("text"),
                url = o.optString("url").takeIf { it.startsWith("https://") },
                once = o.optBoolean("once", true),
                minApp = o.optInt("minApp", -1).takeIf { it >= 0 },
                maxApp = o.optInt("maxApp", -1).takeIf { it >= 0 },
            )
        }
    }.getOrDefault(emptyList())

    /**
     * WebDAV 预设云控覆盖（安全读取，无需 init 装配）：设置页渲染前调用，
     * 与内置预设按 id 合并（见 WebDavProviderSelector.webDavProvidersWithCloud）。
     */
    fun webdavPresetsSafe(context: Context?): List<CloudWebDavOverride> = runCatching {
        val raw = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString("webdavPresets", null) ?: return emptyList()
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CloudWebDavOverride(
                id = id,
                name = o.optString("name").takeIf { it.isNotBlank() },
                serverUrlTemplate = o.optString("serverUrlTemplate").takeIf { it.isNotBlank() },
                description = o.optString("description").takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    /** 公告列表（安全读取，无需 init 装配）：规范化 JSON 解析，容错空列表。 */
    fun announcementsSafe(context: Context?): List<Announcement> = runCatching {
        val raw = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString("announcements", null) ?: return emptyList()
        parseAnnouncementsJson(raw)
    }.getOrDefault(emptyList())

    /**
     * 当前适用的公告（安全读取）：按 versionCode 区间过滤 + once 已读过滤，
     * warn 优先于 info。收件箱顶部胶囊展示。
     */
    fun currentAnnouncement(context: Context): Announcement? {
        val seen = dismissedAnnouncementIds(context)
        val appCode = runCatching {
            context.applicationContext.packageManager
                .getPackageInfo(context.applicationContext.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(0)
        return announcementsSafe(context)
            .filter { a -> a.minApp?.let { appCode >= it } ?: true }
            .filter { a -> a.maxApp?.let { appCode <= it } ?: true }
            .filter { a -> !(a.once && a.id in seen) }
            .maxByOrNull { if (it.severity == "warn") 1 else 0 }
    }

    /** 公告「知道了」：once 公告记入已读集合。 */
    fun dismissAnnouncement(context: Context, id: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("announcementsSeen", emptySet())?.toMutableSet() ?: mutableSetOf()
        seen += id
        prefs.edit().putStringSet("announcementsSeen", seen).apply()
    }

    /**
     * 公告落盘监听（2026-11-21 触达修正）：manifest announcements 被静默拉取覆写时
     * 通知 UI 立即重读——收件箱横幅当次会话即时可见，不等下次启动
     * （真机实测：14:46 拉取落盘成功，横幅到 14:47 重启才出现）。
     * 返回反注册闭包；闭包持有 prefs 与 listener 强引用（SharedPreferences 对监听器
     * 只持弱引用，经典回收坑）。
     */
    fun onAnnouncementsChanged(context: Context?, onChange: () -> Unit): () -> Unit {
        val prefs = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return {}
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "announcements") onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun dismissedAnnouncementIds(context: Context): Set<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("announcementsSeen", emptySet()) ?: emptySet()

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
