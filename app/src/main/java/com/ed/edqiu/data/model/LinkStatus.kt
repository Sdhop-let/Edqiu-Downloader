package com.ed.edqiu.data.model

/**
 * 链接下载状态。状态由扫描下载目录派生（见 DownloadMonitor），
 * 仅在本地缓存用于快速展示，不依赖写死。
 */
enum class LinkStatus {
    /** 已捕获，尚未在监控目录发现对应文件。 */
    PENDING,
    /** 已发现对应下载文件。 */
    DOWNLOADED,
    /** 曾被标记失败（预留，第二期自动模式使用）。 */
    FAILED
}
