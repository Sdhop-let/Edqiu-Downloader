package com.ed.twitterdownloader

import android.content.Intent
import android.net.Uri

object DownloadIntentContract {
    const val ACTION_DOWNLOAD_TWEET = "com.ed.twitterdownload.action.DOWNLOAD_TWEET"
    const val EXTRA_TWEET_URL = "com.ed.twitterdownload.extra.TWEET_URL"
    private const val LEGACY_EXTRA_TWEET_URL = "com.ed.twitterdownloader.extra.TWEET_URL"

    private val tweetIdRegex = Regex("/status/(\\d{11,25})", RegexOption.IGNORE_CASE)
    private val allowedHosts = setOf("x.com", "twitter.com")

    fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        return sequenceOf(
            intent.dataString,
            intent.getStringExtra(EXTRA_TWEET_URL),
            intent.getStringExtra(LEGACY_EXTRA_TWEET_URL)
        ).mapNotNull(::normalizeUrl).firstOrNull()
    }

    fun normalizeUrl(rawUrl: String?): String? {
        val value = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("mobile.")
        if (scheme !in setOf("http", "https") || host !in allowedHosts) return null
        val tweetId = tweetIdRegex.find(uri.path.orEmpty())?.groupValues?.getOrNull(1) ?: return null
        return "https://x.com/i/status/$tweetId"
    }
}

data class ExternalDownloadRequest(
    val requestId: Long,
    val url: String
)

