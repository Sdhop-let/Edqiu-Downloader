package com.ed.edqiu.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条已捕获的推特视频链接（收件箱条目）。
 *
 * 主键使用 tweetId —— 天然实现去重：同一推文无论捕获几次只存一条。
 * 作者、文案、缩略图在捕获时尽力从 fxtwitter 拉取；若拉取失败，
 * 待下载后在刷新阶段从 .meta.json 补全。
 *
 * @param tweetId     推文 ID（主键，去重依据）
 * @param rawUrl      完整原始链接，永不截断
 * @param authorId    作者 @screen_name（如 @tech_blogger）
 * @param authorName  作者显示名
 * @param caption     文案
 * @param thumbnailUrl 缩略图/封面地址
 * @param savedAt     捕获（复制）时间，毫秒时间戳
 * @param status      当前状态（刷新时重算）
 * @param filePath    命中下载文件后的可读名称（用于「打开文件」）
 * @param downloadedAt 命中下载文件的时间戳
 * @param attemptCount 下载器启动尝试次数
 * @param lastAttemptAt 最近一次尝试时间
 * @param lastError 最近一次启动失败原因
 * @param nextRetryAt 自动重试时间；null 表示无需或不再自动重试
 * @param origin 来源线（2026-10-17 双线隔离）：[ORIGIN_USER] 用户分享/手动捕获，
 *   [ORIGIN_SUBSCRIPTION] 作者订阅轮询自动捕获。两线的下载失败记账、重试节奏、
 *   并发调度互相隔离（订阅线不再打全局熔断器、不进全局重试风暴）。
 */
// 2026-10 整改：重试轮询（status=FAILED AND next_retry_at<=now）此前全表扫描
@Entity(
    tableName = "saved_links",
    indices = [Index(value = ["status", "next_retry_at"])]
)
data class SavedLink(
    @PrimaryKey
    val tweetId: String,

    @ColumnInfo(name = "raw_url")
    val rawUrl: String,

    @ColumnInfo(name = "author_id")
    val authorId: String? = null,

    @ColumnInfo(name = "author_name")
    val authorName: String? = null,

    @ColumnInfo(name = "caption")
    val caption: String? = null,

    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,

    @ColumnInfo(name = "author_bio")
    val authorBio: String? = null,

    @ColumnInfo(name = "saved_at")
    val savedAt: Long,

    @ColumnInfo(name = "status")
    val status: LinkStatus = LinkStatus.PENDING,

    @ColumnInfo(name = "file_path")
    val filePath: String? = null,

    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long? = null,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long? = null,

    /** 推文发布时间（epoch ms，来自下载 sidecar；2026-09-15 作者页按发布时间排序）。 */
    @ColumnInfo(name = "published_at")
    val publishedAt: Long? = null,

    /** 来源线：user（默认，存量）/ subscription（作者订阅自动捕获）。 */
    @ColumnInfo(name = "origin", defaultValue = ORIGIN_USER)
    val origin: String = ORIGIN_USER
) {
    companion object {
        /** 用户分享/手动捕获线。 */
        const val ORIGIN_USER = "user"
        /** 作者订阅自动捕获线（独立下载队列/重试/失败记账）。 */
        const val ORIGIN_SUBSCRIPTION = "subscription"
    }
}
