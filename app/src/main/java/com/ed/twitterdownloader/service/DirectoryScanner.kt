package com.ed.twitterdownloader.service

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ed.twitterdownloader.data.database.AppDatabase
import com.ed.twitterdownloader.data.database.DownloadHistoryEntity
import com.ed.twitterdownloader.data.model.MediaFileTypes
import com.ed.twitterdownloader.data.model.MediaType
import com.ed.twitterdownloader.data.preferences.DownloadPathPreferences
import com.ed.twitterdownloader.data.preferences.HiddenHistoryPreferences
import java.io.File

/**
 * Scans the download directory on app startup and rebuilds history from existing files.
 * This ensures data persists across app reinstalls/updates.
 *
 * Filename format: {uploader}_{tweetId}_{mediaIndex}_{format}_{quality}.{ext}
 * Supports both downloaded videos and downloaded images.
 */
object DirectoryScanner {

    private const val TAG = "DirectoryScanner"
    private val TWEET_ID_REGEX = Regex("\\b\\d{11,25}\\b")

    /**
     * Scan download directory and insert any new media files into history database.
     * Skips files that already have a matching entry (by file path).
     */
    suspend fun scanAndRebuildHistory(context: Context) {
        try {
            val dao = AppDatabase.getInstance(context).downloadHistoryDao()
            val hiddenHistoryPreferences = HiddenHistoryPreferences(context)
            val existingPaths = dao.getAllFilePaths()
            val candidateFiles = scanRoots(context)
                .asSequence()
                .filter { it.exists() && it.isDirectory }
                .flatMap { it.walkTopDown() }
                .filter { MediaFileTypes.isSupportedMediaFile(it) }
                .distinctBy { it.absolutePath }
                .toList()

            var newCount = 0
            var skippedCount = 0
            candidateFiles.forEach { file ->
                if (hiddenHistoryPreferences.isHidden(file.absolutePath)) {
                    skippedCount++
                    return@forEach
                }
                if (file.absolutePath !in existingPaths) {
                    val entity = parseFileToEntity(file)
                    if (entity != null) {
                        dao.insert(entity)
                        newCount++
                        Log.d(TAG, "Rebuilt history entry: url=${entity.url}, uploader=${entity.uploader}, mediaType=${entity.mediaType}, mediaIndex=${entity.mediaIndex}, file=${file.name}")
                    } else {
                        skippedCount++
                    }
                }
            }

            Log.i(
                TAG,
                "Directory scan complete: existing=${existingPaths.size}, files=${candidateFiles.size}, rebuilt=$newCount, skipped=$skippedCount"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Directory scan failed", e)
        }
    }

    private fun scanRoots(context: Context): List<File> {
        val pathPreferences = DownloadPathPreferences(context)
        val resolved = File(pathPreferences.resolveDownloadDir(context))
        val appDownload = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val packageDownloads = listOf(
            context.packageName,
            "com.ed.Edqiu",
            "com.ed.edqiu",
            "com.ed.twitterdownload",
            "com.ed.twitterdownloader"
        ).flatMap { packageName ->
            listOf("Download", "download", "Downloads").map { dirName ->
                File(
                    Environment.getExternalStorageDirectory(),
                    "Android/data/$packageName/files/$dirName"
                )
            }
        }
        return (listOfNotNull(resolved, appDownload) + packageDownloads)
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .also { roots -> Log.i(TAG, "Scanning roots: ${roots.joinToString { it.absolutePath }}") }
    }

    /**
     * Parse a media filename into a DownloadHistoryEntity.
     * Also reads the .meta.json sidecar file if it exists for richer metadata.
     */
    private fun parseFileToEntity(file: File): DownloadHistoryEntity? {
        val fileMediaType = MediaFileTypes.fromExtension(file.extension) ?: return null
        val nameWithoutExtension = file.nameWithoutExtension
        val parts = nameWithoutExtension.split("_")

        var uploader: String
        var tweetId: String
        var quality: String
        var mediaIndex: Int? = null
        var mediaType: MediaType = fileMediaType
        var url: String
        var title: String = nameWithoutExtension
        var thumbnail: String = if (fileMediaType == MediaType.IMAGE) file.absolutePath else ""

        val tweetIdIndex = parts.indexOfFirst { it.length > 10 && it.all(Char::isDigit) }
        if (tweetIdIndex >= 0) {
            tweetId = parts[tweetIdIndex]
            uploader = parts.take(tweetIdIndex).joinToString("_").ifBlank { "unknown" }
            mediaIndex = parts.getOrNull(tweetIdIndex + 1)?.toIntOrNull()
            quality = parts.drop(tweetIdIndex + 1)
                .dropWhile { it.toIntOrNull() != null || it == "fx" || it == "best" || it == "img" }
                .joinToString("_")
                .ifBlank { if (fileMediaType == MediaType.IMAGE) "鍥剧墖" else parts.lastOrNull().orEmpty().ifBlank { "unknown" } }
            url = "https://x.com/i/status/$tweetId"
        } else if (parts.size >= 3) {
            quality = parts.last()
            tweetId = parts[parts.size - 2]
            uploader = parts.dropLast(2).joinToString("_").ifBlank { "unknown" }
            url = "https://x.com/i/status/$tweetId"
        } else if (parts.size == 2) {
            uploader = parts[0]
            quality = parts[1]
            tweetId = file.nameWithoutExtension.hashCode().toString()
            url = ""
        } else {
            uploader = "unknown"
            quality = if (fileMediaType == MediaType.IMAGE) "鍥剧墖" else "unknown"
            tweetId = file.nameWithoutExtension.hashCode().toString()
            url = ""
        }

        // Try to read sidecar .meta.json for richer data
        val metaFile = File(file.absolutePath + ".meta.json")
        if (metaFile.exists()) {
            try {
                val json = org.json.JSONObject(metaFile.readText())
                val metaUrl = json.optString("url", "")
                if (metaUrl.isNotBlank()) url = metaUrl
                val metaTweetId = json.optString("tweetId", "")
                if (metaTweetId.isNotBlank()) tweetId = metaTweetId
                val metaUploader = json.optString("uploader", "")
                if (metaUploader.isNotBlank()) uploader = metaUploader
                val metaTitle = json.optString("title", "")
                if (metaTitle.isNotBlank()) title = metaTitle
                val metaQuality = json.optString("quality", "")
                if (metaQuality.isNotBlank()) quality = metaQuality
                val metaThumbnail = json.optString("thumbnail", "")
                if (metaThumbnail.isNotBlank()) thumbnail = metaThumbnail
                val metaMediaType = json.optString("mediaType", "")
                if (metaMediaType.isNotBlank()) {
                    mediaType = runCatching { MediaType.valueOf(metaMediaType) }.getOrDefault(fileMediaType)
                }
                if (json.has("mediaIndex") && !json.isNull("mediaIndex")) {
                    mediaIndex = json.optInt("mediaIndex")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse sidecar metadata: ${metaFile.name}", e)
            }
        }

        if (mediaType == MediaType.IMAGE && thumbnail.isBlank()) thumbnail = file.absolutePath

        // 时长：优先 meta.json，缺失则用 MediaMetadataRetriever 从媒体文件提取（图片为 0）
        var duration = readDurationFromFile(file, mediaType)

        return DownloadHistoryEntity(
            id = listOfNotNull(tweetId, mediaIndex?.toString(), quality, mediaType.name).joinToString("_"),
            url = url,
            title = title,
            thumbnail = thumbnail,
            uploader = uploader,
            quality = quality,
            mediaIndex = mediaIndex,
            mediaType = mediaType,
            filePath = file.absolutePath,
            fileSize = file.length(),
            duration = duration,
            createdAt = file.lastModified(),
            completedAt = file.lastModified()
        )
    }

    /** 从媒体文件读取时长（毫秒）。图片/读取失败返回 0。 */
    private fun readDurationFromFile(file: File, mediaType: MediaType): Long {
        if (mediaType == MediaType.IMAGE) return 0L
        return runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(0L)
    }

    /**
     * Extract tweet ID from a filename.
     * Returns null if the filename doesn't match the expected format.
     */
    fun extractTweetIdFromFilename(filename: String): String? {
        val parts = filename.substringBeforeLast(".").split("_")
        if (parts.size >= 3) {
            val possibleId = parts[parts.size - 2]
            // Tweet IDs are long numeric strings
            if (possibleId.length > 10 && possibleId.all { it.isDigit() }) {
                return possibleId
            }
        }
        return TWEET_ID_REGEX.find(filename)?.value
    }

    /**
     * Check if a URL's tweet ID matches any existing file in the download directory.
     */
    fun isUrlAlreadyDownloaded(context: Context, url: String): Boolean {
        val tweetId = Regex("/status/(\\d+)").find(url)?.groupValues?.getOrNull(1) ?: return false
        return scanRoots(context).any { dir ->
            dir.exists() && dir.walkTopDown().any { file ->
                MediaFileTypes.isSupportedMediaFile(file) && extractTweetIdFromFilename(file.name) == tweetId
            }
        }
    }
}
