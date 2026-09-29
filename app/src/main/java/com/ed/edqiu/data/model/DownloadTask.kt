package com.ed.edqiu.data.model

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
    val completedAt: Long? = null,
    /** 作者头像 URL（FXTwitter author.avatar_url，2026-09-16 播放页真头像）。 */
    val avatarUrl: String = "",
    /** 作者显示名（X 昵称，区别于 uploader handle）。 */
    val authorName: String = ""
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
