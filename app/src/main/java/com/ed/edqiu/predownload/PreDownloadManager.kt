package com.ed.edqiu.predownload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.ed.edqiu.data.model.ProxySettings
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.data.preferences.PreDownloadPreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.service.WebDavSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 预下载协调器：串行执行「保存链接 → 攒批 → 预下载 → 联动网盘同步」流水线。
 *
 * ## 触发方式
 * - 自动：每攒满 10 条 PENDING 记录触发一次（[onLinkCaptured]，受
 *   [PreDownloadPreferences.autoPreDownload] 开关与 [PreDownloadPreferences.nextAutoTriggerCount] 阈值控制）；
 * - 手动：收件箱批量预下载按钮 / 待处理提示（[enqueueManual]，不检查通路、立即尝试）。
 *
 * ## 代理通路检测（不走手机代理 = 不强制设置 HTTP 代理，检测到通路才自动预下载）
 * 预下载前检测用户是否开启了 Clash / 机场等代理通路：
 * - 系统 VPN 隧道（TRANSPORT_VPN / tun 接口）→ 直连下载，流量自动走隧道；
 * - 本地 HTTP 代理端口（127.0.0.1:7890 等）→ 显式设置该代理下载；
 * - 两者皆无 → 自动预下载跳过（避免 fxtwitter 直连失败反复重试堵队列），手动批量仍尝试并快速暴露失败。
 *
 * ## 网盘联动（2026-10 统一 WebDAV 通道）
 * 预下载完成后调用 [WebDavSyncService.syncDownloads] 做一轮双向同步：
 * 远端已有的文件 HEAD 跳过（幂等），新文件断点续传上传；云端独有文件回补本地。
 */
