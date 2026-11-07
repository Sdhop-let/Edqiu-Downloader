package com.ed.edqiu.data.xapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 作者订阅 twitter 检测编排（2026-10-17 v1.9.2 修复）。
 *
 * 策略（替代已废弃的 yt-dlp 用户页通道）：
 * 1. **guest 通道优先**（免登录零配置）：[XApiClient.activateGuest] → UserByScreenName →
 *    UserTweets。实测（2026-10-17）多数账号返回新鲜时间线；
 * 2. **新鲜度门闩**：guest 时间线最新推文超过 [FRESHNESS_DAYS] 天 → 视为被 X 降级
 *    （实测 elonmusk 停更一年之久）→ 有 cookie 则升级 cookie 通道；无 cookie 则
 *    仍采用 guest 数据但标记 [Outcome.Success.degraded]（不阻塞、不谎报）；
 * 3. **cookie 通道兜底**：auth_token/ct0，拿真实时间线；401/403 → 明确提示登录失效。
 *
 * 所有失败都带**面向用户的可操作文案**——不再出现「通道挂了却显示暂无新作品」的静默事故。
 */
object XTimelineDetector {

    /** 单轮扫描的推文条数（与旧 yt-dlp TIMELINE_SCAN 对齐）。 */
    private const val TIMELINE_SCAN = 10

    /** guest 时间线新鲜度阈值：最新推文早于 N 天视为降级数据。 */
    private const val FRESHNESS_DAYS = 30L

    /**
     * 停更确认记忆（2026-11-07 审计修复）：真停更 >30 天的作者每轮固定 guest+cookie
     * 双通道重复请求（6 请求/轮）。记忆「cookie 通道确认 guest 最新推文一致」的结论，
     * 6 小时窗口内直接采用 guest 数据不再双通道；cookie 结果不同即失效。
     * handle → (确认时的最新推文时间戳, 确认时刻)。
     */
    private val staleConfirmed = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    private const val STALE_CONFIRM_WINDOW_MS = 6 * 3600_000L

