package com.ed.edqiu.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 订阅消息（2026-10-17）：作者订阅事件收纳箱。
 *
 * 后台轮询/手动检测产生的事件统一入此表，在「作品订阅」页的「订阅消息」分区展示：
 * - [KIND_NEW_WORKS]：发现新作品并入收件箱；
 * - [KIND_FAILURE]：检测失败（未走代理/代理超时/账号被限/Cookie 失效/接口轮换失败…）；
 * - [KIND_RECOVERED]：失败消失——上轮失败、本轮恢复正常（用户点名要的「失败消失」事件）；
 * - [KIND_NOTE]：软提示（guest 数据降级但仍检出）。
 *
 * 去重规则（SubscriptionManager）：同类失败与上轮 [SubscriptionEntity.lastError] 相同不重复写，
 * 防止 25 分钟一轮把同一错误刷满列表。表内超 [KEEP_LIMIT] 条裁剪最旧记录。
 */
@Entity(tableName = "subscription_messages")
data class SubscriptionMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 事件类型：[KIND_NEW_WORKS]/[KIND_FAILURE]/[KIND_RECOVERED]/[KIND_NOTE]。 */
    val kind: String,
    /** 展示文案（含 @handle 前缀，组装时统一格式）。 */
    val text: String,
    /** 是否已读（订阅页展示时置已读；未读数用于入口角标）。 */
    val read: Boolean = false,
    /** 事件时间（epoch ms）。 */
    val createdAt: Long,
) {
    companion object {
        const val KIND_NEW_WORKS = "new_works"
        const val KIND_FAILURE = "failure"
        const val KIND_RECOVERED = "recovered"
        const val KIND_NOTE = "note"

        /** 表内保留上限，超出裁剪最旧。 */
        const val KEEP_LIMIT = 100
    }
}
