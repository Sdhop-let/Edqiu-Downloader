package com.ed.edqiu.ui.screens

import com.ed.edqiu.data.database.DownloadHistoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 媒体库分组（2026-09-15 排序准则）。
 *
 * 旧逻辑按「单条推文」分组：同一作者同一天连续发的帖子（不同 tweetId）会拆成
 * 多个组，用户观感是"同一天发布同一作者分成了好几组"。
 *
 * 新逻辑：分组单位升级为「作者 + 发布日」——
 * - 同一作者同一天发布的所有帖子合成一个组（组内多条推文的媒体连续排列）；
 * - 发布日取 publishedAt（推文发布时间），无发布时间的旧记录回退 completedAt 的日期；
 * - 组间顺序 = 组内最新媒体时间（publishedAt ?: completedAt）倒序，与列表排序一致；
 * - 组内顺序保持传入列表的既有排序。
 */
data class LibraryGroup(
    /** 作者显示名（列表首个条目的 uploader 原文）；无法识别时为 "未知作者"。 */
    val author: String,
    /** 发布日标签（组内最新媒体的日期，MM-dd）。 */
    val dayLabel: String,
    /** 组内最新媒体时间戳（publishedAt ?: completedAt 最大值），组间排序用。 */
    val sortAt: Long,
    val items: List<DownloadHistoryEntity>,
)

private val DAY_LABEL_FORMAT = SimpleDateFormat("MM-dd", Locale.getDefault())

fun groupLibraryByAuthorDay(items: List<DownloadHistoryEntity>): List<LibraryGroup> {
    data class GroupKey(val author: String, val day: String)

    val groups = mutableListOf<LibraryGroup>()
    val groupIndexByKey = mutableMapOf<GroupKey, Int>()

    items.forEach { item ->
        val author = item.uploader.takeIf { it.isNotBlank() } ?: "未知作者"
        val sortAt = item.publishedAt ?: item.completedAt
        val key = GroupKey(author = author.lowercase(), day = DAY_LABEL_FORMAT.format(Date(sortAt)))
        val existingIndex = groupIndexByKey[key]
        if (existingIndex == null) {
            groupIndexByKey[key] = groups.size
            groups += LibraryGroup(
                author = author,
                dayLabel = key.day,
                sortAt = sortAt,
                items = listOf(item)
            )
        } else {
            val group = groups[existingIndex]
            groups[existingIndex] = group.copy(
                // 组内最新媒体时间决定组排序与组头日期
                sortAt = maxOf(group.sortAt, sortAt),
                items = group.items + item
            )
        }
    }

    // 组间按组内最新媒体时间倒序：最新发布的组在最上（对齐"最新在最上"的直觉）
    return groups.sortedByDescending { it.sortAt }
}

/** 兼容旧调用点的轻量包装（MediaLibraryScreen 仍有调用；推文分组逻辑已被「作者+发布日」分组取代）。 */
fun groupLibraryByTweet(items: List<DownloadHistoryEntity>): List<LibraryGroup> =
    groupLibraryByAuthorDay(items)

/**
 * 按下载完成日分组（2026-10-24 排序重构）：今天 / 昨天 / MM月dd日 / 跨年 yyyy年MM月dd日。
 *
 * 用户心智模型=「下载管理器」：刚下完的在最上。旧排序按发布时间（作者+发布日分组）
 * 导致「今天下载的老作品沉底」（DB 实证：发布于 7 月的推文当天下载却排在 10 月发布
 * 的作品之下）——发布时间排序保留为可切换项，下载时间成为默认。
 * 组内按 completedAt 倒序；组间按日倒序。author 置空（分组头只渲染日期标签）。
 */
fun groupLibraryByDownloadDay(items: List<DownloadHistoryEntity>): List<LibraryGroup> {
    val cal = java.util.Calendar.getInstance()
    val todayStart = cal.apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterdayStart = todayStart - 24 * 60 * 60 * 1000L
    val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    fun labelOf(ts: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        return when {
            ts >= todayStart -> "今天"
            ts >= yesterdayStart -> "昨天"
            c.get(java.util.Calendar.YEAR) == thisYear ->
                "${c.get(java.util.Calendar.MONTH) + 1}月${c.get(java.util.Calendar.DAY_OF_MONTH)}日"
            else -> "${c.get(java.util.Calendar.YEAR)}年${c.get(java.util.Calendar.MONTH) + 1}月${c.get(java.util.Calendar.DAY_OF_MONTH)}日"
        }
    }

    data class DayKey(val dayStart: Long, val label: String)
    val groups = mutableListOf<LibraryGroup>()
    val indexByDay = mutableMapOf<DayKey, Int>()
    items.sortedByDescending { it.completedAt }.forEach { item ->
        val ts = item.completedAt
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val dayStart = c.apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val key = DayKey(dayStart, labelOf(ts))
        val existing = indexByDay[key]
        if (existing == null) {
            indexByDay[key] = groups.size
            groups += LibraryGroup(author = "", dayLabel = key.label, sortAt = dayStart, items = listOf(item))
        } else {
            val g = groups[existing]
            groups[existing] = g.copy(items = g.items + item)
        }
    }
    return groups.sortedByDescending { it.sortAt }
}