    // 2026-11-07 审计修复：SimpleDateFormat 非线程安全——共享单例在 pollOne/pollNow/
    // 后台轮询并发 parse 时会产生**错误的 publishedAt**（不抛异常，直接破坏时间边界
    // 过滤与去重），改为每次调用新建
    private fun newCreatedAtFormat() = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)

    data class DetectedTweet(val id: String, val publishedAt: Long?, val authorHandle: String? = null)

    sealed interface Outcome {
        /**
         * @param tweets 时间线推文（ID 列，新→旧，调用方按需截取）
         * @param degraded true = 数据疑似被 X 降级但仍采用（UI 软提示）
         * @param viaCookie true = 走了 cookie 兜底通道
         */
        data class Success(
            val tweets: List<DetectedTweet>,
            val degraded: Boolean = false,
            val viaCookie: Boolean = false,
        ) : Outcome

        data class Failure(val error: XApiClient.XApiError, val message: String) : Outcome
    }

    /**
     * 检测账号最新时间线。
     * @param screenName 作者 handle（无 @）
     * @param proxyUrl 代理 URL（null = 未配置代理）
     * @param authToken / ct0 X 登录 cookie；null = 未配置（跳过 cookie 兜底）
     */
    suspend fun detect(
        screenName: String,
        proxyUrl: String?,
        authToken: String? = null,
        ct0: String? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        // proxyUrl 直接下传 XApiClient（内部转换）
        val hasCookie = !authToken.isNullOrBlank() && !ct0.isNullOrBlank()

        // ---------- guest 主通道 ----------
        val guest = runGuest(screenName, proxyUrl)
        if (guest is Outcome.Failure) {
            // guest 彻底失败：有 cookie 才值得兜底，否则把guest失败原因交给用户
            return@withContext if (hasCookie) runCookie(screenName, proxyUrl, authToken, ct0)
            else guest
        }
        val success = guest as Outcome.Success

        val newest = success.tweets.mapNotNull { it.publishedAt }.maxOrNull()
        val stale = newest != null &&
            System.currentTimeMillis() - newest > FRESHNESS_DAYS * 24 * 3600 * 1000
        if (!stale) {
            staleConfirmed.remove(screenName)
            return@withContext success
        }

        // ---------- guest 数据陈旧：cookie 兜底 ----------
        // 2026-11-07 审计修复：6 小时内 cookie 已确认过「guest 最新推文一致」的停更
        // 作者不再双通道重复请求（Cookie 用户每作者每轮省 2 次请求，降低 429 概率）
        staleConfirmed[screenName]?.let { (confirmedNewest, at) ->
            if (newest == confirmedNewest && System.currentTimeMillis() - at < STALE_CONFIRM_WINDOW_MS) {
                return@withContext success
            }
        }
        if (!hasCookie) {
            return@withContext success.copy(degraded = true)
        }
        return@withContext when (val cookieOutcome = runCookie(screenName, proxyUrl, authToken, ct0)) {
            is Outcome.Success -> {
                val cookieNewest = cookieOutcome.tweets.mapNotNull { it.publishedAt }.maxOrNull()
                if (cookieNewest != null && newest != null && cookieNewest == newest) {
                    staleConfirmed[screenName] = newest to System.currentTimeMillis()
                } else {
                    staleConfirmed.remove(screenName)
                }
                // 记忆表防无界增长（超限整体清空，代价只是下轮重新确认一次）
                if (staleConfirmed.size > 500) staleConfirmed.clear()
                cookieOutcome
            }
            is Outcome.Failure -> {
                // cookie 也拿不到（如登录已失效）：仍用 guest 数据但标注降级，不静默丢失
                success.copy(degraded = true)
            }
        }
    }

    /** guest 全流程；guest token 401 时自动重新激活重试一次。 */
    private fun runGuest(
        screenName: String,
        proxyUrl: String?,
    ): Outcome {
        val token = XApiClient.activateGuest(proxyUrl).getOrElse { e ->
            return guestFailure(e)
        }
        var channel = XApiClient.Channel.Guest(token)

        var restId = XApiClient.userByScreenName(screenName, channel, proxyUrl)
        if (isAuthExpired(restId)) {
            // guest token 短时效：重新激活一次再试
            val fresh = XApiClient.activateGuest(proxyUrl).getOrElse { e ->
                return guestFailure(e)
            }
            channel = XApiClient.Channel.Guest(fresh)
            restId = XApiClient.userByScreenName(screenName, channel, proxyUrl)
        }
        val id = restId.getOrElse { e ->
            return guestFailure(e)
        }

        var tweets = XApiClient.userTweets(id, TIMELINE_SCAN, channel, proxyUrl)
        if (isAuthExpired(tweets)) {
            val fresh = XApiClient.activateGuest(proxyUrl).getOrElse { e ->
                return guestFailure(e)
            }
            tweets = XApiClient.userTweets(id, TIMELINE_SCAN, XApiClient.Channel.Guest(fresh), proxyUrl)
        }
        return tweets.fold(
            onSuccess = { list ->
                Outcome.Success(
                    tweets = list.map { DetectedTweet(it.id, parseCreatedAt(it.createdAtRaw), it.authorHandle) },
                    degraded = false,
                    viaCookie = false,
                )
            },
            onFailure = { e -> guestFailure(e) },
        )
    }

    /** guest 通道失败文案（2026-11-07 审计修复：401 是 IP 风控常见场景，不再误报「Cookie 已失效」）。 */
    private fun guestFailure(e: Throwable): Outcome.Failure {
        val err = mapError(e)
        val message = if (err == XApiClient.XApiError.AUTH_EXPIRED) {
            "X 游客通道被限制（IP 风控常见）——配置 X Cookie 可改善识别率；若已配置请到「设置 → 通用」更新 Cookie"
        } else {
            describe(e)
        }
        return Outcome.Failure(err, message)
    }

    /** cookie 兜底全流程。 */
    private fun runCookie(
        screenName: String,
        proxyUrl: String?,
        authToken: String,
        ct0: String,
    ): Outcome {
        val channel = XApiClient.Channel.Cookie(authToken, ct0)
        val restId = XApiClient.userByScreenName(screenName, channel, proxyUrl).getOrElse { e ->
            // cookie 通道 401 → 「Cookie 已失效」文案是正确的（与 guest 区分）
            return Outcome.Failure(mapError(e), describe(e))
        }
        return XApiClient.userTweets(restId, TIMELINE_SCAN, channel, proxyUrl).fold(
            onSuccess = { list ->
                Outcome.Success(
                    tweets = list.map { DetectedTweet(it.id, parseCreatedAt(it.createdAtRaw), it.authorHandle) },
                    degraded = false,
                    viaCookie = true,
                )
            },
            onFailure = { e -> Outcome.Failure(mapError(e), describe(e)) },
        )
    }

    // ==================== 失败分类与用户文案 ====================

    private fun mapError(e: Throwable): XApiClient.XApiError =
        (e as? XApiClient.XApiException)?.error ?: XApiClient.XApiError.NETWORK

    private fun isAuthExpired(result: Result<*>): Boolean =
        result.exceptionOrNull()?.let { mapError(it) } == XApiClient.XApiError.AUTH_EXPIRED

    /** 面向用户的具体原因 + 可操作建议（用户要求：补抓不到必须说清为什么）。 */
    fun describe(e: Throwable): String = when (mapError(e)) {
        XApiClient.XApiError.NO_PROXY ->
            "未配置代理：X 接口需代理才能访问——请到「设置 → 网络与同步」开启代理"
        XApiClient.XApiError.PROXY_TIMEOUT ->
            "代理延迟过高或无响应（连接 X 接口超时）——请更换代理节点后重试"
        XApiClient.XApiError.PROXY_UNREACHABLE ->
            "代理不可达——请检查代理客户端是否开启、节点是否可用"
        XApiClient.XApiError.RATE_LIMITED ->
            "X 接口限流（请求过于频繁）——已跳过本轮，下轮自动重试"
        XApiClient.XApiError.AUTH_EXPIRED ->
            "X 登录 Cookie 已失效——请到「设置 → 通用」更新 Cookie 后重试"
        XApiClient.XApiError.STALE_QUERY_ID ->
            "X 接口已更新，自动轮换配置失败——请升级 App 至最新版本"
        XApiClient.XApiError.ACCOUNT_UNAVAILABLE ->
            "该账号当前不可访问（不存在/冻结/游客被限）——配置 X Cookie 可改善识别率"
        XApiClient.XApiError.MALFORMED_RESPONSE ->
            "X 返回了无法解析的响应（接口可能改版）——请稍后重试或升级 App"
        XApiClient.XApiError.NETWORK ->
            "网络错误（${e.message ?: "未知"}）——请稍后重试"
    }

    /** 解析 X created_at（"Thu Apr 28 00:56:58 +0000 2022"）；失败返 null（按未知处理）。 */
    private fun parseCreatedAt(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { newCreatedAtFormat().parse(raw)?.time }.getOrNull()
    }
}
