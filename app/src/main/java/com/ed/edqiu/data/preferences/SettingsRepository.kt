package com.ed.edqiu.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 璁剧疆瀛樺偍锛圖ataStore Preferences锛夛細
 *  - 鐩戞帶鐩綍 URI锛圫AF 鎸佷箙鍖栨巿鏉冨悗鐨勬爲 URI 瀛楃涓诧紝鍙┖锛夛紱
 *  - 鑷姩鎹曡幏鍓创鏉垮紑鍏筹紙榛樿寮€锛夈€? */
class SettingsRepository(context: Context) {

    private val dataStore = context.dataStore

    val monitorDirUriFlow: Flow<String?> =
        dataStore.data.map { it[MONITOR_URI] ?: DEFAULT_MONITOR_URI }

    val autoCaptureFlow: Flow<Boolean> =
        dataStore.data.map { it[AUTO_CAPTURE] ?: true }

    val autoRetryFlow: Flow<Boolean> =
        dataStore.data.map { it[AUTO_RETRY] ?: true }

    val backgroundSyncFlow: Flow<Boolean> =
        dataStore.data.map { it[BACKGROUND_SYNC] ?: true }

    val backupDirUriFlow: Flow<String?> =
        dataStore.data.map { it[BACKUP_DIR_URI] }

    val automaticBackupFlow: Flow<Boolean> =
        dataStore.data.map { it[AUTOMATIC_BACKUP] ?: false }

    val lastBackupAtFlow: Flow<Long?> =
        dataStore.data.map { it[LAST_BACKUP_AT] }

    val lastBackupErrorFlow: Flow<String?> =
        dataStore.data.map { it[LAST_BACKUP_ERROR] }

    val seedColorIndexFlow: Flow<Int> =
        dataStore.data.map { it[SEED_COLOR_INDEX] ?: 0 }

    /** 动态取色开关：true 跟随系统壁纸（Android 12+），false 使用 [seedColorIndexFlow] 种子色方案（默认） */
    val dynamicColorFlow: Flow<Boolean> =
        dataStore.data.map { it[DYNAMIC_COLOR] ?: false }

    suspend fun setMonitorDirUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(MONITOR_URI)
            else prefs[MONITOR_URI] = uri
        }
    }

    suspend fun setAutoCapture(enabled: Boolean) {
        dataStore.edit { it[AUTO_CAPTURE] = enabled }
    }

    suspend fun setAutoRetry(enabled: Boolean) {
        dataStore.edit { it[AUTO_RETRY] = enabled }
    }

    suspend fun setBackgroundSync(enabled: Boolean) {
        dataStore.edit { it[BACKGROUND_SYNC] = enabled }
    }

    suspend fun setBackupDirUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(BACKUP_DIR_URI)
                prefs[AUTOMATIC_BACKUP] = false
            } else {
                prefs[BACKUP_DIR_URI] = uri
            }
        }
    }

    suspend fun setAutomaticBackup(enabled: Boolean) {
        dataStore.edit { it[AUTOMATIC_BACKUP] = enabled }
    }

    suspend fun recordBackupSuccess(timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[LAST_BACKUP_AT] = timestamp
            prefs.remove(LAST_BACKUP_ERROR)
        }
    }

    suspend fun recordBackupError(message: String) {
        dataStore.edit { it[LAST_BACKUP_ERROR] = message }
    }

    suspend fun setSeedColorIndex(index: Int) {
        dataStore.edit { it[SEED_COLOR_INDEX] = index.coerceIn(0, 5) }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    companion object {
        const val DEFAULT_MONITOR_URI = "xinvox://downloads"
        private val MONITOR_URI = stringPreferencesKey("monitor_dir_uri")
        private val AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        private val AUTO_RETRY = booleanPreferencesKey("auto_retry")
        private val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
        private val BACKUP_DIR_URI = stringPreferencesKey("backup_dir_uri")
        private val AUTOMATIC_BACKUP = booleanPreferencesKey("automatic_backup")
        private val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        private val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
        private val SEED_COLOR_INDEX = intPreferencesKey("seed_color_index")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}

private val Context.dataStore by preferencesDataStore(name = "xinvox_settings")

