package com.ed.edqiu.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.ed.edqiu.data.model.DownloadStatus
import com.ed.edqiu.data.model.DownloadTask
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.data.model.VideoFormat
import com.ed.edqiu.data.model.VideoInfo
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.DownloadPathPreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.service.DirectDownloader
import com.ed.edqiu.service.FXTwitterResolver
import com.ed.edqiu.service.YoutubeDLService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class DownloadRepository(private val context: Context) {

    companion object {
        private const val TAG = "DownloadRepository"
    }

    val downloadTasks: StateFlow<List<DownloadTask>> = DownloadTaskBus.tasks

    private val proxyPreferences = ProxyPreferences(context)
    private val cookiePreferences = CookiePreferences(context)
    private val pathPreferences = DownloadPathPreferences(context)

    val outputDir: String
        get() = pathPreferences.resolveDownloadDir(context)

    private val taskRepo = DownloadTaskRepo(context)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Resolve Twitter/X metadata using dual strategy:
     * 1. Try fxtwitter API (fast, no auth needed, direct video/image URLs)
     * 2. If fxtwitter fails, fall back to yt-dlp with cookies (if available) and proxy
     */
    suspend fun resolveVideoInfo(url: String, proxyUrl: String?): Result<VideoInfo> {
        // Strategy 1: fxtwitter API (preferred 鈥?no auth, fast, direct URLs)
        Log.d(TAG, "Strategy 1: Trying fxtwitter API...")
        val fxResult = FXTwitterResolver.resolveVideoInfo(url, proxyUrl)
        if (fxResult.isSuccess) {
            Log.d(TAG, "fxtwitter API succeeded!")
            return fxResult
        }

        val fxError = fxResult.exceptionOrNull()?.message ?: "Unknown fxtwitter error"
        Log.d(TAG, "fxtwitter API failed: $fxError 鈫?falling back to yt-dlp")

        // Strategy 2: yt-dlp with proxy + cookies (if available)
        val cookieFilePath = if (cookiePreferences.hasCookies()) {
            YoutubeDLService.writeCookieFile(context, cookiePreferences.getAuthToken(), cookiePreferences.getCt0())
        } else null

        val ytdlResult = YoutubeDLService.getVideoInfo(url, proxyUrl, cookieFilePath)
        if (ytdlResult.isSuccess) {
            Log.d(TAG, "yt-dlp succeeded!")
            return ytdlResult
        }

        val ytdlError = ytdlResult.exceptionOrNull()?.message ?: "Unknown yt-dlp error"
        Log.d(TAG, "yt-dlp also failed: $ytdlError")

        // Both strategies failed 鈥?return a combined error message
        return Result.failure(
            Exception(
                "fxtwitter: $fxError\nyt-dlp: $ytdlError\n" +
                if (!cookiePreferences.hasCookies()) {
                    "鎻愮ず锛氬湪璁剧疆涓～鍏?Twitter cookies 鍙兘瑙ｅ喅 yt-dlp 瑙ｆ瀽闂"
                } else ""
            )
        )
    }

    /**
     * Start downloading a media item with the selected format.
     * Uses DirectDownloader for fxtwitter-resolved videos/images (directUrl),
     * or yt-dlp for videos resolved via yt-dlp.
     */
    suspend fun startDownload(
        url: String,
        videoInfo: VideoInfo,
        format: VideoFormat,
        proxyUrl: String? = null
    ): Result<String> {
        val taskThumbnail = format.thumbnail?.takeIf { it.isNotBlank() } ?: videoInfo.thumbnail
        val downloaderType = if (format.directUrl != null) "DIRECT" else "YOUTUBEDL"
        val task = DownloadTask(
            url = url,
            title = videoInfo.title,
            thumbnail = taskThumbnail,
            uploader = videoInfo.uploader,
            formatId = format.formatId,
            quality = format.quality,
            ext = format.ext,
            mediaType = format.mediaType,
            mediaIndex = format.mediaIndex,
            status = DownloadStatus.DOWNLOADING
        )

        DownloadTaskBus.add(task)
        taskRepo.addTask(task, downloaderType)

        // Choose download method based on whether we have a direct URL
        val result = if (format.directUrl != null) {
            // fxtwitter-resolved: direct HTTP download (fast, no yt-dlp needed)
            val tweetId = Regex("/status/(\\d+)").find(url)?.groupValues?.getOrNull(1) ?: url.hashCode().toString()
            val mediaPart = format.mediaIndex?.let { "_${it.toString().padStart(2, '0')}" } ?: ""
            val filename = sanitizeFilename("${videoInfo.uploader}_${tweetId}${mediaPart}_${format.formatId}_${format.quality}.${format.ext}")
            DirectDownloader.downloadFile(
                url = format.directUrl,
                outputDir = outputDir,
                filename = filename,
                proxyUrl = proxyUrl,
                onProgress = { progress, eta ->
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(progress = progress, etaSeconds = eta)
                    }
                }
            )
        } else {
            // yt-dlp-resolved: use yt-dlp for download
            val cookieFilePath = if (cookiePreferences.hasCookies()) {
                YoutubeDLService.writeCookieFile(context, cookiePreferences.getAuthToken(), cookiePreferences.getCt0())
            } else null

            YoutubeDLService.downloadVideo(
                url = url,
                formatId = format.formatId,
                outputDir = outputDir,
                onProgress = { progress, eta ->
                    DownloadTaskBus.updateTask(task.id) {
                        it.copy(progress = progress, etaSeconds = eta)
                    }
                },
                proxyUrl = proxyUrl,
                cookieFilePath = cookieFilePath,
                playlistIndex = format.mediaIndex
            )
        }

        val finalizedResult = result.fold(
            onSuccess = { path ->
                runCatching {
                    val mediaFile = File(path)
                    val metaFile = writeSidecarMetadata(
                        mediaFile = mediaFile,
                        sourceUrl = url,
                        videoInfo = videoInfo,
                        format = format,
                        thumbnail = taskThumbnail
                    )
                    copyToCustomTreeIfNeeded(
                        source = metaFile,
                        mimeType = "application/json"
                    )
                    copyToCustomTreeIfNeeded(
                        source = mediaFile,
                        mimeType = MediaFileTypes.mimeTypeForExtension(mediaFile.extension)
                    )
                    path
                }
            },
            onFailure = { Result.failure(it) }
        )

        finalizedResult.onSuccess { path ->
            DownloadTaskBus.updateTask(task.id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    outputPath = path,
                    progress = 100f,
                    completedAt = System.currentTimeMillis()
                )
            }
        }.onFailure { error ->
            DownloadTaskBus.updateTask(task.id) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = error.message ?: "Unknown error"
                )
            }
        }

        val finalTask = if (finalizedResult.isSuccess) {
            task.copy(
                status = DownloadStatus.COMPLETED,
                outputPath = finalizedResult.getOrNull().orEmpty(),
                progress = 100f,
                completedAt = System.currentTimeMillis()
            )
        } else {
            task.copy(
                status = DownloadStatus.FAILED,
                errorMessage = finalizedResult.exceptionOrNull()?.message ?: "Unknown error"
            )
        }
        taskRepo.addTask(finalTask, downloaderType)

        return finalizedResult
    }

    private fun writeSidecarMetadata(
        mediaFile: File,
        sourceUrl: String,
        videoInfo: VideoInfo,
        format: VideoFormat,
        thumbnail: String
    ): File {
        require(mediaFile.exists() && mediaFile.isFile) { "涓嬭浇鏂囦欢涓嶅瓨鍦細${mediaFile.name}" }
        val tweetId = Regex("/status/(\\d+)").find(sourceUrl)?.groupValues?.getOrNull(1) ?: ""
        val metadata = JSONObject()
            .put("url", sourceUrl)
            .put("tweetId", tweetId)
            .put("uploader", videoInfo.uploader)
            .put("title", videoInfo.title)
            .put("quality", format.quality)
            .put("formatId", format.formatId)
            .put("thumbnail", thumbnail)
            .put("formatMode", videoInfo.formatMode.name)
            .put("mediaIndex", format.mediaIndex ?: JSONObject.NULL)
            .put("mediaType", format.mediaType.name)
            .put("ext", format.ext)

        return File(mediaFile.absolutePath + ".meta.json").apply {
            writeText(metadata.toString())
        }
    }

    private fun copyToCustomTreeIfNeeded(source: File, mimeType: String) {
        if (!pathPreferences.useCustomPath || !pathPreferences.hasCustomTreeUri) return
        require(source.exists() && source.isFile) { "待同步文件不存在: ${source.name}" }

        val targetDir = DocumentFile.fromTreeUri(context, Uri.parse(pathPreferences.customTreeUri))
            ?: error("无法访问自定义下载目录")
        val targetFile = targetDir.findFile(source.name)
            ?: targetDir.createFile(mimeType, source.name)
            ?: error("无法创建目标文件: ${source.name}")
        val output = context.contentResolver.openOutputStream(targetFile.uri, "wt")
            ?: error("无法写入目标文件: ${source.name}")

        output.use { stream ->
            source.inputStream().use { input -> input.copyTo(stream) }
        }
    }

    fun cancelDownload(taskId: String) {
        DownloadTaskBus.cancel(taskId)
        ioScope.launch {
            taskRepo.updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED) }
        }
    }

    fun removeTask(taskId: String) {
        DownloadTaskBus.remove(taskId)
        ioScope.launch { taskRepo.removeTask(taskId) }
    }

    fun clearCompleted() {
        DownloadTaskBus.clearCompleted()
        ioScope.launch { taskRepo.removeCompleted() }
    }

    /** Sanitize filename to remove characters not allowed in file names. */
    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    }
}


