package com.ed.twitterdownloader.provider

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
 * 浠ュ彧璇绘柟寮忓悜鍚岀鍚嶅簲鐢ㄦ毚闇蹭笅杞藉櫒鐨?Android/data 涓嬭浇鐩綍銆? * Edqiu 閫氳繃璇?Provider 鏋氫妇濯掍綋涓?sidecar锛屼笉闇€瑕佺洿鎺ョ┛閫忕郴缁熺殑 Android/data 闄愬埗銆? */
class DownloadMediaProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
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
        const val AUTHORITY = "com.ed.twitterdownload.media"
        private const val LEGACY_AUTHORITY = "com.ed.twitterdownloader.media"
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
        }
    }
}

