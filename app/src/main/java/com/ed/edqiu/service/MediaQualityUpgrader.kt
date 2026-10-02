package com.ed.edqiu.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale

/**
 * 媒体画质升级器（2026-09-15）。
 *
 * 用户反馈：同一推文在 X 上播放十分清晰，App 内播放却明显抽帧/模糊（观感 360p）。
 * 根因：X 网页/客户端播放的是 HLS 高码率自适应流，而下载器抓取的是 FXTwitter
 * 变体列表中码率最高的 mp4 分片 —— 二者码率/帧率差距可达数倍，缩放到全屏后
 * 低码率放大 + 低帧率即呈现"360p + 抽帧卡帧"。
 *
 * 工作流（对存量媒体渐进升级）：
 * 1. 候选：download_history 中的视频/图片（url 可提取 tweetId）；
 * 2. 检测：FXTwitter API 拉变体列表，取最高码率 mp4（视频）/ name=orig 原图（图片），
 *    与本地对比 —— 视频按「远端码率 vs 本地码率(fileSize*8/时长)」判定，
 *    显著更高（>1.25 倍且差值 >500kbps）才升级；图片按文件大小对比；
 * 3. 升级：下载新文件 → 删除旧媒体+旧 sidecar → 重写 sidecar → 更新 DB 文件路径/大小/画质；
 * 4. 节奏随网络档位（同 PublishedAtBackfiller 模式）：好 20 条/轮·1 分钟，
 *    中 10 条·2 分钟，差暂停并提示用户。
 *
 * 关于「补帧」：真插帧需要逐帧 AI 推理（RIFE 类模型），手机端实时算力/功耗不可行；
 * 本升级器从源头换高码率/高帧率版本（X 的 HLS 流帧率更高，转码 mp4 后帧率保留），
 * 配合播放端按比例 FIT 缩放（不放大低清像素），观感卡顿模糊显著缓解。
 */
object MediaQualityUpgrader {

    private const val TAG = "MediaQualityUpgrader"
    /** 低画质档位集合（2026-09-16：有 Cookie 时这些档位无条件尝试 HLS 升级）。 */
    private val LOW_QUALITY_TIERS = setOf("720p", "480p", "360p")
    private const val PREFS = "media_quality_upgrade"
    private const val KEY_CHECKED_IDS = "checked_tweet_ids"
    private const val KEY_RETRY_IDS = "retry_tweet_ids"
    private const val KEY_LAST_RUN_AT = "last_run_at"
    private const val KEY_TIER = "last_tier"
    private const val KEY_PAUSED = "paused_slow_network"
    private const val PARALLELISM = 3
    private const val API_BASE = "https://api.fxtwitter.com/status/"

    enum class Tier(val batchSize: Int, val intervalMs: Long, val label: String) {
        // 画质升级下载的是真实媒体文件（单条可达几十 MB），批量按 4 档保守放大；
        // 纯 JSON 的发布时间补拉（PublishedAtBackfiller）才用 300/200/100/50 的激进节奏
        EXCELLENT(50, 60_000L, "网络极好"),
        FAST(30, 120_000L, "网络良好"),
        MEDIUM(15, 180_000L, "网络一般"),
        SLOW(0, 180_000L, "网络不佳");

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

    /**
     * 一轮升级的实时状态（2026-09-15 升级按钮实时反馈）：
     * running=true 期间媒体库「升级」pill 显示「升级 done/total · 网络档位」。
     */
    data class UpgradeUiState(
        val running: Boolean,
        val tier: Tier,
        val total: Int,
        val done: Int,
        val upgraded: Int,
        val phase: String = "",
        val message: String? = null,
        val at: Long = System.currentTimeMillis()
    )

    private val _uiState = MutableStateFlow(UpgradeUiState(running = false, tier = Tier.MEDIUM, total = 0, done = 0, upgraded = 0))
    // 2026-10 整改：重入守卫——进媒体库自动触发与手动按钮可并发进入同一单例，
    // 两会话互相覆盖 _uiState、SharedPreferences 全量回写后写覆盖先写、同推文重复下载。
    private val upgradeRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    // UI 端按 running/message 自行过滤展示（pill 只在 running 时显示进度，消息条只在完成时显示 message）
    val uiState: StateFlow<UpgradeUiState?> = _uiState.asStateFlow()

