package com.ed.edqiu.backup.model

/**
 * 备份/同步模块统一用户可读错误。
 *
 * 所有 provider / engine 失败时抛出或包装为 `Result.failure(BackupException(中文消息))`，
 * 直接展示给用户且不含敏感字段（token/密码一律脱敏）。
 *
 * `open` 供携带附加语义的子类继承（如 [com.ed.edqiu.backup.provider.WebDavTransferException]
 * 的 HTTP 状态码，供重试判定区分瞬态/配置错误）。
 *
 * 2026-10 网盘备份中心（BackupEngine/多网盘适配器/账本）整体移除后，本异常由
 * WebDAV 同步链路（WebDavEngine / WebDavSyncService / MediaBackupViewModel）继续使用。
 */
open class BackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
