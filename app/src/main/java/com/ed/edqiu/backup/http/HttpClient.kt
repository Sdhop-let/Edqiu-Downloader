package com.ed.edqiu.backup.http

import com.ed.edqiu.backup.model.BackupException
import kotlinx.coroutines.CancellationException

/**
 * 备份模块 HTTP 公共工具。
 *
 * 2026-10 统一 WebDAV 通道整改：原 HttpClient 单例（GET/POST JSON + 频控退避，
 * 服务百度 / 阿里 / 123 OAuth 私有 API）随厂商直连适配器一并移除——
 * WebDAV 族统一走 [com.ed.edqiu.backup.provider.WebDavEngine]（OkHttp + Basic/Digest）。
 * 本文件仅保留跨文件复用的两个工具函数。
 */

/**
 * 2026-10 整改：kotlin 的 runCatching 会把 CancellationException 一并捕获为 Result.failure，
 * 上层引擎随之把「任务被取消」当「上传失败」走重试/退避（污染 errorMessage、消耗重试次数）。
 * 备份模块内一律用本函数替代 runCatching：取消异常原样上抛，保持协程取消语义。
 */
inline fun <T> runCatchingNotCancelled(block: () -> T): Result<T> =
    runCatching(block).onFailure { error ->
        if (error is CancellationException) throw error
    }

/** 将底层异常转换为面向用户的中文消息（供各层复用，如 `exceptionOrNull()?.toUserMessage()`）。 */
fun Throwable.toUserMessage(): String = when (this) {
    is BackupException -> message ?: "操作失败，请重试"
    is java.net.UnknownHostException -> "无法连接服务器，请检查网络后重试"
    is java.net.SocketTimeoutException -> "网络请求超时，请检查网络后重试"
    // 2026-08-31 adb 实测：断网时 OkHttp 对 123pan WebDAV 表现为 SSLHandshakeException/EOFException
    //（TLS 握手阶段连接被断），原始 message 是英文（如 "connection closed"），对用户无意义——统一转成中文提示
    is java.net.ConnectException -> "无法连接服务器，请检查网络后重试"
    is javax.net.ssl.SSLException -> "安全连接失败，请检查网络后重试"
    is java.io.EOFException -> "网络连接被中断，请检查网络后重试"
    is java.io.IOException -> if (message?.contains("closed", ignoreCase = true) == true ||
        message?.contains("reset", ignoreCase = true) == true
    ) {
        "网络连接中断，请检查网络后重试"
    } else {
        "网络错误：${message ?: "未知错误"}"
    }
    else -> message ?: "操作失败，请重试"
}
