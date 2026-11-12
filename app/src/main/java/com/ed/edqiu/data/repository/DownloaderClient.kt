package com.ed.edqiu.data.repository

import android.content.Context
import android.os.Environment
import com.ed.edqiu.data.model.DownloadCancelledException
import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.model.ProxySettings
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.preferences.ThirdPartyApiPreferences
import com.ed.edqiu.downloader.DownloadEngineHealth
import com.ed.edqiu.downloader.HlsBetterSourceException
import com.ed.edqiu.downloader.InternalMediaDownloader
import com.ed.edqiu.downloader.TweetGoneException
import com.ed.edqiu.downloader.ThirdPartyApiResolver
import com.ed.edqiu.domain.TweetIdExtractor
import com.ed.edqiu.service.YoutubeDLService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 收件箱下载客户端：三层引擎链（2026-09-15 P0-1 解析链三层冗余）。
 *
 * - 引擎一 FXTwitter（[InternalMediaDownloader]）：最快，走可选代理；
 * - 引擎二 yt-dlp（[YoutubeDLService]）：重但稳，带代理 + Cookie，下载成功后写
 *   `.meta.json` sidecar（含 tweetId），供收件箱 [DownloadMonitor] 扫描匹配；
 * - 引擎三 第三方 API（[ThirdPartyApiResolver]）：前两层都失败后的付费兜底，
 *   **默认关闭**（设置页「网络与认证 → 第三方解析兜底」填入端点才启用）。
 *
 * 可靠性机制（[DownloadEngineHealth]）：
 * - 引擎熔断：**用户下载线**（[launch] manual=true）单引擎连续 3 次失败 → 冷却 10 分钟
 *   直接跳过，不再每次下载先撞一遍必死层；
 * - 订阅线（manual=false，后台自动下载）失败**只记推文亲和，不记全局熔断**——
 *   2026-10-17 双线隔离：订阅失败曾打满全局熔断器，误伤用户手动下载；
 * - 单推文亲和：24h 内失败过的引擎不再重复尝试（两线通用，自动重试直达可用引擎）；
 * - TweetGone 是确定性知识：永久失败不回退、不计熔断。
 *
 * 不再拉起外部下载器 Activity（旧逻辑 startActivity 自己包名 MainActivity 会把 App
 * 顶回前台造成「下载无反应/闪跳」）。
 */
class DownloaderClient(private val context: Context) {

    sealed interface LaunchResult {
        data class Downloaded(val files: List<InternalMediaDownloader.DownloadedFile>) : LaunchResult
        data object LaunchedExternal : LaunchResult

        /**
         * @param fromSkip true = 本轮没有任何引擎真正执行（全部被熔断/推文亲和跳过）。
         *   2026-11-11 断网死锁修复：调用方（SavedLinkRepository）对 fromSkip 失败不烧
         *   attemptCount、不推进退避——「跳过」不是真实失败，烧计数会把自动重试在断网期
         *   烧光，网络恢复后反而停摆。
         */
        data class Failed(val reason: String, val fromSkip: Boolean = false) : LaunchResult

        /** 推文已不存在（被删/私密/未公开）——永久失败，不再回退、不再重试。 */
        data object TweetGone : LaunchResult
    }

    private val internalDownloader = InternalMediaDownloader(context)

