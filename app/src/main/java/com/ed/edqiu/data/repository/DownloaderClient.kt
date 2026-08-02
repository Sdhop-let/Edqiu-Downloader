package com.ed.edqiu.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ed.edqiu.domain.TweetIdExtractor
import com.ed.edqiu.downloader.InternalMediaDownloader

class DownloaderClient(private val context: Context) {

    sealed interface LaunchResult {
        data class Downloaded(val files: List<InternalMediaDownloader.DownloadedFile>) : LaunchResult
        data object LaunchedExternal : LaunchResult
        data class Failed(val reason: String) : LaunchResult
    }

    private val internalDownloader = InternalMediaDownloader(context)

    suspend fun launch(rawUrl: String): LaunchResult {
        val tweetId = TweetIdExtractor.fromUrl(rawUrl)
            ?: return LaunchResult.Failed("链接中没有有效的推文 ID")
        val normalizedUrl = "https://x.com/i/status/$tweetId"

        internalDownloader.downloadTweet(normalizedUrl).onSuccess { files ->
            if (files.isNotEmpty()) return LaunchResult.Downloaded(files)
        }

        return launchExternal(normalizedUrl)
    }

    private fun launchExternal(normalizedUrl: String): LaunchResult {
        val intent = Intent(DOWNLOAD_ACTION, Uri.parse(normalizedUrl)).apply {
            setClassName(DOWNLOADER_PACKAGE, DOWNLOADER_ACTIVITY)
            putExtra(EXTRA_TWEET_URL, normalizedUrl)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        if (intent.resolveActivity(context.packageManager) == null) {
            return LaunchResult.Failed("内部下载失败，且未检测到可用的 Edqiu 下载器")
        }

        return runCatching {
            context.startActivity(intent)
            LaunchResult.LaunchedExternal
        }.getOrElse { error ->
            LaunchResult.Failed(error.message ?: "无法启动 Edqiu 下载器")
        }
    }

    private companion object {
        const val DOWNLOADER_PACKAGE = "com.ed.twitterdownloader"
        const val DOWNLOADER_ACTIVITY = "$DOWNLOADER_PACKAGE.MainActivity"
        const val DOWNLOAD_ACTION = "$DOWNLOADER_PACKAGE.action.DOWNLOAD_TWEET"
        const val EXTRA_TWEET_URL = "$DOWNLOADER_PACKAGE.extra.TWEET_URL"
    }
}
