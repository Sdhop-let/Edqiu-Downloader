package com.ed.edqiu.ui.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.backup.BackupFiles
import com.ed.edqiu.backup.BackupScope
import com.ed.edqiu.backup.BackupScheduler
import com.ed.edqiu.backup.BackupSettings
import com.ed.edqiu.backup.auth.BaiduAuthProgress
import com.ed.edqiu.backup.data.BackupLedgerRepository
import com.ed.edqiu.backup.data.BackupSecrets
import com.ed.edqiu.backup.data.BackupTaskStore
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.engine.BackupEngine
import com.ed.edqiu.backup.model.AuthMode
import com.ed.edqiu.backup.model.BackupException
import com.ed.edqiu.backup.model.BackupSummary
import com.ed.edqiu.backup.model.BackupTask
import com.ed.edqiu.backup.model.BackupTaskStatus
import com.ed.edqiu.backup.model.ProviderId
import com.ed.edqiu.backup.provider.AliPanTarget
import com.ed.edqiu.backup.provider.BaiduPanTarget
import com.ed.edqiu.backup.provider.Pan123OpenTarget
import com.ed.edqiu.backup.provider.Pan123Target
import com.ed.edqiu.backup.provider.ProviderRegistry
import com.ed.edqiu.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 网盘在列表中的展示状态。 */
data class ProviderUiState(
    val id: String,
    val displayName: String,
    val authMode: AuthMode,
    val configured: Boolean,
    val authorized: Boolean,
    val statusText: String,
    /** 凭证脱敏尾部（如 `ab****cd`），空串表示无 token 可展示。 */
    val maskSuffix: String,
)

/** 备份中心聚合 UI 状态。 */
data class CloudBackupUiState(
    val providers: List<ProviderUiState> = emptyList(),
    val tasks: List<BackupTask> = emptyList(),
    val running: Boolean = false,
    val lastSummary: BackupSummary? = null,
    /** 当前选中的网盘 id（决定备份设置卡片显示哪家）。 */
    val selectedProviderId: String? = null,
    val backupScope: BackupScope = BackupScope.ALL,
    val autoBackupEnabled: Boolean = false,
    /** 正在授权的网盘 id（决定渲染哪个授权 Dialog）。 */
    val pendingAuthProviderId: String? = null,
    val baiduAuth: BaiduAuthUiState = BaiduAuthUiState(),
    /** 123 网盘授权页 URL（授权 Dialog 用，发起授权时生成）。 */
    val pan123AuthorizeUrl: String? = null,
    /** 一次性提示消息（Snackbar）。 */
    val message: String? = null,
)

/**
 * 网盘直连备份中心 ViewModel。
 *
 * 职责：
 * - 组装 [ProviderRegistry]（AppContainer 注入，已注册 WebDAV 族 + 百度 + 阿里）；
 * - 驱动 [BackupEngine] 队列（手动备份 / 重试 / 取消）；
 * - 维护各网盘授权状态（设备码 / WebView token / WebDAV 账号密码 / 本机探测）；
 * - 持久化每个网盘的备份范围与自动备份开关（[BackupSettings]）。
 *
 * 凭证安全：所有 token 仅经脱敏（[BackupSecrets.mask]）后暴露给 UI。
 */
