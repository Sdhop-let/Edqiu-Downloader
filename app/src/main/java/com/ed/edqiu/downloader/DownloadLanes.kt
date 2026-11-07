package com.ed.edqiu.downloader

import java.util.concurrent.atomic.AtomicInteger

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
 * 2026-11-07 审计修复：Boolean → 引用计数——用户同时发起单条+批量两条下载时，
 * 旧实现先结束的那条会把「用户线活跃」清掉，订阅线不再让位。
 */
object DownloadLanes {

    private val userActiveCount = AtomicInteger(0)

    val userDownloadActive: Boolean get() = userActiveCount.get() > 0

    /** 用户下载开始（引用计数 +1）。 */
    fun enterUserDownload() {
        userActiveCount.incrementAndGet()
    }

    /** 用户下载结束（引用计数 -1，下限 0）。 */
    fun exitUserDownload() {
        userActiveCount.updateAndGet { current -> if (current > 0) current - 1 else 0 }
    }
}
