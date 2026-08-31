package com.ed.edqiu.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.ed.edqiu.data.metadata.MetadataFetcher
import com.ed.edqiu.data.preferences.DownloadPathPreferences
import com.ed.edqiu.domain.TweetIdExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载目录监控器：扫描下载器 Android/data 下载目录或用户授权的 SAF 目录。
 * 媒体 name.ext 与 sidecar name.ext.meta.json 按完整文件名配对。
 */
class DownloadMonitor(private val context: Context) {

    private val metadataFetcher = MetadataFetcher()

    data class DownloadedItem(
        val tweetId: String,
        val filePath: String,
        val foundAt: Long,
        val authorId: String? = null,
        val authorName: String? = null,
        val caption: String? = null,
        val thumbnailUrl: String? = null
    )

    sealed interface ScanResult {
        data class Success(val items: Map<String, DownloadedItem>) : ScanResult
        data class Failure(val reason: String) : ScanResult
    }

    private data class FileEntry(
        val name: String,
        val relativePath: String,
        val uri: Uri,
        val lastModified: Long
    )

    suspend fun scan(monitorUri: String?): ScanResult =
        withContext(Dispatchers.IO) {
            val uri = monitorUri?.let(Uri::parse)
                ?: Uri.parse(INTERNAL_MONITOR_URI)
            runCatching {
                val entries = when {
                    uri.toString() == INTERNAL_MONITOR_URI -> queryInternalFiles()
                    uri.authority in DOWNLOADER_AUTHORITIES -> queryDownloaderFiles(uri)
                    uri.scheme == "file" -> queryFileTree(File(requireNotNull(uri.path)))
                    uri.scheme.isNullOrBlank() -> queryFileTree(File(uri.toString()))
                    else -> querySafFiles(uri)
                }
                val downloads = buildDownloadMap(entries)
                Log.i(TAG, "Scanned ${entries.size} files, matched ${downloads.size} tweets from $uri")
                ScanResult.Success(downloads)
            }.getOrElse { error ->
                Log.w(TAG, "Failed to scan $uri: ${error.message}")
                ScanResult.Failure(error.message ?: "Scan failed")
            }
        }

    /**
     * 合并扫描监控目录（用户在设置中指定的 SAF 目录，或内置下载目录）
     * 与下载器设置的自定义下载目录，按 tweetId 汇总，监控目录结果优先。
     */
    suspend fun scanMonitorAndDownloadDirs(monitorUri: String?): Map<String, DownloadedItem> {
        val merged = LinkedHashMap<String, DownloadedItem>()
        (scan(monitorUri) as? ScanResult.Success)?.let { merged.putAll(it.items) }
        runCatching {
            val downloadDir = DownloadPathPreferences(context).resolveDownloadDir(context)
            if (downloadDir.isNotBlank()) {
                (scan(Uri.fromFile(File(downloadDir)).toString()) as? ScanResult.Success)
                    ?.items
                    ?.forEach { (tweetId, item) -> merged.putIfAbsent(tweetId, item) }
            }
        }
        return merged
    }

