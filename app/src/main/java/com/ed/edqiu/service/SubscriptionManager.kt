package com.ed.edqiu.service

import android.content.Context
import com.ed.edqiu.EdqiuApplication
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.database.SubscriptionEntity
import com.ed.edqiu.data.database.SubscriptionMessageEntity
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.data.xapi.XApiClient
import com.ed.edqiu.data.xapi.XTimelineDetector
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
 * 作者订阅中枢（2026-09-15 v2 批次4：P2-1；2026-10 多平台「真正作品订阅」；
 * 2026-10-17 v1.9.2 修复：twitter 检测通道重建为 X GraphQL）。
 *
 * - 存储：AppDatabase.subscriptions（twitter_downloader.db），v13 起 +lastError 列；
 * - 平台检测器分发：
 *   - **twitter**：X GraphQL（[XTimelineDetector]）——guest token 主通道 + 登录 cookie 兜底，
 *     queryId 走远程 manifest 静默轮换；失败原因分类透出（不再静默吞成「暂无新作品」）；
 *   - **bluesky**：公共 AppView API `public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed`
 *     （无需登录，JSON）检测最新 post rkey——Bluesky 域名需走用户代理；
 *   - **instagram**：yt-dlp 用户页（实验性；IG 风控严，失败静默下轮再试）；
 * - 新作品处理（2026-10-17 改）：**只 capture 成订阅线作品（PENDING），不自动下载**——
 *   用户在「作品订阅」页看到新作品后自己点按下载（用户线 manual=true）。
 *   订阅起始日期 [SubscriptionEntity.startFromAt] 可自定义：只捞该日期后发布的推文；
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
    private const val NEW_PER_AUTHOR = 8
    private const val TIMELINE_SCAN = 8

    /** guest 数据陈旧（账号被 X 降级）且无 cookie 兜底时的软提示（不阻塞检出）。 */
    private const val SOFT_DEGRADED_NOTE =
        "该账号游客通道数据被 X 降级，可能漏检新作品——在「设置 → 通用」配置 X Cookie 可改善"

    /** 单订阅检测结果（2026-10-17：失败/降级原因显式透出，杜绝静默失败）。 */
    data class DetectOutcome(
        val newCount: Int,
        /** 硬失败：检测通道挂了（未走代理/代理超时/账号被限/Cookie 失效/接口轮换失败）。 */
        val failure: String? = null,
        /** 软提示：检出了但数据疑似降级（仍入库，仅提示）。 */
        val softNote: String? = null,
    )

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

    /**
     * 设置订阅起始检测日期（2026-10-17 用户自定义）：轮询只把该日期**之后**发布的推文
     * 视为新作品。null = 不限（从订阅/下次检测起全部新帖）。
     */
    suspend fun setStartFromAt(context: Context, platform: String, screenName: String, at: Long?) {
        val dao = AppDatabase.getInstance(context).subscriptionDao()
        val existing = dao.get(platform, normalize(screenName)) ?: return
        dao.update(existing.copy(startFromAt = at))
    }

    /** 应用启动时挂载轮询循环（幂等；AppContainer init 调用）。 */
    private val pollInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        // X GraphQL 客户端配置装载（幂等；queryId 默认值/manifest 缓存）
        XApiClient.init(context)
        scope.launch {
            delay(INITIAL_DELAY_MS)
            while (true) {
                // 2026-10 整改：看门狗——yt-dlp 无超时可能永久挂起，单轮 15 分钟封顶；
                // 防重入标记避免上一轮挂起时下一轮叠入（线程越积越多）
                if (pollInFlight.compareAndSet(false, true)) {
                    try {
                        // 注意：后台轮询的失败原因已逐条落库（subscriptions.lastError），
                        // 订阅页可见；这里不再弹窗（后台打扰），仅记录日志
                        val outcome = withTimeoutOrNull(POLL_TIMEOUT_MS) {
                            runCatching { poll(context) }.getOrNull()
                        }
                        if (outcome == null) {
                            android.util.Log.w("SubscriptionManager", "订阅检测超时（>${POLL_TIMEOUT_MS / 60_000}min），跳过本轮")
                        } else if (!outcome.failure.isNullOrBlank()) {
                            android.util.Log.w("SubscriptionManager", "订阅检测失败：${outcome.failure}")
                        }
                    } finally {
                        pollInFlight.set(false)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 手动触发一轮检测（订阅页用）。@return 新作品数 + 失败原因（null=正常）。 */
    suspend fun pollNow(context: Context): DetectOutcome = withContext(Dispatchers.IO) {
        poll(context, ignoreThrottle = true)
    }

    /** 检测单个订阅（订阅页逐作者「立即检测」）。@return 新作品数 + 失败/降级说明。 */
    suspend fun pollOne(context: Context, sub: SubscriptionEntity): DetectOutcome =
        withContext(Dispatchers.IO) {
            detectAndCapture(context, sub)
        }

    private suspend fun poll(context: Context, ignoreThrottle: Boolean = false): DetectOutcome {
        val appContext = context.applicationContext
        val container = (appContext as? EdqiuApplication)?.container
            ?: return DetectOutcome(0, failure = "应用容器未就绪")
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!ignoreThrottle && now - prefs.getLong(KEY_LAST_POLL, 0L) < 10 * 60_000L) {
            return DetectOutcome(0)
        }
        prefs.edit().putLong(KEY_LAST_POLL, now).apply()

        // 2026-10-17：代理开启时静默保鲜 queryId manifest（7 天一次，失败无感不弹窗）
        val proxyUrl = ProxyPreferences(appContext).getProxySettings().toProxyUrl()
        XApiClient.maybeQuietUpgrade(proxyUrl)

        val dao = AppDatabase.getInstance(appContext).subscriptionDao()
        val subs = dao.getEnabled(AUTHORS_PER_POLL)
        if (subs.isEmpty()) return DetectOutcome(0)

        var newCount = 0
        val failures = mutableListOf<String>()
        for (sub in subs) {
            val outcome = detectAndCapture(appContext, sub)
            newCount += outcome.newCount
            outcome.failure?.let { failures += "@${sub.screenName}：$it" }
        }
        // 批量轮询最多带 2 条原因回 UI（弹窗不刷屏）
        return DetectOutcome(newCount, failure = failures.take(2).joinToString("；").ifBlank { null })
    }

    /** 单订阅检测 + 新作品入库；失败/降级原因显式返回并落库（订阅页可见）。 */
    private suspend fun detectAndCapture(context: Context, sub: SubscriptionEntity): DetectOutcome {
        val appContext = context.applicationContext
        val container = (appContext as? EdqiuApplication)?.container
            ?: return DetectOutcome(0, failure = "应用容器未就绪")
        val dao = AppDatabase.getInstance(appContext).subscriptionDao()
        val proxyUrl = ProxyPreferences(appContext).getProxySettings().toProxyUrl()
        val cookiePrefs = CookiePreferences(appContext)
        val cookieReady = cookiePrefs.hasCookies()
        val cookieFile = if (cookieReady) {
            YoutubeDLService.writeCookieFile(appContext, cookiePrefs.getAuthToken(), cookiePrefs.getCt0())
        } else null

        val now = System.currentTimeMillis()
        var softNote: String? = null
        // (id, publishedAt)——twitter 走检测器带发布时间；bluesky/IG 无日期（null 直接放行）
        val candidates: List<Pair<String, Long?>> = when (sub.platform) {
            SubscriptionEntity.PLATFORM_BLUESKY ->
                (fetchBlueskyPostIds(appContext, sub.screenName, proxyUrl, TIMELINE_SCAN) ?: emptyList())
                    .map { it to null }
            SubscriptionEntity.PLATFORM_INSTAGRAM ->
                (fetchInstagramPostIds(sub.screenName, TIMELINE_SCAN, proxyUrl, cookieFile) ?: emptyList())
                    .map { it to null }
            else -> when (val outcome = XTimelineDetector.detect(
                screenName = sub.screenName,
                proxyUrl = proxyUrl,
                authToken = if (cookieReady) cookiePrefs.getAuthToken() else null,
                ct0 = if (cookieReady) cookiePrefs.getCt0() else null,
            )) {
                is XTimelineDetector.Outcome.Success -> {
                    softNote = if (outcome.degraded) SOFT_DEGRADED_NOTE else null
                    outcome.tweets.map { it.id to it.publishedAt }
                }
                is XTimelineDetector.Outcome.Failure -> {
                    // 2026-10-17：失败原因落库 + 透出——旧实现在这里静默返回 0，
                    // UI 显示「暂无新作品」，用户完全看不出是代理没开/账号被限/cookie 失效
                    logFailure(appContext, sub, outcome.message)
                    dao.update(
                        sub.copy(lastCheckedAt = now, lastError = outcome.message, lastErrorAt = now)
                    )
                    return DetectOutcome(0, failure = outcome.message)
                }
            }
        }

        // 用户自定义起始日期：只捞该日期之后发布的推文（无发布时间的保守放行）
        val startFrom = sub.startFromAt
        val ids = candidates.filter { (_, publishedAt) ->
            startFrom == null || publishedAt == null || publishedAt >= startFrom
        }

        if (ids.isEmpty()) {
            // 通道成功但无内容（/bluesky/IG 拉取失败维持旧语义：仅推进检查时间）
            logSuccessEvents(appContext, sub, taken = 0, softNote)
            dao.update(
                sub.copy(
                    lastCheckedAt = now,
                    lastError = softNote,
                    lastErrorAt = if (softNote != null) now else null,
                )
            )
            return DetectOutcome(0, softNote = softNote)
        }

        var taken = 0
        for ((id, _) in ids) {
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

            // 2026-10-17：只 capture 成订阅线作品（PENDING），不自动下载——用户在
            // 「作品订阅」页看到新作品后自己点按下载（用户线 manual=true，熔断语义与手动一致）
            val captureResult = container.savedLinkRepository.capture(
                text = statusUrl,
                origin = SavedLink.ORIGIN_SUBSCRIPTION,
            )
            if (captureResult is SavedLinkRepository.CaptureResult.Added) {
                taken++
            }
        }
        dao.update(
            sub.copy(
                lastCheckedAt = now,
                lastVideoAt = if (taken > 0) now else sub.lastVideoAt,
                lastError = softNote,
                lastErrorAt = if (softNote != null) now else null,
            )
        )
        logSuccessEvents(appContext, sub, taken = taken, softNote = softNote)
        return DetectOutcome(taken, softNote = softNote)
    }

    // ==================== 订阅消息（2026-10-17 事件收纳箱）====================

    /**
     * 失败事件写入订阅消息。与上轮 [SubscriptionEntity.lastError] 相同的失败不重复写——
     * 后台 25 分钟一轮，同一错误（如代理没开）重复写会把消息列表刷满，订阅行本身已常驻展示。
     */
    private suspend fun logFailure(context: Context, sub: SubscriptionEntity, message: String) {
        if (message == sub.lastError) return
        runCatching {
            val dao = AppDatabase.getInstance(context).subscriptionMessageDao()
            dao.insert(
                SubscriptionMessageEntity(
                    kind = SubscriptionMessageEntity.KIND_FAILURE,
                    text = "@${sub.screenName}：$message",
                    createdAt = System.currentTimeMillis(),
                )
            )
            dao.trim(SubscriptionMessageEntity.KEEP_LIMIT)
        }
    }

    /**
     * 成功路径事件：
     * - 发现新作品 → KIND_NEW_WORKS；
     * - 软提示出现/变化（guest 数据降级）→ KIND_NOTE；
     * - 「失败消失」：上轮有错（[SubscriptionEntity.lastError] 非空）、本轮恢复正常 → KIND_RECOVERED。
     */
    private suspend fun logSuccessEvents(
        context: Context,
        sub: SubscriptionEntity,
        taken: Int,
        softNote: String?,
    ) {
        val prev = sub.lastError
        val hasEvent = taken > 0 ||
            (softNote != null && softNote != prev) ||
            (softNote == null && !prev.isNullOrBlank())
        if (!hasEvent) return
        runCatching {
            val dao = AppDatabase.getInstance(context).subscriptionMessageDao()
            val now = System.currentTimeMillis()
            if (taken > 0) {
                dao.insert(
                    SubscriptionMessageEntity(
                        kind = SubscriptionMessageEntity.KIND_NEW_WORKS,
                        text = "@${sub.screenName}：发现 $taken 个新作品，已加入订阅作品，点按下载",
                        createdAt = now,
                    )
                )
            }
            when {
                softNote != null && softNote != prev -> dao.insert(
                    SubscriptionMessageEntity(
                        kind = SubscriptionMessageEntity.KIND_NOTE,
                        text = "@${sub.screenName}：$softNote",
                        createdAt = now,
                    )
                )
                softNote == null && !prev.isNullOrBlank() -> dao.insert(
                    SubscriptionMessageEntity(
                        kind = SubscriptionMessageEntity.KIND_RECOVERED,
                        text = "@${sub.screenName}：检测恢复正常",
                        createdAt = now,
                    )
                )
            }
            dao.trim(SubscriptionMessageEntity.KEEP_LIMIT)
        }
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
