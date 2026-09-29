package com.ed.edqiu.data.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * 预下载偏好（普通 SharedPreferences，非敏感数据）。
 *
 * - [autoPreDownload]：保存链接到收件箱后是否立即自动预下载（防视频下架）；
 * - [proxyEnabled]：预下载前是否探测本机 HTTP 代理（Clash/v2rayNG 常驻 127.0.0.1:7890 等），
 *   探测到可用代理则下载走代理，避免直连失败；
 * - [lastPreDownloadAt] / [lastPreDownloadCount]：最近一次预下载完成时间与文件数（供展示）。
 */
class PreDownloadPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pre_download_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_AUTO = "auto_pre_download"
        private const val KEY_PROXY = "pre_download_proxy"
        private const val KEY_LAST_AT = "last_pre_download_at"
        private const val KEY_LAST_COUNT = "last_pre_download_count"
        private const val KEY_LAST_SYNCED = "last_pre_download_synced"
        private const val KEY_NEXT_TRIGGER = "next_auto_trigger_count"
        private const val KEY_BATCH_SIZE = "pre_download_batch_size"
        private const val KEY_AUTO_START = "pre_download_auto_start"
        private const val KEY_SYNC_CLOUD = "pre_download_sync_cloud"
    }

    /** 保存链接后自动预下载（默认开启，攒满 10 条触发，防止视频下架风险）。 */
    var autoPreDownload: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    /** 预下载走本机 HTTP 代理（默认开启；探测失败自动降级直连）。 */
    var proxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY, true)
        set(value) = prefs.edit().putBoolean(KEY_PROXY, value).apply()

    /** 最近一次预下载完成时间（毫秒），0 表示从未执行。 */
    var lastPreDownloadAt: Long
        get() = prefs.getLong(KEY_LAST_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AT, value).apply()

    /** 最近一次预下载成功媒体数。 */
    var lastPreDownloadCount: Int
        get() = prefs.getInt(KEY_LAST_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_COUNT, value).apply()

    /** 最近一次预下载完成后联动网盘备份是否已执行（避免重复同步）。 */
    var lastSyncedToCloud: Boolean
        get() = prefs.getBoolean(KEY_LAST_SYNCED, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_SYNCED, value).apply()

    /** 下次自动预下载触发的 PENDING 数量阈值（每攒满 [batchSize] 条触发一次，默认首次 [batchSize]）。 */
    var nextAutoTriggerCount: Int
        get() = prefs.getInt(KEY_NEXT_TRIGGER, batchSize)
        set(value) = prefs.edit().putInt(KEY_NEXT_TRIGGER, value).apply()

    /** 攒批条数（用户自定义，默认 10，范围 3..50）；变更时重置下次触发阈值。 */
    var batchSize: Int
        get() = prefs.getInt(KEY_BATCH_SIZE, 10).coerceIn(3, 50)
        set(value) {
            prefs.edit()
                .putInt(KEY_BATCH_SIZE, value.coerceIn(3, 50))
                .putInt(KEY_NEXT_TRIGGER, value.coerceIn(3, 50))
                .apply()
        }

    /** 攒满 [batchSize] 条后是否自动开始下载；关闭则等待用户手动批量下载。 */
    var autoStartDownload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** 预下载完成后是否自动上传到网盘（联动同步）。 */
    var syncToCloud: Boolean
        get() = prefs.getBoolean(KEY_SYNC_CLOUD, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_CLOUD, value).apply()
}
