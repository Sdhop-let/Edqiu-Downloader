package com.ed.twitterdownloader.service

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

private const val LATEST_JSON_URL = "https://raw.githubusercontent.com/qiuqiu-fist/Edqiu-application-public-/main/releases/latest.json"
private const val RAW_BASE_URL = "https://raw.githubusercontent.com/qiuqiu-fist/Edqiu-application-public-/main/"
private const val APK_BASE_URL = "https://github.com/qiuqiu-fist/Edqiu-application-public-/raw/main/"

data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val release: String,
    val publishedAt: String,
    val apkUrl: String,
    val upgradeDocumentUrl: String
)

object AppUpdateService {
    suspend fun checkForUpdate(context: Context): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(fetchText(LATEST_JSON_URL))
            val remoteVersionCode = json.optInt("versionCode", 0)
            if (remoteVersionCode <= BuildConfig.VERSION_CODE) return@runCatching null

            val apkPath = json.optString("apk").ifBlank { json.optString("versionedApk") }
            AppUpdateInfo(
                versionName = json.optString("versionName", "unknown"),
                versionCode = remoteVersionCode,
                release = json.optString("release", "v${remoteVersionCode}"),
                publishedAt = json.optString("publishedAt", ""),
                apkUrl = resolveUrl(apkPath, forApk = true),
                upgradeDocumentUrl = resolveUrl(json.optString("upgradeDocument"), forApk = false)
            )
        }
    }

    suspend fun downloadApk(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "twitter-downloader-${info.release}.apk")
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
                apkFile
            } finally {
                connection.disconnect()
            }
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
        context.startActivity(installIntent)
        return true
    }

    private fun resolveUrl(pathOrUrl: String, forApk: Boolean): String {
        val trimmed = pathOrUrl.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val safePath = trimmed.trimStart('/')
        return (if (forApk) APK_BASE_URL else RAW_BASE_URL) + safePath
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "edqiu-downloader/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
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

