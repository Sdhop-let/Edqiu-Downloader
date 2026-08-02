package com.ed.edqiu.domain

import java.util.regex.Pattern

/**
 * 从推特/X 链接或文件名中提取 tweet（status）ID。
 *
 * 推特链接形态：
 *   https://twitter.com/<user>/status/<id>
 *   https://x.com/<user>/status/<id>
 * 文件名形态（下载器输出）：<uploader>_<tweetId>_<mediaIndex>_...
 *
 * tweet ID 为 11~25 位数字，与下载器 DirectoryScanner 的判重规则保持一致。
 */
object TweetIdExtractor {

    private const val MAX_INPUT_LENGTH = 8_192
    private val STATUS_URL = Pattern.compile(
        """(?i)https?://(?:(?:www|mobile)\.)?(?:x\.com|twitter\.com)/(?:i/(?:web/)?status|[A-Za-z0-9_]+/status)/(\d{11,25})(?!\d)"""
    )
    private val RAW_ID = Pattern.compile("""\b\d{11,25}\b""")

    /** 从一段文本中的严格 Twitter/X status URL 提取 tweet ID。 */
    fun fromUrl(url: String): String? = findStatusId(url)

    /** 从分享正文或剪贴板文本中提取并规范化第一条 status URL。 */
    fun canonicalUrlFromText(text: String?): String? {
        val tweetId = findStatusId(text) ?: return null
        return "https://x.com/i/status/$tweetId"
    }

    private fun findStatusId(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val bounded = text.take(MAX_INPUT_LENGTH)
        val matcher = STATUS_URL.matcher(bounded)
        return if (matcher.find()) matcher.group(1) else null
    }

    /** 从下载器输出文件名提取 tweet ID（第二个下划线分段，须为纯数字）。 */
    fun fromFileName(fileName: String): String? {
        val segments = fileName.split("_")
        if (segments.size >= 2) {
            val candidate = segments[1]
            if (RAW_ID.matcher(candidate).matches()) return candidate
        }
        val matcher = RAW_ID.matcher(fileName)
        return if (matcher.find()) matcher.group(0) else null
    }

    /** 判定文本中是否包含严格匹配的 Twitter/X status URL。 */
    fun isTwitterUrl(text: String?): Boolean = findStatusId(text) != null
}
