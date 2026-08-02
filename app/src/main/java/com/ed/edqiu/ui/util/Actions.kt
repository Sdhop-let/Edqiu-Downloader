package com.ed.edqiu.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val DOWNLOADER_MEDIA_AUTHORITY = "com.ed.twitterdownload.media"
private const val LEGACY_DOWNLOADER_MEDIA_AUTHORITY = "com.ed.twitterdownloader.media"
private val DOWNLOADER_MEDIA_AUTHORITIES = setOf(DOWNLOADER_MEDIA_AUTHORITY, LEGACY_DOWNLOADER_MEDIA_AUTHORITY)

/** 时间格式化：复制时间/下载时间展示。 */
fun formatTime(ts: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return sdf.format(Date(ts))
}

/** 相对时间格式化：如 "2 分钟前"、"3 小时前"、"2 天前"，超过 7 天回退到绝对时间。 */
fun formatRelativeTime(ts: Long, now: Long = System.currentTimeMillis()): String {
    val diff = abs(now - ts)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        else -> formatTime(ts)
    }
}

/** 作者头像占位字母（取名称首字）。 */
fun initials(name: String?): String {
    if (name.isNullOrBlank()) return "?"
    val clean = name.trimStart('@')
    return clean.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** 复制文本到剪贴板（不显示 Toast，由调用方通过 Snackbar 反馈）。 */
fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun twitterStatusWebUri(tweetId: String): String = "https://x.com/i/status/$tweetId"

private val X_APP_PACKAGES = listOf("com.twitter.android", "com.x.android")

fun openTwitterStatus(context: Context, tweetId: String): Boolean {
    val webUri = Uri.parse(twitterStatusWebUri(tweetId))
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK

    // 1) 尝试定向 X App（App Links）
    for (pkg in X_APP_PACKAGES) {
        val appIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            setPackage(pkg)
            addFlags(flags)
        }
        if (appIntent.resolveActivity(context.packageManager) != null) {
            return runCatching { context.startActivity(appIntent); true }.getOrDefault(false)
        }
    }

    // 2) 通用 intent，系统自动匹配 App Links 或浏览器
    val generalIntent = Intent(Intent.ACTION_VIEW, webUri).apply { addFlags(flags) }
    return runCatching { context.startActivity(generalIntent); true }.getOrDefault(false)
}

/** 通过 SAF 打开已下载的文件（支持监控目录下的子目录）。 */
fun openDownloadedFile(context: Context, monitorUri: String?, filePath: String?): Boolean {
    if (monitorUri.isNullOrBlank() || filePath.isNullOrBlank()) {
        return false
    }
    return runCatching {
        val monitor = Uri.parse(monitorUri)
        val fileUri = if (monitor.authority in DOWNLOADER_MEDIA_AUTHORITIES) {
            Uri.Builder()
                .scheme("content")
                .authority(monitor.authority)
                .appendPath("file")
                .appendPath(filePath)
                .build()
        } else {
            val tree = DocumentFile.fromTreeUri(context, monitor)
            val file = filePath.split('/').filter { it.isNotBlank() }.fold(tree) { current, segment ->
                current?.findFile(segment)
            }
            file?.takeIf { it.exists() && it.isFile }?.uri
        }

        if (fileUri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, context.contentResolver.getType(fileUri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } else {
            false
        }
    }.getOrDefault(false)
}
