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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 作者订阅中枢（2026-09-15 v2 批次4：P2-1 作者订阅自动下载）。
 *
 * - 存储：AppDatabase.subscriptions（twitter_downloader.db）；
 * - 轮询：每 [POLL_INTERVAL_MS] 一轮，每轮取 lastCheckedAt 最旧的 ≤[AUTHORS_PER_POLL] 个
 *   enabled 订阅 → yt-dlp 用户页（--flat-playlist，最新 8 条）检测新推文 ID；
 * - 新作品处理：收件箱 capture（PENDING 落库）→ requestDownload 走三层引擎链；
 *   SavedLinkRepository 的 onDownloadRequested 钩子会把任务同步登记进 WorkManager
 *   持久队列（P0-4），进程被杀也不丢；
 * - 限速：每作者每轮最多 [NEW_PER_AUTHOR] 条，防风控；轮询失败静默跳过（下一轮再试）。
 */
object SubscriptionManager {

    private const val PREFS = "subscription_poller"
    private const val KEY_LAST_POLL = "last_poll"
    private const val POLL_INTERVAL_MS = 25 * 60_000L
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

    suspend fun isSubscribed(context: Context, screenName: String): Boolean =
        AppDatabase.getInstance(context).subscriptionDao()
            .getByScreenName(normalize(screenName))?.enabled == true

    /** 切换订阅状态。@return 切换后是否处于订阅中。 */
    suspend fun toggle(context: Context, screenName: String): Boolean {
        val dao = AppDatabase.getInstance(context).subscriptionDao()
        val name = normalize(screenName)
        val existing = dao.getByScreenName(name)
        return if (existing == null) {
            dao.insert(SubscriptionEntity(screenName = name, enabled = true, createdAt = System.currentTimeMillis()))
            true
        } else {
            val next = !existing.enabled
            dao.update(existing.copy(enabled = next))
            next
        }
    }

    suspend fun setEnabled(context: Context, screenName: String, enabled: Boolean) {
        val dao = AppDatabase.getInstance(context).subscriptionDao()
        val existing = dao.getByScreenName(normalize(screenName)) ?: return
        dao.update(existing.copy(enabled = enabled))
    }

    suspend fun remove(context: Context, screenName: String) {
        AppDatabase.getInstance(context).subscriptionDao().deleteByScreenName(normalize(screenName))
    }

    /** 应用启动时挂载轮询循环（幂等；AppContainer init 调用）。 */
    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            delay(INITIAL_DELAY_MS)
            while (true) {
                runCatching { poll(context) }
                    .onFailure { android.util.Log.w("SubscriptionManager", "poll failed", it) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 手动触发一轮检测（设置页用）。@return 新发现并已入队的作品数。 */
    suspend fun pollNow(context: Context): Int = withContext(Dispatchers.IO) {
        poll(context)
    }

    private suspend fun poll(context: Context): Int {
        val appContext = context.applicationContext
        val container = (appContext as? EdqiuApplication)?.container ?: return 0
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_POLL, 0L) < 10 * 60_000L) return 0
        prefs.edit().putLong(KEY_LAST_POLL, now).apply()

        val dao = AppDatabase.getInstance(appContext).subscriptionDao()
        val subs = dao.getEnabled(AUTHORS_PER_POLL)
        if (subs.isEmpty()) return 0

        val proxyUrl = ProxyPreferences(appContext).getProxySettings().toProxyUrl()
        val cookiePrefs = CookiePreferences(appContext)
        val cookieFile = if (cookiePrefs.hasCookies()) {
            YoutubeDLService.writeCookieFile(appContext, cookiePrefs.getAuthToken(), cookiePrefs.getCt0())
        } else null

        var newCount = 0
        for (sub in subs) {
            val ids = YoutubeDLService.getUserTimelineTweetIds(
                screenName = sub.screenName,
                limit = TIMELINE_SCAN,
                proxyUrl = proxyUrl,
                cookieFilePath = cookieFile
            ).getOrNull()

            if (ids == null) {
                // 拉取失败：只更新检查时间，下一轮再试（不弹错、不断循环）
                dao.update(sub.copy(lastCheckedAt = System.currentTimeMillis()))
                continue
            }

            var taken = 0
            for (id in ids) {
                if (taken >= NEW_PER_AUTHOR) break
                val tweetId = id.takeWhile { it.isDigit() }
                if (tweetId.isBlank()) continue
                if (container.savedLinkRepository.getByTweetId(tweetId) != null) continue

                val captureResult = container.savedLinkRepository.capture("https://x.com/i/status/$tweetId")
                if (captureResult is SavedLinkRepository.CaptureResult.Added) {
                    container.savedLinkRepository.requestDownload(tweetId)
                    taken++
                    newCount++
                }
            }
            dao.update(
                sub.copy(
                    lastCheckedAt = System.currentTimeMillis(),
                    lastVideoAt = if (taken > 0) System.currentTimeMillis() else sub.lastVideoAt
                )
            )
        }
        return newCount
    }
}
