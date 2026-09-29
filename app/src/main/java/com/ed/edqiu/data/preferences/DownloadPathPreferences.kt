package com.ed.edqiu.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

class DownloadPathPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("download_path", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USE_CUSTOM = "use_custom_path"
        private const val KEY_CUSTOM_PATH = "custom_path"
        private const val KEY_CUSTOM_TREE_URI = "custom_tree_uri"
        private const val KEY_CUSTOM_DISPLAY_PATH = "custom_display_path"
    }

    var useCustomPath: Boolean
        get() = prefs.getBoolean(KEY_USE_CUSTOM, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_CUSTOM, value).apply()

    var customPath: String
        get() = prefs.getString(KEY_CUSTOM_PATH, "") ?: ""
        set(value) = prefs.edit()
            .putString(KEY_CUSTOM_PATH, normalizeManualPath(value))
            .putString(KEY_CUSTOM_TREE_URI, value.takeIf { it.startsWith("content://") } ?: "")
            .apply()

    var customTreeUri: String
        get() = prefs.getString(KEY_CUSTOM_TREE_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_TREE_URI, value).apply()

    var customDisplayPath: String
        get() = prefs.getString(KEY_CUSTOM_DISPLAY_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_DISPLAY_PATH, value).apply()

    val hasCustomTreeUri: Boolean
        get() = customTreeUri.isNotBlank()

    fun saveCustomTreeUri(uri: String, displayPath: String) {
        prefs.edit()
            .putString(KEY_CUSTOM_TREE_URI, uri)
            .putString(KEY_CUSTOM_PATH, uri)
            .putString(KEY_CUSTOM_DISPLAY_PATH, displayPath)
            .putBoolean(KEY_USE_CUSTOM, true)
            .apply()
    }

    fun displayDownloadDir(context: Context): String {
        if (useCustomPath && customDisplayPath.isNotBlank()) return customDisplayPath
        return resolveDownloadDir(context)
    }

    fun resolveDownloadDir(context: Context): String {
        if (useCustomPath && customTreeUri.isNotBlank()) {
            return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                ?: context.filesDir.absolutePath
        }

        if (useCustomPath && customPath.isNotBlank()) {
            val dir = File(normalizeManualPath(customPath))
            if (dir.exists() || dir.mkdirs()) {
                return dir.absolutePath
            }
        }
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
            ?: context.filesDir.absolutePath
    }

    fun resolveCustomTreeDisplayName(): String {
        val uri = customTreeUri.takeIf { it.isNotBlank() } ?: return ""
        return runCatching {
            val parsed = android.net.Uri.parse(uri)
            DocumentsContract.getTreeDocumentId(parsed).substringAfter(':').replace(':', '/')
                .ifBlank { customDisplayPath.ifBlank { uri } }
        }.getOrElse { customDisplayPath.ifBlank { uri } }
    }

    fun normalizeManualPath(value: String): String {
        val trimmed = value.trim().trim('"')
        if (trimmed.isBlank() || trimmed.startsWith("content://")) return trimmed
        val withoutFileScheme = trimmed.removePrefix("file://")
        if (withoutFileScheme.startsWith("/")) return withoutFileScheme
        val clean = withoutFileScheme
            .removePrefix("内部存储/")
            .removePrefix("内置存储/")
            .removePrefix("sdcard/")
            .removePrefix("/sdcard/")
            .trimStart('/')
        return File(Environment.getExternalStorageDirectory(), clean).absolutePath
    }
}

