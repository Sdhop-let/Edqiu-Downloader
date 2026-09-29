package com.ed.edqiu.backup.provider

import android.content.Context
import com.ed.edqiu.backup.data.CredentialStore
import com.ed.edqiu.backup.model.BackupTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 适配器注册表：providerId → [BackupTarget] 单例。
 *
 * 使用方式（AppContainer 统一构造注入）：
 * ```
 * val registry = ProviderRegistry(context, credentialStore)
 * registry.registerWebDavFamily()            // T02：注册 WebDAV 族三个适配器
 * registry.register(baiduTarget)             // T03：百度
 * registry.register(aliTarget)               // T04：阿里
 * val target = registry.get(ProviderId.WEBDAV)
 * ```
 *
 * T01 骨架阶段为无参空注册；T02 增加 context/credentialStore 构造参数，
 * 提供 [registerWebDavFamily] 与真实的 [detectCloudDrive2] 探测。
 */
class ProviderRegistry(
    private val context: Context,
    private val credentialStore: CredentialStore,
) {

    private val targets = ConcurrentHashMap<String, BackupTarget>()
    private val engine = WebDavEngine()

    /**
     * 注册一个适配器（幂等：同 id 重复注册以后者覆盖，便于测试替换）。
     * 返回 this 支持链式注册。
     */
    fun register(target: BackupTarget): ProviderRegistry {
        require(target.id.isNotBlank()) { "BackupTarget.id 不能为空" }
        targets[target.id] = target
        return this
    }

    /** 注册 WebDAV 复用族三个适配器（自定义 WebDAV / 123网盘 / CloudDrive2），返回 this。 */
    fun registerWebDavFamily(): ProviderRegistry {
        register(WebDavBackupTarget(context, engine))
        register(Pan123Target(context, credentialStore, engine))
        register(CloudDrive2Target(context, credentialStore, engine))
        return this
    }

    /** 按 id 取适配器；未注册返回 null。 */
    fun get(id: String): BackupTarget? = targets[id]

    /** 全部已注册适配器（注册顺序稳定）。 */
    fun all(): List<BackupTarget> = targets.values.toList()

    /** 是否已注册。 */
    fun isRegistered(id: String): Boolean = targets.containsKey(id)

    /** 已注册的 provider id 集合。 */
    fun registeredIds(): Set<String> = targets.keys.toSet()

    /**
     * 探测本机 CloudDrive2（127.0.0.1:19798/dav 连通性）。
     *
     * T02 实现真实探测（OPTIONS）：探测成功返回 [CloudDrive2Target] 实例并自动注册；
     * 未发现返回 `success(null)`。
     */
    suspend fun detectCloudDrive2(): Result<BackupTarget?> = withContext(Dispatchers.IO) {
        val target = CloudDrive2Target(context, credentialStore, engine)
        if (target.detectLocal()) {
            register(target)
            Result.success(target)
        } else {
            Result.success(null)
        }
    }
}
