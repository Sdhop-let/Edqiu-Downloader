package com.ed.edqiu.backup.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 备份任务状态机。
 *
 * 正常流转：`PENDING → UPLOADING → DONE | FAILED`；
 * 失败重试：`FAILED → PENDING(attempt + 1)`；用户取消：`PENDING/UPLOADING → CANCELLED`。
 */
@Serializable
enum class BackupTaskStatus {
    PENDING,
    UPLOADING,
    DONE,
    FAILED,
    CANCELLED,
}

/**
 * 备份任务最小单元。
 *
 * `taskId = sha256(targetId + "|" + remotePath)`，天然幂等去重：
 * 同一目标同一远程路径只存在一个任务。
 *
 * @property taskId      任务唯一 id（sha256 十六进制）
 * @property targetId    目标 provider id，见 [ProviderId]
 * @property localPath   本地文件绝对路径
 * @property remotePath  远程相对路径（如 "Edqiu/xxx.mp4"）
 * @property size        文件大小（字节）
 * @property status      当前状态，见 [BackupTaskStatus]
 * @property attempt     已重试次数（首次为 0）
 * @property bytesDone   已完成字节数（分片上传按字节累计，随任务落盘供续传与 UI 恢复）
 * @property errorMessage 最近一次失败原因（面向用户、中文、脱敏）
 */
@Serializable
data class BackupTask(
    val taskId: String,
    val targetId: String,
    val localPath: String,
    val remotePath: String,
    val size: Long,
    val status: BackupTaskStatus = BackupTaskStatus.PENDING,
    val attempt: Int = 0,
    val bytesDone: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {

    /** 进度 0..1（字节累计 / 总大小）；size 为 0 时恒为 0。 */
    val progress: Float
        get() = if (size <= 0L) 0f else (bytesDone.toFloat() / size.toFloat()).coerceIn(0f, 1f)

    /** 任务是否处于可执行状态（排队中或上传中）。 */
    val isActive: Boolean
        get() = status == BackupTaskStatus.PENDING || status == BackupTaskStatus.UPLOADING

    /** 任务是否已终态（成功 / 失败 / 取消）。 */
    val isFinished: Boolean
        get() = status == BackupTaskStatus.DONE || status == BackupTaskStatus.FAILED || status == BackupTaskStatus.CANCELLED

    /** 标记开始上传。 */
    fun markUploading(): BackupTask = copy(
        status = BackupTaskStatus.UPLOADING,
        errorMessage = null,
        updatedAt = System.currentTimeMillis(),
    )

    /** 更新进度（字节数），保持 UPLOADING 状态。 */
    fun withProgress(done: Long): BackupTask = copy(
        bytesDone = done.coerceIn(0L, size),
        updatedAt = System.currentTimeMillis(),
    )

    /** 标记成功完成（bytesDone 归位为文件大小）。 */
    fun markDone(): BackupTask = copy(
        status = BackupTaskStatus.DONE,
        bytesDone = size,
        errorMessage = null,
        updatedAt = System.currentTimeMillis(),
    )

    /** 标记失败并记录面向用户的中文错误信息。 */
    fun markFailed(message: String): BackupTask = copy(
        status = BackupTaskStatus.FAILED,
        errorMessage = message,
        updatedAt = System.currentTimeMillis(),
    )

    /** 重试：回到 PENDING，attempt + 1，清空错误。 */
    fun retry(): BackupTask = copy(
        status = BackupTaskStatus.PENDING,
        attempt = attempt + 1,
        errorMessage = null,
        updatedAt = System.currentTimeMillis(),
    )

    /** 取消任务。 */
    fun cancel(): BackupTask = copy(
        status = BackupTaskStatus.CANCELLED,
        updatedAt = System.currentTimeMillis(),
    )

    companion object {
        /**
         * 计算任务幂等 id：`sha256(targetId + "|" + remotePath)`（十六进制小写）。
         */
        fun computeId(targetId: String, remotePath: String): String {
            val input = "$targetId|$remotePath".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** 一轮队列执行结束后的汇总（供 UI 展示与日志）。 */
data class BackupSummary(
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val totalBytes: Long = 0L,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = System.currentTimeMillis(),
) {
    /** 执行耗时（毫秒）。 */
    val durationMs: Long
        get() = (finishedAt - startedAt).coerceAtLeast(0L)
}