    /**
     * @param manual 是否用户下载线（用户点下载/详情页/重试= true；订阅轮询自动下载 = false）。
     *   仅 manual 的引擎失败计入全局熔断器（[DownloadEngineHealth]），订阅线失败不影响手动下载。
     */
    suspend fun launch(rawUrl: String, proxy: ProxySettings? = null, manual: Boolean = false): LaunchResult {
        val tweetId = TweetIdExtractor.fromUrl(rawUrl)
            ?: return LaunchResult.Failed("链接中没有有效的推文 ID")
        val normalizedUrl = "https://x.com/i/status/$tweetId"

        // 2026-11-11 断网死锁修复（手动线）：用户点下载/详情页/重试 = 明确授权重探，
        // 先清该推文的引擎亲和记忆，保证手动重试永远真正触碰引擎链。旧实现 24h 亲和
        // 会把断网期失败过的全部引擎拉黑，手动重试空转成「近期失败过已跳过」。
        if (manual) DownloadEngineHealth.clearTweetFailures(tweetId)

        // 2026-10-22「下载无反馈」修复：引擎链启动期（FXTwitter API 分析 ~2s +
        // 熔断判定 + yt-dlp 二进制初始化 3-8s）内 Bus 无任何任务，收件箱卡片停在
        // PENDING 完全无变化——用户体感「点了没反应/背后另起通道」。立即注册
        // 「准备中」占位任务（0%），引擎自任务接管进度后本任务在 finally 移除；
        // InboxDownloadProgress 对同推文多任务取 max，占位 0% 不拖低真实进度。
        val pendingTaskId = "pending_$tweetId"
        DownloadTaskBus.add(
            DownloadTask(
                id = pendingTaskId,
                url = normalizedUrl,
                title = "准备下载",
                thumbnail = "",
                uploader = "unknown",
                formatId = "pending",
                quality = "",
                ext = "",
                status = DownloadStatus.DOWNLOADING
            )
        )
        try {
            return launchEngines(rawUrl, tweetId, normalizedUrl, proxy, manual)
        } finally {
            DownloadTaskBus.remove(pendingTaskId)
        }
    }

