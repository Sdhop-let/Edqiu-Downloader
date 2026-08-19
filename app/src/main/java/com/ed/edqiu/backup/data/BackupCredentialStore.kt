package com.ed.edqiu.backup.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

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

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    /**
     * 创建加密 SharedPreferences，密钥失配时降级重建，避免拖垮 App 启动。
     *
     * EncryptedSharedPreferences 的 keyset 由 Android Keystore 的 master key 加密落盘；
     * 刷机 / 数据恢复 / 清除凭据等操作会让 master key 与密文失配，create() 抛出
     * AEADBadTagException。此处捕获后清理损坏密文与 master key 并重建空存储——
     * 仅丢失网盘凭证（需重新登录），不导致闪退。
     */
    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            doCreate(context)
        } catch (e: Exception) {
            Log.w(TAG, "加密凭证密钥失配，降级重建空存储", e)
            context.deleteSharedPreferences(PREFS_FILE_NAME)
            deleteMasterKey()
            doCreate(context)
        }
    }

    private fun doCreate(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey(context),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun masterKey(context: Context): MasterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun deleteMasterKey() {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "删除 master key 失败（忽略）", e)
        }
    }

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
        const val TAG = "EncryptedCredentialStore"
        const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
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
