package com.ed.twitterdownloader.data.preferences

import android.content.Context
import android.content.SharedPreferences

class CloudSyncPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENABLED = "sync_enabled"
        private const val KEY_SERVER_URL = "webdav_server_url"
        private const val KEY_USERNAME = "webdav_username"
        private const val KEY_PASSWORD = "webdav_password"
        private const val KEY_REMOTE_PATH = "webdav_remote_path"
        private const val KEY_LAST_SYNC = "last_sync_time"
        private const val KEY_SYNC_COUNT = "sync_file_count"
        private const val KEY_PROVIDER_ID = "webdav_provider_id"
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var remotePath: String
        get() = prefs.getString(KEY_REMOTE_PATH, "Edqiu") ?: "Edqiu"
        set(value) = prefs.edit().putString(KEY_REMOTE_PATH, value.trim('/')).apply()

    /** Backward-compatible alias for old cloud directory setting. */
    var targetPath: String
        get() = remotePath
        set(value) { remotePath = value }

    var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var syncedFileCount: Int
        get() = prefs.getInt(KEY_SYNC_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_SYNC_COUNT, value).apply()

    /** 选中的网盘预设 id（见 WebDavProvider），默认 "custom" 表示自定义。 */
    var providerId: String
        get() = prefs.getString(KEY_PROVIDER_ID, "custom") ?: "custom"
        set(value) = prefs.edit().putString(KEY_PROVIDER_ID, value).apply()

    fun hasWebDavConfig(): Boolean = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    fun hasTargetPath(): Boolean = remotePath.isNotBlank()

    fun clear() = prefs.edit().clear().apply()
}

