package com.ed.edqiu.data.metadata

/**
 * 统一后的推文元数据（App 内部使用）。
 * 来源可以是 fxtwitter 实时解析，或下载器写入的 .meta.json。
 */
data class TweetMeta(
    val tweetId: String,
    val authorId: String? = null,   // @screen_name
    val authorName: String? = null,
    val caption: String? = null,
    val avatarUrl: String? = null,
    val thumbnailUrl: String? = null,
    val authorBio: String? = null,
    /** 推文发布时间（epoch ms，2026-09-15 旧记录发布时间补拉用）。 */
    val publishedAt: Long? = null,
    // 2026-10-02 批次B：主媒体显示宽高（px，视频为显示尺寸无需旋转校正）。
    // 来源 fxtwitter photos[0]/videos[0]；null=来源未提供，靠回填 Worker 兜底。
    /** 主媒体显示宽（px）；null=未知。 */
    val mediaWidth: Int? = null,
    /** 主媒体显示高（px）；null=未知。 */
    val mediaHeight: Int? = null
)
