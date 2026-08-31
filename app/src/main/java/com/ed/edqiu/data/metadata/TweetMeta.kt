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
    val authorBio: String? = null
)
