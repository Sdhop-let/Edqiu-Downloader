package com.ed.edqiu.downloader

import java.util.concurrent.ConcurrentHashMap

/**
 * 下载引擎健康中枢（2026-09-15 P0-1 解析链三层冗余的可靠性底座）。
 *
 * 1. **引擎熔断器**：单引擎连续失败 ≥[FAILURE_THRESHOLD] 次 → 冷却 [COOLDOWN_MS]，
 *    冷却期内直接跳过该引擎，避免每次下载都先撞一遍必死的 FXTwitter（雪崩式等待）。
 * 2. **单推文亲和**：tweetId → 24h 内失败过的引擎不再重复尝试。
 *    典型收益：自动重试场景下，FXTwitter 挂了 → 重试直接走 yt-dlp/第三方，
 *    不再每次浪费 5-15s 超时；yt-dlp 失败（如登录态缺失）→ 重试直达第三方兜底。
 *
 * 设计说明：
 * - **必须是 object 单例**——DownloaderClient 每次调用都新建实例，实例级状态活不过一次请求；
 * - 进程被杀状态自然重置，可接受（熔断窗口本就只有 10 分钟）；
 * - TweetGone 是确定性知识而非引擎故障，由调用方决定**不**计入熔断。
 */
object DownloadEngineHealth {

    const val ENGINE_FXTWITTER = "fxtwitter"
    const val ENGINE_YTDLP = "ytdlp"
    const val ENGINE_THIRD_PARTY = "thirdparty"

    private const val FAILURE_THRESHOLD = 3
    private const val COOLDOWN_MS = 10 * 60_000L
    private const val TWEET_TTL_MS = 24 * 60 * 60_000L

    private data class EngineState(
        val consecutiveFailures: Int = 0,
        val openUntil: Long = 0L,
        val lastError: String? = null
    )

    private val engines = ConcurrentHashMap<String, EngineState>()

    /** tweetId → (engine → lastFailedAt)。 */
    private val tweetFailures = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    /** 引擎当前是否可用（冷却期内不可用）。 */
    fun isEngineAvailable(engine: String): Boolean {
        val state = engines[engine] ?: return true
        return System.currentTimeMillis() >= state.openUntil
    }

    /** 引擎熔断描述（供失败原因聚合展示），未熔断返回 null。 */
    fun engineBreakerNote(engine: String): String? {
        val state = engines[engine] ?: return null
        if (System.currentTimeMillis() < state.openUntil) {
            val minutes = (state.openUntil - System.currentTimeMillis() + 59_999) / 60_000
            return "（连续失败 ${state.consecutiveFailures} 次，冷却 ${minutes} 分钟）"
        }
        return null
    }

    /** 该推文近期是否在该引擎上失败过（[TWEET_TTL_MS] 内有效）。 */
    fun failedBefore(tweetId: String, engine: String): Boolean {
        val failedAt = tweetFailures[tweetId]?.get(engine) ?: return false
        if (System.currentTimeMillis() - failedAt > TWEET_TTL_MS) {
            tweetFailures[tweetId]?.remove(engine)
            return false
        }
        return true
    }

    /** 引擎成功：熔断清零（冷却后首个成功即完全恢复）。 */
    fun recordEngineSuccess(engine: String) {
        engines[engine] = EngineState()
    }

    /** 引擎失败：累计连续失败，达到阈值进入冷却。 */
    fun recordEngineFailure(engine: String, error: String?) {
        val current = engines[engine] ?: EngineState()
        val failures = current.consecutiveFailures + 1
        engines[engine] = if (failures >= FAILURE_THRESHOLD) {
            EngineState(
                consecutiveFailures = failures,
                openUntil = System.currentTimeMillis() + COOLDOWN_MS,
                lastError = error
            )
        } else {
            current.copy(consecutiveFailures = failures, lastError = error)
        }
    }

    fun markTweetFailed(tweetId: String, engine: String) {
        tweetFailures.getOrPut(tweetId) { ConcurrentHashMap() }[engine] = System.currentTimeMillis()
    }

    /** 任一引擎成功即清掉该推文的全部失败记忆（换网络后重试应从最快引擎重新开始）。 */
    fun markTweetSuccess(tweetId: String) {
        tweetFailures.remove(tweetId)
    }

    /**
     * 该推文的两个必跑引擎（FXTwitter + yt-dlp）是否全部被挡（24h 亲和命中或全局熔断冷却中）。
     *
     * 2026-11-11 断网死锁修复：推文亲和的设计前提是「至少有一个未失败引擎可直达路由」，
     * 断网导致三层全失败时该前提崩塌——全部引擎被拉黑 24h，此后自动/手动重试全部命中
     * 「近期失败过已跳过」，没有任何引擎真正被尝试，永远无法发现网络已恢复。
     * 全拉黑时亲和不再提供任何路由价值，调用方应解除拉黑真正重探。
     */
    fun allEnginesBlockedForTweet(tweetId: String): Boolean =
        isEngineUnavailableForTweet(tweetId, ENGINE_FXTWITTER) &&
            isEngineUnavailableForTweet(tweetId, ENGINE_YTDLP)

    private fun isEngineUnavailableForTweet(tweetId: String, engine: String): Boolean =
        !isEngineAvailable(engine) || failedBefore(tweetId, engine)

    /** 解除单推文全部引擎亲和记录（不清全局熔断——熔断 10 分钟自愈，24h 亲和才是死锁主因）。 */
    fun clearTweetFailures(tweetId: String) {
        tweetFailures.remove(tweetId)
    }

    // ---------- P0-2 自救热修（2026-09-15 批次2） ----------

    private val selfHealAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 是否应该尝试一次 yt-dlp 热修（每进程最多一次）：
     * 三层引擎全失败 → 说明解析器规则整体过时（X 改接口 / yt-dlp extractors 过旧），
     * 此时运行 updateYoutubeDL 拉最新解析器，成功后清空全部熔断状态。
     */
    fun shouldAttemptSelfHeal(): Boolean = selfHealAttempted.compareAndSet(false, true)

    /** 热修成功：清空全部引擎熔断状态与推文亲和（下次下载从最快引擎重新开始）。
     *  2026-11-07 审计修复：旧实现只清 engines、不清 tweetFailures——对触发热修的
     *  那条推文，三层引擎仍命中「近期失败过已跳过」，热修要等 24h 才对它生效。 */
    fun clearAll() {
        engines.clear()
        tweetFailures.clear()
    }
}
