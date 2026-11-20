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
    // 2026-11-04 裸链容错：scheme 变为可选（x.com/user/status/123 不带 https:// 也认），
    // 前置负向断言 (?<![A-Za-z0-9.]) 防止在 fox.com / box.com 等域名内部误命中 x.com
    private val STATUS_URL = Pattern.compile(
        """(?i)(?<![A-Za-z0-9.])(?:https?://)?(?:(?:www|mobile)\.)?(?:x\.com|twitter\.com)/(?:i/(?:web/)?status|[A-Za-z0-9_]+/status)/(\d{11,25})(?!\d)"""
    )
    // 2026-10：\b 在下划线相邻处不成立（Twitter 屏幕名带下划线极常见）——数字边界改前后瞻
    private val RAW_ID = Pattern.compile("""(?<!\d)\d{11,25}(?!\d)""")

    /** 从一段文本中的严格 Twitter/X status URL 提取 tweet ID。 */
    fun fromUrl(url: String): String? = findStatusId(url)

    /** 从分享正文或剪贴板文本中提取并规范化第一条 status URL。 */
    fun canonicalUrlFromText(text: String?): String? {
        val tweetId = findStatusId(text) ?: return null
        return "https://x.com/i/status/$tweetId"
    }

    // 2026-11-04 零宽字符清洗：部分 App 复制的正文夹带不可见字符（U+200B-U+200D/U+FEFF/U+2060），
    // 会把 URL 拆断导致正则失配，提取前统一剔除
    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF\\u2060]")

    private fun findStatusId(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val bounded = ZERO_WIDTH.replace(text.take(MAX_INPUT_LENGTH), "")
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

    /**
     * 严格版（P3 审计修复 2026-11-21）：仅认下载器规约名（`{uploader}_{tweetId}_{序号}_...`）
     * 的第二段纯数字，**无裸数字兜底**——兜底版会把监控目录里带 epoch 毫秒时间戳等
     * 11-25 位数字串的任意文件（`1731800000000.mp4`）赋伪 tweetId，扫进收件箱成
     * 指向不存在推文的幽灵条目。供扫描导入路径使用；媒体库内已有条目的回填
     * （升级器/发布时间/播放器）仍用 [fromFileName]（文件已是合法媒体）。
     */
    fun fromConventionFileName(fileName: String): String? {
        val candidate = fileName.split("_").getOrNull(1) ?: return null
        return candidate.takeIf { RAW_ID.matcher(it).matches() }
    }

    /** 判定文本中是否包含严格匹配的 Twitter/X status URL。 */
}
