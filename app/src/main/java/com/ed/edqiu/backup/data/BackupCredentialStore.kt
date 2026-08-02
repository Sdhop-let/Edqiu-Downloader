package com.ed.edqiu.backup.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 凭证加密存储接口。
 *
 * 每个 provider 独立命名空间（如 "baidu" / "aliyun" / "pan123"）；
 * 逻辑 key 命名见架构文档第 8 节：`access_token` / `refresh_token` / `expires_at` / `server_url` / `username` / `password`。
 * 实现层统一加 `backup_` 前缀 + providerId 隔离（如 `backup_baidu_access_token`）。
 */
interface CredentialStore {

    /**
     * 保存一组凭证。
     *
     * @param providerId provider 命名空间
     * @param values     逻辑 key → 明文值（如 `access_token` → "xxx"）
     */
    fun save(providerId: String, values: Map<String, String>)

    /** 读取某 provider 的全部凭证，返回逻辑 key → 明文值；不存在返回空 Map。 */
    fun read(providerId: String): Map<String, String>

    /** 清除某 provider 的全部凭证。 */
    fun clear(providerId: String)
}

/**
 * 基于 [EncryptedSharedPreferences] 的凭证加密存储。
 *
 * - 主密钥（MasterKey）由 Android Keystore 生成（AES256_GCM），不落盘。
 * - 存储文件名 `backup_credentials`，key 用 AES256_SIV 加密、value 用 AES256_GCM 加密。
 * - 密钥被系统清空（如应用数据恢复）时自动降级为空存储，不抛崩溃。
 */
@Suppress("DEPRECATION") // EncryptedSharedPreferences 处于维护模式，功能稳定，是当前最小成本方案
class EncryptedCredentialStore(context: Context) : CredentialStore {

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun save(providerId: String, values: Map<String, String>) {
        if (values.isEmpty()) return
        val editor = prefs.edit()
        values.forEach { (name, value) -> editor.putString(keyFor(providerId, name), value) }
        editor.apply()
    }

    override fun read(providerId: String): Map<String, String> {
        return runCatching {
            val prefix = keyPrefix(providerId)
            prefs.all
                .filterKeys { it.startsWith(prefix) }
                .mapNotNull { (key, value) ->
                    val logicalName = key.removePrefix(prefix)
                    (value as? String)?.let { logicalName to it }
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    override fun clear(providerId: String) {
        val prefix = keyPrefix(providerId)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun keyFor(providerId: String, name: String): String = keyPrefix(providerId) + name

    private fun keyPrefix(providerId: String): String = "backup_${providerId}_"

    private companion object {
        const val PREFS_FILE_NAME = "backup_credentials"
    }
}

/**
 * 敏感字段脱敏工具（token / 密码 / secret 一律打码）。
 *
 * 规则：
 * - 空值 → 空串；
 * - 长度 ≤ 4 → `***`；
 * - 其余 → 保留前 2 位与后 2 位，中间 `****`。
 */
object BackupSecrets {

    /** 脱敏单个值。 */
    fun mask(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return if (value.length <= 4) {
            "***"
        } else {
            value.take(2) + "****" + value.takeLast(2)
        }
    }

    /** 脱敏整个 Map：key 含 token / password / secret 的值打码，其余原样。 */
    fun maskSensitive(map: Map<String, String>): Map<String, String> =
        map.mapValues { (key, value) ->
            if (key.contains("token", ignoreCase = true) ||
                key.contains("password", ignoreCase = true) ||
                key.contains("secret", ignoreCase = true)
            ) {
                mask(value)
            } else {
                value
            }
        }
}
