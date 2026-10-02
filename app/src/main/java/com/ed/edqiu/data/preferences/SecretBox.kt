package com.ed.edqiu.data.preferences

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 通用敏感字符串加密存储（AndroidKeyStore + AES/GCM）。
 *
 * - 方案与 [CookiePreferences] 一致：随机 12 字节 IV 前置拼接，Base64 后落盘，
 *   "enc:v1:" 前缀版本化；每个实例使用独立 Keystore 别名（[keyAlias]）互不影响；
 * - 迁移：旧版无前缀明文在首次读取时自动加密重写，用户无感；
 * - 降级：Keystore 异常（极端设备故障）时写路径回退明文（"plain:v1:"）并 Log.w，
 *   保功能可用不崩。
 *
 * 用途：WebDAV 密码（[CloudSyncPreferences]）等与 Cookie 同级的账号凭证。
 * Cookie 自身仍走 CookiePreferences 内同名实现（保持其已验证路径不动）。
 */
internal class SecretBox(
    private val prefs: SharedPreferences,
    private val keyAlias: String,
    private val logTag: String,
) {

    fun readSecret(key: String): String {
        val raw = prefs.getString(key, null) ?: return ""
        if (raw.isBlank()) return ""
        return when {
            raw.startsWith(ENC_PREFIX) ->
                decryptFromBase64(raw.removePrefix(ENC_PREFIX)) ?: ""
            raw.startsWith(PLAIN_PREFIX) -> raw.removePrefix(PLAIN_PREFIX)
            else -> {
                // 旧版明文数据：自动迁移为密文（用户无感）
                val encrypted = encryptToBase64(raw)
                if (encrypted != null) {
                    prefs.edit().putString(key, ENC_PREFIX + encrypted).apply()
                    Log.i(logTag, "明文凭证已自动迁移为 Keystore 加密存储")
                }
                raw
            }
        }
    }

    fun writeSecret(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = encryptToBase64(value)
        val stored = if (encrypted != null) ENC_PREFIX + encrypted else PLAIN_PREFIX + value
        prefs.edit().putString(key, stored).apply()
    }

    // ---------------- 加密实现（AndroidKeyStore + AES/GCM） ----------------

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 明文 → Base64(IV(12B) + GCM 密文)；Keystore 故障时返回 null（调用方降级）。 */
    private fun encryptToBase64(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }.onFailure { Log.w(logTag, "凭证加密失败（Keystore 异常），本次降级明文存储", it) }
        .getOrNull()

    /** Base64(IV + 密文) → 明文；解密失败（密钥变更/数据损坏）返回 null。 */
    private fun decryptFromBase64(base64: String): String? = runCatching {
        val data = Base64.decode(base64, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val payload = data.copyOfRange(GCM_IV_LENGTH, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.onFailure { Log.w(logTag, "凭证解密失败，按空值处理（需重新保存）", it) }
        .getOrNull()

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val ENC_PREFIX = "enc:v1:"
        private const val PLAIN_PREFIX = "plain:v1:"
    }
}
