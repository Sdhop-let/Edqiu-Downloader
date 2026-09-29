package com.ed.edqiu.data.preferences

import android.content.Context
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
 * Stores Twitter/X authentication cookies for yt-dlp fallback.
 * Required cookies: auth_token and ct0 (CSRF token).
 * Users can extract these from their browser's cookie store.
 *
 * 2026-09-16 加密存储（用户要求）：Cookie 属于账号登录态，等同密码，落盘必须密文。
 * - 方案：AndroidKeyStore 硬件密钥（TEE 保护、不可导出）+ AES/GCM/NoPadding，
 *   随机 12 字节 IV 前置拼接，Base64 后存入 SharedPreferences（"enc:v1:" 前缀版本化）。
 * - 迁移：旧版明文值在首次读取时自动加密重写，用户无感；
 * - 降级：Keystore 异常（极端设备故障）时回退明文并 Log.w，保功能可用不崩。
 * - clearCookies 只清数据文件，Keystore 密钥保留复用（无敏感残留，密钥无导出能力）。
 */
class CookiePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("twitter_cookies", Context.MODE_PRIVATE)

    fun getAuthToken(): String = readSecret("auth_token")
    fun getCt0(): String = readSecret("ct0")

    fun saveCookies(authToken: String, ct0: String) {
        writeSecret("auth_token", authToken.trim())
        writeSecret("ct0", ct0.trim())
    }

    fun clearCookies() {
        prefs.edit().clear().apply()
    }

    /** Whether we have enough cookies to attempt yt-dlp authenticated extraction. */
    fun hasCookies(): Boolean = getAuthToken().isNotBlank() && getCt0().isNotBlank()

    // ---------------- 加密实现（AndroidKeyStore + AES/GCM） ----------------

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
    }.onFailure { Log.w(TAG, "Cookie 加密失败（Keystore 异常），本次降级明文存储", it) }
        .getOrNull()

    /** Base64(IV + 密文) → 明文；解密失败（密钥变更/数据损坏）返回 null。 */
    private fun decryptFromBase64(base64: String): String? = runCatching {
        val data = Base64.decode(base64, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val payload = data.copyOfRange(GCM_IV_LENGTH, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.onFailure { Log.w(TAG, "Cookie 解密失败，按空值处理（需重新保存）", it) }
        .getOrNull()

    private fun readSecret(key: String): String {
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
                    Log.i(TAG, "Cookie 明文已自动迁移为 Keystore 加密存储")
                }
                raw
            }
        }
    }

    private fun writeSecret(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = encryptToBase64(value)
        val stored = if (encrypted != null) ENC_PREFIX + encrypted else PLAIN_PREFIX + value
        prefs.edit().putString(key, stored).apply()
    }

    companion object {
        private const val TAG = "CookiePreferences"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "edqiu_cookie_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val ENC_PREFIX = "enc:v1:"
        private const val PLAIN_PREFIX = "plain:v1:"
    }
}
