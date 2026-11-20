package com.ed.edqiu.service

import android.content.Context
import com.ed.edqiu.data.preferences.ProxyPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * yt-dlp 引擎独立更新器（2026-11-21 轻云控，B+双通道）。
 *
 * 背景（反编译随包库 youtubedl-android 0.18.1 实证）：
 * - yt-dlp 是 Python zipapp，落点 `{noBackupFilesDir}/youtubedl-android/yt-dlp/yt-dlp`，
 *   版本号记在 SharedPreferences `youtubedl-android` 的 `dlpVersion`/`dlpVersionName`；
 * - 冷启动 init 仅当二进制**不存在**时才从 APK 释放（`if (!binary.exists())`）——
 *   云端更新的版本重启后不会被覆盖；
 * - 库内 YoutubeDLUpdater 只认 GitHub 直连（api.github.com 写死），Android 连接栈上
 *   JVM 系统属性注入不可靠（AppUpdateService 2026-11-11 真机结论），等于墙内不可用。
 *
 * 因此自写更新器接管 API+下载两步，双通道自动回退（与 App 更新检查同构）：
 * ① 代理通道：显式 Proxy 对象直连 GitHub（用户都开着代理，与 yt-dlp `--proxy` 同款通道）；
 * ② 镜像通道：gh-proxy.com 前缀直连（国内可达），失败才有它。
 *
 * 校验：zipapp 完整性下限（≥1MB + "PK" 头，yt-dlp release 资产是 zip 容器）；
 * SHA256 校验属完全云控（C 档，自建清单）范畴，本档不引入。
 */
object YtdlpEngineUpdater {

    /** 更新通道：稳定 = yt-dlp 官方 releases/latest；尝鲜 = nightly-builds。 */
    enum class Channel(val apiUrl: String) {
        STABLE("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"),
        NIGHTLY("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")
    }

    sealed class Outcome {
        /** 更新成功（viaMirror=走了 gh-proxy 镜像通道）。 */
        data class Updated(val version: String, val viaMirror: Boolean) : Outcome()
        /** 已是最新（当前版本 ≥ 远端 tag）。 */
        object AlreadyUpToDate : Outcome()
    }

    data class ReleaseInfo(
        val version: String,
        val versionName: String,
        val downloadUrl: String,
        /** 镜像直链（gh-proxy 前缀同一资产）。 */
        val downloadMirrorUrl: String
    )

    private const val MIRROR_PREFIX = "https://gh-proxy.com/"
    private const val PREFS_NAME = "youtubedl-android"
    private const val PREF_VERSION = "dlpVersion"
    private const val PREF_VERSION_NAME = "dlpVersionName"
    private const val BASE_DIR_NAME = "youtubedl-android"
    private const val YTDLP_DIR_NAME = "yt-dlp"
    private const val YTDLP_BIN_NAME = "yt-dlp"
    /** zipapp 完整性下限：yt-dlp release 资产约 3MB，低于 1MB 必然是截断/列表文本。 */
    private const val MIN_BINARY_BYTES = 1L * 1024 * 1024

