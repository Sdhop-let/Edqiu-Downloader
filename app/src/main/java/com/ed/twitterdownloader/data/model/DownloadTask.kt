package com.ed.twitterdownloader.data.model

import java.util.UUID

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = "",
    val thumbnail: String = "",
    val uploader: String = "",
    val formatId: String = "best",
    val quality: String = "",
    val ext: String = "mp4",
    val mediaType: MediaType = MediaType.VIDEO,
    val mediaIndex: Int? = null,
    val progress: Float = 0f,
    val etaSeconds: Long = 0,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val outputPath: String = "",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    val progressInt: Int
        get() = progress.toInt()

    val isCompleted: Boolean
        get() = status == DownloadStatus.COMPLETED

    val isActive: Boolean
        get() = status == DownloadStatus.PENDING ||
                status == DownloadStatus.RESOLVING ||
                status == DownloadStatus.DOWNLOADING
}
