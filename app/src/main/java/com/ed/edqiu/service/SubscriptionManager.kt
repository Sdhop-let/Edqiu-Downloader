package com.ed.edqiu.service

import android.content.Context
import com.ed.edqiu.EdqiuApplication
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.database.SubscriptionEntity
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.repository.SavedLinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 作者订阅中枢（2026-09-15 v2 批次4：P2-1；2026-10 多平台「真正作品订阅」）。
 *
 * - 存储：AppDatabase.subscriptions（twitter_downloader.db），v12 起 +platform 列；
 * - 平台检测器分发：
 *   - **twitter**：yt-dlp 用户页（--flat-playlist，最新 8 条）检测新推文 ID；
 *   - **bluesky**：公共 AppView API `public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed`
 *     （无需登录，JSON）检测最新 post rkey——Bluesky 域名需走用户代理；
 *   - **instagram**：yt-dlp 用户页（实验性；IG 风控严，失败静默下轮再试）；
 * - 新作品处理：收件箱 capture（PENDING 落库）→ requestDownload 走三层引擎链（自动下载）；
 * - 限速：每作者每轮最多 [NEW_PER_AUTHOR] 条，防风控；轮询失败静默跳过（下一轮再试）。
 */
object SubscriptionManager {

    private const val PREFS = "subscription_poller"
    private const val KEY_LAST_POLL = "last_poll"
    private const val POLL_INTERVAL_MS = 25 * 60_000L
    /** 单轮订阅检测超时（看门狗，2026-10）：yt-dlp execute 无内建超时。 */
    private const val POLL_TIMEOUT_MS: Long = 15 * 60_000L
    private const val INITIAL_DELAY_MS = 90_000L
    private const val AUTHORS_PER_POLL = 3
    private const val NEW_PER_AUTHOR = 3
    private const val TIMELINE_SCAN = 8

    @Volatile
    private var started = false

    private fun normalize(screenName: String): String =
        screenName.trim().removePrefix("@").trim()

    fun observe(context: Context): Flow<List<SubscriptionEntity>> =
        AppDatabase.getInstance(context.applicationContext).subscriptionDao().observeAll()

    suspend fun isSubscribed(
        context: Context,
        platform: String,
        screenName: String,
    ): Boolean =
        AppDatabase.getInstance(context).subscriptionDao()
            .get(platform, normalize(screenName))?.enabled == true

    /** 切换订阅状态。@return 切换后是否处于订阅中。 */
    suspend fun toggle(context: Context, platform: String, screenName: String): Boolean {
        val dao = AppDatabase.getInstance(context).subscriptionDao()
        val name = normalize(screenName)
        val existing = dao.get(platform, name)
        return if (existing == null) {
            dao.insert(
                SubscriptionEntity(
                    screenName = name,
                    platform = platform,
                    enabled = true,
                    createdAt = System.currentTimeMillis()
                )
            )
            true
        } else {
            val next = !existing.enabled
            dao.update(existing.copy(enabled = next))
            next
        }
    }

    suspend fun setEnabled(context: Context, platform: String, screenName: String, enabled: Boolean) {
        val dao = AppDatabase.getInstance(context).subscriptionDao()
        val existing = dao.get(platform, normalize(screenName)) ?: return
        dao.update(existing.copy(enabled = enabled))
    }

    suspend fun remove(context: Context, platform: String, screenName: String) {
        AppDatabase.getInstance(context).subscriptionDao()
            .delete(platform, normalize(screenName))
    }

