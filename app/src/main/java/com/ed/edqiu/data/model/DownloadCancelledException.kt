package com.ed.edqiu.data.model

/**
 * 用户主动取消下载（下载中心/收件箱点取消，经 DownloadCancellation 登记生效）。
 * 下载层收到该异常后任务状态应置为 CANCELLED（而非 FAILED），不消耗重试、不提示错误。
 */
class DownloadCancelledException(message: String = "下载已取消") : Exception(message)
