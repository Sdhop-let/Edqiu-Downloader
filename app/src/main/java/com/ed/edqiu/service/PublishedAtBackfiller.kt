package com.ed.edqiu.service

import android.content.Context
import android.util.Log
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.metadata.MetadataFetcher
import com.ed.edqiu.domain.TweetIdExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 旧记录推文发布时间补拉器（2026-09-15，v1.4.21 动态调速版）。
 *
 * 背景：v1.4.19 之前下载的媒体，sidecar 里没有 publishedAt 字段（数据源头缺失），
 * 本地回填读不到 → 这些记录在媒体库永远垫底。本补拉器按记录 url 提取 tweetId，
 * 反查 FXTwitter 公开 API 拿真实发布时间：
 * 1. 回填 download_history.publishedAt（排序/时间标签立即归位）；
 * 2. 回写 sidecar .meta.json（下次本地扫描不再重复拉取）；
 * 3. 拉取失败（推文被删/私密/网络不通）记入 SP 跳过名单，不反复请求。
 *
 * ## 动态调速（按用户 2026-09-15 要求）
 * 以实测单条请求延迟分层：
 * - [Tier.FAST]（<900ms）：一轮 100 条，间隔 1 分钟；
 * - [Tier.MEDIUM]（<2500ms）：一轮 50 条，间隔 2 分钟；
 * - [Tier.SLOW]（≥2500ms）：一轮 10 条，间隔 3 分钟（上限），并通过 [uiState]
 *   向媒体库 UI 反馈「网络不佳已降速」。
 * 每轮按首条实测延迟校准档位，批量内 4 路并发；档位与上次运行时间持久化，
 * 跨进程记住节奏。
 */
object PublishedAtBackfiller {

    private const val TAG = "PublishedAtBackfiller"
    private const val PREFS = "published_at_backfill"
    private const val KEY_FAILED_IDS = "failed_tweet_ids"
    private const val KEY_LAST_RUN_AT = "last_run_at"
    private const val KEY_TIER = "last_tier"
    private const val PARALLELISM = 8

    /** 网络档位：批量大小与轮间隔随网络好差同步调整（间隔上限 3 分钟）。 */
    enum class Tier(val batchSize: Int, val intervalMs: Long, val label: String) {
        EXCELLENT(300, 60_000L, "网络极好"),
        FAST(200, 120_000L, "网络良好"),
        MEDIUM(100, 180_000L, "网络一般"),
        SLOW(50, 180_000L, "网络不佳");

        companion object {
            fun fromLatency(latencyMs: Long): Tier = when {
                latencyMs < 450L -> EXCELLENT
                latencyMs < 900L -> FAST
                latencyMs < 2_500L -> MEDIUM
                else -> SLOW
            }

            fun fromName(name: String?): Tier =
                entries.firstOrNull { it.name == name } ?: MEDIUM
        }
    }

    /** 一轮补拉的结果反馈（媒体库 UI 展示；SLOW 时提示用户已降速）。 */
    /**
     * 一轮补拉的实时状态（2026-09-15 升级按钮实时反馈）：
     * running=true 期间媒体库「升级」pill 显示「补齐 done/total · 网络档位」。
     */
    data class BackfillUiState(
        val running: Boolean,
        val tier: Tier,
        val total: Int,
        val done: Int,
        val fixed: Int,
        val phase: String = "",
        /** 完成消息（running=false 时供 UI 消息条展示）。 */
        val message: String? = null,
        val at: Long = System.currentTimeMillis()
    )

    private val _uiState = MutableStateFlow(BackfillUiState(running = false, tier = Tier.MEDIUM, total = 0, done = 0, fixed = 0))
    // UI 端按 running/message 自行过滤展示（pill 只在 running 时显示进度，消息条只在完成时显示 message）
    val uiState: StateFlow<BackfillUiState?> = _uiState.asStateFlow()