    /** 应用启动时挂载轮询循环（幂等；AppContainer init 调用）。 */
    private val pollInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            delay(INITIAL_DELAY_MS)
            while (true) {
                // 2026-10 整改：看门狗——yt-dlp 无超时可能永久挂起，单轮 15 分钟封顶；
                // 防重入标记避免上一轮挂起时下一轮叠入（线程越积越多）
                if (pollInFlight.compareAndSet(false, true)) {
                    try {
                        val outcome = withTimeoutOrNull(POLL_TIMEOUT_MS) {
                            runCatching { poll(context) }.getOrDefault(0)
                        }
                        if (outcome == null) {
                            android.util.Log.w("SubscriptionManager", "订阅检测超时（>${POLL_TIMEOUT_MS / 60_000}min），跳过本轮")
                        }
                    } finally {
                        pollInFlight.set(false)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 手动触发一轮检测（订阅页用）。@return 新发现并已入队的作品数。 */
    suspend fun pollNow(context: Context): Int = withContext(Dispatchers.IO) {
        poll(context, ignoreThrottle = true)
    }

    /** 检测单个订阅（订阅页逐作者「立即检测」）。@return 新发现并已入队的作品数。 */
    suspend fun pollOne(context: Context, sub: SubscriptionEntity): Int =
        withContext(Dispatchers.IO) {
            detectAndCapture(context, sub)
        }

    private suspend fun poll(context: Context, ignoreThrottle: Boolean = false): Int {
        val appContext = context.applicationContext
        val container = (appContext as? EdqiuApplication)?.container ?: return 0
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!ignoreThrottle && now - prefs.getLong(KEY_LAST_POLL, 0L) < 10 * 60_000L) return 0
        prefs.edit().putLong(KEY_LAST_POLL, now).apply()

        val dao = AppDatabase.getInstance(appContext).subscriptionDao()
        val subs = dao.getEnabled(AUTHORS_PER_POLL)
        if (subs.isEmpty()) return 0

        var newCount = 0
        for (sub in subs) {
            newCount += detectAndCapture(appContext, sub)
        }
        return newCount
    }

    /** 单订阅检测 + 新作品入库；返回入库条数（检测失败静默 0，仅更新检查时间）。 */
    private suspend fun detectAndCapture(context: Context, sub: SubscriptionEntity): Int {
        val appContext = context.applicationContext
        val container = (appContext as? EdqiuApplication)?.container ?: return 0
        val dao = AppDatabase.getInstance(appContext).subscriptionDao()
        val proxyUrl = ProxyPreferences(appContext).getProxySettings().toProxyUrl()
        val cookiePrefs = CookiePreferences(appContext)
        val cookieFile = if (cookiePrefs.hasCookies()) {
            YoutubeDLService.writeCookieFile(appContext, cookiePrefs.getAuthToken(), cookiePrefs.getCt0())
        } else null

        val ids: List<String> = when (sub.platform) {
            SubscriptionEntity.PLATFORM_BLUESKY ->
                fetchBlueskyPostIds(appContext, sub.screenName, proxyUrl, TIMELINE_SCAN) ?: emptyList()
            SubscriptionEntity.PLATFORM_INSTAGRAM ->
                fetchInstagramPostIds(sub.screenName, TIMELINE_SCAN, proxyUrl, cookieFile) ?: emptyList()
            else ->
                YoutubeDLService.getUserTimelineTweetIds(
                    screenName = sub.screenName,
                    limit = TIMELINE_SCAN,
                    proxyUrl = proxyUrl,
                    cookieFilePath = cookieFile
                ).getOrNull() ?: emptyList()
        }

        if (ids.isEmpty()) {
            // 拉取失败：只更新检查时间，下一轮再试（不弹错、不断循环）
            dao.update(sub.copy(lastCheckedAt = System.currentTimeMillis()))
            return 0
        }

        var taken = 0
        for (id in ids) {
            if (taken >= NEW_PER_AUTHOR) break
            val cleanId = id.takeWhile { it.isDigit() || it == '-' || it.isLetterOrDigit() }
            if (cleanId.isBlank()) continue
            val statusUrl = when (sub.platform) {
                SubscriptionEntity.PLATFORM_BLUESKY ->
                    "https://bsky.app/profile/${sub.screenName}/post/$id"
                SubscriptionEntity.PLATFORM_INSTAGRAM ->
                    "https://www.instagram.com/p/$id/"
                else ->
                    "https://x.com/i/status/$cleanId"
            }
            val dedupeId = if (sub.platform == SubscriptionEntity.PLATFORM_TWITTER) cleanId.takeWhile { it.isDigit() } else cleanId
            if (dedupeId.isBlank()) continue
            if (container.savedLinkRepository.getByTweetId(dedupeId) != null) continue

            val captureResult = container.savedLinkRepository.capture(statusUrl)
            if (captureResult is SavedLinkRepository.CaptureResult.Added) {
                container.savedLinkRepository.requestDownload(dedupeId)
                taken++
            }
        }
        dao.update(
            sub.copy(
                lastCheckedAt = System.currentTimeMillis(),
                lastVideoAt = if (taken > 0) System.currentTimeMillis() else sub.lastVideoAt
            )
        )
        return taken
    }

    // ================= Bluesky：公共 AppView API（无需登录） =================

    /**
     * 拉取 Bluesky 作者最新 post rkey 列表。
     * `public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=<handle>&limit=N`
     * 返回 JSON：feed[].post.uri = `at://did:plc:xxx/app.bsky.feed.post/<rkey>`。
     * 国内网络需代理（用户设置），失败返回 null（静默下轮再试）。
     */
    private suspend fun fetchBlueskyPostIds(
        context: Context,
        handle: String,
        proxyUrl: String?,
        limit: Int,
    ): List<String>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed" +
                "?actor=${java.net.URLEncoder.encode(handle, "UTF-8")}&limit=$limit&filter=posts_with_media"
            val clientBuilder = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
            proxyUrl?.let { p ->
                val proxy = toJavaProxy(p)
                if (proxy != null) clientBuilder.proxy(proxy)
            }
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Edqiu-Subscription/1.0")
                .build()
            clientBuilder.build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val root = org.json.JSONObject(body)
                val feed = root.optJSONArray("feed") ?: return@runCatching null
                val ids = mutableListOf<String>()
                for (i in 0 until feed.length()) {
                    val uri = feed.optJSONObject(i)?.optJSONObject("post")?.optString("uri") ?: continue
                    val rkey = uri.substringAfterLast('/').takeIf { it.isNotBlank() } ?: continue
                    ids += rkey
                }
                ids
            }
        }.getOrNull()
    }

    /** Instagram：yt-dlp 用户页 flat-playlist（实验性，风控严）。返回 shortcode 列表。 */
    private suspend fun fetchInstagramPostIds(
        handle: String,
        limit: Int,
        proxyUrl: String?,
        cookieFilePath: String?,
    ): List<String>? = withContext(Dispatchers.IO) {
        YoutubeDLService.getFlatPlaylistIds(
            pageUrl = "https://www.instagram.com/$handle/",
            limit = limit,
            proxyUrl = proxyUrl,
            cookieFilePath = cookieFilePath,
            digitOnly = false,
        ).getOrNull()
    }

    private fun toJavaProxy(proxyUrl: String): java.net.Proxy? = runCatching {
        val uri = java.net.URI.create(proxyUrl)
        val host = uri.host ?: return@runCatching null
        val port = if (uri.port > 0) uri.port else when (uri.scheme) {
            "http", "https" -> 80
            "socks" -> 1080
            else -> return@runCatching null
        }
        val type = if (uri.scheme.startsWith("socks")) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
        java.net.Proxy(type, java.net.InetSocketAddress(host, port))
    }.getOrNull()
}
