package com.ed.edqiu.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 作者订阅（2026-09-15 v2 批次4：P2-1 作者订阅自动下载）。
 *
 * 订阅键 = screenName（X handle，无 @ 前缀，保留原大小写）。
 * 轮询：SubscriptionManager 每 25 分钟取 lastCheckedAt 最旧的 ≤3 个 enabled 订阅，
 * 经 yt-dlp 用户页（--flat-playlist）检测最新推文 ID，新 ID 走收件箱 capture + 三层引擎链下载。
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey
    val screenName: String,
    /** 订阅中（true）/已暂停（false）。 */
    val enabled: Boolean = true,
    /** 上次轮询时间（epoch ms），轮询调度按最旧优先。 */
    val lastCheckedAt: Long? = null,
    /** 上次发现新作品时间（epoch ms，展示用）。 */
    val lastVideoAt: Long? = null,
    val createdAt: Long
)
