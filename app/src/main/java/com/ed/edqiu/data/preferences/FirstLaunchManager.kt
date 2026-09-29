package com.ed.edqiu.data.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * 首次启动标记：仅在新安装 / 清除数据后显示开屏。
 * 用 SharedPreferences（非 DataStore），因为初始化极快、无协程依赖。
 */
object FirstLaunchManager {
    private const val PREFS_NAME = "xinvox_first_launch"
    private const val KEY_DONE = "splash_shown"

    fun isFirstLaunch(context: Context): Boolean {
        return !prefs(context).getBoolean(KEY_DONE, false)
    }

    fun markShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