class CloudBackupViewModel(
    application: Application,
    private val registry: ProviderRegistry,
    private val engine: BackupEngine,
    private val taskStore: BackupTaskStore,
    private val credentialStore: CredentialStore,
    private val ledgerRepository: BackupLedgerRepository,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {

    private val context = application.applicationContext

    /** 授权侧状态（网盘列表 / 待授权网盘 / 123 授权页 / 提示消息）。 */
    private data class AuthUiState(
        val providers: List<ProviderUiState> = emptyList(),
        val pendingAuthProviderId: String? = null,
        val pan123AuthorizeUrl: String? = null,
        val message: String? = null,
    )

    /** 设置侧状态（当前选中网盘 + 其备份范围 / 自动备份）。 */
    private data class SettingsUiState(
        val selectedProviderId: String? = null,
        val backupScope: BackupScope = BackupScope.ALL,
        val autoBackupEnabled: Boolean = false,
    )

    private val _auth = MutableStateFlow(AuthUiState())
    private val _settings = MutableStateFlow(SettingsUiState())
    private val _baiduAuth = MutableStateFlow(BaiduAuthUiState())

    private val engineState = engine.state
    private val tasksFlow = combine(engineState, taskStore.observe()) { es, stored ->
        // 引擎内存态（含实时进度）优先；空闲时用磁盘持久化任务（App 重启恢复）
        if (es.tasks.isNotEmpty()) es.tasks else stored
    }

    val uiState: StateFlow<CloudBackupUiState> = combine(
        engineState,
        tasksFlow,
        _auth,
        _settings,
        _baiduAuth,
    ) { es, tasks, auth, settings, baidu ->
        CloudBackupUiState(
            providers = auth.providers,
            tasks = tasks,
            running = es.running,
            lastSummary = es.lastSummary,
            selectedProviderId = settings.selectedProviderId,
            backupScope = settings.backupScope,
            autoBackupEnabled = settings.autoBackupEnabled,
            pendingAuthProviderId = auth.pendingAuthProviderId,
            baiduAuth = baidu,
            pan123AuthorizeUrl = auth.pan123AuthorizeUrl,
            message = auth.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudBackupUiState())

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            taskStore.load()
            refreshProviders()
        }
    }

    // ================= 授权流程 =================

    /** 点击网盘行：已授权 → 展开设置；未授权 → 进入授权流程。 */
    fun onProviderClick(providerId: String) {
        selectProvider(providerId)
        val authorized = _auth.value.providers.find { it.id == providerId }?.authorized == true
        if (!authorized) authorize(providerId)
    }

    /** 进入指定网盘的授权流程（Dialog 由 CloudBackupScreen 按 [CloudBackupUiState.pendingAuthProviderId] 渲染）。 */
    fun authorize(providerId: String) {
        when (providerId) {
            ProviderId.BAIDU -> startBaiduAuth()
            ProviderId.ALIYUN -> _auth.update { it.copy(pendingAuthProviderId = ProviderId.ALIYUN) }
            ProviderId.PAN123 -> _auth.update { it.copy(pendingAuthProviderId = ProviderId.PAN123) }
            ProviderId.PAN123_OPEN -> startPan123Auth()
            ProviderId.WEBDAV -> _auth.update { it.copy(pendingAuthProviderId = ProviderId.WEBDAV) }
            ProviderId.CLOUDDRIVE2 -> viewModelScope.launch {
                registry.detectCloudDrive2()
                refreshProviders()
                postMessage("已重新探测本机 CloudDrive2")
            }
        }
    }

    /** 关闭授权 Dialog（同时取消百度轮询）。 */
    fun cancelAuth() {
        pollJob?.cancel()
        pollJob = null
        _auth.update { it.copy(pendingAuthProviderId = null, pan123AuthorizeUrl = null) }
        _baiduAuth.value = BaiduAuthUiState()
    }

    /** 百度授权成功回调（Dialog 成功态触发一次）。 */
    fun onBaiduAuthSuccess() {
        viewModelScope.launch { refreshProviders() }
        postMessage("百度网盘授权成功")
    }

    /** 阿里 WebView 截取到 refresh_token（含手动粘贴兜底）。 */
    fun onAliTokenReceived(token: String) {
        viewModelScope.launch {
            val target = registry.get(ProviderId.ALIYUN) as? AliPanTarget
            if (target == null) {
                _auth.update { it.copy(pendingAuthProviderId = null) }
                postMessage("阿里云盘适配器未注册")
                return@launch
            }
            target.setRefreshToken(token).fold(
                onSuccess = {
                    _auth.update { it.copy(pendingAuthProviderId = null) }
                    refreshProviders()
                    postMessage("阿里云盘授权成功")
                },
                onFailure = { error ->
                    _auth.update { it.copy(pendingAuthProviderId = null) }
                    postMessage("阿里云盘授权失败：${error.message ?: "未知错误"}")
                },
            )
        }
    }

    /** 123 网盘 OAuth 授权码回调（WebView 拦截 code 后触发）。 */
    fun onPan123CodeReceived(code: String) {
        viewModelScope.launch {
            val target = registry.get(ProviderId.PAN123_OPEN) as? Pan123OpenTarget
            if (target == null) {
                _auth.update { it.copy(pendingAuthProviderId = null, pan123AuthorizeUrl = null) }
                postMessage("123 网盘适配器未注册")
                return@launch
            }
            target.setAuthCode(code).fold(
                onSuccess = {
                    _auth.update { it.copy(pendingAuthProviderId = null, pan123AuthorizeUrl = null) }
                    refreshProviders()
                    postMessage("123 网盘授权成功")
                },
                onFailure = { error ->
                    _auth.update { it.copy(pendingAuthProviderId = null, pan123AuthorizeUrl = null) }
                    postMessage("123 网盘授权失败：${error.message ?: "未知错误"}")
                },
            )
        }
    }

    /**
     * 提交 WebDAV 账号密码（123 网盘 / 自定义 WebDAV）。
     * 成功：保存凭证 + 连通性校验 + 关闭 Dialog；失败：Dialog 保持打开并内联展示错误。
     */
    suspend fun submitWebDavCredential(
        providerId: String,
        serverUrl: String,
        username: String,
        password: String,
        remotePath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val target = registry.get(providerId)
            ?: return@withContext Result.failure(BackupException("未找到备份目标：$providerId"))
        runCatching {
            when (providerId) {
                ProviderId.PAN123 -> {
                    val url = serverUrl.trim()
                    val values = mutableMapOf(
                        "username" to username.trim(),
                        "password" to password,
                        "remote_path" to remotePath.trim().trim('/').ifBlank { Pan123Target.DEFAULT_REMOTE_ROOT },
                    )
                    // 完整地址存 server_url；否则存 account_id（Pan123Target 自动拼标准地址）
                    if (url.contains("://")) {
                        values["server_url"] = url
                    } else if (url.isNotBlank()) {
                        values["account_id"] = url.trim()
                    }
                    credentialStore.save(providerId, values)
                }
                ProviderId.WEBDAV -> {
                    val prefs = CloudSyncPreferences(context)
                    prefs.serverUrl = serverUrl.trim()
                    prefs.username = username.trim()
                    prefs.password = password
                    prefs.remotePath = remotePath.trim().trim('/').ifBlank { prefs.remotePath }
                }
                else -> throw BackupException("该网盘无需填写 WebDAV 账号密码")
            }
            target.testConnection().getOrElse { throw it }
            Unit
        }.fold(
            onSuccess = {
                _auth.update { it.copy(pendingAuthProviderId = null) }
                refreshProviders()
                postMessage("「${target.displayName}」配置成功，连接正常")
                Result.success(Unit)
            },
            onFailure = { error -> Result.failure(error) },
        )
    }

    /** 刷新某网盘登录状态（触发 token 自动刷新 / 连通性测试）。 */
    fun refreshToken(providerId: String) {
        viewModelScope.launch {
            val target = registry.get(providerId)
            if (target == null) {
                postMessage("未找到备份目标")
                return@launch
            }
            val name = target.displayName
            target.testConnection().fold(
                onSuccess = { postMessage("$name 连接正常，登录状态已校验") },
                onFailure = { postMessage("$name 连接失败：${it.message}") },
            )
            refreshProviders()
        }
    }

    /** 清除某网盘登录状态（重新授权）。 */
    fun clearAuth(providerId: String) {
        viewModelScope.launch {
            when (providerId) {
                ProviderId.BAIDU -> (registry.get(providerId) as? BaiduPanTarget)?.clearAuth()
                ProviderId.ALIYUN, ProviderId.PAN123, ProviderId.PAN123_OPEN -> credentialStore.clear(providerId)
                ProviderId.WEBDAV -> CloudSyncPreferences(context).apply {
                    serverUrl = ""
                    username = ""
                    password = ""
                    remotePath = "Edqiu"
                }
            }
            // 换账号后旧账本（DONE 记录 + 云端 file id）失效，一并清除避免误跳过
            runCatching { ledgerRepository.clear(providerId) }
            refreshProviders()
            postMessage("已清除登录状态，请重新授权")
        }
    }

    // ================= 备份设置 =================

    /** 选中某网盘（加载其备份范围 / 自动备份开关）。 */
    fun selectProvider(providerId: String) {
        _settings.update {
            it.copy(
                selectedProviderId = providerId,
                backupScope = BackupSettings.scope(context, providerId),
                autoBackupEnabled = BackupSettings.autoEnabled(context, providerId),
            )
        }
    }

    /** 设置备份范围（全部 / 仅视频 / 仅图片），作用于当前选中网盘。 */
    fun setBackupScope(scope: BackupScope) {
        val providerId = _settings.value.selectedProviderId ?: return
        BackupSettings.setScope(context, providerId, scope)
        _settings.update { it.copy(backupScope = scope) }
    }

    /** 切换自动备份（WorkManager 周期任务），作用于当前选中网盘。 */
    fun toggleAutoBackup(enabled: Boolean) {
        val providerId = _settings.value.selectedProviderId ?: return
        BackupSettings.setAutoEnabled(context, providerId, enabled)
        BackupScheduler.setAutoBackup(context, providerId, enabled)
        _settings.update { it.copy(autoBackupEnabled = enabled) }
        postMessage(if (enabled) "已开启自动备份（每天后台执行）" else "已关闭自动备份")
    }

    // ================= 备份执行 =================

    /** 立即备份当前选中网盘（扫描 + 入队 + 串行执行，UI 实时进度）。 */
    fun backupNow() {
        val providerId = _settings.value.selectedProviderId ?: return
        viewModelScope.launch {
            val target = registry.get(providerId)
            if (target == null) {
                postMessage("未找到备份目标")
                return@launch
            }
            if (!runCatching { target.isConfigured() }.getOrDefault(false)) {
                postMessage("「${target.displayName}」尚未配置，请先授权")
                return@launch
            }
            val scope = BackupSettings.scope(context, providerId)
            val doneTaskIds = taskStore.load()
                .filter { it.targetId == providerId && it.status == BackupTaskStatus.DONE }
                .map { it.taskId }
                .toSet()
            val monitorUri = settingsRepository.monitorDirUriFlow.first()
            val files = BackupFiles.collect(context, monitorUri, scope, providerId, doneTaskIds, ledgerRepository)
            if (files.isEmpty()) {
                postMessage("没有需要备份的新文件")
                return@launch
            }
            engine.enqueue(providerId, files)
            engine.runQueue().fold(
                onSuccess = { summary ->
                    postMessage("备份完成：成功 ${summary.succeeded}，失败 ${summary.failed}，跳过 ${summary.skipped}")
                },
                onFailure = { error ->
                    postMessage("备份失败：${error.message ?: "未知错误"}")
                },
            )
        }
    }

    /** 重试当前选中网盘的全部失败任务。 */
    fun retryFailed() {
        val providerId = _settings.value.selectedProviderId ?: return
        viewModelScope.launch {
            engine.retryFailed(providerId)
            engine.runQueue().fold(
                onSuccess = { summary ->
                    postMessage("重试完成：成功 ${summary.succeeded}，失败 ${summary.failed}，跳过 ${summary.skipped}")
                },
                onFailure = { error ->
                    postMessage("重试失败：${error.message ?: "未知错误"}")
                },
            )
        }
    }

    /** 取消单个任务（仅 PENDING / UPLOADING 可取消）。 */
    fun cancelTask(taskId: String) {
        viewModelScope.launch { engine.cancel(taskId) }
    }

    // ================= 内部 =================

    private suspend fun refreshProviders() {
        val list = registry.all().map { target ->
            val configured = runCatching { target.isConfigured() }.getOrDefault(false)
            val authorized = runCatching { target.isAuthorized() }.getOrDefault(false)
            val statusText = when {
                target.id == ProviderId.CLOUDDRIVE2 ->
                    if (authorized) "本机已运行" else "本机未检测到"
                authorized -> "已授权"
                configured -> "已配置"
                else -> "未配置"
            }
            ProviderUiState(
                id = target.id,
                displayName = target.displayName,
                authMode = target.authMode,
                configured = configured,
                authorized = authorized,
                statusText = statusText,
                maskSuffix = maskFor(target.id),
            )
        }
        _auth.update { it.copy(providers = list) }
    }

    /** 只读存储并脱敏展示凭证尾部；不授权任何 token 明文给 UI。 */
    private fun maskFor(providerId: String): String {
        val creds = credentialStore.read(providerId)
        return when (providerId) {
            ProviderId.BAIDU -> BackupSecrets.mask(creds["access_token"])
            ProviderId.ALIYUN -> BackupSecrets.mask(creds["refresh_token"])
            ProviderId.PAN123 -> BackupSecrets.mask(creds["password"])
            ProviderId.PAN123_OPEN -> BackupSecrets.mask(creds["refresh_token"])
            ProviderId.WEBDAV -> BackupSecrets.mask(CloudSyncPreferences(context).password)
            else -> ""
        }
    }

    /** 发起 123 网盘 OAuth 授权：生成授权页 URL 后弹出 WebView 登录。 */
    private fun startPan123Auth() {
        val target = registry.get(ProviderId.PAN123_OPEN) as? Pan123OpenTarget
        if (target == null) {
            postMessage("123 网盘适配器未注册")
            return
        }
        if (!target.isClientConfigured) {
            postMessage("未配置 123 网盘应用资质，请在构建配置中填写 PAN123_CLIENT_ID")
            return
        }
        val state = UUID.randomUUID().toString()
        val url = runCatching { target.buildAuthorizeUrl(state) }.getOrNull()
        if (url.isNullOrBlank()) {
            postMessage("生成 123 网盘授权链接失败，请检查应用资质配置")
            return
        }
        _auth.update {
            it.copy(
                pendingAuthProviderId = ProviderId.PAN123_OPEN,
                pan123AuthorizeUrl = url,
            )
        }
    }

    private fun startBaiduAuth() {
        pollJob?.cancel()
        _auth.update { it.copy(pendingAuthProviderId = ProviderId.BAIDU) }
        _baiduAuth.value = BaiduAuthUiState(requesting = true)
        val target = registry.get(ProviderId.BAIDU) as? BaiduPanTarget
        if (target == null) {
            _baiduAuth.value = BaiduAuthUiState(errorMessage = "百度网盘适配器未注册")
            return
        }
        viewModelScope.launch {
            target.startDeviceCodeAuth().fold(
                onSuccess = { session ->
                    _baiduAuth.value = BaiduAuthUiState(session = session, polling = true)
                    pollJob = viewModelScope.launch {
                        target.pollDeviceToken(session) { progress ->
                            when (progress) {
                                is BaiduAuthProgress.Waiting ->
                                    _baiduAuth.update { it.copy(hint = progress.message, polling = true) }
                                is BaiduAuthProgress.Failed ->
                                    _baiduAuth.update { it.copy(errorMessage = progress.message, polling = false) }
                                is BaiduAuthProgress.Success -> Unit // 成功由返回值统一处理
                            }
                        }.fold(
                            onSuccess = { bundle ->
                                target.saveAuthResult(bundle)
                                _baiduAuth.value = BaiduAuthUiState(success = true)
                                refreshProviders()
                            },
                            onFailure = { error ->
                                _baiduAuth.update { it.copy(errorMessage = error.message, polling = false) }
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _baiduAuth.value = BaiduAuthUiState(errorMessage = error.message)
                },
            )
        }
    }

    private fun postMessage(message: String) {
        _auth.update { it.copy(message = message) }
    }

    /** 消费一次性提示（Snackbar 展示后调用）。 */
    fun consumeMessage() {
        _auth.update { it.copy(message = null) }
    }
}