    private data class RemoteMedia(
        val bestVideoUrl: String?,
        val bestVideoBitrate: Int,
        val hasHls: Boolean,
        val imageOrigUrl: String?
    )

    /** 媒体库扫描后触发（档位间隔节流；SLOW 档暂停一轮并提示）。 */
    suspend fun upgradeAfterScan(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tier = Tier.fromName(prefs.getString(KEY_TIER, null))
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_RUN_AT, 0L) < tier.intervalMs) return
        prefs.edit().putLong(KEY_LAST_RUN_AT, now).apply()
        if (tier == Tier.SLOW) {
            if (!prefs.getBoolean(KEY_PAUSED, false)) {
                prefs.edit().putBoolean(KEY_PAUSED, true).apply()
                _uiState.value = UpgradeUiState(
                    running = false, tier = tier, total = 0, done = 0, upgraded = 0,
                    message = "网络不佳，画质升级已暂停（网络恢复后自动继续）"
                )
            }
            return
        }
        prefs.edit().putBoolean(KEY_PAUSED, false).apply()
        upgrade(context)
    }

    /** 手动强制一轮升级（媒体库「升级」按钮，无视节流与慢网暂停）。 */
    suspend fun upgradeNow(context: Context): Int {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_RUN_AT, System.currentTimeMillis())
            .putBoolean(KEY_PAUSED, false)
            .apply()
        return upgrade(context, manual = true)
    }

    /**
     * 画质升级（2026-09-15 多轮自动衔接修复）：单次调用内循环跑多轮，
     * 每轮 batch 条，轮间 2s 短歇——直到候选耗尽 / 无进展 / 达到轮数上限。
     * 2026-09-16 修复：
     * ①删掉 remaining 前置检查（原 LIMIT 1 查询不过滤已检查集合，恒真但导致
     *   空转时连 upgradeSingleRound 都不进，「点击零反馈」的成因之一）；
     * ②候选改由 upgradeSingleRound 一次拉全量（LIMIT 2000）再内存过滤，
     *   修复「LIMIT 200 窗口被已检查记录占满、窗口外长尾永不轮到」的盲区；
     * ③收尾必清 uiState.running（此前遗留 running=true，pill 会卡死在旧进度）；
     * ④manual=true（用户点按钮）才发完成/空转消息，自动路径静默不打扰。
     */
    suspend fun upgrade(context: Context, manual: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (!upgradeRunning.compareAndSet(false, true)) {
            Log.i(TAG, "upgrade() 已有会话在执行，跳过本次触发（重入守卫）")
            return@withContext 0
        }
        try {
            upgradeSession(context, manual)
        } finally {
            upgradeRunning.set(false)
        }
    }

    private suspend fun upgradeSession(context: Context, manual: Boolean): Int = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).downloadHistoryDao()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val checkedIds = prefs.getStringSet(KEY_CHECKED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val retryIds = prefs.getStringSet(KEY_RETRY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

        var totalUpgraded = 0
        var round = 0
        while (round < 10) {
            val outcome = upgradeSingleRound(context, dao, prefs, checkedIds, retryIds, round, manual)
            totalUpgraded += outcome.upgraded
            // 无进展保护：本轮没有任何条目被实际处理 → 停止（剩余留给下次触发）
            if (outcome.processed == 0) break
            round++
            if (round < 10) delay(2_000L) // 轮间短歇
        }

        // 收尾：清 running 防 pill 卡死；手动路径给完成摘要（空转原因由 upgradeSingleRound 设置，此处不覆盖）
        val last = _uiState.value
        if (last.running) {
            _uiState.value = if (manual) last.copy(
                running = false,
                message = if (totalUpgraded > 0) "画质升级完成：升级 $totalUpgraded 条"
                else "画质检测完成 ${last.done}/${last.total}：均已是最高可用画质" + hlsHint(context)
            ) else last.copy(running = false, message = null)
        }

        Log.i(TAG, "Upgrade session done: rounds=$round, totalUpgraded=$totalUpgraded")
        totalUpgraded
    }

    private data class RoundOutcome(val processed: Int, val upgraded: Int)

    /**
     * 2026-09-16「假升级」体验修复：X 网页播的 HLS 高码率流需要登录态才能拿到最高档，
     * 未配置 Cookie 时 yt-dlp 拉到的 HLS 上限受限（甚至全部失败）——表现即"升级了但画质
     * 没变"。这里把原因明示给用户：0 升级且未配置 Cookie 时追加提示。
     */
    private fun hlsHint(context: Context): String =
        if (!com.ed.edqiu.data.preferences.CookiePreferences(context).hasCookies()) {
            "（检测到 X 高画质流需要登录态，可在设置中配置 X Cookie 后重试）"
        } else ""

    /** 单轮升级：探测定档 → 批量处理（实时上报进度）。 */
    private suspend fun upgradeSingleRound(
        context: Context,
        dao: com.ed.edqiu.data.database.DownloadHistoryDao,
        prefs: android.content.SharedPreferences,
        checkedIds: MutableSet<String>,
        retryIds: MutableSet<String>,
        round: Int,
        manual: Boolean
    ): RoundOutcome = withContext(Dispatchers.IO) {
        // 1. 探测定档（1 条视频的 API 延迟）
        // 2026-09-16 修复长尾盲区：一次拉全量（LIMIT 2000 覆盖现有库容）再内存过滤已检查，
        // 此前 LIMIT 200 窗口被已检查记录占满后，窗口外候选永不轮到
        val rawCandidates = dao.getQualityUpgradeCandidates(2000)
        val candidates = rawCandidates.filterNot { tweetIdOf(it) in checkedIds }
        if (candidates.isEmpty()) {
            // 空转反馈（仅手动路径发，自动路径静默）：修「点击升级零反应」。
            // 2026-09-16 补 hlsHint：存量全部检测过时恰恰最需要知道 HLS 高画质没跑的原因。
            if (manual) {
                _uiState.value = UpgradeUiState(
                    running = false, tier = Tier.fromName(prefs.getString(KEY_TIER, null)),
                    total = 0, done = 0, upgraded = 0,
                    message = (if (rawCandidates.isEmpty()) "媒体库暂无可检测的推文媒体"
                    else "存量 ${checkedIds.size} 条推文已全部检测过，暂无可升级媒体") + hlsHint(context)
                )
            }
            return@withContext RoundOutcome(0, 0)
        }

        // 探测期间先亮出「检测网络环境」状态
        _uiState.value = UpgradeUiState(
            running = true, tier = Tier.fromName(prefs.getString(KEY_TIER, null)),
            total = 0, done = 0, upgraded = 0, phase = "检测网络环境"
        )
        val probeStart = System.currentTimeMillis()
        val probeRemote = fetchRemoteMedia(candidates.first())
        val probeLatency = System.currentTimeMillis() - probeStart
        val tier = probeRemote?.let { Tier.fromLatency(probeLatency) }
            ?: Tier.fromName(prefs.getString(KEY_TIER, null))
        prefs.edit().putString(KEY_TIER, tier.name).apply()

        // 计数统一读 doneCounter / upgradedCounter（2026-10：并发协程中普通 var 自增会丢失更新）
        val checkedBatch = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        // 2026-09-16 失败重试机制：API 拉取失败/下载失败不再一次进永久黑名单——
        // 首次失败进 retryIds（下轮重试），retry 中再失败才 checkedIds 永久跳过；
        // 成功则从 retryIds 洗白。修复「网络抖动一次就永久放弃（720p 卡住不动）」
        val failedBatch = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val successBatch = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val semaphore = Semaphore(PARALLELISM)
        // HLS 升级走 yt-dlp（下载 HLS 分片 + ffmpeg 合并，单条 30-90s）：预算制串行重任务
        val hlsBudget = java.util.concurrent.atomic.AtomicInteger(
            when (tier) {
                Tier.EXCELLENT -> 3
                Tier.FAST -> 2
                Tier.MEDIUM -> 1
                Tier.SLOW -> 0
            }
        )

        // 探测那条（batch[0]）也计入批次处理
        val batch = candidates.take(tier.batchSize)
        val total = batch.size
        val doneCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val upgradedCounter = java.util.concurrent.atomic.AtomicInteger(0)
        fun report() {
            _uiState.value = UpgradeUiState(
                running = true, tier = tier, total = total,
                done = doneCounter.get(), upgraded = upgradedCounter.get(),
                phase = if (round > 0) "第 ${round + 1} 轮 · 画质检测与升级" else "画质检测与升级"
            )
        }
        report()

        coroutineScope {
            batch.forEachIndexed { index, entity ->
                launch {
                    semaphore.withPermit {
                        val tweetId = tweetIdOf(entity) ?: run {
                            doneCounter.incrementAndGet(); report(); return@withPermit
                        }
                        val remote = if (index == 0) probeRemote
                        else fetchRemoteMedia(entity)
                        if (remote == null) {
                            failedBatch += tweetId // 推文没了或网络抖动：走 retry，两次失败才永久跳过
                            doneCounter.incrementAndGet(); report()
                            return@withPermit
                        }
                        val better = when (entity.mediaType) {
                            MediaType.VIDEO -> videoNeedsUpgrade(entity, remote)
                            MediaType.IMAGE -> remote.imageOrigUrl != null
                            else -> false
                        }
                        if (!better) {
                            checkedBatch += tweetId
                            doneCounter.incrementAndGet(); report()
                            return@withPermit
                        }
                        // 有 HLS 高画质源且 mp4 变体不够好 → 走 yt-dlp 拉 HLS 最佳（预算制）。
                        // 2026-09-16 放宽：低档视频（720p 及以下）+ 用户已配置 Cookie →
                        // 无条件进 HLS 路径——FXTwitter 的 hasHls 标记经常缺失，而 X 登录态
                        // 下 yt-dlp 能拉到 1080p HLS（这正是「配了 Cookie 才解锁高画质」的
                        // 意义）；产物有大小保护（不比原文件大就不替换），失败进 retry。
                        val needsHls = entity.mediaType == MediaType.VIDEO && (
                            (remote.hasHls && remote.bestVideoBitrate < 2_000_000) ||
                                (entity.quality in LOW_QUALITY_TIERS &&
                                    runCatching {
                                        com.ed.edqiu.data.preferences.CookiePreferences(context).hasCookies()
                                    }.getOrDefault(false))
                            )
                        val ok = if (needsHls) {
                            if (hlsBudget.getAndDecrement() <= 0) {
                                doneCounter.incrementAndGet(); report(); return@withPermit // 预算用尽：下轮再升
                            }
                            runCatching { performHlsUpgrade(context, dao, entity, tweetId) }
                                .getOrElse { error ->
                                    Log.w(TAG, "HLS upgrade failed for ${entity.filePath}", error)
                                    false
                                }
                        } else {
                            runCatching { performUpgrade(dao, entity, remote) }
                                .getOrElse { error ->
                                    Log.w(TAG, "Upgrade failed for ${entity.filePath}", error)
                                    false
                                }
                        }
                        if (ok) {
                            upgradedCounter.incrementAndGet()
                            successBatch += tweetId
                        } else {
                            failedBatch += tweetId
                        }
                        doneCounter.incrementAndGet()
                        report()
                    }
                }
            }
        }
        checkedIds += checkedBatch
        failedBatch.forEach { tid ->
            if (!retryIds.add(tid)) {
                // 已在 retry = 连续第二次失败 → 永久跳过
                retryIds.remove(tid)
                checkedIds += tid
            }
        }
        successBatch.forEach { retryIds.remove(it) }
        prefs.edit()
            .putStringSet(KEY_CHECKED_IDS, checkedIds)
            .putStringSet(KEY_RETRY_IDS, retryIds)
            .apply()

        val roundProcessed = doneCounter.get()
        val roundUpgraded = upgradedCounter.get()
        Log.i(TAG, "Upgrade round done: tier=${tier.name}, total=$total, upgraded=$roundUpgraded, processed=$roundProcessed")
        RoundOutcome(processed = roundProcessed, upgraded = roundUpgraded)
    }

    // ---------------- 检测 ----------------

    /** FXTwitter 响应 → 最佳视频变体（最高码率 mp4 + 是否有 HLS）与图片原图。 */
    private fun fetchRemoteMedia(entity: DownloadHistoryEntity): RemoteMedia? {
        val tweetId = tweetIdOf(entity) ?: return null
        val body = fetchText("$API_BASE$tweetId") ?: return null
        return runCatching {
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 200) return null
            val tweet = root.getJSONObject("tweet")
            val media = tweet.optJSONObject("media") ?: JSONObject()
            val candidates = buildList {
                addAll(media.optJSONArray("videos")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } } ?: emptyList())
                addAll(media.optJSONArray("all")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } } ?: emptyList())
                addAll(media.optJSONArray("photos")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } } ?: emptyList())
                addAll(media.optJSONArray("images")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } } ?: emptyList())
            }
            var bestUrl: String? = null
            var bestBitrate = 0
            var hasHls = false
            var imageOrig: String? = null
            candidates.forEach { item ->
                val variants = item.optJSONArray("variants")
                    ?: item.optJSONObject("video_info")?.optJSONArray("variants")
                variants?.let { arr ->
                    (0 until arr.length()).forEach { i ->
                        val v = arr.optJSONObject(i) ?: return@forEach
                        val url = v.optString("url", "")
                        when {
                            url.contains(".m3u8") -> hasHls = true
                            url.contains(".mp4") || v.optString("content_type") == "video/mp4" -> {
                                val bitrate = v.optInt("bitrate", 0)
                                if (bitrate > bestBitrate) {
                                    bestBitrate = bitrate
                                    bestUrl = url
                                }
                            }
                        }
                    }
                }
                val imageUrl = item.optString("url", "").ifBlank {
                    item.optString("image_url", "").ifBlank { item.optString("media_url_https", "") }
                }
                if (imageUrl.contains("pbs.twimg.com/media") && imageOrig == null) {
                    imageOrig = toOrigImageUrl(imageUrl)
                }
            }
            RemoteMedia(bestUrl, bestBitrate, hasHls, imageOrig)
        }.getOrNull()
    }

    /** pbs.twimg.com 图片 url 换 name=orig（原图，无压缩参数）。 */
    private fun toOrigImageUrl(url: String): String = when {
        url.contains("name=") -> url.substringBefore("name=") + "name=orig"
        else -> "$url&name=orig"
    }

    /** 视频升级判定：远端最高 mp4 码率 vs 本地码率（fileSize*8/时长），显著更高才升级。 */
    private fun videoNeedsUpgrade(entity: DownloadHistoryEntity, remote: RemoteMedia): Boolean {
        val remoteUrl = remote.bestVideoUrl ?: return false
        if (remote.bestVideoBitrate <= 0) return false
        val file = File(entity.filePath)
        if (!file.exists()) return false
        // 本地时长：DB 缺失时重读
        val durationMs = entity.duration.takeIf { it > 0 } ?: readDuration(file) ?: return false
        val localBitrate = (file.length() * 8.0) / (durationMs / 1000.0)
        // 本地分辨率（用于"低分辨率 + 远端高清"场景）
        val localHeight = readHeight(file)
        val significant = remote.bestVideoBitrate > localBitrate * 1.25 &&
            (remote.bestVideoBitrate - localBitrate) > 500_000
        val lowResUpgrade = localHeight in 1..719 && remote.bestVideoBitrate >= 2_000_000
        return significant || lowResUpgrade
    }

    private fun readDuration(file: File): Long? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    private fun readHeight(file: File): Int = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } finally {
            runCatching { r.release() }
        }
    }.getOrDefault(0)

    // ---------------- 升级执行 ----------------

    /**
     * HLS 高画质升级（yt-dlp 引擎）：拉 X 的 HLS 自适应流最高档（1080p+），
     * ffmpeg 合并 mp4 后替换本地低码率文件。单条 30-90s，预算制串行调用。
     */
    private suspend fun performHlsUpgrade(
        context: Context,
        dao: com.ed.edqiu.data.database.DownloadHistoryDao,
        entity: DownloadHistoryEntity,
        tweetId: String
    ): Boolean {
        // 2026-10：16KB 内存页设备 ffmpeg 不可用（libwebp* 未适配），HLS 升级整体跳过
        val app = context.applicationContext as? com.ed.edqiu.TwitterDownloaderApp
        if (app?.is16kPageDevice == true) {
            _uiState.value = _uiState.value.copy(running = false, message = "16KB 内存页机型暂不支持 HLS 高画质升级（ffmpeg 依赖未适配）")
            Log.w(TAG, "skip HLS upgrade on 16KB-page device")
            return false
        }
        val proxyUrl = com.ed.edqiu.data.preferences.ProxyPreferences(context)
            .getProxySettings().toProxyUrl()
        val cookiePreferences = com.ed.edqiu.data.preferences.CookiePreferences(context)
        val cookieFile = if (cookiePreferences.hasCookies()) {
            com.ed.edqiu.service.YoutubeDLService.writeCookieFile(
                context, cookiePreferences.getAuthToken(), cookiePreferences.getCt0()
            )
        } else null
        val outputDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download")
        outputDir.mkdirs()

        val newPath = com.ed.edqiu.service.YoutubeDLService.downloadVideo(
            url = "https://x.com/i/status/$tweetId",
            formatId = "best",
            outputDir = outputDir.absolutePath,
            onProgress = { _, _ -> },
            proxyUrl = proxyUrl,
            cookieFilePath = cookieFile,
            playlistIndex = null
        ).getOrElse { error ->
            Log.w(TAG, "yt-dlp HLS download failed for tweet=$tweetId: ${error.message}")
            return false
        }

        val newFile = File(newPath)
        // 同路径防护：再次升级 HLS 时 yt-dlp 会按同一输出模板覆盖同路径文件，
        // 此时绝不能删除 entity.filePath（删的就是刚下载的新文件本身）
        val samePath = newFile.absolutePath == entity.filePath
        if (!newFile.exists() || newFile.length() <= File(entity.filePath).length()) {
            // 异常小的产物不替换
            if (newFile.exists() && !samePath) newFile.delete()
            return false
        }

        // sidecar：旧 → 新（quality 标注 HLS best）
        val oldSidecar = File(entity.filePath + ".meta.json")
        val newSidecar = File(newPath + ".meta.json")
        runCatching {
            val json = if (oldSidecar.exists()) JSONObject(oldSidecar.readText()) else JSONObject()
            json.put("quality", "best")
            json.put("formatId", "ytdlp_hls")
            json.put("ext", "mp4")
            newSidecar.writeText(json.toString())
        }

        // 2026-10 P2 整改：终态迁移不可取消——取消落在挂起点会把"新文件已落盘、DB 仍指旧
        // 文件"的中间态固化（孤儿新文件被扫描成重复条目）；NonCancellable 保证三步原子完成
        withContext(kotlinx.coroutines.NonCancellable) {
            dao.updateMediaFile(
                oldPath = entity.filePath,
                newPath = newFile.absolutePath,
                fileSize = newFile.length(),
                quality = "best"
            )
            if (!samePath) {
                File(entity.filePath).delete()
                oldSidecar.delete()
            }
        }
        Log.i(TAG, "HLS upgraded: ${File(entity.filePath).name} -> ${newFile.name}")
        return true
    }

    /** 下载更优画质替换：新文件落盘 → 删旧媒体/sidecar → 重写 sidecar → 更新 DB。 */
    private suspend fun performUpgrade(
        dao: com.ed.edqiu.data.database.DownloadHistoryDao,
        entity: DownloadHistoryEntity,
        remote: RemoteMedia
    ): Boolean {
        val tweetId = tweetIdOf(entity) ?: return false
        val isVideo = entity.mediaType == MediaType.VIDEO
        val remoteUrl = if (isVideo) remote.bestVideoUrl ?: return false
        else remote.imageOrigUrl ?: return false

        val dir = File(entity.filePath).parentFile ?: return false
        val uploader = entity.uploader.ifBlank { "unknown" }
        val mediaIndex = entity.mediaIndex ?: 1
        val kind = if (isVideo) "video" else "image"
        val ext = if (isVideo) "mp4" else remoteUrl.substringAfter("format=", "jpg").substringBefore("&")
            .takeIf { it.length <= 5 } ?: "jpg"
        val newQuality = if (isVideo) bitrateToQuality(remote.bestVideoBitrate) else "original"
        val newName = sanitize("${uploader}_${tweetId}_${mediaIndex}_${kind}_${newQuality}.$ext")
        val newFile = File(dir, newName)
        if (newFile.absolutePath == entity.filePath) {
            // 目标与当前记录同路径（已是目标画质），无可升级
            return false
        }
        if (newFile.exists()) {
            // 2026-10 整改：目标已存在多为上次中断残留的孤儿文件（DB 仍指向旧文件）——
            // 旧实现直接放弃导致该条目永久无法升级；删除残留后重新下载
            Log.i(TAG, "Target exists (stale orphan), overwrite: ${newFile.name}")
            newFile.delete()
        }

        val proxy = null // 升级下载跟随系统网络（用户代理场景多走 VPN 隧道，无需显式 HTTP 代理）
        val ok = downloadToFile(remoteUrl, newFile, proxy)
        if (!ok) {
            newFile.delete()
            return false
        }

        // 图片：下载后对比，未显著更大则放弃替换
        if (!isVideo && newFile.length() <= File(entity.filePath).length() * 1.05) {
            newFile.delete()
            return false
        }

        // sidecar：旧 → 新（quality 更新）
        val oldSidecar = File(entity.filePath + ".meta.json")
        val newSidecar = File(newFile.absolutePath + ".meta.json")
        runCatching {
            val json = if (oldSidecar.exists()) JSONObject(oldSidecar.readText()) else JSONObject()
            json.put("quality", newQuality)
            json.put("ext", ext)
            newSidecar.writeText(json.toString())
        }

        // 2026-10 P2 整改：同 HLS——终态迁移不可取消；updateMediaFile 按 filePath 匹配，
        // 用户已删记录时 0 行更新，绝不能再删用户选择保留的文件
        withContext(kotlinx.coroutines.NonCancellable) {
            dao.updateMediaFile(
                oldPath = entity.filePath,
                newPath = newFile.absolutePath,
                fileSize = newFile.length(),
                quality = newQuality
            )
            File(entity.filePath).delete()
            oldSidecar.delete()
        }
        Log.i(TAG, "Upgraded: ${File(entity.filePath).name} -> ${newFile.name} (bitrate=${remote.bestVideoBitrate})")
        return true
    }

    /** 精简版 HTTP 下载（与 InternalMediaDownloader 同策略：64KB 缓冲 + .part + 单次重试）。 */
    private fun downloadToFile(url: String, target: File, proxy: Proxy?): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        return runCatching {
            val conn = if (proxy != null) URL(url).openConnection(proxy) as HttpURLConnection
            else URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Edqiu/1.0")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                conn.inputStream.use { raw ->
                    java.io.BufferedInputStream(raw, 64 * 1024).use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                true
            } finally {
                conn.disconnect()
            }
        }.getOrElse { error ->
            Log.w(TAG, "Download failed: $url", error)
            part.delete()
            false
        }
    }

    /** 与 InternalMediaDownloader.bitrateToQuality 同口径（sidecar/文件名一致）。 */
    private fun bitrateToQuality(bitrate: Int): String = when {
        bitrate >= 2_000_000 -> "1080p"
        bitrate >= 832_000 -> "720p"
        bitrate >= 320_000 -> "480p"
        bitrate > 0 -> "360p"
        else -> "best"
    }

    private fun sanitize(name: String): String = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    private fun fetchText(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Edqiu/1.0")
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun tweetIdOf(entity: DownloadHistoryEntity): String? =
        com.ed.edqiu.domain.TweetIdExtractor.fromUrl(entity.url)
            ?: com.ed.edqiu.domain.TweetIdExtractor.fromFileName(File(entity.filePath).name)

    @Suppress("unused")
    private fun isPortOpenForProxy(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(InetSocketAddress(host, port), 250); true }
    }.getOrDefault(false)
}
