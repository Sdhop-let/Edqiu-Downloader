package com.ed.edqiu.service

import android.content.Context
import android.util.Log
import com.ed.edqiu.data.model.VideoFormat
import com.ed.edqiu.data.model.VideoInfo
import com.ed.edqiu.data.model.VideoInfo.FormatMode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object YoutubeDLService {

    private const val TAG = "YoutubeDLService"

    /**
     * Extract video metadata without downloading.
     * Uses --dump-single-json so playlist/media entries are returned in one JSON object.
     * @param proxyUrl Optional HTTP proxy URL (e.g. "http://127.0.0.1:7890") for Clash/V2Ray.
     * @param cookieFilePath Optional Netscape cookie file path for Twitter auth.
     */
    suspend fun getVideoInfo(url: String, proxyUrl: String? = null, cookieFilePath: String? = null): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-warnings")
                addOption("--no-check-certificate")
                if (proxyUrl != null) {
                    addOption("--proxy", proxyUrl)
                }
                if (cookieFilePath != null) {
                    addOption("--cookies", cookieFilePath)
                }
            }

            val result = YoutubeDL.getInstance().execute(request, null)

            // Check for errors: if stderr is not empty, something went wrong
            if (result.err.isNotBlank()) {
                val errorMsg = result.err.trim()
                // Some warnings in stderr are normal; only treat as error if out is empty
                if (result.out.isBlank()) {
                    return@withContext Result.failure(Exception(errorMsg))
                }
            }

            val json = parseDumpJson(result.out)
            val videoInfo = parseVideoInfo(url, json)

            Result.success(videoInfo)
        } catch (e: Exception) {
            Log.e(TAG, "getVideoInfo error", e)
            Result.failure(e)
        }
    }

    /**
     * Download video with progress callback.
     * Junkfood02 fork callback signature: (progress: Float, eta: Long, line: String) -> Unit
     * @param proxyUrl Optional HTTP proxy URL (e.g. "http://127.0.0.1:7890") for Clash/V2Ray.
     * @param cookieFilePath Optional Netscape cookie file path for Twitter auth.
     */
    suspend fun downloadVideo(
        url: String,
        formatId: String,
        outputDir: String,
        onProgress: ((Float, Long) -> Unit)? = null,
        proxyUrl: String? = null,
        cookieFilePath: String? = null,
        playlistIndex: Int? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val processId = UUID.randomUUID().toString()
            val outputPath = if (playlistIndex != null) {
                "$outputDir/%(uploader)s/%(title)s_%(playlist_index)s.%(ext)s"
            } else {
                "$outputDir/%(uploader)s/%(title)s.%(ext)s"
            }

            val request = YoutubeDLRequest(url).apply {
                addOption("-f", formatId)
                addOption("-o", outputPath)
                addOption("--newline")
                if (playlistIndex != null) {
                    addOption("--playlist-items", playlistIndex.toString())
                } else {
                    addOption("--no-playlist")
                }
                addOption("--no-check-certificate")
                addOption("--no-continue")
                if (proxyUrl != null) {
                    addOption("--proxy", proxyUrl)
                }
                if (cookieFilePath != null) {
                    addOption("--cookies", cookieFilePath)
                }
            }

            val result = YoutubeDL.getInstance().execute(
                request,
                processId,
                { progress, eta, _ ->
                    onProgress?.invoke(progress, eta)
                }
            )

            // Check for errors
            if (result.err.isNotBlank() && result.out.isBlank()) {
                return@withContext Result.failure(Exception(result.err.trim()))
            }

            // Extract the actual file path from yt-dlp output
            val filePath = extractFilePath(result.out, outputDir)
            Result.success(filePath)
        } catch (e: Exception) {
            Log.e(TAG, "downloadVideo error", e)
            Result.failure(e)
        }
    }

    /**
     * Cancel an ongoing download by process ID.
     */
    fun cancelDownload(processId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (e: Exception) {
            Log.e(TAG, "cancelDownload error", e)
        }
    }

    /**
     * Update yt-dlp binary to latest version.
     * No proxy needed 鈥?this only updates the yt-dlp binary itself.
     */
    suspend fun updateYoutubeDL(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                context,
                com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.STABLE
            )
            Result.success(status?.name ?: "Updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "updateYoutubeDL error", e)
            Result.failure(e)
        }
    }

    private fun parseFormats(json: org.json.JSONObject): List<VideoFormat> {
        val formatsArray = json.optJSONArray("formats") ?: return emptyList()
        val formats = mutableListOf<VideoFormat>()

        for (i in 0 until formatsArray.length()) {
            val f = formatsArray.getJSONObject(i)
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")

            // Only include formats with video
            if (vcodec == "none" || vcodec.isEmpty()) continue

            val width = f.optInt("width", 0)
            val height = f.optInt("height", 0)
            val quality = if (height > 0) "${height}p" else "unknown"

            formats.add(
                VideoFormat(
                    formatId = buildDownloadFormatId(f),
                    quality = quality,
                    ext = f.optString("ext", "mp4"),
                    filesize = f.optLong("filesize", f.optLong("filesize_approx", 0)),
                    vcodec = vcodec,
                    acodec = acodec,
                    fps = f.optInt("fps", 0)
                )
            )
        }

        return formats.sortedByDescending {
            it.quality.removeSuffix("p").toIntOrNull() ?: 0
        }.distinctBy { it.quality }
    }

    private fun parseVideoInfo(url: String, json: org.json.JSONObject): VideoInfo {
        val entries = json.optJSONArray("entries")
        if (entries != null && entries.length() == 1) {
            entries.optJSONObject(0)?.let { entry ->
                val formats = parseFormats(entry)
                if (formats.isNotEmpty()) {
                    return VideoInfo(
                        url = url,
                        title = entry.optString("title", json.optString("title", json.optString("id", "twitter_video"))),
                        thumbnail = entry.optString("thumbnail", json.optString("thumbnail", "")),
                        duration = entry.optLong("duration", json.optLong("duration", 0)),
                        uploader = entry.optString("uploader", json.optString("uploader", json.optString("channel", "unknown"))),
                        formats = formats,
                        formatMode = FormatMode.QUALITY
                    )
                }
            }
        }

        if (entries != null && entries.length() > 1) {
            val mediaItems = mutableListOf<VideoFormat>()
            var thumbnail = json.optString("thumbnail", "")
            var duration = json.optLong("duration", 0)

            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val mediaIndex = index + 1
                if (thumbnail.isBlank()) thumbnail = entry.optString("thumbnail", "")
                if (duration <= 0) duration = entry.optLong("duration", 0)

                val entryThumbnail = entry.optString("thumbnail", json.optString("thumbnail", ""))
                val bestFormat = parseFormats(entry).firstOrNull()
                val quality = bestFormat?.quality ?: entry.optString("format", "best").ifBlank { "best" }
                val ext = bestFormat?.ext ?: entry.optString("ext", "mp4")
                val formatId = bestFormat?.formatId?.takeIf { it.isNotBlank() } ?: "best"

                mediaItems += VideoFormat(
                    formatId = formatId,
                    quality = "瑙嗛${mediaIndex.toString().padStart(2, '0')} 路 $quality",
                    ext = ext,
                    filesize = bestFormat?.filesize ?: 0,
                    vcodec = bestFormat?.vcodec ?: "",
                    acodec = bestFormat?.acodec ?: "",
                    fps = bestFormat?.fps ?: 0,
                    thumbnail = entryThumbnail.takeIf { it.isNotBlank() },
                    mediaIndex = mediaIndex,
                    isMediaItem = true
                )
            }

            if (mediaItems.isNotEmpty()) {
                return VideoInfo(
                    url = url,
                    title = json.optString("title", json.optString("id", "twitter_video")),
                    thumbnail = thumbnail,
                    duration = duration,
                    uploader = json.optString("uploader", json.optString("channel", "unknown")),
                    formats = mediaItems,
                    formatMode = FormatMode.MEDIA_ITEMS
                )
            }
        }

        val formats = parseFormats(json)
        return VideoInfo(
            url = url,
            title = json.optString("title", json.optString("id", "twitter_video")),
            thumbnail = json.optString("thumbnail", ""),
            duration = json.optLong("duration", 0),
            uploader = json.optString("uploader", json.optString("channel", "unknown")),
            formats = formats,
            formatMode = FormatMode.QUALITY
        )
    }

    private fun parseDumpJson(output: String): org.json.JSONObject {
        val trimmed = output.trim()
        if (trimmed.startsWith("{")) return org.json.JSONObject(trimmed)

        val firstJsonLine = output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") && it.endsWith("}") }

        if (firstJsonLine != null) return org.json.JSONObject(firstJsonLine)
        throw IllegalArgumentException("yt-dlp did not return JSON metadata")
    }

    private fun buildDownloadFormatId(formatJson: org.json.JSONObject): String {
        val formatId = formatJson.optString("format_id", "").ifBlank { "best" }
        val acodec = formatJson.optString("acodec", "none")
        return if (acodec == "none" || acodec.isBlank()) {
            "$formatId+bestaudio/best"
        } else {
            formatId
        }
    }

    private fun extractFilePath(output: String, baseDir: String): String {
        val lines = output.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[download] Destination:")) {
                return trimmed.removePrefix("[download] Destination:").trim()
            }
            if (trimmed.contains("has already been downloaded")) {
                return trimmed.substringAfter("[download] ").substringBefore(" has already").trim()
            }
            if (trimmed.startsWith("[Merger] Merging formats into")) {
                return trimmed.removePrefix("[Merger] Merging formats into").trim()
                    .removeSurrounding("\"")
            }
        }
        return "$baseDir/downloaded_video.mp4"
    }

    /**
     * Write a Netscape-format cookie file for yt-dlp --cookies option.
     * Required cookies: auth_token and ct0 from Twitter/X.
     * @param context Application context for file storage
     * @param authToken Twitter auth_token cookie value
     * @param ct0 Twitter ct0 (CSRF) cookie value
     * @return Absolute path to the written cookie file
     */
    fun writeCookieFile(context: Context, authToken: String, ct0: String): String {
        val cookieDir = File(context.filesDir, "yt-dlp-cookies")
        cookieDir.mkdirs()
        val cookieFile = File(cookieDir, "twitter_cookies.txt")

        val content = buildString {
            appendLine("# Netscape HTTP Cookie File")
            appendLine("# This is a generated file!  Do not edit.")
            appendLine()
            // Twitter/X cookies 鈥?domain .x.com and .twitter.com
            appendLine(".x.com\tTRUE\t/\tTRUE\t0\tauth_token\t$authToken")
            appendLine(".x.com\tTRUE\t/\tTRUE\t0\tct0\t$ct0")
            appendLine(".twitter.com\tTRUE\t/\tTRUE\t0\tauth_token\t$authToken")
            appendLine(".twitter.com\tTRUE\t/\tTRUE\t0\tct0\t$ct0")
        }

        cookieFile.writeText(content)
        Log.d(TAG, "Cookie file written to: ${cookieFile.absolutePath}")
        return cookieFile.absolutePath
    }
}