    private suspend fun launchEngines(
        rawUrl: String,
        tweetId: String,
        normalizedUrl: String,
        proxy: ProxySettings?,
        manual: Boolean
    ): LaunchResult {

        // 智能代理（2026-09-14 网络路径优化）：调用方未显式传 proxy 时，
        // 读取用户在设置里配置的代理（2026-09-15 P0-3 起支持 http / socks5 双类型）。
        val effectiveProxy = proxy ?: ProxyPreferences(context).getProxySettings().takeIf { it.enabled }
        val health = DownloadEngineHealth

        // 2026-11-11 断网死锁修复（自动线）：两个必跑引擎全部被挡（24h 亲和/全局熔断）
        // 时，亲和已无任何「直达可用引擎」的路由价值——自动解除拉黑，本轮真正重探。
        // 网络未恢复 → 真失败重新拉黑（下轮 retryDueDownloads 再解除再探，15 分钟一轮，
        // 天然变成断网探测心跳）；网络已恢复 → 首轮重试即成功。
        if (health.allEnginesBlockedForTweet(tweetId)) {
            health.clearTweetFailures(tweetId)
        }
        // 2026-11-11 fromSkip 跟踪：本轮是否有任何引擎真正执行过（含 HLS 路由/回退）。
        // 三层全被跳过时最终 Failed 带 fromSkip=true，由调用方决定不烧重试计数。
        var anyEngineExecuted = false

        // ---------- 引擎一：FXTwitter ----------
        var internalError: String? = null
        if (health.isEngineAvailable(DownloadEngineHealth.ENGINE_FXTWITTER) &&
            !health.failedBefore(tweetId, DownloadEngineHealth.ENGINE_FXTWITTER)
        ) {
            anyEngineExecuted = true
            internalError = internalDownloader.downloadTweet(normalizedUrl, effectiveProxy).fold(
                onSuccess = { files ->
                    if (files.isNotEmpty()) {
                        health.recordEngineSuccess(DownloadEngineHealth.ENGINE_FXTWITTER)
                        health.markTweetSuccess(tweetId)
                        return LaunchResult.Downloaded(files)
                    }
                    "推文中没有可下载的媒体"
                },
                onFailure = { error ->
                    // 推文已不存在：确定性知识，不计引擎失败、跳过全部回退（回退必败，纯浪费流量）
                    if (error is TweetGoneException) {
                        health.recordEngineSuccess(DownloadEngineHealth.ENGINE_FXTWITTER)
                        return LaunchResult.TweetGone
                    }
                    // 2026-09-15 源头画质优化：仅低码率 mp4 + 存在 HLS 流 → 直接转 yt-dlp 拉 HLS 最佳。
                    // 这是路由决策而非引擎故障：API 活着，不计熔断。
                    if (error is HlsBetterSourceException) {
                        health.recordEngineSuccess(DownloadEngineHealth.ENGINE_FXTWITTER)
                        val ytResult = ytdlpFallback(
                            normalizedUrl,
                            tweetId,
                            error.message ?: "存在 HLS 高画质源",
                            // 2026-11-11 实证闸门基准：FXTwitter 渐进 mp4 最高变体码率。
                            // HLS 产物实测码率必须严格超过它才落位，否则丢弃走回退直下 mp4
                            baselineBitrate = error.maxMp4Bitrate
                        )
                        // 2026-10-25 宽路由保底：yt-dlp 拉流失败（无 HLS / 登录失效 / 网络）时
                        // 回退内部引擎直下 mp4——「拉不到最高画质」不能变成「什么都拿不到」。
                        // 用户主动取消不回退（尊重取消）
                        if (ytResult is LaunchResult.Failed && ytResult.reason != "下载已取消") {
                            val files = internalDownloader
                                .downloadTweet(normalizedUrl, effectiveProxy, allowHlsRouting = false)
                                .getOrNull()
                                .orEmpty()
                            if (files.isNotEmpty()) {
                                health.recordEngineSuccess(DownloadEngineHealth.ENGINE_FXTWITTER)
                                return LaunchResult.Downloaded(files)
                            }
                        }
                        return ytResult
                    }
                    // 双线隔离（2026-10-17）：仅用户线失败计入全局熔断，
                    // 订阅线失败只记推文亲和（下方 markTweetFailed），不打熔断器
                    if (manual) {
                        health.recordEngineFailure(DownloadEngineHealth.ENGINE_FXTWITTER, error.message)
                    }
                    health.markTweetFailed(tweetId, DownloadEngineHealth.ENGINE_FXTWITTER)
                    error.message ?: "内部下载失败"
                },
            )
        } else {
            // 2026-10-17：区分两种跳过——全局熔断（该说冷却）vs 本推文亲和（该说近期失败过），
            // 旧文案一律显示「引擎熔断中」，把普通重试跳过误报成熔断故障
            internalError = if (health.failedBefore(tweetId, DownloadEngineHealth.ENGINE_FXTWITTER)) {
                "FXTwitter 近期失败过已跳过"
            } else {
                "FXTwitter 引擎熔断中${health.engineBreakerNote(DownloadEngineHealth.ENGINE_FXTWITTER) ?: ""}"
            }
        }
        // ---------- 引擎二：yt-dlp（代理 + Cookie） ----------
        var ytdlpError: String? = null
        if (health.isEngineAvailable(DownloadEngineHealth.ENGINE_YTDLP) &&
            !health.failedBefore(tweetId, DownloadEngineHealth.ENGINE_YTDLP)
        ) {
            anyEngineExecuted = true
            when (val result = ytdlpFallback(normalizedUrl, tweetId, internalError)) {
                is LaunchResult.Downloaded -> {
                    health.markTweetSuccess(tweetId)
                    return result
                }
                is LaunchResult.TweetGone -> return result
                is LaunchResult.Failed -> {
                    ytdlpError = result.reason
                    health.markTweetFailed(tweetId, DownloadEngineHealth.ENGINE_YTDLP)
                }
                else -> health.markTweetFailed(tweetId, DownloadEngineHealth.ENGINE_YTDLP)
            }
        } else {
            ytdlpError = "近期失败过已跳过"
        }

        // ---------- 引擎三：第三方 API 兜底（默认关闭，设置页配置后启用） ----------
        val (thirdParty, thirdPartyExecuted) = thirdPartyFallback(normalizedUrl, tweetId, effectiveProxy, manual)
        if (thirdPartyExecuted) anyEngineExecuted = true
        return when (thirdParty) {
            is LaunchResult.Downloaded -> thirdParty
            else -> {
                val tpReason = (thirdParty as? LaunchResult.Failed)?.reason
                val message = buildString {
                    append("内部下载失败")
                    if (!internalError.isNullOrBlank()) append("（$internalError）")
                    if (!ytdlpError.isNullOrBlank()) append("；yt-dlp 回退失败（$ytdlpError）")
                    if (!tpReason.isNullOrBlank()) append("；$tpReason")
                }
                // P0-2 自救热修（2026-09-15 批次2）：三层全挂 → 解析规则大概率整体过时。
                // 每进程一次：运行时更新 yt-dlp 至最新（新 extractor 常能救回解析），成功清空全部熔断。
                // 2026-10：16KB 内存页设备上更新 yt-dlp 二进制解决不了 ffmpeg 依赖库 4KB 对齐问题，
                // 自救热修必败，跳过以免白耗流量与时间（安全写法：cast 失败/null 视为非 16KB 设备）
                if (DownloadEngineHealth.shouldAttemptSelfHeal() &&
                    (context.applicationContext as? com.ed.edqiu.TwitterDownloaderApp)?.is16kPageDevice != true
                ) {
                    runCatching { YoutubeDLService.updateYoutubeDL(context) }
                        .onSuccess {
                            DownloadEngineHealth.clearAll()
                            android.util.Log.i("DownloaderClient", "yt-dlp self-heal updated OK, breakers cleared")
                        }
                        .onFailure { android.util.Log.w("DownloaderClient", "yt-dlp self-heal failed", it) }
                }
                // 2026-11-11：fromSkip = 三层引擎全部被跳过、没有任何引擎真正执行——
                // 调用方对这类失败不烧 attemptCount（跳过 ≠ 真失败）
                LaunchResult.Failed(message, fromSkip = !anyEngineExecuted)
            }
        }
    }