    private fun queryInternalFiles(): List<FileEntry> {
        val root = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            ""
        )
        if (!root.exists() || !root.isDirectory) return emptyList()
        return queryFileTree(root)
    }

    private fun queryFileTree(root: File): List<FileEntry> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                FileEntry(
                    name = file.name,
                    relativePath = file.absolutePath,
                    uri = Uri.fromFile(file),
                    lastModified = file.lastModified()
                )
            }
            .toList()
    }

    private fun queryDownloaderFiles(filesUri: Uri): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        val cursor = context.contentResolver.query(
            filesUri,
            arrayOf(
                COLUMN_RELATIVE_PATH,
                OpenableColumns.DISPLAY_NAME,
                COLUMN_LAST_MODIFIED
            ),
            null,
            null,
            null
        ) ?: error("Downloader media provider is unavailable: $filesUri")
        cursor.use {
            val pathIndex = cursor.getColumnIndexOrThrow(COLUMN_RELATIVE_PATH)
            val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            val modifiedIndex = cursor.getColumnIndexOrThrow(COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(pathIndex) ?: continue
                val name = cursor.getString(nameIndex) ?: continue
                entries += FileEntry(
                    name = name,
                    relativePath = relativePath,
                    uri = downloaderFileUri(filesUri.authority ?: DOWNLOADER_AUTHORITY, relativePath),
                    lastModified = cursor.getLong(modifiedIndex)
                )
            }
        }
        return entries
    }

    private fun querySafFiles(treeUri: Uri): List<FileEntry> {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Monitor directory is unavailable: $treeUri")
        return tree.walkFiles()
    }

    private fun buildDownloadMap(entries: List<FileEntry>): Map<String, DownloadedItem> {
        val metaByMediaPath = mutableMapOf<String, MetadataFetcher.DownloaderMeta>()
        entries.forEach { entry ->
            if (!entry.relativePath.endsWith(META_SUFFIX, ignoreCase = true)) return@forEach
            val mediaPath = entry.relativePath.dropLast(META_SUFFIX.length)
            metadataFetcher.parseDownloaderMeta(readText(entry.uri))?.let {
                metaByMediaPath[mediaPath] = it
            }
        }

        val result = mutableMapOf<String, DownloadedItem>()
        entries.forEach { entry ->
            if (!isSupportedMedia(entry.name)) return@forEach
            val meta = metaByMediaPath[entry.relativePath]
            val tweetId = meta?.tweetId
                ?.takeIf { TWEET_ID.matches(it) }
                ?: TweetIdExtractor.fromFileName(entry.name)
                ?: return@forEach
            if (result.containsKey(tweetId)) return@forEach

            result[tweetId] = DownloadedItem(
                tweetId = tweetId,
                filePath = entry.relativePath,
                foundAt = entry.lastModified.takeIf { it > 0L } ?: System.currentTimeMillis(),
                authorId = meta?.uploader?.let { if (it.startsWith("@")) it else "@$it" },
                authorName = meta?.authorName ?: meta?.uploader,
                caption = meta?.title,
                thumbnailUrl = meta?.thumbnail
            )
        }
        return result
    }

    private fun DocumentFile.walkFiles(parentPath: String = ""): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val relativePath = if (parentPath.isBlank()) name else "$parentPath/$name"
            when {
                child.isDirectory -> result += child.walkFiles(relativePath)
                child.isFile -> result += FileEntry(
                    name = name,
                    relativePath = relativePath,
                    uri = child.uri,
                    lastModified = child.lastModified()
                )
            }
        }
        return result
    }

    private fun readText(uri: Uri): String? = try {
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).readText()
        } else {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }
    } catch (_: Exception) {
        null
    }

    private fun downloaderFileUri(authority: String, relativePath: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath("file")
            .appendPath(relativePath)
            .build()

    private fun isSupportedMedia(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_MEDIA_EXTENSIONS
    }

    companion object {
        private const val TAG = "DownloadMonitor"
        const val INTERNAL_MONITOR_URI = "xinvox://downloads"
        private const val DOWNLOADER_PACKAGE = "com.ed.Edqiu"
        private const val DOWNLOADER_AUTHORITY = "$DOWNLOADER_PACKAGE.media"
        private const val LEGACY_DOWNLOADER_AUTHORITY = "com.ed.edqiu.media"
        private const val OLDEST_DOWNLOADER_AUTHORITY = "com.ed.twitterdownload.media"
        private val DOWNLOADER_AUTHORITIES = setOf(
            DOWNLOADER_AUTHORITY,
            LEGACY_DOWNLOADER_AUTHORITY,
            OLDEST_DOWNLOADER_AUTHORITY,
        )
        private const val COLUMN_RELATIVE_PATH = "relative_path"
        private const val COLUMN_LAST_MODIFIED = "last_modified"
        private const val META_SUFFIX = ".meta.json"
        private val TWEET_ID = Regex("\\d{11,25}")
        private val SUPPORTED_MEDIA_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "m4v", "jpg", "jpeg", "png", "webp", "gif"
        )
    }
}
