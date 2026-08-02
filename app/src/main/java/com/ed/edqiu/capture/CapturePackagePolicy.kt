package com.ed.edqiu.capture

import android.content.Context
import com.ed.edqiu.system.DeviceCapabilityReader

/** 限制哪些应用的疑似复制事件可以触发剪贴板读取。 */
class CapturePackagePolicy(private val context: Context) {

    fun isAllowed(packageName: CharSequence?): Boolean {
        val sourcePackage = packageName?.toString()?.trim().orEmpty()
        if (sourcePackage.isBlank() || sourcePackage == context.packageName) return false

        val inputMethodPackage = DeviceCapabilityReader.currentInputMethod(context)?.packageName
        if (sourcePackage == inputMethodPackage) return false

        return sourcePackage in ALLOWED_PACKAGES
    }

    private companion object {
        val ALLOWED_PACKAGES = setOf(
            "com.twitter.android",
            "com.tencent.mm",
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.google.android.apps.chrome",
            "com.microsoft.emmx",
            "com.microsoft.emmx.beta",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.brave.browser",
            "com.heytap.browser",
            "com.coloros.browser",
            "com.opera.browser"
        )
    }
}
