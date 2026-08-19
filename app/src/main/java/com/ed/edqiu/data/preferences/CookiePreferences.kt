package com.ed.edqiu.data.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores Twitter/X authentication cookies for yt-dlp fallback.
 * Required cookies: auth_token and ct0 (CSRF token).
 * Users can extract these from their browser's cookie store.
 */
class CookiePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("twitter_cookies", Context.MODE_PRIVATE)

    fun getAuthToken(): String = prefs.getString("auth_token", "") ?: ""
    fun getCt0(): String = prefs.getString("ct0", "") ?: ""

    fun saveCookies(authToken: String, ct0: String) {
        prefs.edit()
            .putString("auth_token", authToken.trim())
            .putString("ct0", ct0.trim())
            .apply()
    }

    fun clearCookies() {
        prefs.edit().clear().apply()
    }

    /** Whether we have enough cookies to attempt yt-dlp authenticated extraction. */
    fun hasCookies(): Boolean = getAuthToken().isNotBlank() && getCt0().isNotBlank()
}

