package com.ed.edqiu.service

import android.util.Log
import com.ed.edqiu.data.model.DownloadCancelledException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Direct HTTP file downloader for fxtwitter-resolved videos.
 * When fxtwitter provides direct MP4 URLs, we bypass yt-dlp entirely
 * and download via plain HTTP 鈥?much faster and more reliable.
 *
 * 2026-10 安全/健壮性整改：
 * - 写入 `.part` 临时文件，完成后转正；失败/取消不残留半截最终文件
 *   （旧实现直接写最终文件名，半截文件会被媒体扫描误当成完整下载入库）；
 * - 流统一 use{} 关闭（旧实现异常路径 FileOutputStream 泄漏）；
 * - 循环内响应取消标记（[isCancelled]），取消即中止并清理；
 * - 代理解析支持 socks5 与可选认证段（旧实现 socks5 被静默丢弃 → 用户以为走代理实际直连）。
 */
object DirectDownloader {

    private const val TAG = "DirectDownloader"
    private const val BUFFER_SIZE = 8192

    /**
     * Download a video file from a direct URL with progress tracking.
     * @param url Direct video URL (e.g. from fxtwitter variants)
     * @param outputDir Directory to save the file
     * @param filename Output filename (e.g. "uploader_1080p.mp4")
     * @param proxyUrl Optional HTTP/socks5 proxy for Clash/V2Ray
     * @param onProgress Callback: (progressPercent, etaSeconds)
     * @param isCancelled 取消探测（返回 true 立即中止下载并清理 .part）
     */
    suspend fun downloadFile(
        url: String,
        outputDir: String,
        filename: String,
        proxyUrl: String?,
        onProgress: ((Float, Long) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Ensure output directory exists
            val dir = File(outputDir)
            if (!dir.exists()) dir.mkdirs()

            val outputFile = File(dir, filename)
            val partFile = File(dir, "$filename.part")
            Log.d(TAG, "Starting direct download: $url 鈫?${outputFile.absolutePath}")

            val proxy = parseProxy(proxyUrl)
            val connection = if (proxy != null) {
                URL(url).openConnection(proxy) as HttpURLConnection
            } else {
                URL(url).openConnection() as HttpURLConnection
            }

            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Edqiu/1.0")
                connectTimeout = 30000
                readTimeout = 60000
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(
                        Exception("HTTP $responseCode 下载失败")
                    )
                }

                val totalSize = connection.contentLengthLong
                val startTime = System.currentTimeMillis()

                var downloaded = 0L
                val buffer = ByteArray(BUFFER_SIZE)

                connection.inputStream.use { inputStream ->
                    partFile.outputStream().use { outputStream ->
                        while (true) {
                            if (isCancelled?.invoke() == true) {
                                throw DownloadCancelledException()
                            }
                            val read = inputStream.read(buffer)
                            if (read == -1) break
                            outputStream.write(buffer, 0, read)
                            downloaded += read

                            // Report progress
                            if (totalSize > 0 && onProgress != null) {
                                val progress = (downloaded.toFloat() / totalSize) * 100f
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                                val speed = if (elapsed > 0) downloaded / elapsed else 0f
                                val remaining = totalSize - downloaded
                                val eta = if (speed > 0) (remaining / speed).toLong() else 0L
                                onProgress.invoke(progress, eta)
                            }
                        }
                    }
                }

                // 下载完成：.part 原子转正
                if (outputFile.exists()) outputFile.delete()
                if (!partFile.renameTo(outputFile)) {
                    partFile.copyTo(outputFile, overwrite = true)
                    partFile.delete()
                }

                Log.d(TAG, "Download complete: ${outputFile.absolutePath} (${downloaded} bytes)")
                Result.success(outputFile.absolutePath)
            } finally {
                connection.disconnect()
                // 成功路径 .part 已转正不存在；失败/取消路径清理残留
                if (partFile.exists()) partFile.delete()
            }
        } catch (e: DownloadCancelledException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Direct download error", e)
            Result.failure(e)
        }
    }

    /**
     * 解析代理 URL 为 [Proxy]。支持 http(s)/socks5 scheme 与可选的 user:pass@ 认证段
     * （认证段当前忽略——HttpURLConnection 需 Authenticator 才能带认证，此处仅保证
     * host:port 解析不再失败导致静默直连）。socks5 → [Proxy.Type.SOCKS]。
     */
    private fun parseProxy(proxyUrl: String?): Proxy? {
        if (proxyUrl.isNullOrBlank()) return null
        return try {
            val trimmed = proxyUrl.trim()
            val scheme = Regex("^(https?|socks5?)://", RegexOption.IGNORE_CASE)
                .find(trimmed)?.value?.lowercase()
            val remainder = if (scheme != null) trimmed.substring(scheme.length) else trimmed
            val hostPort = remainder.substringAfterLast("@")
            val parts = hostPort.split(":")
            if (parts.size != 2) return null
            val host = parts[0].trim()
            val port = parts[1].trim().toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65535) return null
            val type = if (scheme?.startsWith("socks") == true) Proxy.Type.SOCKS else Proxy.Type.HTTP
            Proxy(type, InetSocketAddress(host, port))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse proxy URL", e)
            null
        }
    }
}

