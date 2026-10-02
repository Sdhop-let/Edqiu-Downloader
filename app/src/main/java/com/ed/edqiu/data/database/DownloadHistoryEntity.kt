package com.ed.edqiu.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ed.edqiu.data.model.MediaType

// 2026-10 整改：补齐热查询索引（pHash 补算/发布时间回填/URL 配对等周期 Worker 反复全表扫描）
@Entity(
    tableName = "download_history",
    indices = [
        Index(value = ["filePath"]),
        Index(value = ["url"]),
        Index(value = ["publishedAt"]),
        Index(value = ["phash"]),
    ]
)
data class DownloadHistoryEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String,
    val uploader: String,
    val quality: String,
    val mediaIndex: Int? = null,
    val mediaType: MediaType = MediaType.VIDEO,
    val filePath: String,
    val fileSize: Long = 0,
    val duration: Long = 0,
    val createdAt: Long,
    val completedAt: Long,
    /** 推文发布时间（epoch ms，来自 sidecar；2026-09-15 媒体库按发布时间排序，null 垫底）。 */
    val publishedAt: Long? = null,
    /** 感知哈希（64bit DCT pHash，2026-09-15 批次3 重复媒体检测；NULL=未计算）。 */
    val phash: Long? = null,
    /** 作者头像 URL（FXTwitter author.avatar_url，2026-09-16 播放页真头像；null=字母块回退）。 */
    val avatarUrl: String? = null,
    /** 作者显示名（X 昵称，如 "Elon Musk"；null=用 uploader handle 兜底）。 */
    val authorName: String? = null
)
