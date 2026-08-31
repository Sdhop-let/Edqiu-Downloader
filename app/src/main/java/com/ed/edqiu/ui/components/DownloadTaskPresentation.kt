package com.ed.edqiu.ui.components

import com.ed.edqiu.data.model.DownloadStatus

/**
 * 下载任务状态 → 用户可读文案。
 *
 * @param status 当前状态
 * @param mediaType 媒体类型名（如「视频」），用于完成态展示
 * @param progressPercent 下载进度（0..100），仅下载中展示
 */
fun downloadStatusText(status: DownloadStatus, mediaType: String, progressPercent: Int): String =
    when (status) {
        DownloadStatus.PENDING -> "等待中"
        DownloadStatus.RESOLVING -> "解析中"
        DownloadStatus.DOWNLOADING -> "下载中 $progressPercent%"
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.COMPLETED -> mediaType
        DownloadStatus.FAILED -> "下载失败"
        DownloadStatus.CANCELLED -> "已取消"
    }

/** 失败 / 取消 / 暂停的任务都可以从断点恢复重新入队。 */
fun DownloadStatus.canResumeDownload(): Boolean =
    this == DownloadStatus.FAILED || this == DownloadStatus.CANCELLED || this == DownloadStatus.PAUSED
