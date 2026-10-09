package com.ed.edqiu.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ed.edqiu.ui.theme.ThemeMode
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

    // ===== 主题设置（2026-08-15 主题设置页扩展） =====

    /** 主题模式：SYSTEM（默认）/ LIGHT / DARK */
    val themeModeFlow: Flow<ThemeMode> =
        safeData.map { ThemeMode.fromStorage(it[THEME_MODE]) }

    /**
     * 强调色（Accent Color）—— ARGB 原始值，作为全局 tint 的唯一事实来源。
     * 默认 0xFF007AFF（iOS 蓝）。预设色选择与自定义取色都写入此值。
     * 2026-10-10 固定配色重构：仅注入 primary 族 tint，背景/中性面固定不变。
     */
    val accentColorFlow: Flow<Int> =
        safeData.map { it[ACCENT_COLOR] ?: DEFAULT_ACCENT_COLOR }

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

    // ── 玻璃外观四项（v1.8.0）：默认值 = v1.7.0 既有行为，四项均为中性元 ──

    /** 描边粗细 dp（默认 1.25 = 原硬编码值）：玻璃内描边线宽，0 = 不描边 */
    val glassEdgeWidthFlow: Flow<Float> =
        safeData.map { it[GLASS_EDGE_WIDTH] ?: DEFAULT_GLASS_EDGE_WIDTH }

    /** 亮边强度倍数（默认 1.0 = 中性）：等比缩放内描边白高光与顶部光带 */
    val glassEdgeLightStrengthFlow: Flow<Float> =
        safeData.map { it[GLASS_EDGE_LIGHT_STRENGTH] ?: DEFAULT_GLASS_LIGHT_STRENGTH }

    /** 描边颜色 ARGB（默认 0 = 跟随主题色）：仅覆盖内描边 seed 层，不参与 ColorScheme 派生 */
    val glassEdgeColorFlow: Flow<Int> =
        safeData.map { it[GLASS_EDGE_COLOR] ?: DEFAULT_GLASS_EDGE_COLOR }

    /** 压暗程度 0.0-1.0（默认 0.0 = 不压暗）：玻璃材质层叠加黑色，深色模式自动 ×1.5 */
    val glassDimAmountFlow: Flow<Float> =
        safeData.map { it[GLASS_DIM_AMOUNT] ?: DEFAULT_GLASS_DIM_AMOUNT }

    /** 按压震动档位（2026-09-14）：0=关闭 1=轻(CLOCK_TICK) 2=中(VIRTUAL_KEY) 3=明确(CONFIRM) */
    val hapticStrengthFlow: Flow<Int> =
        safeData.map { it[HAPTIC_STRENGTH] ?: 2 }

    /** 强制最高刷新率（2026-09-14，默认 ON）：MainActivity 锁定设备支持的 120Hz 模式 */
    val highRefreshRateFlow: Flow<Boolean> =
        safeData.map { it[HIGH_REFRESH_RATE] ?: true }

    /**
     * 真双播放器开关（2026-10-03 批次F 定案，默认 ON）：
     * 滑动翻页时邻页用第二个静音 ExoPlayer 呈现活视频。最终生效 =
     * 本开关 ∧ FlagshipDetector 硬件达标（RAM ≥ 16GB，见 domain/FlagshipDetector）；
     * 不达标或关闭时自动回退「邻页真帧预览」（批次E），功能无损。
     * 设置页「播放与媒体库」分区提供带详细说明的开关。
     */
    val dualPlayerFlow: Flow<Boolean> =
        safeData.map { it[DUAL_PLAYER] ?: true }

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

    suspend fun setThemeMode(mode: ThemeMode) {
        editSafe { it[THEME_MODE] = mode.storageValue }
    }

    /** 设置强调色（ARGB）。预设色与自定义取色统一走此入口。 */
    suspend fun setAccentColor(argb: Int) {
        editSafe { it[ACCENT_COLOR] = argb }
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

    suspend fun setGlassEdgeWidth(width: Float) {
        editSafe { it[GLASS_EDGE_WIDTH] = width.coerceIn(0f, 2.5f) }
    }

    suspend fun setGlassEdgeLightStrength(strength: Float) {
        editSafe { it[GLASS_EDGE_LIGHT_STRENGTH] = strength.coerceIn(0f, 1.6f) }
    }

    /** 描边颜色；传 0 表示恢复「跟随主题色」。 */
    suspend fun setGlassEdgeColor(argb: Int) {
        editSafe { it[GLASS_EDGE_COLOR] = argb }
    }

    suspend fun setGlassDimAmount(amount: Float) {
        editSafe { it[GLASS_DIM_AMOUNT] = amount.coerceIn(0f, 1f) }
    }

    /**
     * 恢复「玻璃外观」分组的默认值（2026-10-09 v1.8.0）。
     *
     * 只重置本组四项，不触碰透明度 / 磨砂 / 折射 / 液态玻璃开关——
     * 分组级恢复默认的语义是「只回退这一组参数」，与全局重置严格区分。
     */
    suspend fun resetGlassAppearance() {
        editSafe { prefs ->
            prefs.remove(GLASS_EDGE_WIDTH)
            prefs.remove(GLASS_EDGE_LIGHT_STRENGTH)
            prefs.remove(GLASS_EDGE_COLOR)
            prefs.remove(GLASS_DIM_AMOUNT)
        }
    }

    /** 「玻璃外观」分组是否处于默认状态（用于恢复默认按钮的可用性判断）。 */
    val glassAppearanceIsDefaultFlow: Flow<Boolean> =
        safeData.map { prefs ->
            (prefs[GLASS_EDGE_WIDTH] ?: DEFAULT_GLASS_EDGE_WIDTH) == DEFAULT_GLASS_EDGE_WIDTH &&
                (prefs[GLASS_EDGE_LIGHT_STRENGTH] ?: DEFAULT_GLASS_LIGHT_STRENGTH) == DEFAULT_GLASS_LIGHT_STRENGTH &&
                (prefs[GLASS_EDGE_COLOR] ?: DEFAULT_GLASS_EDGE_COLOR) == DEFAULT_GLASS_EDGE_COLOR &&
                (prefs[GLASS_DIM_AMOUNT] ?: DEFAULT_GLASS_DIM_AMOUNT) == DEFAULT_GLASS_DIM_AMOUNT
        }

    suspend fun setHapticStrength(level: Int) {
        editSafe { it[HAPTIC_STRENGTH] = level.coerceIn(0, 3) }
    }

    suspend fun setHighRefreshRate(enabled: Boolean) {
        editSafe { it[HIGH_REFRESH_RATE] = enabled }
    }

    /** 真双播放器开关（见 dualPlayerFlow 注释） */
    suspend fun setDualPlayer(enabled: Boolean) {
        editSafe { it[DUAL_PLAYER] = enabled }
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
        const val DEFAULT_ACCENT_COLOR = 0xFF007AFF.toInt() // iOS 系统蓝
        // 玻璃外观四项默认值（= v1.7.0 既有行为；设置页「恢复默认」与首选项移除后共用）
        const val DEFAULT_GLASS_EDGE_WIDTH = 1.25f
        const val DEFAULT_GLASS_LIGHT_STRENGTH = 1f
        const val DEFAULT_GLASS_EDGE_COLOR = 0
        const val DEFAULT_GLASS_DIM_AMOUNT = 0f
        private val MONITOR_URI = stringPreferencesKey("monitor_dir_uri")
        private val AUTO_RETRY = booleanPreferencesKey("auto_retry")
        private val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
        private val BACKUP_DIR_URI = stringPreferencesKey("backup_dir_uri")
        private val AUTOMATIC_BACKUP = booleanPreferencesKey("automatic_backup")
        private val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        private val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val ACCENT_COLOR = intPreferencesKey("accent_color_argb")
        private val BLUR_ENABLED = booleanPreferencesKey("blur_enabled")
        private val BLUR_INTENSITY = floatPreferencesKey("blur_intensity")
        private val GLASS_TRANSPARENCY = floatPreferencesKey("glass_transparency")
        private val REFRACTION_INTENSITY = floatPreferencesKey("refraction_intensity")
        // 玻璃外观四项（v1.8.0）
        private val GLASS_EDGE_WIDTH = floatPreferencesKey("glass_edge_width")
        private val GLASS_EDGE_LIGHT_STRENGTH = floatPreferencesKey("glass_edge_light_strength")
        private val GLASS_EDGE_COLOR = intPreferencesKey("glass_edge_color_argb")
        private val GLASS_DIM_AMOUNT = floatPreferencesKey("glass_dim_amount")
        private val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        private val HIGH_REFRESH_RATE = booleanPreferencesKey("high_refresh_rate")
        private val FLOATING_TAB_BAR = booleanPreferencesKey("floating_tab_bar")
        private val DUAL_PLAYER = booleanPreferencesKey("dual_player")
        private val LIQUID_GLASS_ENABLED = booleanPreferencesKey("liquid_glass_enabled")
        private val PREDICTIVE_BACK = booleanPreferencesKey("predictive_back")
        private val DISPLAY_SCALE = floatPreferencesKey("display_scale")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}

private val Context.dataStore by preferencesDataStore(name = "xinvox_settings")

