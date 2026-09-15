package com.ed.edqiu.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ed.edqiu.data.model.MediaType

@Entity(tableName = "download_history")
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
    val phash: Long? = null
)
