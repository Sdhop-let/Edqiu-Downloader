package com.ed.edqiu.service

import android.content.Context
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * yt-dlp 引擎独立更新器（2026-11-21 轻云控，B+双通道 + LKG 回退门禁）。
 *
 * 背景（反编译随包库 youtubedl-android 0.18.1 实证）：
 * - yt-dlp 是 Python zipapp，落点 `{noBackupFilesDir}/youtubedl-android/yt-dlp/yt-dlp`，
 *   版本号记在 SharedPreferences `youtubedl-android` 的 `dlpVersion`/`dlpVersionName`；
 * - 冷启动 init 仅当二进制**不存在**时才从 APK 释放（`if (!binary.exists())`）——
 *   云端更新的版本重启后不会被覆盖；
 * - 库内 YoutubeDLUpdater 只认 GitHub 直连（api.github.com 写死），Android 连接栈上
 *   JVM 系统属性注入不可靠（AppUpdateService 2026-11-11 真机结论），等于墙内不可用。
 *
 * 因此自写更新器接管 API+下载+安装三步，双通道自动回退（与 App 更新检查同构）：
 * ① 代理通道：显式 Proxy 对象直连 GitHub（用户都开着代理，与 yt-dlp `--proxy` 同款通道）；
 * ② 镜像通道：gh-proxy.com 前缀直连（国内可达），失败才有它。
 *
 * **坏版本防线（2026-11-21 用户定案：LKG 门禁）**：
 * - 先装后验：新版本落位后立即用**库的真实运行环境**（同一 python + 同款环境变量）
 *   跑 `--version` 试运行——比离线模拟准确；
 * - 试运行失败 → 自动回滚到旧二进制（.prev 原样挪回）→ 标记该远端版本进本地黑名单
 *   （当日后续检查跳过）→ 拉**仓库内静态清单**的最后已知可用版本（LKG）装上；
 * - 清单 = `engine/ytdlp-manifest.json`（Edqiu-Downloader 仓库），人工维护：引擎升级
 *   并真机验证通过后手动更新四个字段。远端正常时不修改清单——LKG 永远是「验证过」的。
 *
 * 校验：zipapp 完整性（≥1MB 下限 + shebang/zip 头 + GitHub 元数据 size 精确一致——
 * 2026-11-21 机器直测：`yt-dlp` 资产 3,073,072 字节，头两字节为 `#!` 的 zipapp）。
 */
object YtdlpEngineUpdater {

    /** 更新通道：稳定 = yt-dlp 官方 releases/latest；尝鲜 = nightly-builds。 */
    enum class Channel(val apiUrl: String) {
        STABLE("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"),
        NIGHTLY("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")
    }

    sealed class Outcome {
        /** 新版本更新成功并通过试运行（viaMirror=走了 gh-proxy 镜像通道）。 */
        data class Updated(val version: String, val viaMirror: Boolean) : Outcome()
        /** 新版本试运行失败，已回退到仓库清单的最后已知可用版本。 */
        data class RolledBackToFallback(val version: String, val failedVersion: String) : Outcome()
        /** 新版本试运行失败且无可用回退（清单缺失/与当前一致），保持当前版本。 */
        data class KeptCurrent(val version: String, val failedVersion: String) : Outcome()
        /** 已是最新（当前版本 ≥ 远端 tag）。 */
        object AlreadyUpToDate : Outcome()
    }

    data class ReleaseInfo(
        val version: String,
        val versionName: String,
        val downloadUrl: String,
        /** 镜像直链（gh-proxy 前缀同一资产）。 */
        val downloadMirrorUrl: String,
        /** 资产精确字节数（GitHub 元数据）：下载后长度必须一致（防截断/污染，强于魔数校验）。 */
        val assetSize: Long = 0L
    )

    /** 仓库静态清单的「最后已知可用版本」（LKG）。 */
    data class LkgInfo(
        val version: String,
        val downloadUrl: String,
        val downloadMirrorUrl: String,
        val assetSize: Long
    )