    /** 当前引擎版本（库账本读取；全新安装未更新过时为 null）。 */
    fun currentVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_VERSION, null)?.takeIf { it.isNotBlank() }
    }

    // ---------- 后台自更新开关（2026-11-21） ----------

    private const val PREF_AUTO_UPDATE = "engineAutoUpdateEnabled"

    /** 引擎每日后台自更新开关（默认开）：Worker 每日一次检查，已是最新不下载。 */
    fun isAutoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_AUTO_UPDATE, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_AUTO_UPDATE, enabled).apply()
    }

    /**
     * 检查远端最新 release（双通道）：解析 tag_name + name 为 "yt-dlp" 的资产直链。
     * 代理通道失败自动回退 gh-proxy 镜像（直连）。
     */
    suspend fun checkLatest(context: Context, channel: Channel): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                parseRelease(fetchTextWithFallback(context, channel.apiUrl))
            }
        }

    /**
     * 双通道更新：检查 → 比较版本 → 下载（代理直连 → 镜像回退）→ 校验 → 原子落位
     * → 写库账本（dlpVersion/dlpVersionName，与库内 updater 同键，version()/自救热修
     * 的 ALREADY_UP_TO_DATE 判断立即一致）。
     */
    suspend fun update(context: Context, channel: Channel): Result<Outcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val appContext = context.applicationContext
                val release = checkLatest(appContext, channel).getOrElse { throw it }
                val current = currentVersion(appContext)
                // current 为 null = 全新安装从未更新过（库账本无记录）——视为可更新
                if (current != null && !isRemoteNewer(current, release.version)) {
                    return@runCatching Outcome.AlreadyUpToDate
                }

                // ① 代理通道：显式 Proxy 直连 GitHub（URL 恒为规范化 release 资产）
                // ② 镜像通道：gh-proxy 前缀直连（国内可达）
                val proxy = ProxyPreferences(appContext).getProxySettings().toJavaProxy()
                var viaMirror = false
                val tmp = File(appContext.cacheDir, "yt-dlp-update-${System.currentTimeMillis()}.bin")
                try {
                    try {
                        downloadTo(release.downloadUrl, proxy, tmp)
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "YtdlpEngineUpdater",
                            "代理通道下载失败，回退 gh-proxy 镜像：${e.message}"
                        )
                        downloadTo(release.downloadMirrorUrl, null, tmp)
                        viaMirror = true
                    }

                    validateBinary(tmp)

                    // 原子落位：同目录 tmp → rename（进程被杀不落半截二进制）
                    val dir = File(appContext.noBackupFilesDir, BASE_DIR_NAME)
                    if (!dir.exists()) dir.mkdirs()
                    val ytdlpDir = File(dir, YTDLP_DIR_NAME)
                    if (!ytdlpDir.exists()) ytdlpDir.mkdirs()
                    val staging = File(ytdlpDir, "$YTDLP_BIN_NAME.new")
                    tmp.inputStream().use { input ->
                        staging.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                    }
                    val binary = File(ytdlpDir, YTDLP_BIN_NAME)
                    if (!staging.renameTo(binary)) {
                        staging.copyTo(binary, overwrite = true)
                        staging.delete()
                    }
                    // 与库 init_ytdlp 释放物同权限（可执行）
                    binary.setExecutable(true, false)

                    // 写库账本（与 YoutubeDLUpdater 同文件同键）
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_VERSION, release.version)
                        .putString(PREF_VERSION_NAME, release.versionName)
                        .apply()

                    Outcome.Updated(release.version, viaMirror)
                } finally {
                    tmp.delete()
                }
            }
        }

    /**
     * 解析 release JSON（纯函数，可单测）：tag_name + name 为 "yt-dlp" 的资产直链。
     * 库内 updater 同款筛选：Python zipapp 资产名恰为 "yt-dlp"（无扩展名），
     * 不会误中 yt-dlp.exe / yt-dlp.zip / yt-dlp.tar.gz 等宿主机产物。
     */
    internal fun parseRelease(body: String): ReleaseInfo {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").trim()
        require(tag.isNotBlank()) { "远端 release 缺少 tag_name" }
        val assets = json.optJSONArray("assets")
            ?: throw IllegalStateException("远端 release 缺少 assets")
        var url: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == YTDLP_BIN_NAME) {
                url = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                break
            }
        }
        val downloadUrl = requireNotNull(url) { "远端 release 缺少 yt-dlp 资产" }
        return ReleaseInfo(
            version = tag.removePrefix("v"),
            versionName = json.optString("name").ifBlank { tag },
            downloadUrl = downloadUrl,
            downloadMirrorUrl = "$MIRROR_PREFIX$downloadUrl"
        )
    }

    /** 简单语义化比较（"YYYY.MM.DD[.HHMMSS]" 形态，逐段数字比较）。 */
    internal fun isRemoteNewer(current: String, remote: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").split('.', '-', '_')
            .map { it.toLongOrNull() ?: 0L }
        val a = parse(current)
        val b = parse(remote)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return y > x
        }
        return false
    }

    /** zipapp 完整性下限：长度 + "PK"（zip 容器）魔数。 */
    private fun validateBinary(file: File) {
        require(file.isFile && file.length() >= MIN_BINARY_BYTES) {
            "下载产物过小（${file.length()} 字节），疑似截断或错误内容"
        }
        val head = ByteArray(2)
        file.inputStream().use { it.read(head) }
        require(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            "下载产物不是 zip 容器（yt-dlp zipapp），疑似被劫持或返回了错误内容"
        }
    }

    /**
     * 双通道版本检查请求（与 AppUpdateService.fetchTextWithFallback 同构）：
     * ① 显式 Proxy 对象（App 配置的代理，实测可达 GitHub API）；
     * ② gh-proxy.com 镜像前缀（国内直连可达，已实测代理 api.github.com 返回完整 JSON）。
     */
    private fun fetchTextWithFallback(context: Context, url: String): String {
        val proxy = ProxyPreferences(context).getProxySettings().toJavaProxy()
        if (proxy != null) {
            try {
                return fetchText(url, proxy)
            } catch (e: Exception) {
                android.util.Log.w("YtdlpEngineUpdater", "代理通道检查失败，回退 gh-proxy 镜像：${e.message}")
            }
        }
        return fetchText("$MIRROR_PREFIX$url", null)
    }

    private fun fetchText(url: String, proxy: Proxy?): String {
        val connection = if (proxy != null) {
            URL(url).openConnection(proxy) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** 下载 zipapp 到 tmp（跟随重定向；404/5xx 抛错由调用方回退镜像）。 */
    private fun downloadTo(url: String, proxy: Proxy?, target: File) {
        val connection = if (proxy != null) {
            URL(url).openConnection(proxy) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Edqiu/1.0")
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.use { raw ->
                target.outputStream().use { output -> raw.copyTo(output, 128 * 1024) }
            }
        } finally {
            connection.disconnect()
        }
    }
}