    /** 媒体库扫描完成后触发（按档位间隔节流；未到间隔直接返回 0）。 */
    suspend fun backfillAfterScan(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tier = Tier.fromName(prefs.getString(KEY_TIER, null))
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_RUN_AT, 0L) < tier.intervalMs) return 0
        prefs.edit().putLong(KEY_LAST_RUN_AT, now).apply()
        return backfill(context)
    }

    /** 手动强制一轮补拉（媒体库「升级」按钮，无视节流间隔）。 */
    suspend fun backfillNow(context: Context): Int {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_RUN_AT, System.currentTimeMillis()).apply()
        return backfill(context)
    }

    /**
     * 补拉会话（2026-09-15 多轮自动衔接）：单次调用内循环跑多轮，
     * 直到候选耗尽 / 无进展 / 达到轮数上限，避免“一轮 200 条后剩余候选无人管”。
     */
    suspend fun backfill(context: Context): Int = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).downloadHistoryDao()
        val fetcher = MetadataFetcher()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val failedIds = prefs.getStringSet(KEY_FAILED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

        var totalFixed = 0
        var round = 0
        while (round < 5) {
            val remaining = dao.getMissingPublishedWithUrl(1).isNotEmpty()
            if (!remaining) break

            val outcome = backfillSingleRound(dao, fetcher, prefs, failedIds, round)
            totalFixed += outcome.fixed
            if (outcome.processed == 0) break
            round++
            if (round < 5) delay(1_500L)
        }
        prefs.edit().putStringSet(KEY_FAILED_IDS, failedIds).apply()
        Log.i(TAG, "Backfill session done: rounds=$round, totalFixed=$totalFixed")
        totalFixed
    }

    private data class BackfillRoundOutcome(val processed: Int, val fixed: Int)

    /** 单轮补拉：探测定档 → 批量拉取（实时上报进度）。 */
    private suspend fun backfillSingleRound(
        dao: com.ed.edqiu.data.database.DownloadHistoryDao,
        metadataFetcher: MetadataFetcher,
        prefs: android.content.SharedPreferences,
        failedIds: MutableSet<String>,
        round: Int
    ): BackfillRoundOutcome = withContext(Dispatchers.IO) {
        val candidates = dao.getMissingPublishedWithUrl(400)
            .filterNot { entity -> tweetIdOf(entity.url, entity.filePath) in failedIds }
        if (candidates.isEmpty()) return@withContext BackfillRoundOutcome(0, 0)

        // 探测期间先亮出「检测网络环境」状态
        _uiState.value = BackfillUiState(
            running = true, tier = Tier.fromName(prefs.getString(KEY_TIER, null)),
            total = 0, done = 0, fixed = 0, phase = "检测网络环境"
        )
        val probe = candidates.first()
        val probeStart = System.currentTimeMillis()
        val probePublished = probeFetch(metadataFetcher, probe.url, probe.filePath)
        val probeLatency = System.currentTimeMillis() - probeStart
        val tier = probePublished?.let { Tier.fromLatency(probeLatency) }
            ?: Tier.fromName(prefs.getString(KEY_TIER, null))
        prefs.edit().putString(KEY_TIER, tier.name).apply()

        var fixed = 0
        var processed = 0
        // 探测命中即直接回填（计入本轮）
        if (probePublished != null && probePublished > 0L) {
            dao.backfillPublishedAt(probe.filePath, probePublished)
            writeBackToSidecar(probe.filePath, probePublished)
            fixed++
        } else {
            probe.tweetIdOf()?.let { failedIds += it }
        }
        processed++

        val batch = candidates.drop(1).take(tier.batchSize - 1)
        val total = batch.size + 1
        val doneCounter = java.util.concurrent.atomic.AtomicInteger(1)
        val fixedCounter = java.util.concurrent.atomic.AtomicInteger(fixed)
        fun report() {
            _uiState.value = BackfillUiState(
                running = true, tier = tier, total = total,
                done = doneCounter.get(), fixed = fixedCounter.get(),
                phase = if (round > 0) "第 ${round + 1} 轮 · 补拉发布时间" else "补拉发布时间"
            )
        }
        report()

        val semaphore = Semaphore(PARALLELISM)
        val failedBatch = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        coroutineScope {
            batch.forEach { entity ->
                launch {
                    semaphore.withPermit {
                        val tweetId = entity.tweetIdOf() ?: run {
                            doneCounter.incrementAndGet(); report(); return@withPermit
                        }
                        val publishedAt = probeFetch(metadataFetcher, entity.url, entity.filePath)
                        if (publishedAt != null && publishedAt > 0L) {
                            dao.backfillPublishedAt(entity.filePath, publishedAt)
                            writeBackToSidecar(entity.filePath, publishedAt)
                            fixed++
                            fixedCounter.incrementAndGet()
                        } else {
                            failedBatch += tweetId
                        }
                        processed++
                        doneCounter.incrementAndGet()
                        report()
                    }
                }
            }
        }
        failedIds += failedBatch
        Log.i(TAG, "Backfill round done: tier=${tier.name}, total=$total, fixed=$fixed, processed=$processed")
        BackfillRoundOutcome(processed = processed, fixed = fixed)
    }

    /** 单条补拉：tweetId → FXTwitter 发布时间（epoch ms）；失败返回 null。 */
    private suspend fun probeFetch(
        metadataFetcher: MetadataFetcher,
        url: String,
        filePath: String
    ): Long? {
        val tweetId = tweetIdOf(url, filePath) ?: return null
        return runCatching { metadataFetcher.fetchFromTwitter(tweetId)?.publishedAt }.getOrNull()
    }

    private fun com.ed.edqiu.data.database.DownloadHistoryEntity.tweetIdOf(): String? =
        tweetIdOf(url, filePath)

    private fun tweetIdOf(url: String, filePath: String): String? =
        TweetIdExtractor.fromUrl(url)
            ?: TweetIdExtractor.fromFileName(File(filePath).name)

    /** 发布时间写回 sidecar（保留其他字段），本地扫描即可持续读到，无需二次联网。 */
    private fun writeBackToSidecar(mediaPath: String, publishedAt: Long) {
        runCatching {
            val metaFile = File("$mediaPath.meta.json")
            val json = if (metaFile.exists()) {
                JSONObject(metaFile.readText())
            } else {
                JSONObject().put("url", "https://x.com/i/status/")
            }
            json.put("publishedAt", publishedAt)
            metaFile.writeText(json.toString())
        }.onFailure { Log.w(TAG, "Failed to write sidecar for $mediaPath", it) }
    }
}
