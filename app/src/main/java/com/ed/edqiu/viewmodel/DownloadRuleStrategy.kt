package com.ed.edqiu.viewmodel

/**
 * 同一作者重复下载时的目录/命名策略。
 *
 * - [MULTI_CHANNEL]：按作者分频道目录（`{uploader}/…`），适合同作者的多条独立视频；
 * - [SEQUENTIAL]：同目录顺序编号（`{uploader}_002_…`），适合连续剧/合集式更新。
 */
enum class DownloadRuleStrategy { MULTI_CHANNEL, SEQUENTIAL }

/** 下载规则判定所需的上下文信息。 */
data class DownloadRuleContext(
    val uploader: String,
    val title: String,
    val isSameAuthor: Boolean,
    val hasMultipleMediaItems: Boolean,
    val formatCount: Int,
    val recentAuthorNames: Set<String>,
)

/** 标题中的连续剧特征：续集 / 第 N 集(话/期/季) / (P|Part|EP) 编号。 */
private val SERIES_TITLE_HINT = Regex(
    """(续|第\s*[0-9一二三四五六七八九十]+\s*[集话話期部季]|[（(]?[Pp](?:art)?\s*[.．]?\s*\d+[）)]?|[Ee][Pp]\s*[.．]?\s*\d+)"""
)

/**
 * 判定下载策略：
 * - 只有「同作者 + 单条媒体 + 单格式 + 标题带连续剧特征」才走 [DownloadRuleStrategy.SEQUENTIAL]；
 * - 同作者的多媒体/多格式普通投稿走 [DownloadRuleStrategy.MULTI_CHANNEL]；
 * - 非同作者（一次性下载）不做频道聚合，走 [DownloadRuleStrategy.SEQUENTIAL]。
 */
fun decideDownloadRule(context: DownloadRuleContext): DownloadRuleStrategy {
    val isSeriesContinuation = !context.hasMultipleMediaItems &&
        context.formatCount <= 1 &&
        SERIES_TITLE_HINT.containsMatchIn(context.title)
    val isKnownRecurringAuthor = context.isSameAuthor ||
        context.uploader in context.recentAuthorNames
    return if (isKnownRecurringAuthor && !isSeriesContinuation) {
        DownloadRuleStrategy.MULTI_CHANNEL
    } else {
        DownloadRuleStrategy.SEQUENTIAL
    }
}