    /** yt-dlp 回退下载：进度进下载中心、成功写 sidecar 供收件箱匹配。 */
    private suspend fun ytdlpFallback(
        url: String,
        tweetId: String,
        internalError: String?,
        uploader: String = "unknown",
        /** 2026-11-11 实证闸门基准：FXTwitter 渐进 mp4 最高变体码率（0 = 无基准，跳过闸门）。 */
        baselineBitrate: Int = 0,
    ): LaunchResult {
        return runCatching {
            val proxyUrl = ProxyPreferences(context).getProxySettings().toProxyUrl()
            val cookiePreferences = CookiePreferences(context)
            val cookieFile = if (cookiePreferences.hasCookies()) {
                YoutubeDLService.writeCookieFile(context, cookiePreferences.getAuthToken(), cookiePreferences.getCt0())
            } else null
            // 统一落盘到内部下载目录（与 InternalMediaDownloader 一致），确保收件箱监控能匹配到，
            // 避免自定义下载路径导致「下载了但收件箱显示未下载」
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "Download")
            outputDir.mkdirs()

            val task = DownloadTask(
                id = "ytdlp_$tweetId",
                url = url,
                title = "yt-dlp 回退下载",
                thumbnail = "",
                uploader = "unknown",
                formatId = "bestvideo+bestaudio/best",
                quality = "best",
                ext = "mp4",
                status = DownloadStatus.DOWNLOADING
            )
            DownloadTaskBus.add(task)
            DownloadCancellation.register(task.id)

            // 2026-11-11 回弹修复：`bestvideo+bestaudio/best` 会先下视频流再下音频流，
            // yt-dlp 每段各自 0→100 报数——旧实现直接透传，视频流下完瞬间进度从 ~100%
            // 回弹到 0% 重新爬（用户误读为「下载到一半重来」）。段切换检测（进度骤降
            // >10 个百分点）后按段数加权：显示值 = (已完成段×100 + 当前段进度) / 段数。
            var segCount = 1
            var segPeak = 0f
            val launchResult = YoutubeDLService.downloadVideo(
                url = url,
                // 2026-11-07 审计修复：`best` 只选预合并（video+audio 单文件）格式，X 的
                // 1080p+ 是 video-only HLS 自适应流——v1.9.10「直拉 1080p+」实际只拉到
                // 最高码率渐进 mp4。改为 video+audio 合并选择
                formatId = "bestvideo+bestaudio/best",
                outputDir = outputDir.absolutePath,
                onProgress = { progress, eta ->
                    if (progress < segPeak - 10f && segCount == 1) {
                        segCount = 2
                        segPeak = progress
                    } else if (progress > segPeak) {
                        segPeak = progress
                    }
                    val aggregated = ((segCount - 1) * 100f + progress) / segCount
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(progress = aggregated, etaSeconds = eta)
                    }
                },
                proxyUrl = proxyUrl,
                cookieFilePath = cookieFile,
                playlistIndex = null,
                processId = task.id
            ).fold(
                onSuccess = { rawPath ->
                    // 2026-10-25 产物规约化（对齐 MediaQualityUpgrader.performHlsUpgrade 修复）：
                    // yt-dlp 模板产物在 {uploader}/{标题}.mp4 子目录且名含推文无关标题——
                    // 扫描期解析依赖规约名，历史遗留曾因此产生重复条目。此处重命名/移动回
                    // 规约名 {uploader}_{tweetId}_{idx}_video_best.mp4（根目录），并清空残留子目录。
                    // 2026-11-07 审计修复：规约名已存在时递增 idx——旧实现无条件 delete 旧产物，
                    // 同推文先后两次 yt-dlp 下载（不同视频）会静默覆盖丢上一份
                    val rawFile = File(rawPath)
                    if (!rawFile.exists()) {
                        // 2026-11-07 审计修复：产物存在性防御——绝不给不存在的文件写
                        // sidecar/置 COMPLETED（旧链路会出现「下载成功但文件不存在」横跳）
                        val msg = "下载完成但产物不存在（${rawPath.take(120)}）"
                        DownloadTaskBus.updateTask(task.id) {
                            it.copy(status = DownloadStatus.FAILED, errorMessage = msg)
                        }
                        return@fold LaunchResult.Failed(msg)
                    }
                    // 2026-11-11 画质实证闸门：路由 yt-dlp ≠ 拉到了更高画质——hasHls 只是
                    // FXTwitter 的字符串级弱信号（m3u8 URL 匹配，标记可缺失可误报），无
                    // Cookie 时 HLS 上限甚至可能低于渐进 mp4。产物必须实测（码率 =
                    // fileSize*8/duration，含音频口径与渐进变体一致）严格优于基准才落位；
                    // 测不出（retriever 失败/损坏文件）一律按不达标处理。拒绝落位 → 走宽
                    // 路由回退直下 mp4——用户要的是肉眼可见的提升，不是「走过 HLS 通道」。
                    val measured = measureMediaSpecs(rawFile)
                    if (baselineBitrate > 0 && (measured == null || measured.first <= baselineBitrate)) {
                        val msg = "HLS 产物画质未超过渐进变体" +
                            "（实测 ${measured?.first?.toString() ?: "未知"}bps vs 基准 ${baselineBitrate}bps），回退直下 mp4"
                        android.util.Log.w("DownloaderClient", "HLS gate rejected tweet=$tweetId: $msg")
                        runCatching { rawFile.delete() }
                        DownloadTaskBus.updateTask(task.id) {
                            it.copy(status = DownloadStatus.FAILED, errorMessage = msg)
                        }
                        return@fold LaunchResult.Failed(msg)
                    }
                    // 落位：sidecar 画质标签用实测分辨率（短边）/码率反推的档位（实证值，不再写死 "best"）
                    val measuredQuality = bitrateToQualityLabel(
                        measured?.first ?: 0,
                        measured?.second ?: 0,
                        measured?.third ?: 0
                    )
                    android.util.Log.i(
                        "DownloaderClient",
                        "HLS 产物落位 tweet=$tweetId：实测 ${measured?.second ?: 0}x${measured?.third ?: 0} /" +
                            "${(measured?.first ?: 0) / 1000}kbps → 档位 $measuredQuality"
                    )
                    val safeUploader = uploader.replace(Regex("[/\\:*?\"<>|]"), "_").ifBlank { "unknown" }
                    val convention = run {
                        var idx = 1
                        var candidate = File(outputDir, "${safeUploader}_${tweetId}_${idx}_video_best.mp4")
                        while (candidate.exists() && candidate.absolutePath != rawFile.absolutePath) {
                            idx++
                            candidate = File(outputDir, "${safeUploader}_${tweetId}_${idx}_video_best.mp4")
                        }
                        candidate
                    }
                    val finalFile = if (convention.absolutePath != rawFile.absolutePath) {
                        runCatching {
                            if (convention.exists()) convention.delete()
                            if (rawFile.renameTo(convention)) {
                                rawFile.parentFile
                                    ?.takeIf { it.isDirectory && it.list()?.isEmpty() == true }
                                    ?.delete()
                                convention
                            } else {
                                rawFile.copyTo(convention, overwrite = true).also { rawFile.delete() }
                            }
                        }.getOrElse { rawFile }
                    } else rawFile
                    val path = finalFile.absolutePath
                    writeSidecar(File(path), url, tweetId, quality = measuredQuality)
                    // 2026-11-11 真机实测修复（收件箱 99% 卡住）：旧实现补拉图片**同步**跑在
                    // COMPLETED 更新之前——FXTwitter API + 封面补拉 10-30s 里，视频文件早已
                    // 落盘且被媒体库扫描收编（用户看到「下载完成」），但 Bus 还是
                    // ytdlp COMPLETED(100) + 补拉图片 DOWNLOADING(99) → 收件箱聚合
                    // (100+99)/2=99% 卡住，DB 落位同样被拖住。改为先置 COMPLETED、
                    // 补拉图片 fire-and-forget 到应用级 IO scope（失败静默，升级器兜底）。
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            outputPath = path,
                            progress = 100f,
                            completedAt = System.currentTimeMillis()
                        )
                    }
                    runCatching {
                        val appScope = (context.applicationContext as? com.ed.edqiu.EdqiuApplication)
                            ?.container?.globalIoScope
                        appScope?.launch {
                            runCatching {
                                val settings = ProxyPreferences(context).getProxySettings()
                                internalDownloader.downloadTweet(
                                    rawUrl = url,
                                    proxy = settings,
                                    allowHlsRouting = false,
                                    skipVideos = true,
                                    // 独立前缀：不进收件箱聚合正则（bgimg 不匹配任何归并 ID），
                                    // 补拉期间卡片已由 DB DOWNLOADED 接管，不回拉 99%
                                    taskPrefix = "bgimg",
                                )
                            }.onFailure {
                                android.util.Log.w("DownloaderClient", "混合推文补拉图片失败（不影响视频）：$tweetId", it)
                            }
                        }
                    }
                    LaunchResult.Downloaded(
                        listOf(
                            InternalMediaDownloader.DownloadedFile(
                                tweetId = tweetId,
                                filePath = path,
                                downloadedAt = System.currentTimeMillis(),
                                authorId = null,
                                authorName = null,
                                caption = null,
                                thumbnailUrl = null
                            )
                        )
                    )
                },
                onFailure = { error ->
                    val cancelled = error is DownloadCancelledException || DownloadCancellation.isCancelled(task.id)
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(
                            status = if (cancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED,
                            errorMessage = if (cancelled) "下载已取消" else (error.message ?: "yt-dlp 下载失败")
                        )
                    }
                    LaunchResult.Failed(if (cancelled) "下载已取消" else (error.message ?: "未知错误"))
                }
            )
            DownloadCancellation.unregister(task.id)
            // 2026-11-07 审计修复：明文 cookie 文件用完即删（旧实现会话期内常驻磁盘，
            // 清理仅在 App 启动时做一次）
            cookieFile?.let { runCatching { File(it).delete() } }
            launchResult
        }.getOrElse { error ->
            LaunchResult.Failed("yt-dlp 回退异常：${error.message ?: "未知错误"}")
        }
    }

    /**
     * 第三方 API 兜底（P0-1 第三层）：解析直链 → 复用内部引擎的多线程分段下载器落盘 + 写 sidecar。
     * 未配置端点时静默关闭（返回 Failed 附说明）；配置后仍受熔断/单推文亲和约束。
     * 注意：任务 ID 前缀 tpapi_ 不在收件箱实时进度归并正则内（xinvox_/ytdlp_），
     * 下载中心仍可见任务，收件箱卡片以最终落库状态为准。
     *
     * @return (结果, 是否真正执行过解析/下载)。未配置或被熔断/亲和挡住 = false，
     *   供 launchEngines 的 fromSkip 判定（跳过 ≠ 真失败）。
     */
    private suspend fun thirdPartyFallback(
        url: String,
        tweetId: String,
        proxy: ProxySettings?,
        manual: Boolean,
    ): Pair<LaunchResult, Boolean> {
        val apiPrefs = ThirdPartyApiPreferences(context)
        if (!apiPrefs.isConfigured) {
            return LaunchResult.Failed("第三方 API 兜底未配置（设置 → 网络与认证）") to false
        }
        val health = DownloadEngineHealth
        if (!health.isEngineAvailable(DownloadEngineHealth.ENGINE_THIRD_PARTY) ||
            health.failedBefore(tweetId, DownloadEngineHealth.ENGINE_THIRD_PARTY)
        ) {
            return LaunchResult.Failed("第三方 API 兜底暂不可用（熔断/近期失败）") to false
        }

        val medias = ThirdPartyApiResolver()
            .resolve(tweetId, apiPrefs.getEndpoint(), apiPrefs.getApiKey(), proxy)
            .fold(
                onSuccess = { it },
                onFailure = { error ->
                    if (manual) {
                        health.recordEngineFailure(DownloadEngineHealth.ENGINE_THIRD_PARTY, error.message)
                    }
                    health.markTweetFailed(tweetId, DownloadEngineHealth.ENGINE_THIRD_PARTY)
                    return LaunchResult.Failed("第三方 API 解析失败：${error.message ?: "未知错误"}") to true
                },
            )

        return withContext(Dispatchers.IO) {
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "Download")
            outputDir.mkdirs()
            val files = mutableListOf<InternalMediaDownloader.DownloadedFile>()
            var lastError: String? = null

            medias.forEachIndexed { index, media ->
                val mediaIndex = index + 1
                val target = File(outputDir, sanitizeName("tpapi_${tweetId}_$mediaIndex.${media.ext}"))
                val task = DownloadTask(
                    id = "tpapi_${tweetId}_$mediaIndex",
                    url = url,
                    title = "第三方 API 兜底下载",
                    thumbnail = media.thumbnail ?: media.url,
                    uploader = "unknown",
                    formatId = "thirdparty",
                    quality = media.quality,
                    ext = media.ext,
                    status = DownloadStatus.DOWNLOADING
                )
                DownloadTaskBus.add(task)
                DownloadCancellation.register(task.id)

                runCatching {
                    internalDownloader.downloadFile(media.url, target, proxy, taskId = task.id)
                    internalDownloader.writeSidecar(
                        mediaFile = target,
                        sourceUrl = url,
                        tweetId = tweetId,
                        uploader = "unknown",
                        authorName = null,
                        caption = null,
                        thumbnail = media.thumbnail,
                        quality = media.quality,
                        mediaIndex = mediaIndex,
                        mediaType = if (media.kind == "image") "IMAGE" else "VIDEO",
                        ext = media.ext
                    )
                }.onSuccess {
                    val downloadedAt = target.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                    files += InternalMediaDownloader.DownloadedFile(
                        tweetId = tweetId,
                        filePath = target.absolutePath,
                        downloadedAt = downloadedAt,
                        authorId = null,
                        authorName = null,
                        caption = null,
                        thumbnailUrl = media.thumbnail ?: media.url,
                        publishedAt = null
                    )
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            outputPath = target.absolutePath,
                            progress = 100f,
                            completedAt = downloadedAt
                        )
                    }
                }.onFailure { error ->
                    lastError = error.message
                    val cancelled = error is DownloadCancelledException || DownloadCancellation.isCancelled(task.id)
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(
                            status = if (cancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED,
                            errorMessage = if (cancelled) "下载已取消" else (error.message ?: "下载失败")
                        )
                    }
                }
                DownloadCancellation.unregister(task.id)
            }

            if (files.isNotEmpty()) {
                health.recordEngineSuccess(DownloadEngineHealth.ENGINE_THIRD_PARTY)
                health.markTweetSuccess(tweetId)
                LaunchResult.Downloaded(files) to true
            } else {
                if (manual) {
                    health.recordEngineFailure(DownloadEngineHealth.ENGINE_THIRD_PARTY, lastError)
                }
                health.markTweetFailed(tweetId, DownloadEngineHealth.ENGINE_THIRD_PARTY)
                LaunchResult.Failed("第三方 API 链接下载失败：${lastError ?: "未知错误"}") to true
            }
        }
    }

    private fun sanitizeName(name: String): String = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    /**
     * 实测产物规格（2026-11-11 画质实证闸门）：MediaMetadataRetriever 读时长与宽高，
     * 码率 = fileSize*8/duration（含音频，与 X 渐进 mp4 变体的 bitrate 字段口径一致，
     * 可直接对比）。读不出（损坏/不支持的封装）返回 null → 闸门按不达标处理。
     * 返回 (码率bps, 宽, 高)。
     */
    private fun measureMediaSpecs(file: File): Triple<Int, Int, Int>? {
        if (!file.exists() || file.length() <= 0L) return null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            if (durationMs == null || durationMs <= 0L) return null
            val bitrate = ((file.length() * 8.0) / (durationMs / 1000.0)).toInt()
            val width = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            Triple(bitrate, width, height)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 实测规格 → 档位标签（2026-11-11 升级为分辨率优先）：2160p 档（Premium+ 创作者
     * 4K60 内容）此前被 ≥2M→1080p 的纯码率映射吞掉——实测 8Mbps 的 4K 产物被标成
     * "1080p"，标签失真。现 height 实测值（MediaMetadataRetriever）优先判档，
     * height 读不出才退回码率映射。
     *
     * 2026-11-11 真机实测修正：判档用 min(宽, 高)——720×1280 竖屏视频的 height=1280
     * 被旧逻辑误标 "1080p"（实测 1112kbps 也印证它是 720p 档）。分辨率档位以短边为准。
     */
    private fun bitrateToQualityLabel(bitrate: Int, width: Int = 0, height: Int = 0): String {
        // 短边 = 有效分辨率（竖屏取宽、横屏取高；缺一边取另一边）
        val shortSide = if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height)
        return when {
            shortSide >= 2000 -> "2160p"
            shortSide >= 1000 -> "1080p"
            shortSide >= 700 -> "720p"
            shortSide >= 460 -> "480p"
            shortSide > 0 -> "360p"
            bitrate >= 2_000_000 -> "1080p"
            bitrate >= 832_000 -> "720p"
            bitrate >= 320_000 -> "480p"
            bitrate > 0 -> "360p"
            else -> "best"
        }
    }

    /**
     * 写 sidecar（.meta.json），供收件箱 DownloadMonitor 按 tweetId 配对；yt-dlp 路径无发布时间（JSON null 占位）。
     * 2026-11-11：quality 改由调用方传实测档位（实证闸门通过后反推），不再写死 "best"。
     */
    private fun writeSidecar(
        mediaFile: File,
        sourceUrl: String,
        tweetId: String,
        quality: String = "best"
    ) {
        val json = JSONObject()
            .put("url", sourceUrl)
            .put("tweetId", tweetId)
            .put("uploader", "unknown")
            .put("authorName", "")
            .put("title", "")
            .put("thumbnail", "")
            .put("quality", quality)
            .put("formatId", "ytdlp")
            .put("mediaIndex", 1)
            .put("mediaType", "VIDEO")
            .put("ext", "mp4")
            .put("publishedAt", JSONObject.NULL)
        File(mediaFile.absolutePath + META_SUFFIX).writeText(json.toString())
    }

    private companion object {
        const val META_SUFFIX = ".meta.json"
    }
}
