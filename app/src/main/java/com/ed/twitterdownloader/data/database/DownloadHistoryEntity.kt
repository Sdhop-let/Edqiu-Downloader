package com.ed.twitterdownloader.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ed.twitterdownloader.data.model.MediaType

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
    val completedAt: Long
)
