package com.ed.edqiu.data.repository

import android.content.Context
import android.os.Environment
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
 * - 引擎熔断：单引擎连续 3 次失败 → 冷却 10 分钟直接跳过，不再每次下载先撞一遍必死层；
 * - 单推文亲和：24h 内失败过的引擎不再重复尝试（自动重试直达可用引擎）；
 * - TweetGone 是确定性知识：永久失败不回退、不计熔断。
 *
 * 不再拉起外部下载器 Activity（旧逻辑 startActivity 自己包名 MainActivity 会把 App
 * 顶回前台造成「下载无反应/闪跳」）。
 */
class DownloaderClient(private val context: Context) {

    sealed interface LaunchResult {
        data class Downloaded(val files: List<InternalMediaDownloader.DownloadedFile>) : LaunchResult
        data object LaunchedExternal : LaunchResult
        data class Failed(val reason: String) : LaunchResult
        /** 推文已不存在（被删/私密/未公开）——永久失败，不再回退、不再重试。 */
        data object TweetGone : LaunchResult
    }

    private val internalDownloader = InternalMediaDownloader(context)

    suspend fun launch(rawUrl: String, proxy: ProxySettings? = null): LaunchResult {
        val tweetId = TweetIdExtractor.fromUrl(rawUrl)
            ?: return LaunchResult.Failed("链接中没有有效的推文 ID")
        val normalizedUrl = "https://x.com/i/status/$tweetId"

        // 智能代理（2026-09-14 网络路径优化）：调用方未显式传 proxy 时，
        // 读取用户在设置里配置的代理（2026-09-15 P0-3 起支持 http / socks5 双类型）。
        val effectiveProxy = proxy ?: ProxyPreferences(context).getProxySettings().takeIf { it.enabled }
        val health = DownloadEngineHealth

        // ---------- 引擎一：FXTwitter ----------
        var internalError: String? = null
        if (health.isEngineAvailable(DownloadEngineHealth.ENGINE_FXTWITTER) &&
            !health.failedBefore(tweetId, DownloadEngineHealth.ENGINE_FXTWITTER)
        ) {
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
                        return ytdlpFallback(normalizedUrl, tweetId, error.message ?: "存在 HLS 高画质源")
                    }
                    health.recordEngineFailure(DownloadEngineHealth.ENGINE_FXTWITTER, error.message)
                    health.markTweetFailed(tweetId, DownloadEngineHealth.ENGINE_FXTWITTER)
                    error.message ?: "内部下载失败"
                },
            )
        } else {
            internalError = "FXTwitter 引擎熔断中${health.engineBreakerNote(DownloadEngineHealth.ENGINE_FXTWITTER) ?: ""}"
        }

        // ---------- 引擎二：yt-dlp（代理 + Cookie） ----------
        var ytdlpError: String? = null
        if (health.isEngineAvailable(DownloadEngineHealth.ENGINE_YTDLP) &&
            !health.failedBefore(tweetId, DownloadEngineHealth.ENGINE_YTDLP)
        ) {
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
        return when (val thirdParty = thirdPartyFallback(normalizedUrl, tweetId, effectiveProxy)) {
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
                if (DownloadEngineHealth.shouldAttemptSelfHeal()) {
                    runCatching { YoutubeDLService.updateYoutubeDL(context) }
                        .onSuccess {
                            DownloadEngineHealth.clearAll()
                            android.util.Log.i("DownloaderClient", "yt-dlp self-heal updated OK, breakers cleared")
                        }
                        .onFailure { android.util.Log.w("DownloaderClient", "yt-dlp self-heal failed", it) }
                }
                LaunchResult.Failed(message)
            }
        }
    }

    /** yt-dlp 回退下载：进度进下载中心、成功写 sidecar 供收件箱匹配。 */
    private suspend fun ytdlpFallback(url: String, tweetId: String, internalError: String?): LaunchResult {
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
                formatId = "best",
                quality = "best",
                ext = "mp4",
                status = DownloadStatus.DOWNLOADING
            )
            DownloadTaskBus.add(task)

            YoutubeDLService.downloadVideo(
                url = url,
                formatId = "best",
                outputDir = outputDir.absolutePath,
                onProgress = { progress, eta ->
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(progress = progress, etaSeconds = eta)
                    }
                },
                proxyUrl = proxyUrl,
                cookieFilePath = cookieFile,
                playlistIndex = null
            ).fold(
                onSuccess = { path ->
                    writeSidecar(File(path), url, tweetId)
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            outputPath = path,
                            progress = 100f,
                            completedAt = System.currentTimeMillis()
                        )
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
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(status = DownloadStatus.FAILED, errorMessage = error.message ?: "yt-dlp 下载失败")
                    }
                    LaunchResult.Failed(error.message ?: "未知错误")
                }
            )
        }.getOrElse { error ->
            LaunchResult.Failed("yt-dlp 回退异常：${error.message ?: "未知错误"}")
        }
    }

    /**
     * 第三方 API 兜底（P0-1 第三层）：解析直链 → 复用内部引擎的断点续传下载器落盘 + 写 sidecar。
     * 未配置端点时静默关闭（返回 Failed 附说明）；配置后仍受熔断/单推文亲和约束。
     * 注意：任务 ID 前缀 tpapi_ 不在收件箱实时进度归并正则内（xinvox_/ytdlp_），
     * 下载中心仍可见任务，收件箱卡片以最终落库状态为准。
     */
    private suspend fun thirdPartyFallback(url: String, tweetId: String, proxy: ProxySettings?): LaunchResult {
        val apiPrefs = ThirdPartyApiPreferences(context)
        if (!apiPrefs.isConfigured) {
            return LaunchResult.Failed("第三方 API 兜底未配置（设置 → 网络与认证）")
        }
        val health = DownloadEngineHealth
        if (!health.isEngineAvailable(DownloadEngineHealth.ENGINE_THIRD_PARTY) ||
            health.failedBefore(tweetId, DownloadEngineHealth.ENGINE_THIRD_PARTY)
        ) {
            return LaunchResult.Failed("第三方 API 兜底暂不可用（熔断/近期失败）")
        }

        val medias = ThirdPartyApiResolver()
            .resolve(tweetId, apiPrefs.getEndpoint(), apiPrefs.getApiKey(), proxy)
            .fold(
                onSuccess = { it },
                onFailure = { error ->
                    health.recordEngineFailure(DownloadEngineHealth.ENGINE_THIRD_PARTY, error.message)
                    health.markTweetFailed(tweetId, DownloadEngineHealth.ENGINE_THIRD_PARTY)
                    return LaunchResult.Failed("第三方 API 解析失败：${error.message ?: "未知错误"}")
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
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(status = DownloadStatus.FAILED, errorMessage = error.message ?: "下载失败")
                    }
                }
            }

            if (files.isNotEmpty()) {
                health.recordEngineSuccess(DownloadEngineHealth.ENGINE_THIRD_PARTY)
                health.markTweetSuccess(tweetId)
                LaunchResult.Downloaded(files)
            } else {
                health.recordEngineFailure(DownloadEngineHealth.ENGINE_THIRD_PARTY, lastError)
                health.markTweetFailed(tweetId, DownloadEngineHealth.ENGINE_THIRD_PARTY)
                LaunchResult.Failed("第三方 API 链接下载失败：${lastError ?: "未知错误"}")
            }
        }
    }

    private fun sanitizeName(name: String): String = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    /** 写 sidecar（.meta.json），供收件箱 DownloadMonitor 按 tweetId 配对；yt-dlp 路径无发布时间（JSON null 占位）。 */
    private fun writeSidecar(mediaFile: File, sourceUrl: String, tweetId: String) {
        val json = JSONObject()
            .put("url", sourceUrl)
            .put("tweetId", tweetId)
            .put("uploader", "unknown")
            .put("authorName", "")
            .put("title", "")
            .put("thumbnail", "")
            .put("quality", "best")
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
