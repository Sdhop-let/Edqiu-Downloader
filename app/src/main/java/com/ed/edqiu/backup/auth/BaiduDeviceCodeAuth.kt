package com.ed.edqiu.backup.auth

import com.ed.edqiu.BuildConfig
import com.ed.edqiu.backup.model.BackupException
import com.ed.edqiu.backup.provider.BaiduPanApi
import com.ed.edqiu.backup.provider.BaiduTokenResponse
import com.ed.edqiu.backup.provider.DeviceCodeResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 百度网盘设备码授权状态机。
 *
 * 流程：`requestDeviceCode()` 请求设备码（返回二维码/验证码）→ 展示给用户 →
 * `pollToken()` 按 `interval` 轮询换 token（成功 / 等待 / 失败 / 取消）。
 *
 * - 成功：返回 [BaiduTokenBundle]（access_token + refresh_token + 过期时间），由调用方加密存储；
 * - 等待：通过 [BaiduAuthProgress.Waiting] 回调通知 UI 继续轮询；
 * - 失败 / 超时 / 用户拒绝：返回 [Result.failure]，错误信息面向用户、中文、脱敏；
 * - 取消：轮询循环使用协程协作取消（`ensureActive()`），调用方取消协程即停止轮询。
 *
 * client_id / client_secret 取自 `BuildConfig`（gradle.properties 注入，开发期可留空）；
 * 未配置时返回「未配置应用资质」错误，UI 不弹授权窗。
 */
class BaiduDeviceCodeAuth(
    private val api: BaiduPanApi = BaiduPanApi(),
    private val clientId: String = BuildConfig.BAIDU_CLIENT_ID,
    private val clientSecret: String = BuildConfig.BAIDU_CLIENT_SECRET,
) {

    /** 应用资质是否已配置（client_id 非空即可发起设备码请求）。 */
    val isConfigured: Boolean
        get() = clientId.isNotBlank()

    /** 请求设备码。 */
    suspend fun requestDeviceCode(): Result<DeviceCodeSession> {
        if (clientId.isBlank()) {
            return Result.failure(
                BackupException("未配置百度网盘应用资质（BAIDU_CLIENT_ID），请联系开发者配置后使用")
            )
        }
        return api.requestDeviceCode(clientId).map { it.toSession() }
    }

    /**
     * 轮询换 token，最长 `expires_in` 秒。
     *
     * @param session     [requestDeviceCode] 返回的会话
     * @param onProgress  每次轮询后的进度回调（等待 / 成功）
     */
    suspend fun pollToken(
        session: DeviceCodeSession,
        onProgress: (BaiduAuthProgress) -> Unit = {},
    ): Result<BaiduTokenBundle> {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return Result.failure(
                BackupException("未配置百度网盘应用资质（BAIDU_CLIENT_ID / BAIDU_CLIENT_SECRET），请联系开发者配置后使用")
            )
        }
        var remainingSeconds = session.expiresIn.coerceAtLeast(1L)
        var interval = session.interval.coerceAtLeast(3L)
        while (remainingSeconds > 0) {
            // 支持取消：调用方取消协程（如关闭授权弹窗）即抛出 CancellationException，停止轮询
            currentCoroutineContext().ensureActive()
            delay(interval * 1000L)
            remainingSeconds -= interval

            val result = api.pollDeviceToken(clientId, clientSecret, session.deviceCode)
            val token = result.getOrNull()
            if (token == null) {
                return Result.failure(
                    result.exceptionOrNull() ?: BackupException("百度网盘授权失败，请重试")
                )
            }
            if (token.error.isNullOrBlank() && token.accessToken.isNotBlank()) {
                val bundle = BaiduTokenBundle(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken.ifBlank { token.accessToken },
                    expiresIn = token.expiresIn,
                )
                onProgress(BaiduAuthProgress.Success(bundle))
                return Result.success(bundle)
            }
            when (token.error) {
                "authorization_pending" -> onProgress(BaiduAuthProgress.Waiting("等待扫码授权…"))
                "slow_down" -> {
                    onProgress(BaiduAuthProgress.Waiting("请求过于频繁，请稍候…"))
                    interval += 5
                }
                "expired_token" -> return Result.failure(BackupException("授权二维码已过期，请重新发起登录"))
                "access_denied" -> return Result.failure(BackupException("您拒绝了百度网盘授权"))
                else -> return Result.failure(
                    BackupException("百度网盘授权失败：${token.errorDescription ?: token.error}")
                )
            }
        }
        return Result.failure(BackupException("授权超时，请重新发起登录"))
    }

    /** 用 refresh_token 刷新 access_token（授权过期兜底）。 */
    suspend fun refresh(refreshToken: String): Result<BaiduTokenBundle> {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return Result.failure(
                BackupException("未配置百度网盘应用资质（BAIDU_CLIENT_ID / BAIDU_CLIENT_SECRET），请联系开发者配置后使用")
            )
        }
        val result = api.refreshToken(clientId, clientSecret, refreshToken)
        val token = result.getOrNull()
        if (token == null) {
            return Result.failure(
                result.exceptionOrNull() ?: BackupException("刷新百度网盘登录状态失败")
            )
        }
        if (!token.error.isNullOrBlank() || token.accessToken.isBlank()) {
            return Result.failure(
                BackupException("刷新百度网盘登录状态失败：${token.errorDescription ?: token.error ?: "未知错误"}")
            )
        }
        return Result.success(
            BaiduTokenBundle(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken.ifBlank { refreshToken },
                expiresIn = token.expiresIn,
            )
        )
    }

    private fun DeviceCodeResponse.toSession(): DeviceCodeSession = DeviceCodeSession(
        deviceCode = deviceCode,
        userCode = userCode,
        verificationUrl = verificationUrl,
        qrcodeUrl = qrcodeUrl,
        expiresIn = expiresIn,
        interval = interval,
    )
}

/** 设备码会话（展示二维码 / 验证码 + 轮询参数）。 */
data class DeviceCodeSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val qrcodeUrl: String,
    val expiresIn: Long,
    val interval: Long,
)

/** token 捆绑包（access_token + refresh_token + 过期时间）。 */
data class BaiduTokenBundle(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
) {
    /** 过期时间（epoch 毫秒）。 */
    val expiresAtMillis: Long
        get() = System.currentTimeMillis() + (expiresIn * 1000L)
}

/** 授权轮询进度（供 UI 回调）。 */
sealed interface BaiduAuthProgress {
    /** 仍在等待用户扫码 / 确认。 */
    data class Waiting(val message: String) : BaiduAuthProgress

    /** 授权成功。 */
    data class Success(val bundle: BaiduTokenBundle) : BaiduAuthProgress

    /** 授权失败。 */
    data class Failed(val message: String) : BaiduAuthProgress
}
