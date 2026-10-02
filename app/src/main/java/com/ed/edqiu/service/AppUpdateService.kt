package com.ed.edqiu.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ed.edqiu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// 2026-09-28 检查源切换：旧仓库静态 JSON → GitHub Releases API（Sdhop-let/Edqiu-Downloader）
private const val RELEASES_LATEST_URL = "https://api.github.com/repos/Sdhop-let/Edqiu-Downloader/releases/latest"

data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val release: String,
    val publishedAt: String,
    val apkUrl: String,
    val upgradeDocumentUrl: String
)

object AppUpdateService {
    /**
     * 检查 GitHub Releases 是否发布新版本：
     * - 取 latest release（自动排除草稿与预发布）；
     * - 版本比较按数字段逐段进行（v1.6.10 > v1.6.9 > v1.6.2）；
     * - APK 下载地址取第一个 .apk 资产的 browser_download_url（直链、跟随重定向）；
     * - 仓库尚无任何 Release（HTTP 404）视为"无更新"而非错误。
     */
    suspend fun checkForUpdate(context: Context): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = try {
                fetchText(RELEASES_LATEST_URL)
            } catch (e: IllegalStateException) {
                // 仓库还没有发布过任何 Release
                if (e.message?.contains("404") == true) return@runCatching null else throw e
            }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            if (tag.isBlank()) return@runCatching null
            if (!isNewerVersion(tag, BuildConfig.VERSION_NAME)) return@runCatching null

            // 取第一个 .apk 资产直链（跳过 .idsig 等附属文件）
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (url.isNotBlank() && name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = url
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) return@runCatching null

            AppUpdateInfo(
                versionName = tag.removePrefix("v").removePrefix("V").ifBlank { tag },
                versionCode = componentVersionCode(tag),
                release = json.optString("name").ifBlank { tag },
                publishedAt = json.optString("published_at").substringBefore('T'),
                apkUrl = apkUrl,
                upgradeDocumentUrl = json.optString("html_url")
            )
        }
    }

    /** 语义化版本比较：按数字段逐段比较，缺段按 0（v2.0 < v2.0.1）。 */
    private fun isNewerVersion(remote: String, current: String): Boolean {
        fun parts(v: String) = Regex("\\d+").findAll(v).mapNotNull { it.value.toIntOrNull() }.toList()
        val r = parts(remote)
        val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun componentVersionCode(tag: String): Int {
        val parts = Regex("\\d+").findAll(tag).mapNotNull { it.value.toIntOrNull() }.toList()
        return parts.getOrElse(0) { 0 } * 10_000 +
            parts.getOrElse(1) { 0 } * 100 +
            parts.getOrElse(2) { 0 }.coerceAtMost(99)
    }

    suspend fun downloadApk(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            // release 名直接来自远端 JSON，消毒后再作文件名（防路径分隔符破坏下载/写入）
            val safeRelease = info.release.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val apkFile = File(updateDir, "twitter-downloader-$safeRelease.apk")
            if (apkFile.exists()) apkFile.delete()

            val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "edqiu-downloader/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/octet-stream")
            }

            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("APK download failed: HTTP $code")
                val total = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
                var copied = 0L
                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0L) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (apkFile.length() <= 0L) throw IllegalStateException("Downloaded APK is empty")
                // 安装前完整性自校验（2026-10 安全整改）：版本不低于当前 + 签名与已装应用一致
                verifyApkIntegrity(context, apkFile)
                apkFile
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * APK 安装前完整性自校验（2026-10 安全整改）：
     * 1. 归档可解析且 versionCode 不低于当前版本（拦截半截包/降级包）；
     * 2. 签名证书与当前已安装应用一致（拦截第三方重打包/被替换的 release 资产）。
     * 下载源为 GitHub Releases（HTTPS），本校验提供设备侧最后防线。
     */
    private fun verifyApkIntegrity(context: Context, apkFile: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            android.content.pm.PackageManager.GET_SIGNATURES
        ) ?: throw IllegalStateException("下载的 APK 无法解析，可能下载不完整，请重试")
        if (archive.versionCode < BuildConfig.VERSION_CODE) {
            throw IllegalStateException("下载的 APK 版本（${archive.versionCode}）低于当前版本（${BuildConfig.VERSION_CODE}），已阻止安装")
        }
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_SIGNATURES
        )
        val archiveSignature = archive.signatures?.firstOrNull()?.toByteArray()
            ?: throw IllegalStateException("下载的 APK 缺少签名信息，已阻止安装")
        val installedSignature = installed.signatures?.firstOrNull()?.toByteArray()
            ?: throw IllegalStateException("无法读取当前应用签名，已阻止安装")
        if (!archiveSignature.contentEquals(installedSignature)) {
            throw IllegalStateException("下载的 APK 签名与当前应用不一致，已阻止安装")
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permissionIntent)
            return false
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 安装器被禁用/无 handler 时 startActivity 抛 ActivityNotFoundException（2026-10 修复）
        return try {
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            android.util.Log.w("AppUpdateService", "No activity to handle install intent", e)
            false
        }
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "edqiu-downloader/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Request failed: HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

