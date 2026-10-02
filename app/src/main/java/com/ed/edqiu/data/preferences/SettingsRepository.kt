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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 璁剧疆瀛樺偍锛圖ataStore Preferences锛夛細
 *  - 鐩戞帶鐩綍 URI锛圫AF 鎸佷箙鍖栨巿鏉冨悗鐨勬爲 URI 瀛楃涓诧紝鍙┖锛夛紱
 *  - 鑷姩鎹曡幏鍓创鏉垮紑鍏筹紙榛樿寮€锛夈€? */
class SettingsRepository(context: Context) {

    private val dataStore = context.dataStore

    // 2026-10 整改：偏好文件损坏/IO 异常时 dataStore.data 会向订阅方直接抛 IOException
    // （发生在启动链路即崩溃循环）——统一走带 catch 的流，损坏时回退为空偏好（各 flow 取默认值）。
    private val safeData: Flow<androidx.datastore.preferences.core.Preferences> =
        dataStore.data.catch { error ->
            if (error is java.io.IOException) {
                android.util.Log.w("SettingsRepository", "设置偏好读取失败，按默认值处理", error)
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw error
            }
        }

    val monitorDirUriFlow: Flow<String?> =
        safeData.map { it[MONITOR_URI] ?: DEFAULT_MONITOR_URI }

    val autoRetryFlow: Flow<Boolean> =
        safeData.map { it[AUTO_RETRY] ?: true }

    val backgroundSyncFlow: Flow<Boolean> =
        safeData.map { it[BACKGROUND_SYNC] ?: true }

    /** 备份目录（2026-09-28 默认重定向到手机公共目录，首次写入时自动新建 edqiu 文件夹） */
    val backupDirUriFlow: Flow<String?> =
        safeData.map { it[BACKUP_DIR_URI] ?: DEFAULT_BACKUP_DIR }

    val automaticBackupFlow: Flow<Boolean> =
        safeData.map { it[AUTOMATIC_BACKUP] ?: false }

    val lastBackupAtFlow: Flow<Long?> =
        safeData.map { it[LAST_BACKUP_AT] }

    val lastBackupErrorFlow: Flow<String?> =
        safeData.map { it[LAST_BACKUP_ERROR] }

    val seedColorIndexFlow: Flow<Int> =
        safeData.map { it[SEED_COLOR_INDEX] ?: 0 }

    /** 动态取色开关：true 跟随系统壁纸（Android 12+），false 使用 [seedColorIndexFlow] 种子色方案（默认） */
    val dynamicColorFlow: Flow<Boolean> =
        safeData.map { it[DYNAMIC_COLOR] ?: false }

    // ===== 主题设置（2026-08-15 主题设置页扩展） =====

    /** 主题模式：SYSTEM（默认）/ LIGHT / DARK */
    val themeModeFlow: Flow<ThemeMode> =
        safeData.map { ThemeMode.fromStorage(it[THEME_MODE]) }

    /** 强调色索引（复用 seedPresets 列表；默认 0 = 墨蓝/蓝色） */
    val keyColorIndexFlow: Flow<Int> =
        safeData.map { it[KEY_COLOR_INDEX] ?: 0 }

    /**
     * 强调色（Accent Color）—— ARGB 原始值，作为全局强调色的唯一事实来源。
     * 默认 0xFF2F4C8F（墨蓝）。预设色选择与自定义取色都写入此值。
     */
    val accentColorFlow: Flow<Int> =
        safeData.map { it[ACCENT_COLOR] ?: DEFAULT_ACCENT_COLOR }

    /** 色彩风格（TonalSpot / Vibrant / Expressive / FruitSalad / Fidelity / Content） */
    val tonalStyleFlow: Flow<TonalStyle> =
        safeData.map { TonalStyle.fromStorage(it[TONAL_STYLE]) }

    /** 色彩标准（SPEC_2021 / CAM16） */
    val monetSpecFlow: Flow<MonetSpec> =
        safeData.map { MonetSpec.fromStorage(it[MONET_SPEC]) }

    /** 顶栏底栏模糊（默认 ON） */
    val blurEnabledFlow: Flow<Boolean> =
        safeData.map { it[BLUR_ENABLED] ?: true }

