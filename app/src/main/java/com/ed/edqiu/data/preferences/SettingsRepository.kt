package com.ed.edqiu.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ed.edqiu.ui.theme.MonetSpec
import com.ed.edqiu.ui.theme.ThemeMode
import com.ed.edqiu.ui.theme.TonalStyle
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

    // ===== 主题设置（2026-08-15 主题设置页扩展） =====

    /** 主题模式：SYSTEM（默认）/ LIGHT / DARK */
    val themeModeFlow: Flow<ThemeMode> =
        dataStore.data.map { ThemeMode.fromStorage(it[THEME_MODE]) }

    /** 强调色索引（复用 seedPresets 列表；默认 0 = 墨蓝/蓝色） */
    val keyColorIndexFlow: Flow<Int> =
        dataStore.data.map { it[KEY_COLOR_INDEX] ?: 0 }

    /**
     * 强调色（Accent Color）—— ARGB 原始值，作为全局强调色的唯一事实来源。
     * 默认 0xFF2F4C8F（墨蓝）。预设色选择与自定义取色都写入此值。
     */
    val accentColorFlow: Flow<Int> =
        dataStore.data.map { it[ACCENT_COLOR] ?: DEFAULT_ACCENT_COLOR }

    /** 色彩风格（TonalSpot / Vibrant / Expressive / FruitSalad / Fidelity / Content） */
    val tonalStyleFlow: Flow<TonalStyle> =
        dataStore.data.map { TonalStyle.fromStorage(it[TONAL_STYLE]) }

    /** 色彩标准（SPEC_2021 / CAM16） */
    val monetSpecFlow: Flow<MonetSpec> =
        dataStore.data.map { MonetSpec.fromStorage(it[MONET_SPEC]) }

    /** 顶栏底栏模糊（默认 ON） */
    val blurEnabledFlow: Flow<Boolean> =
        dataStore.data.map { it[BLUR_ENABLED] ?: true }

    /** 模糊强度 0.0-1.0（默认 0.6）。作用于背景莫奈色域与玻璃磨砂层的软化程度 */
    val blurIntensityFlow: Flow<Float> =
        dataStore.data.map { it[BLUR_INTENSITY] ?: 0.6f }

    /** 按压震动档位（2026-09-14）：0=关闭 1=轻(CLOCK_TICK) 2=中(VIRTUAL_KEY) 3=明确(CONFIRM) */
    val hapticStrengthFlow: Flow<Int> =
        dataStore.data.map { it[HAPTIC_STRENGTH] ?: 2 }

    /** 强制最高刷新率（2026-09-14，默认 ON）：MainActivity 锁定设备支持的 120Hz 模式 */
    val highRefreshRateFlow: Flow<Boolean> =
        dataStore.data.map { it[HIGH_REFRESH_RATE] ?: true }

    /** Apple 风格悬浮底栏（默认 ON） */
    val floatingTabBarFlow: Flow<Boolean> =
        dataStore.data.map { it[FLOATING_TAB_BAR] ?: true }

    /** 悬浮底栏的液态玻璃效果（默认 ON；OFF 时 LiquidTabBar 退回扁平透明） */
    val liquidGlassEnabledFlow: Flow<Boolean> =
        dataStore.data.map { it[LIQUID_GLASS_ENABLED] ?: true }

    /** 预测性返回手势（默认 ON；Android 14+） */
    val predictiveBackFlow: Flow<Boolean> =
        dataStore.data.map { it[PREDICTIVE_BACK] ?: true }

    /** 界面缩放 0.7-1.0（默认 0.8 = 80%） */
    val displayScaleFlow: Flow<Float> =
        dataStore.data.map { it[DISPLAY_SCALE] ?: 0.8f }

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

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.storageValue }
    }

    suspend fun setKeyColorIndex(index: Int) {
        dataStore.edit { it[KEY_COLOR_INDEX] = index.coerceIn(0, 5) }
    }

    /** 设置强调色（ARGB）。预设色与自定义取色统一走此入口。 */
    suspend fun setAccentColor(argb: Int) {
        dataStore.edit { it[ACCENT_COLOR] = argb }
    }

    suspend fun setTonalStyle(style: TonalStyle) {
        dataStore.edit { it[TONAL_STYLE] = style.storageValue }
    }

    suspend fun setMonetSpec(spec: MonetSpec) {
        dataStore.edit { it[MONET_SPEC] = spec.storageValue }
    }

    suspend fun setBlurEnabled(enabled: Boolean) {
        dataStore.edit { it[BLUR_ENABLED] = enabled }
    }

    suspend fun setBlurIntensity(intensity: Float) {
        dataStore.edit { it[BLUR_INTENSITY] = intensity.coerceIn(0f, 1f) }
    }

    suspend fun setHapticStrength(level: Int) {
        dataStore.edit { it[HAPTIC_STRENGTH] = level.coerceIn(0, 3) }
    }

    suspend fun setHighRefreshRate(enabled: Boolean) {
        dataStore.edit { it[HIGH_REFRESH_RATE] = enabled }
    }

    suspend fun setFloatingTabBar(enabled: Boolean) {
        dataStore.edit { it[FLOATING_TAB_BAR] = enabled }
    }

    suspend fun setLiquidGlassEnabled(enabled: Boolean) {
        dataStore.edit { it[LIQUID_GLASS_ENABLED] = enabled }
    }

    suspend fun setPredictiveBack(enabled: Boolean) {
        dataStore.edit { it[PREDICTIVE_BACK] = enabled }
    }

    suspend fun setDisplayScale(scale: Float) {
        dataStore.edit { it[DISPLAY_SCALE] = scale.coerceIn(0.7f, 1.0f) }
    }

    companion object {
        const val DEFAULT_MONITOR_URI = "xinvox://downloads"
        const val DEFAULT_ACCENT_COLOR = 0xFF2F4C8F.toInt()
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
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_COLOR_INDEX = intPreferencesKey("key_color_index")
        private val ACCENT_COLOR = intPreferencesKey("accent_color_argb")
        private val TONAL_STYLE = stringPreferencesKey("tonal_style")
        private val MONET_SPEC = stringPreferencesKey("monet_spec")
        private val BLUR_ENABLED = booleanPreferencesKey("blur_enabled")
        private val BLUR_INTENSITY = floatPreferencesKey("blur_intensity")
        private val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        private val HIGH_REFRESH_RATE = booleanPreferencesKey("high_refresh_rate")
        private val FLOATING_TAB_BAR = booleanPreferencesKey("floating_tab_bar")
        private val LIQUID_GLASS_ENABLED = booleanPreferencesKey("liquid_glass_enabled")
        private val PREDICTIVE_BACK = booleanPreferencesKey("predictive_back")
        private val DISPLAY_SCALE = floatPreferencesKey("display_scale")
    }
}

private val Context.dataStore by preferencesDataStore(name = "xinvox_settings")

