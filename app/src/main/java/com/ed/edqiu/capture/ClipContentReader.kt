package com.ed.edqiu.capture

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 剪贴板多形态读取器（2026-11-04 小米 12 Pro / HyperOS 机型特性适配）。
 *
 * 背景：X App「分享→复制链接」多选推文时放进剪贴板的内容形态因 ROM/App 而异，
 * 单读「第一个条目的纯文本」会漏掉大多数形态：
 * - 多条目 ClipData（每条链接一个 Item）——只读 getItemAt(0) 丢掉其余链接；
 * - Intent 条目（Item.intent）——coerceToText 产出 `intent:#Intent;...;end` 编码串，
 *   链接被 URL 编码吞掉，正则完全看不见；
 * - HTML 条目（Item.htmlText）——部分 App 只把链接放进 <a href>；
 * - URI 条目——需要显式取 uri.toString()；
 * - 分享正文可能整体 URL 编码（%3A%2F%2F）。
 *
 * 这里把每种形态都解出来，原样 + URL 解码（两轮）全部交给上层正则提取，
 * 并打 EdqiuClip 诊断日志（真机排查「多条识别不了」的第一手证据）。
 */
object ClipContentReader {

    private const val TAG = "EdqiuClip"

    /** 读取剪贴板全部可用文本形态：原样条目 + Intent 解包 + URL 解码兜底，按序去重。 */
    fun readClipTexts(clip: ClipData?, context: Context): List<String> {
        if (clip == null || clip.itemCount <= 0) {
            Log.d(TAG, "clip empty (null=${clip == null})")
            return emptyList()
        }

        val raw = LinkedHashSet<String>()
        val shapes = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            shapes.append(buildString {
                if (item?.text != null) append('T')
                if (item?.uri != null) append('U')
                if (item?.intent != null) append('I')
                if (item?.htmlText != null) append('H')
            })
            if (i < clip.itemCount - 1) shapes.append('|')

            runCatching { item?.coerceToText(context)?.toString() }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { raw += it }
            runCatching { item?.htmlText }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { raw += it }
            runCatching { item?.uri?.toString() }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { raw += it }
            // Intent 条目显式解包：coerce 只给编码串，正文链接藏在这些 extra 里
            runCatching { item?.intent }.getOrNull()?.let { intent ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.takeIf { it.isNotBlank() }?.let { raw += it }
                intent.dataString
                    ?.takeIf { it.isNotBlank() }?.let { raw += it }
                intent.getStringExtra("browser_fallback_url")
                    ?.takeIf { it.isNotBlank() }?.let { raw += it }
            }
        }

        // URL 解码兜底（两轮：分享正文/Intent URI 常见单层编码，个别场景双层）
        val decodedOnce = raw.map { runCatching { Uri.decode(it) }.getOrDefault(it) }
        val decodedTwice = decodedOnce.map { runCatching { Uri.decode(it) }.getOrDefault(it) }
        val variants = LinkedHashSet<String>()
        variants += raw
        variants += decodedOnce
        variants += decodedTwice

        Log.d(
            TAG,
            "clip items=${clip.itemCount} mimeCount=${clip.description?.mimeTypeCount} " +
                "shapes=$shapes first=${variants.firstOrNull()?.take(120)?.replace('\n', ' ')}"
        )
        return variants.toList()
    }
}
