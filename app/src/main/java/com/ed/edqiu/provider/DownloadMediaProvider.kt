package com.ed.edqiu.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 以只读方式向同签名应用暴露下载器的 Android/data 下载目录。
 * Edqiu 通过该 Provider 枚举媒体与 sidecar，不需要直接穿透系统的 Android/data 限制。
 *
 * 2026-10 安全加固：manifest 的 signature 自定义权限存在「被抢先安装的恶意应用抢注」
 * 风险（抢注者自动成为权限 owner 并持权）。因此在每个入口做运行时双重校验——
 * 调用方必须与本应用签名一致才放行，权限持有但签名不符一律拒绝。
 */
class DownloadMediaProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** 调用方可信校验：uid 归属包必须与本应用同签名（自身调用恒通过）。 */
    private fun isTrustedCaller(): Boolean {
        val appContext = context ?: return false
        val callingUid = android.os.Binder.getCallingUid()
        if (callingUid == android.os.Process.myUid()) return true
        val pm = appContext.packageManager
        val callerPackages = pm.getPackagesForUid(callingUid) ?: return false
        return callerPackages.any { pkg ->
            pm.checkSignatures(appContext.packageName, pkg) >= android.content.pm.PackageManager.SIGNATURE_MATCH
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (!isTrustedCaller()) throw SecurityException("Caller is not trusted: " + callingPackage.orEmpty())
        if (URI_MATCHER.match(uri) != MATCH_FILES) {
            throw IllegalArgumentException("Unsupported URI: $uri")
        }

        val columns = projection?.takeIf { it.isNotEmpty() } ?: DEFAULT_COLUMNS
        val cursor = MatrixCursor(columns)
        val root = downloadRoot()
        Log.i(
            TAG,
            "Query from ${callingPackage.orEmpty()}: root=${root.absolutePath}, exists=${root.exists()}, " +
                "isDirectory=${root.isDirectory}, children=${root.listFiles()?.size ?: -1}"
        )
        if (!root.exists()) return cursor

        val files = root.walkTopDown().filter { it.isFile }.toList()
        Log.i(TAG, "Serving ${files.size} Android/data download files to ${callingPackage.orEmpty()}")
        files.forEach { file ->
                val relativePath = file.relativeTo(root).invariantSeparatorsPath
                val values = mapOf(
                    COLUMN_RELATIVE_PATH to relativePath,
                    OpenableColumns.DISPLAY_NAME to file.name,
                    OpenableColumns.SIZE to file.length(),
                    COLUMN_MIME_TYPE to mimeType(file),
                    COLUMN_LAST_MODIFIED to file.lastModified()
                )
                cursor.addRow(columns.map { values[it] })
            }
        return cursor
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        MATCH_FILES -> "vnd.android.cursor.dir/vnd.$AUTHORITY.file"
        MATCH_FILE -> resolveFile(uri)?.let(::mimeType)
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!isTrustedCaller()) throw SecurityException("Caller is not trusted: " + callingPackage.orEmpty())
        if (URI_MATCHER.match(uri) != MATCH_FILE || mode != "r") {
            throw FileNotFoundException("Unsupported URI or mode: $uri ($mode)")
        }
        val file = resolveFile(uri)
            ?.takeIf { it.exists() && it.isFile }
            ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Read-only provider")

    private fun downloadRoot(): File {
        val appContext = requireNotNull(context)
        return appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "Download")
    }

    private fun resolveFile(uri: Uri): File? {
        val relativePath = uri.pathSegments.drop(1).joinToString("/")
        if (relativePath.isBlank()) return null
        val root = downloadRoot().canonicalFile
        val target = File(root, relativePath).canonicalFile
        val allowedPrefix = root.path + File.separator
        return target.takeIf { it.path.startsWith(allowedPrefix) }
    }

    private fun mimeType(file: File): String {
        if (file.name.endsWith(".meta.json", ignoreCase = true)) return "application/json"
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    companion object {
        private const val TAG = "DownloadMediaProvider"
        const val AUTHORITY = "com.ed.Edqiu.media"
        private const val LEGACY_AUTHORITY = "com.ed.edqiu.media"
        private const val OLDEST_AUTHORITY = "com.ed.twitterdownload.media"
        const val COLUMN_RELATIVE_PATH = "relative_path"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_LAST_MODIFIED = "last_modified"

        private const val MATCH_FILES = 1
        private const val MATCH_FILE = 2
        private val DEFAULT_COLUMNS = arrayOf(
            COLUMN_RELATIVE_PATH,
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            COLUMN_MIME_TYPE,
            COLUMN_LAST_MODIFIED
        )
        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "files", MATCH_FILES)
            addURI(AUTHORITY, "file/*", MATCH_FILE)
            addURI(LEGACY_AUTHORITY, "files", MATCH_FILES)
            addURI(LEGACY_AUTHORITY, "file/*", MATCH_FILE)
            addURI(OLDEST_AUTHORITY, "files", MATCH_FILES)
            addURI(OLDEST_AUTHORITY, "file/*", MATCH_FILE)
        }
    }
}

