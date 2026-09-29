package com.ed.edqiu.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 进行中下载任务的持久化（`download_tasks` 表）。
 *
 * 与 [DownloadHistoryEntity]（仅已完成记录）分离：此表保存尚未完成的下载任务，
 * 供进程被杀/重启后恢复为可重新下载的状态，避免下载任务凭空丢失。
 */
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String,
    val uploader: String,
    val formatId: String,
    val quality: String,
    val ext: String,
    val mediaType: String,
    val mediaIndex: Int? = null,
    val progress: Float = 0f,
    val etaSeconds: Long = 0L,
    val status: String,
    val outputPath: String = "",
    val errorMessage: String = "",
    val createdAt: Long,
    val completedAt: Long? = null,
    val downloaderType: String = "DIRECT",
    val isCancelled: Boolean = false,
)