package com.ed.edqiu.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.ed.edqiu.capture.EdqiuAccessibilityService

/** 读取当前输入法和无障碍捕获服务状态，不访问输入法私有数据。 */
object DeviceCapabilityReader {

    data class InputMethodState(
        val displayName: String,
        val componentName: String,
        val packageName: String
    )

    fun currentInputMethod(context: Context): InputMethodState? = runCatching {
        val rawComponent = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return@runCatching null
        val component = ComponentName.unflattenFromString(rawComponent)
            ?: return@runCatching null
        val inputMethodManager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val inputMethodInfo = inputMethodManager
            ?.enabledInputMethodList
            ?.firstOrNull { info ->
                info.id == rawComponent ||
                    (info.packageName == component.packageName &&
                        info.serviceName == component.className)
            }
        val displayName = inputMethodInfo
            ?.loadLabel(context.packageManager)
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?: component.packageName

        InputMethodState(
            displayName = displayName,
            componentName = rawComponent,
            packageName = component.packageName
        )
    }.getOrNull()

    fun isAccessibilityCaptureEnabled(context: Context): Boolean {
        val target = ComponentName(context, EdqiuAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return enabledServices
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it.packageName == target.packageName && it.className == target.className }
    }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
