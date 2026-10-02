package com.ed.edqiu.service

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.data.model.MediaType
import com.ed.edqiu.data.preferences.DownloadPathPreferences
import com.ed.edqiu.data.preferences.HiddenHistoryPreferences
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
    // 2026-10：\\b 对下划线相邻数字不成立（"user_1698…_1" 提取失败）——改用数字前瞻后顾
    private val TWEET_ID_REGEX = Regex("(?<!\\d)\\d{11,25}(?!\\d)")

    /**
     * Scan download directory and insert any new media files into history database.
     * Skips files that already have a matching entry (by file path).
     */
    suspend fun scanAndRebuildHistory(context: Context) {
        try {
            val dao = AppDatabase.getInstance(context).downloadHistoryDao()
            val hiddenHistoryPreferences = HiddenHistoryPreferences(context)
            val existingPaths = dao.getAllFilePaths().toHashSet()
            // 2026-09-15：旧记录（sidecar 已有发布时间但 DB 还没写入）的回填清单
            val missingPublishedPaths = dao.getFilePathsMissingPublishedAt().toHashSet()
            // 2026-09-16：无头像/显示名记录的回填清单（播放页真头像）
            val missingAuthorInfoPaths = dao.getFilePathsMissingAuthorInfo().toHashSet()
            val candidateFiles = scanRoots(context)
                .asSequence()
                .filter { it.exists() && it.isDirectory }
                .flatMap { it.walkTopDown() }
                .filter { MediaFileTypes.isSupportedMediaFile(it) }
                .distinctBy { it.absolutePath }
                .toList()

            var newCount = 0
            var skippedCount = 0
            var backfilledCount = 0
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
                } else if (file.absolutePath in missingPublishedPaths) {
                    // 回填：老记录没有发布时间，从 sidecar 补齐（媒体库排序归位）
                    readPublishedAtFromSidecar(file)?.let { publishedAt ->
                        dao.backfillPublishedAt(file.absolutePath, publishedAt)
                        backfilledCount++
                    }
                } else if (file.absolutePath in missingAuthorInfoPaths) {
                    // 回填：老记录没有头像/显示名，从 sidecar 补齐（2026-09-16 播放页真头像）
                    readAuthorInfoFromSidecar(file)?.let { (avatar, name) ->
                        dao.backfillAuthorInfo(file.absolutePath, avatar, name)
                        backfilledCount++
                    }
                }
            }

            Log.i(
                TAG,
                "Directory scan complete: existing=${existingPaths.size}, files=${candidateFiles.size}, rebuilt=$newCount, backfilled=$backfilledCount, skipped=$skippedCount"
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
            "com.ed.edqiu",
            "com.ed.twitterdownload",
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
        var publishedAt: Long? = null
        var avatarUrl: String? = null
        var authorName: String? = null

        val tweetIdIndex = parts.indexOfFirst { it.length > 10 && it.all(Char::isDigit) }
        if (tweetIdIndex >= 0) {
            tweetId = parts[tweetIdIndex]
            uploader = parts.take(tweetIdIndex).joinToString("_").ifBlank { "unknown" }
            mediaIndex = parts.getOrNull(tweetIdIndex + 1)?.toIntOrNull()
            quality = parts.drop(tweetIdIndex + 1)
                .dropWhile { it.toIntOrNull() != null || it == "fx" || it == "best" || it == "img" }
                .joinToString("_")
                .ifBlank { if (fileMediaType == MediaType.IMAGE) "图片" else parts.lastOrNull().orEmpty().ifBlank { "unknown" } }
            url = "https://x.com/i/status/$tweetId"
        } else if (parts.size >= 3) {
            quality = parts.last()
            tweetId = parts[parts.size - 2]
            uploader = parts.dropLast(2).joinToString("_").ifBlank { "unknown" }
            url = "https://x.com/i/status/$tweetId"
        } else if (parts.size == 2) {
            uploader = parts[0]
            quality = parts[1]
            // 2026-10 整改：合成 id 用路径 SHA-1（旧实现 name.hashCode() 跨扫描根同名文件
            // 生成相同 id，INSERT REPLACE 会静默覆盖另一条记录）
            tweetId = syntheticFileId(file)
            url = ""
        } else {
            uploader = "unknown"
            quality = if (fileMediaType == MediaType.IMAGE) "图片" else "unknown"
            // 2026-10 整改：合成 id 用路径 SHA-1（旧实现 name.hashCode() 跨扫描根同名文件
            // 生成相同 id，INSERT REPLACE 会静默覆盖另一条记录）
            tweetId = syntheticFileId(file)
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
                if (json.has("publishedAt") && !json.isNull("publishedAt")) {
                    json.optLong("publishedAt", 0L).takeIf { it > 0L }?.let { publishedAt = it }
                }
                if (json.has("avatarUrl") && !json.isNull("avatarUrl")) {
                    avatarUrl = json.optString("avatarUrl", "").takeIf { it.isNotBlank() }
                }
                if (json.has("authorName") && !json.isNull("authorName")) {
                    authorName = json.optString("authorName", "").takeIf { it.isNotBlank() }
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
            completedAt = file.lastModified(),
            publishedAt = publishedAt,
            avatarUrl = avatarUrl,
            authorName = authorName
        )
    }

    /** 只读 sidecar 的推文发布时间（epoch ms），供已有记录回填；无值返回 null。 */
    private fun readPublishedAtFromSidecar(file: File): Long? {
        val metaFile = File(file.absolutePath + ".meta.json")
        if (!metaFile.exists()) return null
        return runCatching {
            val json = org.json.JSONObject(metaFile.readText())
            if (json.has("publishedAt") && !json.isNull("publishedAt")) {
                json.optLong("publishedAt", 0L).takeIf { it > 0L }
            } else null
        }.getOrNull()
    }

    /** 只读 sidecar 的作者头像与显示名，供已有记录回填；两者皆无返回 null。 */
    private fun readAuthorInfoFromSidecar(file: File): Pair<String?, String?>? {
        val metaFile = File(file.absolutePath + ".meta.json")
        if (!metaFile.exists()) return null
        return runCatching {
            val json = org.json.JSONObject(metaFile.readText())
            val avatar = json.optString("avatarUrl", "").takeIf { it.isNotBlank() }
            val name = json.optString("authorName", "").takeIf {
                it.isNotBlank() && it != json.optString("uploader", "")
            }
            if (avatar != null || name != null) avatar to name else null
        }.getOrNull()
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

    /** 无推文 ID 文件的确定性合成 id：file_ + 路径 SHA-1 前 16 位（跨根同名不冲突）。 */
    private fun syntheticFileId(file: File): String =
        "file_" + java.security.MessageDigest.getInstance("SHA-1")
            .digest(file.absolutePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

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

    /** 已下载推文 ID 快照缓存（2026-10 整改：旧实现每次调用全量走一遍扫描根）。 */
    @Volatile
    private var cachedTweetIds: Set<String>? = null
    @Volatile
    private var cachedTweetIdsAt = 0L
    private const val SNAPSHOT_TTL_MS = 30_000L

    private fun tweetIdSnapshot(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        cachedTweetIds?.takeIf { now - cachedTweetIdsAt < SNAPSHOT_TTL_MS }?.let { return it }
        val ids = scanRoots(context)
            .asSequence()
            .filter { it.exists() && it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { MediaFileTypes.isSupportedMediaFile(it) }
            .mapNotNull { extractTweetIdFromFilename(it.name) }
            .toSet()
        cachedTweetIds = ids
        cachedTweetIdsAt = now
        return ids
    }

    /**
     * Check if a URL's tweet ID matches any existing file in the download directory.
     * 2026-10 整改：30s 快照缓存（DB 查询仍是主判据，此处仅为文件系统兜底），
     * 旧实现每次调用全量 walk 扫描根（数千文件数百 ms）。
     */
    fun isUrlAlreadyDownloaded(context: Context, url: String): Boolean {
        val tweetId = Regex("/status/(\\d+)").find(url)?.groupValues?.getOrNull(1) ?: return false
        return tweetId in tweetIdSnapshot(context)
    }
}
