package com.ed.edqiu.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 作者订阅（2026-09-15 v2 批次4：P2-1 作者订阅自动下载）。
 *
 * 订阅键 = screenName（平台 handle，无 @ 前缀，保留原大小写）。
 * 2026-10 多平台（真正作品订阅）：+platform 列（twitter / bluesky / instagram）——
 * - twitter：X GraphQL 双通道（data/xapi，guest/Cookie + 远程 manifest 轮换；
 *   v1.9.3 重建——旧 yt-dlp 用户页通道是死通道，已废弃）；
 * - bluesky：公共 AppView API（public.api.bsky.app，无需登录）检测最新 post rkey；
 * - instagram：yt-dlp 用户页（实验性，风控严，失败静默）。
 * 轮询：SubscriptionManager 每 25 分钟取 lastCheckedAt 最旧的 ≤3 个 enabled 订阅，
 * 新作品走订阅线 capture（不进收件箱）+ 用户点按下载。
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey
    val screenName: String,
    /** 作品来源平台：twitter（默认，存量）/ bluesky / instagram。 */
    val platform: String = "twitter",
    /** 订阅中（true）/已暂停（false）。 */
    val enabled: Boolean = true,
    /** 上次轮询时间（epoch ms），轮询调度按最旧优先。 */
    val lastCheckedAt: Long? = null,
    /** 上次发现新作品时间（epoch ms，展示用）。 */
    val lastVideoAt: Long? = null,
    /**
     * 最近一次检测失败/数据降级的原因（null=正常）。
     * 2026-10-17：修复 twitter 通道静默失败 bug——失败原因落库，订阅页直接展示，
     * 不再把「通道挂了」伪装成「暂无新作品」。
     */
    val lastError: String? = null,
    /** lastError 写入时间（epoch ms，展示用）。 */
    val lastErrorAt: Long? = null,
    /**
     * 订阅起始检测日期（epoch ms，null = 不限/从订阅日起）。
     * 2026-10-17：用户自定义（如 10-17）——轮询只把**该日期之后发布**的推文视为新作品，
     * 之前的旧帖不再涌进收件箱。
     */
    val startFromAt: Long? = null,
    val createdAt: Long
) {
    companion object {
        const val PLATFORM_TWITTER = "twitter"
        const val PLATFORM_BLUESKY = "bluesky"
        const val PLATFORM_INSTAGRAM = "instagram"
    }
}
