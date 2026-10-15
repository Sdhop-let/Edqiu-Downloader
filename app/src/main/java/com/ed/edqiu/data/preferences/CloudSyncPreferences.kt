package com.ed.edqiu.data.preferences

import android.content.Context
import android.content.SharedPreferences

/** 单个网盘预设的独立账号配置（每网盘一份，互不覆盖）。 */
data class WebDavAccountConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val remotePath: String = "",
    /** 该网盘配置是否已通过「测试连接」验证。 */
    val verified: Boolean = false,
    /** 上次验证通过的时间戳（epoch ms）；0 = 从未验证通过。 */
    val verifiedAt: Long = 0L,
) {
    /** 三元组齐全即可发起同步（verified 只影响入口展示）。 */
    val isComplete: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/**
 * 云同步（WebDAV）配置存储。
 *
 * 2026-10 安全整改：password（网盘主密码级别凭证）由明文 SharedPreferences 改为
 * [SecretBox]（AndroidKeyStore + AES/GCM）加密落盘；旧版明文值在首次读取时自动迁移。
 *
 * 2026-10 每网盘独立配置重构：
 * - 每个网盘预设（123pan/ctfile/clouddrive2/migu/…/custom）独立一组
 *   `cfg_{pid}_*` 键（server_url / username / password / remote_path / verified / verified_at），
 *   切换预设互不覆盖、互不串号；
 * - **保存语义**：仅「测试连接」成功时由设置页显式调用 [saveAccount] 落盘；
 *   编辑失败不落盘（旧版 600ms 去抖自动落盘已随本重构移除）；
 * - 旧全局 key（webdav_server_url 等）首次读取时一次性迁移到当前选中预设命名空间；
 * - 全局键保留：sync_enabled / webdav_provider_id（当前选中预设）/ auto_backup_* /
 *   allow_insecure_tls / last_sync_time / sync_file_count。
 */
class CloudSyncPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    private val secrets = SecretBox(
        prefs = prefs,
        keyAlias = "edqiu_cloud_sync_key",
        logTag = "CloudSyncPreferences",
    )

    companion object {
        private const val KEY_ENABLED = "sync_enabled"
        private const val KEY_PROVIDER_ID = "webdav_provider_id"
        private const val KEY_AUTO_BACKUP = "auto_backup_enabled"
        private const val KEY_AUTO_BACKUP_DAYS = "auto_backup_days"
        private const val KEY_ALLOW_INSECURE_TLS = "allow_insecure_tls"
        private const val KEY_SYNC_SMALL_FIRST = "sync_small_first"
        private const val KEY_LAST_SYNC = "last_sync_time"
        private const val KEY_SYNC_COUNT = "sync_file_count"

        // 旧全局配置键（2026-10 迁移源，只读不写）
        private const val LEGACY_SERVER_URL = "webdav_server_url"
        private const val LEGACY_USERNAME = "webdav_username"
        private const val LEGACY_PASSWORD = "webdav_password"
        private const val LEGACY_REMOTE_PATH = "webdav_remote_path"
        private const val LEGACY_VERIFIED = "connection_verified"
        private const val LEGACY_VERIFIED_AT = "last_verified_at"

        private const val CFG_PREFIX = "cfg_"
        private const val CFG_SERVER_URL = "_server_url"
        private const val CFG_USERNAME = "_username"
        private const val CFG_PASSWORD = "_password"
        private const val CFG_REMOTE_PATH = "_remote_path"
        private const val CFG_VERIFIED = "_verified"
        private const val CFG_VERIFIED_AT = "_verified_at"

        /** 迁移标记：旧全局配置只搬一次，避免用户改了新配置又被旧值覆盖。 */
        private const val KEY_MIGRATED = "per_provider_migrated"
    }

    // ================= 全局开关 =================

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 当前选中的网盘预设 id（同步链路读取 [currentAccount] 的依据）。 */
    var providerId: String
        get() = prefs.getString(KEY_PROVIDER_ID, "custom") ?: "custom"
        set(value) = prefs.edit().putString(KEY_PROVIDER_ID, value).apply()

    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BACKUP, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BACKUP, value).apply()

    var autoBackupDays: Int
        get() = prefs.getInt(KEY_AUTO_BACKUP_DAYS, 1)
        set(value) = prefs.edit().putInt(KEY_AUTO_BACKUP_DAYS, value.coerceIn(1, 30)).apply()

    /** 允许自签名/自建 CA 证书（HTTPS 自建 NAS 场景），默认关。 */
    var allowInsecureTls: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_INSECURE_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_INSECURE_TLS, value).apply()

    /**
     * 上传队列「小文件优先」排序（2026-10）：true = 小文件先传（快速清队列、
     * 感知更快），false = 按修改时间新→旧。对本轮队列构建生效，下轮同步可改。
     */
    var syncSmallFirst: Boolean
        get() = prefs.getBoolean(KEY_SYNC_SMALL_FIRST, false)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_SMALL_FIRST, value).apply()

    var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var syncedFileCount: Int
        get() = prefs.getInt(KEY_SYNC_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_SYNC_COUNT, value).apply()

    // ================= 每网盘独立配置 =================

    /**
     * 读取指定网盘预设的独立配置。
     * 首次调用触发旧全局配置一次性迁移（迁入迁移发生时的 [providerId] 命名空间）。
     */
    fun accountFor(providerId: String): WebDavAccountConfig {
        migrateLegacyOnce()
        val pid = providerId.ifBlank { "custom" }
        return WebDavAccountConfig(
            serverUrl = prefs.getString(CFG_PREFIX + pid + CFG_SERVER_URL, "") ?: "",
            username = prefs.getString(CFG_PREFIX + pid + CFG_USERNAME, "") ?: "",
            password = secrets.readSecret(CFG_PREFIX + pid + CFG_PASSWORD),
            remotePath = prefs.getString(CFG_PREFIX + pid + CFG_REMOTE_PATH, "") ?: "",
            verified = prefs.getBoolean(CFG_PREFIX + pid + CFG_VERIFIED, false),
            verifiedAt = prefs.getLong(CFG_PREFIX + pid + CFG_VERIFIED_AT, 0L),
        )
    }

    /** 仅在「测试连接」成功后由设置页调用：显式落盘该网盘配置与验证结论。 */
    fun saveAccount(providerId: String, config: WebDavAccountConfig) {
        val pid = providerId.ifBlank { "custom" }
        prefs.edit()
            .putString(CFG_PREFIX + pid + CFG_SERVER_URL, config.serverUrl.trim())
            .putString(CFG_PREFIX + pid + CFG_USERNAME, config.username)
            .putString(CFG_PREFIX + pid + CFG_REMOTE_PATH, config.remotePath.trim('/'))
            .putBoolean(CFG_PREFIX + pid + CFG_VERIFIED, config.verified)
            .putLong(CFG_PREFIX + pid + CFG_VERIFIED_AT, config.verifiedAt)
            .apply()
        secrets.writeSecret(CFG_PREFIX + pid + CFG_PASSWORD, config.password)
    }

    /** 当前选中预设的配置（同步链路统一从这里取凭证）。 */
    fun currentAccount(): WebDavAccountConfig = accountFor(providerId)

    /** 当前选中预设的三元组是否齐全（可发起同步）。 */
    fun hasWebDavConfig(): Boolean = currentAccount().isComplete

    /** 清空当前选中预设的独立配置（清除登录状态）。 */
    fun clearCurrentAccount() {
        clearAccount(providerId)
    }

    fun clearAccount(providerId: String) {
        val pid = providerId.ifBlank { "custom" }
        prefs.edit()
            .remove(CFG_PREFIX + pid + CFG_SERVER_URL)
            .remove(CFG_PREFIX + pid + CFG_USERNAME)
            .remove(CFG_PREFIX + pid + CFG_REMOTE_PATH)
            .remove(CFG_PREFIX + pid + CFG_VERIFIED)
            .remove(CFG_PREFIX + pid + CFG_VERIFIED_AT)
            .apply()
        secrets.writeSecret(CFG_PREFIX + pid + CFG_PASSWORD, "")
    }

    /**
     * 旧全局配置一次性迁移（v1.9.1 及之前只有一套配置）：
     * 搬到迁移时的 [providerId] 命名空间；之后旧键只读不再使用。
     */
    private fun migrateLegacyOnce() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val pid = providerId.ifBlank { "custom" }
        val legacyUrl = prefs.getString(LEGACY_SERVER_URL, "").orEmpty()
        val legacyUser = prefs.getString(LEGACY_USERNAME, "").orEmpty()
        val legacyPass = runCatching { secrets.readSecret(LEGACY_PASSWORD) }.getOrDefault("")
        val legacyPath = prefs.getString(LEGACY_REMOTE_PATH, "").orEmpty()
        if (legacyUrl.isNotBlank() || legacyUser.isNotBlank() || legacyPass.isNotBlank()) {
            val already = prefs.getString(CFG_PREFIX + pid + CFG_SERVER_URL, "").orEmpty()
            if (already.isBlank()) {
                saveAccount(
                    pid,
                    WebDavAccountConfig(
                        serverUrl = legacyUrl,
                        username = legacyUser,
                        password = legacyPass,
                        remotePath = legacyPath.ifBlank { "Edqiu" },
                        verified = prefs.getBoolean(LEGACY_VERIFIED, false),
                        verifiedAt = prefs.getLong(LEGACY_VERIFIED_AT, 0L),
                    ),
                )
            }
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
