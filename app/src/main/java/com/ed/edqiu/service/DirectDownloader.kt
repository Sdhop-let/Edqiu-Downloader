package com.ed.edqiu.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Direct HTTP file downloader for fxtwitter-resolved videos.
 * When fxtwitter provides direct MP4 URLs, we bypass yt-dlp entirely
 * and download via plain HTTP 鈥?much faster and more reliable.
 */
object DirectDownloader {

    private const val TAG = "DirectDownloader"
    private const val BUFFER_SIZE = 8192

    /**
     * Download a video file from a direct URL with progress tracking.
     * @param url Direct video URL (e.g. from fxtwitter variants)
     * @param outputDir Directory to save the file
     * @param filename Output filename (e.g. "uploader_1080p.mp4")
     * @param proxyUrl Optional HTTP proxy for Clash/V2Ray
     * @param onProgress Callback: (progressPercent, etaSeconds)
     */
    suspend fun downloadFile(
        url: String,
        outputDir: String,
        filename: String,
        proxyUrl: String?,
        onProgress: ((Float, Long) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Ensure output directory exists
            val dir = File(outputDir)
            if (!dir.exists()) dir.mkdirs()

            val outputFile = File(dir, filename)
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
                        Exception("HTTP $responseCode 涓嬭浇澶辫触")
                    )
                }

                val totalSize = connection.contentLengthLong
                val startTime = System.currentTimeMillis()
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(outputFile)

                var downloaded = 0L
                val buffer = ByteArray(BUFFER_SIZE)

                while (true) {
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

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                Log.d(TAG, "Download complete: ${outputFile.absolutePath} (${downloaded} bytes)")
                Result.success(outputFile.absolutePath)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct download error", e)
            Result.failure(e)
        }
    }

    private fun parseProxy(proxyUrl: String?): Proxy? {
        if (proxyUrl == null) return null
        try {
            val withoutProtocol = proxyUrl.removePrefix("http://")
            val parts = withoutProtocol.split(":")
            if (parts.size != 2) return null
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: return null
            return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse proxy URL: $proxyUrl", e)
            return null
        }
    }
}