    /** 磨砂强度 0.0-1.0（默认 0.6）：真玻璃 blur 4-20dp、卡片磨砂雾感、背景柔化 */
    val blurIntensityFlow: Flow<Float> =
        safeData.map { it[BLUR_INTENSITY] ?: 0.6f }

    /** 玻璃透明度 0.0-1.0（默认 0.6，0=实、1=透）：真玻璃表面色 alpha、卡片底色透明度 */
    val glassTransparencyFlow: Flow<Float> =
        safeData.map { it[GLASS_TRANSPARENCY] ?: 0.6f }

    /** 折射强度 0.0-1.0（默认 0.6）：真玻璃边缘 lens 位移 0.2x-1.6x（仅液态玻璃模式生效） */
    val refractionIntensityFlow: Flow<Float> =
        safeData.map { it[REFRACTION_INTENSITY] ?: 0.6f }

    /** 按压震动档位（2026-09-14）：0=关闭 1=轻(CLOCK_TICK) 2=中(VIRTUAL_KEY) 3=明确(CONFIRM) */
    val hapticStrengthFlow: Flow<Int> =
        safeData.map { it[HAPTIC_STRENGTH] ?: 2 }

    /** 强制最高刷新率（2026-09-14，默认 ON）：MainActivity 锁定设备支持的 120Hz 模式 */
    val highRefreshRateFlow: Flow<Boolean> =
        safeData.map { it[HIGH_REFRESH_RATE] ?: true }

    /** Apple 风格悬浮底栏（默认 ON） */
    val floatingTabBarFlow: Flow<Boolean> =
        safeData.map { it[FLOATING_TAB_BAR] ?: true }

    /** 悬浮底栏的液态玻璃效果（默认 ON；OFF 时 LiquidTabBar 退回扁平透明） */
    val liquidGlassEnabledFlow: Flow<Boolean> =
        safeData.map { it[LIQUID_GLASS_ENABLED] ?: true }

    /** 预测性返回手势（默认 ON；Android 14+） */
    val predictiveBackFlow: Flow<Boolean> =
        safeData.map { it[PREDICTIVE_BACK] ?: true }

    /** 界面缩放 0.5-1.0（默认 0.8 = 80%）；同时缩放 density（dp 排版/卡片）与字号（sp） */
    val displayScaleFlow: Flow<Float> =
        safeData.map { it[DISPLAY_SCALE] ?: 0.8f }

    /** 首次安装引导是否已完成（2026-10 UX 短板补齐：Cookie/代理开机引导）。 */
    val onboardingCompletedFlow: Flow<Boolean> =
        safeData.map { it[ONBOARDING_COMPLETED] ?: false }

    suspend fun setMonitorDirUri(uri: String?) {
        editSafe { prefs ->
            if (uri == null) prefs.remove(MONITOR_URI)
            else prefs[MONITOR_URI] = uri
        }
    }

    suspend fun setAutoRetry(enabled: Boolean) {
        editSafe { it[AUTO_RETRY] = enabled }
    }

    suspend fun setBackgroundSync(enabled: Boolean) {
        editSafe { it[BACKGROUND_SYNC] = enabled }
    }

    suspend fun setBackupDirUri(uri: String?) {
        editSafe { prefs ->
            if (uri == null) {
                prefs.remove(BACKUP_DIR_URI)
                prefs[AUTOMATIC_BACKUP] = false
            } else {
                prefs[BACKUP_DIR_URI] = uri
            }
        }
    }

    suspend fun setAutomaticBackup(enabled: Boolean) {
        editSafe { it[AUTOMATIC_BACKUP] = enabled }
    }

    suspend fun recordBackupSuccess(timestamp: Long) {
        editSafe { prefs ->
            prefs[LAST_BACKUP_AT] = timestamp
            prefs.remove(LAST_BACKUP_ERROR)
        }
    }

    suspend fun recordBackupError(message: String) {
        editSafe { it[LAST_BACKUP_ERROR] = message }
    }

