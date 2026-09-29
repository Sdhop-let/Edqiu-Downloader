package com.ed.edqiu.domain

import java.util.Locale

/**
 * 从一段文本（剪贴板 / 分享正文 / 批量粘贴）中批量提取 Twitter/X status 链接。
 *
 * 与 [TweetIdExtractor] 的单链接语义保持一致：
 * - 仅接受严格的 status URL（x.com / twitter.com / www / mobile，`/status/` 或 `/i/status/`）；
 * - 统一规范化为 `https://x.com/i/status/{tweetId}`；
 * - 按 tweetId 去重（同一条推文的不同平台变体只保留第一条）；
 * - 保留原始 URL（含 query 参数），供 UI 展示与详情回溯。
 */
object BatchTweetUrlExtractor {

    private const val MAX_INPUT_LENGTH = 8_192

    private val STATUS_URL = Regex(
        pattern = """(?i)https?://(?:(?:www|mobile)\.)?(?:x\.com|twitter\.com)/(?:i/(?:web/)?status|[A-Za-z0-9_]+/status)/(\d{11,25})(?!\d)""",
        options = setOf(RegexOption.IGNORE_CASE),
    )

    /** query 部分允许的字符（不含空白与全角标点，URL 天然在它们处截断）。 */
    private val QUERY_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9') +
        listOf('=', '&', '%', '-', '_', '+', '~', '.', '*', '\'', '(', ')', ';', ':', '@', '$', '!', ',', '/')

    /** 一条提取结果：原始链接（含 query）+ 规范化链接 + tweetId。 */
    data class TweetUrlPair(
        val originalUrl: String,
        val normalizedUrl: String,
        val tweetId: String,
    )

    /** 批量提取并去重，返回规范化链接列表（按出现顺序）。 */
    fun extract(text: String?): List<String> =
        extractWithOriginals(text).map { it.normalizedUrl }

    /** 批量提取，保留原始链接，按 tweetId 去重。 */
    fun extractWithOriginals(text: String?): List<TweetUrlPair> {
        if (text.isNullOrBlank()) return emptyList()
        val bounded = text.take(MAX_INPUT_LENGTH)
        val pairs = mutableListOf<TweetUrlPair>()
        for (match in STATUS_URL.findAll(bounded)) {
            val tweetId = match.groupValues[1]
            pairs += TweetUrlPair(
                originalUrl = originalUrlOf(bounded, match),
                normalizedUrl = "https://x.com/i/status/$tweetId",
                tweetId = tweetId,
            )
        }
        return pairs.distinctBy { it.tweetId }
    }

    /** 从匹配位置向后吞掉 query 部分，还原用户复制的原始 URL。 */
    private fun originalUrlOf(text: String, match: MatchResult): String {
        var end = match.range.last + 1
        if (end < text.length && text[end] == '?') {
            end++
            while (end < text.length && text[end] in QUERY_CHARS) end++
        }
        return text.substring(match.range.first, end)
    }
}