class PreDownloadManager(
    private val context: Context,
    private val savedLinkRepository: SavedLinkRepository,
    private val preDownloadPreferences: PreDownloadPreferences,
    private val proxyPreferences: ProxyPreferences,
    private val cloudSyncPreferences: CloudSyncPreferences,
    private val settingsRepository: SettingsRepository,
    ioScope: CoroutineScope,
) {

    /** 一轮预下载执行结果（供 UI 弹窗/提示）。 */
    data class RunResult(
        val launched: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val syncedToCloud: Boolean = false,
        val pathDesc: String = "",
        val message: String = "",
    )

    /** 对外运行状态（UI 只读）。 */
    data class PreDownloadUiState(
        val running: Boolean = false,
        val lastResult: RunResult? = null,
    )

    /** 检测到的代理通路三态。 */
    private sealed interface NetworkPath {
        /** 系统 VPN 隧道（Clash 等 VPN 模式）：直连即走隧道。 */
        data object VpnTunnel : NetworkPath
        /** 本地 HTTP 代理端口（Clash HTTP 模式等）：需显式设置代理。 */
        data class LocalProxy(val settings: ProxySettings) : NetworkPath
        /** 未检测到任何代理通路。 */
        data object None : NetworkPath
    }

    private val ioScope: CoroutineScope = ioScope
    private val queueMutex = Mutex()

    private val _state = MutableStateFlow(PreDownloadUiState())
    val state: StateFlow<PreDownloadUiState> = _state.asStateFlow()

    companion object {
        private const val TAG = "PreDownloadManager"
    }

    /**
     * 保存链接成功后触发自动预下载（AppContainer 挂载到 [SavedLinkRepository.onCaptured]）。
     * 攒批语义：每攒满 [PreDownloadPreferences.batchSize] 条 PENDING 触发一轮（受
     * [PreDownloadPreferences.autoPreDownload] 总开关与 [PreDownloadPreferences.autoStartDownload]
     * 自动开始开关控制）；不满足则仅更新阈值等待下一批。
     * 非挂起：丢给 IO 作用域串行执行，不阻塞捕获返回。
     */
    fun onLinkCaptured(tweetId: String) {
        if (!preDownloadPreferences.autoPreDownload) return
        ioScope.launch {
            val pending = savedLinkRepository.pendingCountNow()
            val batchSize = preDownloadPreferences.batchSize
            val next = preDownloadPreferences.nextAutoTriggerCount
            if (pending >= next) {
                preDownloadPreferences.nextAutoTriggerCount = next + batchSize
                if (preDownloadPreferences.autoStartDownload) {
                    Log.i(TAG, "onLinkCaptured() PENDING=$pending ≥ 阈值 $next，自动开始预下载（下次 $next+$batchSize）")
                    runFor(savedLinkRepository.pendingTweetIds(), auto = true)
                } else {
                    Log.i(TAG, "onLinkCaptured() PENDING=$pending ≥ 阈值 $next，但未开启自动开始，等待手动批量下载")
                }
            } else {
                Log.i(TAG, "onLinkCaptured() PENDING=$pending < 阈值 $next，暂不触发")
            }
        }
    }

    /** 手动批量预下载入口（收件箱批量按钮 / 待处理提示，不检查通路立即尝试）。 */
    fun enqueueManual(tweetIds: Collection<String>) {
        if (tweetIds.isEmpty()) return
        Log.i(TAG, "enqueueManual() 手动预下载 ${tweetIds.size} 条")
        ioScope.launch {
            runFor(tweetIds, auto = false)
        }
    }

    /**
     * 手动下载完成后的网盘联动入口（ListViewModel 批量下载成功后调用，fire-and-forget）。
     * 无论预下载总开关状态，手动下载完成后都尝试把未备份媒体同步到备份中心选中网盘
     * （与预下载的 [PreDownloadPreferences.syncToCloud] 开关独立：手动联动恒有）。
     */
    fun syncAfterManualDownload() {
        Log.i(TAG, "syncAfterManualDownload() 手动下载完成，联动网盘同步")
        ioScope.launch {
            runCatching { syncNewlyDownloadedToCloud() }
                .onFailure { Log.w(TAG, "syncAfterManualDownload() 联动失败", it) }
        }
    }

    /**
     * 执行一轮预下载（串行）：
     * 1. 检测代理通路（VPN 隧道 / 本地代理端口 / 无）；
     * 2. 自动模式无通路 → 直接跳过；其余按通路选择直连或代理下载；
     * 3. 有新增下载 → 联动网盘备份；
     * 4. 更新偏好与对外状态。
     */
    suspend fun runFor(tweetIds: Collection<String>, auto: Boolean): RunResult = queueMutex.withLock {
        _state.update { it.copy(running = true) }
        val startedAt = System.currentTimeMillis()
        try {
            val path = detectNetworkPath()
            val proxy = when (path) {
                is NetworkPath.LocalProxy -> path.settings
                else -> null // VPN 隧道直连 / 无通路
            }
            val pathDesc = when (path) {
                NetworkPath.VpnTunnel -> "系统 VPN 隧道已开启，直连"
                is NetworkPath.LocalProxy -> "本地代理 ${path.settings.host}:${path.settings.port}"
                NetworkPath.None -> "未检测到代理通路"
            }
            Log.i(TAG, "runFor(${tweetIds.size} 条, auto=$auto) $pathDesc")

            if (path is NetworkPath.None && auto) {
                val runResult = RunResult(pathDesc = pathDesc, message = "未检测到代理通路（Clash/VPN 未开启），已跳过自动预下载")
                Log.i(TAG, "runFor() 自动预下载无通路，跳过")
                _state.update { it.copy(running = false, lastResult = runResult) }
                return@withLock runResult
            }

            val result = savedLinkRepository.requestDownloads(tweetIds, proxy)

            val downloadedCount = result.launched + result.skipped
            // 联动网盘：预下载路径受 syncToCloud 开关控制（auto=true 时）；手动路径(auto=false)由调用方决定
            val synced = if (downloadedCount > 0 && (!auto || preDownloadPreferences.syncToCloud)) {
                syncNewlyDownloadedToCloud()
            } else {
                false
            }

            val now = System.currentTimeMillis()
            preDownloadPreferences.lastPreDownloadAt = now
            preDownloadPreferences.lastPreDownloadCount = result.launched
            preDownloadPreferences.lastSyncedToCloud = synced

            val message = buildMessage(result, synced, pathDesc)
            Log.i(TAG, "runFor() 完成: $message 耗时 ${System.currentTimeMillis() - startedAt}ms")

            val runResult = RunResult(
                launched = result.launched,
                failed = result.failed,
                skipped = result.skipped,
                syncedToCloud = synced,
                pathDesc = pathDesc,
                message = message,
            )
            _state.update { it.copy(running = false, lastResult = runResult) }
            runResult
        } catch (error: Throwable) {
            Log.w(TAG, "runFor() 未捕获异常", error)
            val runResult = RunResult(message = "预下载异常：${error.message ?: "未知错误"}")
            _state.update { it.copy(running = false, lastResult = runResult) }
            runResult
        }
    }

    // ---------------- 内部 ----------------

    /** 检测当前代理通路：优先系统 VPN 隧道，其次本地 HTTP 代理端口，最后无通路。 */
    private fun detectNetworkPath(): NetworkPath {
        if (isSystemVpnActive()) return NetworkPath.VpnTunnel
        return detectLocalProxyPort()?.let { NetworkPath.LocalProxy(it) } ?: NetworkPath.None
    }

    /** 系统 VPN 隧道检测（TRANSPORT_VPN 优先，NetworkInterface 兜底）。 */
    private fun isSystemVpnActive(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val byTransport = cm?.allNetworks?.any { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: false
        if (byTransport) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .any { iface ->
                    iface.name.startsWith("tun") ||
                        iface.name.startsWith("ppp") ||
                        iface.name.startsWith("wg") ||
                        iface.name.startsWith("utun")
                }
        }.getOrDefault(false)
    }

    /** 本地 HTTP 代理端口探测（复用 ProxyPreferences 探测框架，探测到可用端口才返回）。 */
    private fun detectLocalProxyPort(): ProxySettings? {
        if (!preDownloadPreferences.proxyEnabled) return null
        val detected = proxyPreferences.detectProxySettings()
        val host = detected.host.ifBlank { "127.0.0.1" }
        val port = detected.port
        return if (isPortOpen(host, port)) detected.copy(enabled = true, host = host) else null
    }

    private fun isPortOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 250)
            true
        }
    }.getOrDefault(false)

    /**
     * 联动网盘（2026-10 统一 WebDAV 通道）：对当前选中网盘跑一轮双向同步——
     * 远端已有的文件 HEAD 跳过（幂等），新文件断点续传上传，云端独有文件回补本地。
     * 返回是否全部成功（冲突视为未完全成功）。
     */
    private suspend fun syncNewlyDownloadedToCloud(): Boolean = withContext(Dispatchers.IO) {
        val account = cloudSyncPreferences.currentAccount()
        if (!cloudSyncPreferences.enabled || !account.verified || !account.isComplete) {
            Log.i(TAG, "syncNewlyDownloadedToCloud() WebDAV 未启用/未验证，跳过联动")
            return@withContext false
        }
        val result = WebDavSyncService.syncDownloads(context, cloudSyncPreferences)
        result.fold(
            onSuccess = { r ->
                Log.i(TAG, "syncNewlyDownloadedToCloud() 完成：上传 ${r.uploaded}，下载 ${r.downloaded}，" +
                    "跳过 ${r.skippedExisting}，冲突 ${r.conflicts}")
                r.conflicts == 0
            },
            onFailure = { e ->
                Log.w(TAG, "syncNewlyDownloadedToCloud() 失败：${e.message}")
                false
            },
        )
    }

    private fun buildMessage(
        result: SavedLinkRepository.BatchDownloadResult,
        synced: Boolean,
        pathDesc: String,
    ): String {
        val parts = mutableListOf<String>()
        parts += "预下载完成：成功 ${result.launched}，失败 ${result.failed}，跳过 ${result.skipped}"
        parts += pathDesc
        if (synced) parts += "已同步到网盘" else parts += "未同步网盘"
        return parts.joinToString(" · ")
    }
}
