package com.ed.edqiu.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 浏览模式（2026-11-07 隐私模式定案；同日改版命名对齐直觉）：
 * - PRIVATE 标准模式 = 媒体正常显示；
 * - PUBLIC 隐私模式 = 不适合公开展示的视频/图片全量模糊（按住可临时查看）。
 *
 * 用 SharedPreferences（非 DataStore）：启动链 setContent 前必须**同步**读到上次模式——
 * 若走 DataStore 异步首读，上次选「隐私模式」的用户会在读到前闪现清晰媒体 = 泄露事故；
 * 读偏好用同步通道、读 UI 状态用 StateFlow 转发，两全。FirstLaunchManager 同款先例。
 */
enum class PrivacyMode(val storageValue: String) {
    /** 标准模式：媒体正常显示。注意枚举名以「使用场景」命名——PRIVATE=私人环境放心看。 */
    PRIVATE("private"),

    /** 隐私模式：媒体全量模糊防偷看（按住临时查看）。PUBLIC=公共场合防偷看——
     *  反直觉点：PUBLIC 才是模糊模式，全链路映射 `privacyBlurActive = mode == PUBLIC`，
     *  后续维护者勿按字面猜反（2026-11-07 定案命名，历史沿用勿改）。 */
    PUBLIC("public");

    companion object {
        fun from(value: String?): PrivacyMode =
            if (value == PUBLIC.storageValue) PUBLIC else PRIVATE
    }
}

object PrivacyModeStore {
    private const val PREFS_NAME = "edqiu_privacy"
    private const val KEY_MODE = "browse_mode"
    private const val KEY_ASK = "ask_on_startup"

    @Volatile
    private var initialized = false

    private val _mode = MutableStateFlow(PrivacyMode.PRIVATE)
    private val _askOnStartup = MutableStateFlow(true)

    /** 进程内一次：同步读盘预热 StateFlow（MainActivity.setContent 前调用） */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = prefs(context)
            _mode.value = PrivacyMode.from(prefs.getString(KEY_MODE, null))
            _askOnStartup.value = prefs.getBoolean(KEY_ASK, true)
            initialized = true
        }
    }

    private fun ensureInit(context: Context) {
        init(context.applicationContext)
    }

    fun modeFlow(context: Context): StateFlow<PrivacyMode> {
        ensureInit(context)
        return _mode
    }

    fun askOnStartupFlow(context: Context): StateFlow<Boolean> {
        ensureInit(context)
        return _askOnStartup
    }

    /** 同步读当前模式（启动门控/防闪现场景用） */
    fun currentMode(context: Context): PrivacyMode {
        ensureInit(context)
        return _mode.value
    }

    fun currentAskOnStartup(context: Context): Boolean {
        ensureInit(context)
        return _askOnStartup.value
    }

    fun setMode(context: Context, mode: PrivacyMode) {
        ensureInit(context)
        prefs(context).edit().putString(KEY_MODE, mode.storageValue).apply()
        _mode.value = mode
    }

    fun setAskOnStartup(context: Context, enabled: Boolean) {
        ensureInit(context)
        prefs(context).edit().putBoolean(KEY_ASK, enabled).apply()
        _askOnStartup.value = enabled
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
