package com.ed.edqiu.downloader

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下载车道信号（2026-10-17 双线隔离）。
 *
 * - **用户下载线**：用户点下载/详情页/重试触发（[SavedLinkRepository.requestDownload]
 *   manual=true）——交互场景，失败记全局熔断（保护用户不再干等必死引擎）；
 * - **订阅线**：作者订阅轮询的自动下载（manual=false）——后台场景，失败只记
 *   推文亲和，不打全局熔断器。
 *
 * [userDownloadActive]：用户下载进行中置位。订阅线队列见到该信号让位等待，
 * 避免后台自动下载与用户手动下载抢引擎（yt-dlp 进程级单例）与带宽。
 */
object DownloadLanes {

    @Volatile
    private var userActive = AtomicBoolean(false)

    val userDownloadActive: Boolean get() = userActive.get()

    /** 用户下载开始（幂等置位）。 */
    fun enterUserDownload() {
        userActive.set(true)
    }

    /** 用户下载结束。 */
    fun exitUserDownload() {
        userActive.set(false)
    }
}
