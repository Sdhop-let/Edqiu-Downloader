package com.ed.edqiu.data.preferences

import android.content.Context

/** Stores app-only deleted media paths so directory rebuild does not show them again. */
class HiddenHistoryPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("hidden_history_files", Context.MODE_PRIVATE)

    fun isHidden(filePath: String): Boolean {
        if (filePath.isBlank()) return false
        return prefs.getStringSet(KEY_HIDDEN_PATHS, emptySet())?.contains(filePath) == true
    }

    fun hide(filePath: String) {
        if (filePath.isBlank()) return
        val next = prefs.getStringSet(KEY_HIDDEN_PATHS, emptySet()).orEmpty().toMutableSet()
        next += filePath
        prefs.edit().putStringSet(KEY_HIDDEN_PATHS, next).apply()
    }

    fun unhide(filePath: String) {
        if (filePath.isBlank()) return
        val next = prefs.getStringSet(KEY_HIDDEN_PATHS, emptySet()).orEmpty().toMutableSet()
        next -= filePath
        prefs.edit().putStringSet(KEY_HIDDEN_PATHS, next).apply()
    }

    companion object {
        private const val KEY_HIDDEN_PATHS = "hidden_paths"
    }
}