    private const val MIRROR_PREFIX = "https://gh-proxy.com/"
    private const val PREFS_NAME = "youtubedl-android"
    private const val PREF_VERSION = "dlpVersion"
    private const val PREF_VERSION_NAME = "dlpVersionName"
    private const val PREF_AUTO_UPDATE = "engineAutoUpdateEnabled"
    private const val PREF_BLOCKED_REMOTE = "remoteBlockedVersion"
    private const val BASE_DIR_NAME = "youtubedl-android"
    private const val YTDLP_DIR_NAME = "yt-dlp"
    private const val YTDLP_BIN_NAME = "yt-dlp"
    /** zipapp 完整性下限：yt-dlp release 资产约 3MB，低于 1MB 必然是截断/列表文本。 */
    private const val MIN_BINARY_BYTES = 1L * 1024 * 1024

    /** 仓库内静态 LKG 清单（人工维护，见 engine/ytdlp-manifest.json 的 _doc）。 */
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/Sdhop-let/Edqiu-Downloader/main/engine/ytdlp-manifest.json"

    /** 当前引擎版本（库账本读取；全新安装未更新过时为 null）。 */
    fun currentVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_VERSION, null)?.takeIf { it.isNotBlank() }
    }

    // ---------- 后台自更新开关（2026-11-21） ----------

    /** 引擎每日后台自更新开关（默认开）：Worker 每日一次检查，已是最新不下载。 */
    fun isAutoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_AUTO_UPDATE, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_AUTO_UPDATE, enabled).apply()
    }

    // ---------- 远端版本黑名单（试运行判死后当日跳过） ----------

    private fun blockedRemoteVersion(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_BLOCKED_REMOTE, null)?.takeIf { it.isNotBlank() }

    private fun setBlockedRemoteVersion(context: Context, version: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .let { if (version == null) it.remove(PREF_BLOCKED_REMOTE) else it.putString(PREF_BLOCKED_REMOTE, version) }
            .apply()
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
     * 双通道更新 + 试运行门禁 + LKG 回退：
     * 1. 检查远端最新（版本不新于当前 → AlreadyUpToDate）；
     * 2. 远端版本在本地黑名单（此前试运行判死）→ 跳过远端，直接保证 LKG/当前版本；
     * 3. 下载新版本（双通道）→ 落位 → **库真实运行环境跑 `--version` 试运行**；
     *    - 通过 → 写账本 → Updated；
     *    - 失败 → 原样回滚旧二进制 → 标黑名单 → 拉仓库 LKG 清单：
     *      LKG 存在且与当前不同 → 走同款「装后试运行」装上 → RolledBackToFallback；
     *      LKG 缺失/与当前一致/试运行也失败 → 保持当前版本 → KeptCurrent。
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

                if (blockedRemoteVersion(appContext) == release.version) {
                    // 该远端版本此前已判死：不再浪费 3MB 下载，直接保证 LKG 落位
                    val fallback = ensureFallback(appContext, current, release.version)
                    return@runCatching fallback ?: Outcome.AlreadyUpToDate
                }

                when (val install = installAndVerify(appContext, release)) {
                    is InstallResult.Verified -> return@runCatching install.outcome
                    InstallResult.Inconclusive ->
                        // 库未初始化/进程中断等非引擎原因：保持现状不判死，下轮再试
                        return@runCatching Outcome.KeptCurrent(
                            current ?: "内置版本", release.version
                        )
                    InstallResult.FailedCanary -> {
                        // 新版本试运行失败：标记黑名单 + 回退仓库 LKG
                        android.util.Log.w(
                            "YtdlpEngineUpdater",
                            "远端 ${release.version} 试运行失败，标记黑名单并尝试仓库 LKG"
                        )
                        setBlockedRemoteVersion(appContext, release.version)
                        ensureFallback(appContext, current, release.version)
                            ?: Outcome.KeptCurrent(current ?: "内置版本", release.version)
                    }
                }
            }
        }

    /** 装+验结果：区分「可判死」（标黑名单+回退 LKG）与「不可判定」（恢复原状，不判死）。 */
    private sealed class InstallResult {
        data class Verified(val outcome: Outcome) : InstallResult()
        object FailedCanary : InstallResult()
        object Inconclusive : InstallResult()
    }

    private fun isCanaryInconclusive(error: Throwable?): Boolean {
        var current: Throwable? = error
        repeat(4) {
            val t = current ?: return false
            if (t is InterruptedException) return true
            if (t is IllegalStateException && t.message?.contains("not initialized", ignoreCase = true) == true) {
                return true
            }
            current = t.cause
        }
        return false
    }

    /**
     * 装+验一体：下载（双通道）→ 校验 → 旧二进制挪到 .prev → 新版落位 → 库真实运行
     * 环境跑 `--version` 试运行：
     * - 通过 → 写账本返回 Verified(Updated)；
     * - 失败（引擎/产物问题）→ 原样回滚 .prev → FailedCanary（调用方标黑名单+回退 LKG）；
     * - 不可判定（库未初始化/进程中断——与引擎质量无关）→ 原样回滚 → Inconclusive
     *   （不标黑名单，防止误杀好版本）。
     */
    private fun installAndVerify(context: Context, release: ReleaseInfo): InstallResult {
        val proxy = ProxyPreferences(context).getProxySettings().toJavaProxy()
        var viaMirror = false
        val tmp = File(context.cacheDir, "yt-dlp-update-${System.currentTimeMillis()}.bin")
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

            validateBinary(tmp, release.assetSize)

            val binary = ytdlpBinary(context)
            val prev = File(binary.parentFile, "$YTDLP_BIN_NAME.prev")
            if (prev.exists()) prev.delete()
            // 旧二进制先挪走（rename 原子；无旧文件 = 首次云更，无回退物）
            binary.renameTo(prev)

            val staging = File(binary.parentFile, "$YTDLP_BIN_NAME.new")
            tmp.inputStream().use { input ->
                staging.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
            if (!staging.renameTo(binary)) {
                staging.copyTo(binary, overwrite = true)
                staging.delete()
            }
            binary.setExecutable(true, false)

            // 试运行：库真实运行环境（同 python、同环境变量）跑 --version，
            // 通过 = 引擎可运行 + 输出含版本号
            val canary = runCatching {
                val response = YoutubeDL.getInstance().execute(YoutubeDLRequest(listOf("--version")))
                response.out.trim()
            }
            val canaryVersion = canary.getOrNull()
            if (canary.isSuccess && !canaryVersion.isNullOrBlank() &&
                canaryVersion.any { it.isDigit() }
            ) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_VERSION, release.version)
                    .putString(PREF_VERSION_NAME, release.versionName)
                    .apply()
                prev.delete()
                android.util.Log.i(
                    "YtdlpEngineUpdater",
                    "试运行通过：--version=${canaryVersion.take(40)}，落位 ${release.version}"
                )
                return InstallResult.Verified(Outcome.Updated(release.version, viaMirror))
            }

            // 失败分类回滚
            val ex = canary.exceptionOrNull()
            android.util.Log.w(
                "YtdlpEngineUpdater",
                "试运行失败（version=$canaryVersion err=${ex?.message?.take(160)}），回滚旧二进制"
            )
            binary.delete()
            if (prev.exists()) prev.renameTo(binary)
            return if (isCanaryInconclusive(ex)) {
                android.util.Log.w("YtdlpEngineUpdater", "试运行不可判定（非引擎问题），不标记黑名单")
                InstallResult.Inconclusive
            } else {
                InstallResult.FailedCanary
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * 仓库 LKG 回退：拉静态清单（双通道，404 = 无清单返回 null）→ LKG 与当前版本不同
     * → 走同款「装后试运行」装上 → RolledBackToFallback；装失败/无事可做 → null。
     */
    private fun ensureFallback(context: Context, current: String?, failedVersion: String): Outcome? {
        val lkg = fetchManifest(context) ?: return null
        if (lkg.version == current) return null
        val info = ReleaseInfo(
            version = lkg.version,
            versionName = lkg.version,
            downloadUrl = lkg.downloadUrl,
            downloadMirrorUrl = lkg.downloadMirrorUrl,
            assetSize = lkg.assetSize
        )
        return when (val result = installAndVerify(context, info)) {
            is InstallResult.Verified -> {
                // 黑名单只针对判死的那个远端版本；LKG 装上后清除，远端出更新的修复版可再试
                setBlockedRemoteVersion(context, null)
                Outcome.RolledBackToFallback(lkg.version, failedVersion)
            }
            else -> {
                android.util.Log.w("YtdlpEngineUpdater", "LKG ${lkg.version} 试运行也失败，保持当前版本")
                null
            }
        }
    }

    /** 拉仓库静态 LKG 清单（双通道；404/解析失败 = 无清单，静默返回 null）。 */
    private fun fetchManifest(context: Context): LkgInfo? = runCatching {
        parseManifest(fetchTextWithFallback(context, MANIFEST_URL))
    }.getOrElse { e ->
        android.util.Log.w("YtdlpEngineUpdater", "LKG 清单不可用（视为无回退）：${e.message}")
        null
    }

    /** 解析仓库静态清单（纯函数，可单测）：lastKnownGood.version/assetUrl/size。 */
    internal fun parseManifest(body: String): LkgInfo {
        val json = JSONObject(body)
        val lkg = json.optJSONObject("lastKnownGood")
            ?: throw IllegalStateException("清单缺少 lastKnownGood")
        val version = lkg.optString("version").trim()
        require(version.isNotBlank()) { "清单缺少 version" }
        val url = lkg.optString("assetUrl").trim()
        require(url.startsWith("https://")) { "清单 assetUrl 非法" }
        return LkgInfo(
            version = version.removePrefix("v"),
            downloadUrl = url,
            downloadMirrorUrl = "$MIRROR_PREFIX$url",
            assetSize = lkg.optLong("size", 0L)
        )
    }

    /** 库 init/updater 同款落点（反编译实证）：noBackupFilesDir/youtubedl-android/yt-dlp/yt-dlp。 */
    private fun ytdlpBinary(context: Context): File {
        val dir = File(context.noBackupFilesDir, BASE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        val ytdlpDir = File(dir, YTDLP_DIR_NAME)
        if (!ytdlpDir.exists()) ytdlpDir.mkdirs()
        return File(ytdlpDir, YTDLP_BIN_NAME)
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
        var size = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == YTDLP_BIN_NAME) {
                url = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                size = asset.optLong("size", 0L)
                break
            }
        }
        val downloadUrl = requireNotNull(url) { "远端 release 缺少 yt-dlp 资产" }
        return ReleaseInfo(
            version = tag.removePrefix("v"),
            versionName = json.optString("name").ifBlank { tag },
            downloadUrl = downloadUrl,
            downloadMirrorUrl = "$MIRROR_PREFIX$downloadUrl",
            assetSize = size
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

    /**
     * zipapp 完整性校验：
     * - 长度下限（防列表文本/空响应）；
     * - 头部为 zipapp 合法形态之一——`#!`（shebang + 内嵌 zip，GitHub `yt-dlp` 资产
     *   实测形态，2026-11-21 机器直测 3,073,072 字节头两字节即 `#!`）或 `PK`（纯 zip）；
     *   旧实现只认 "PK" 会把正常 zipapp 拒掉（实测抓出）；
     * - assetSize > 0 时长度必须精确一致（GitHub 元数据，强于魔数——截断/镜像污染全拦）。
     */
    internal fun validateBinary(file: File, expectedSize: Long = 0L) {
        require(file.isFile && file.length() >= MIN_BINARY_BYTES) {
            "下载产物过小（${file.length()} 字节），疑似截断或错误内容"
        }
        if (expectedSize > 0) {
            require(file.length() == expectedSize) {
                "下载产物长度（${file.length()}）≠ GitHub 元数据（$expectedSize），疑似截断或被污染"
            }
        }
        val head = ByteArray(2)
        file.inputStream().use { it.read(head) }
        val isZipapp = head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
        val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        require(isZipapp || isZip) { "下载产物不是 yt-dlp zipapp/zip 形态，疑似被劫持或返回了错误内容" }
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
