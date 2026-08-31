package com.ed.edqiu.data.repository

import android.content.Context
import android.os.Environment
import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.model.ProxySettings
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.downloader.InternalMediaDownloader
import com.ed.edqiu.downloader.TweetGoneException
import com.ed.edqiu.domain.TweetIdExtractor
import com.ed.edqiu.service.YoutubeDLService
import org.json.JSONObject
import java.io.File

/**
 * 收件箱下载客户端：双引擎回退。
 *
 * - 引擎一：内部 FXTwitter 直连下载（[InternalMediaDownloader]），走可选本地代理；
 * - 引擎二：失败后回退 yt-dlp（[YoutubeDLService]，带代理 + Cookie），下载成功后写
 *   `.meta.json` sidecar（含 tweetId），供收件箱 [DownloadMonitor] 扫描匹配；
 * - 两引擎均失败才返回 [LaunchResult.Failed]，附带两段失败原因。
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

        // 引擎一：内部 FXTwitter 直连下载
        val internalError = internalDownloader.downloadTweet(normalizedUrl, proxy).fold(
            onSuccess = { files ->
                if (files.isNotEmpty()) return LaunchResult.Downloaded(files)
                "推文中没有可下载的媒体"
            },
            onFailure = { error ->
                // 推文已不存在：永久失败，跳过 yt-dlp 回退（推文没了，回退必败，纯浪费流量）
                if (error is TweetGoneException) return LaunchResult.TweetGone
                error.message ?: "内部下载失败"
            },
        )

        // 引擎二：yt-dlp 回退（代理 + Cookie）
        return ytdlpFallback(normalizedUrl, tweetId, internalError)
    }

    /** yt-dlp 回退下载：进度进下载中心、成功写 sidecar 供收件箱匹配。 */
    private suspend fun ytdlpFallback(url: String, tweetId: String, internalError: String): LaunchResult {
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
                    LaunchResult.Failed("内部下载失败（$internalError）；yt-dlp 回退失败：${error.message ?: "未知错误"}")
                }
            )
        }.getOrElse { error ->
            LaunchResult.Failed("yt-dlp 回退异常：${error.message ?: "未知错误"}")
        }
    }

    /** 写 sidecar（.meta.json），供收件箱 DownloadMonitor 按 tweetId 配对。 */
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
        File(mediaFile.absolutePath + META_SUFFIX).writeText(json.toString())
    }

    private companion object {
        const val META_SUFFIX = ".meta.json"
    }
}