    suspend fun setSeedColorIndex(index: Int) {
        editSafe { it[SEED_COLOR_INDEX] = index.coerceIn(0, 5) }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        editSafe { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        editSafe { it[THEME_MODE] = mode.storageValue }
    }

    suspend fun setKeyColorIndex(index: Int) {
        editSafe { it[KEY_COLOR_INDEX] = index.coerceIn(0, 5) }
    }

    /** 设置强调色（ARGB）。预设色与自定义取色统一走此入口。 */
    suspend fun setAccentColor(argb: Int) {
        editSafe { it[ACCENT_COLOR] = argb }
    }

    suspend fun setTonalStyle(style: TonalStyle) {
        editSafe { it[TONAL_STYLE] = style.storageValue }
    }

    suspend fun setMonetSpec(spec: MonetSpec) {
        editSafe { it[MONET_SPEC] = spec.storageValue }
    }

    suspend fun setBlurEnabled(enabled: Boolean) {
        editSafe { it[BLUR_ENABLED] = enabled }
    }

    suspend fun setBlurIntensity(intensity: Float) {
        editSafe { it[BLUR_INTENSITY] = intensity.coerceIn(0f, 1f) }
    }

    suspend fun setGlassTransparency(transparency: Float) {
        editSafe { it[GLASS_TRANSPARENCY] = transparency.coerceIn(0f, 1f) }
    }

    suspend fun setRefractionIntensity(intensity: Float) {
        editSafe { it[REFRACTION_INTENSITY] = intensity.coerceIn(0f, 1f) }
    }

    suspend fun setHapticStrength(level: Int) {
        editSafe { it[HAPTIC_STRENGTH] = level.coerceIn(0, 3) }
    }

    suspend fun setHighRefreshRate(enabled: Boolean) {
        editSafe { it[HIGH_REFRESH_RATE] = enabled }
    }

    suspend fun setFloatingTabBar(enabled: Boolean) {
        editSafe { it[FLOATING_TAB_BAR] = enabled }
    }

    suspend fun setLiquidGlassEnabled(enabled: Boolean) {
        editSafe { it[LIQUID_GLASS_ENABLED] = enabled }
    }

    suspend fun setPredictiveBack(enabled: Boolean) {
        editSafe { it[PREDICTIVE_BACK] = enabled }
    }

    suspend fun setDisplayScale(scale: Float) {
        editSafe { it[DISPLAY_SCALE] = scale.coerceIn(0.5f, 1.0f) }
    }

    suspend fun setOnboardingCompleted(done: Boolean) {
        editSafe { it[ONBOARDING_COMPLETED] = done }
    }

    /** 写入防护（2026-10）：磁盘满/IO 异常不炸调用协程（设置项写失败仅记日志）。 */
    private suspend fun editSafe(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runCatching { dataStore.edit(transform) }
            .onFailure { error ->
                // 2026-10：取消透传（与备份模块 runCatchingNotCancelled 同理），只吞真实 IO 失败
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.w("SettingsRepository", "设置写入失败（忽略）", error)
            }
    }

    companion object {
        const val DEFAULT_MONITOR_URI = "xinvox://downloads"
        /** 默认备份目录：手机公共存储根目录下的 edqiu 文件夹（文件管理器可直接浏览） */
        const val DEFAULT_BACKUP_DIR = "/storage/emulated/0/edqiu"
        const val DEFAULT_ACCENT_COLOR = 0xFF2F4C8F.toInt()
        private val MONITOR_URI = stringPreferencesKey("monitor_dir_uri")
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
        private val GLASS_TRANSPARENCY = floatPreferencesKey("glass_transparency")
        private val REFRACTION_INTENSITY = floatPreferencesKey("refraction_intensity")
        private val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        private val HIGH_REFRESH_RATE = booleanPreferencesKey("high_refresh_rate")
        private val FLOATING_TAB_BAR = booleanPreferencesKey("floating_tab_bar")
        private val LIQUID_GLASS_ENABLED = booleanPreferencesKey("liquid_glass_enabled")
        private val PREDICTIVE_BACK = booleanPreferencesKey("predictive_back")
        private val DISPLAY_SCALE = floatPreferencesKey("display_scale")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}

private val Context.dataStore by preferencesDataStore(name = "xinvox_settings")

