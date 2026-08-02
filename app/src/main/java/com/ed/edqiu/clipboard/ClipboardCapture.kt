package com.ed.edqiu.clipboard

import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板读取助手。
 *
 * 重要平台约束（Android 10+）：
 *   后台进程无法读取剪贴板，仅有当 Edqiu 处于前台时才能读取。
 *   「自动捕获」的可靠时机是 App 回到前台（onResume），
 *   因此列表屏在 ON_RESUME 时调用 [readLatest]，并在顶部提供
 *   「粘贴并捕获」按钮作为后台复制场景的兜底。
 */
object ClipboardCapture {

    /** 读取剪贴板第一条文本内容；无内容或不可读时返回 null。 */
    fun readLatest(context: Context): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip ?: return@runCatching null
            if (clip.itemCount == 0) return@runCatching null
            clip.getItemAt(0).text?.toString()
        }.getOrNull()
    }
}
