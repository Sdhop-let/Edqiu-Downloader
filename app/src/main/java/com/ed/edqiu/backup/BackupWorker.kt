package com.ed.edqiu.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ed.edqiu.EdqiuApplication
import com.ed.edqiu.backup.data.BackupLedgerRepository
import com.ed.edqiu.backup.model.BackupTask
import com.ed.edqiu.backup.model.BackupTaskStatus
import com.ed.edqiu.data.repository.DownloadMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 备份范围（网盘设置项，见 CloudBackupScreen「备份设置」）。
 */
enum class BackupScope {
    ALL,
    VIDEO,
    IMAGE,
}

/**
 * 网盘备份偏好（普通 SharedPreferences，非敏感数据）。
 *
 * 每个 provider 独立命名空间：`scope_{providerId}` / `auto_{providerId}`。
 * 由 CloudBackupViewModel 与 BackupWorker 共用。
 */
object BackupSettings {

    private const val PREFS = "backup_settings"
    private const val KEY_SELECTED = "selected_provider"
    private fun scopeKey(providerId: String) = "scope_$providerId"
    private fun autoKey(providerId: String) = "auto_$providerId"

    /** 备份中心当前选中的网盘 id（预下载联动 / 备份状态页共用；null = 从未选中）。 */
    fun selectedProvider(context: Context): String? =
        prefs(context).getString(KEY_SELECTED, null)

    /** 持久化备份中心当前选中的网盘 id。 */
    fun setSelectedProvider(context: Context, providerId: String) {
        prefs(context).edit().putString(KEY_SELECTED, providerId).apply()
    }

    fun scope(context: Context, providerId: String): BackupScope {
        val name = prefs(context).getString(scopeKey(providerId), null) ?: return BackupScope.ALL
        return runCatching { BackupScope.valueOf(name) }.getOrDefault(BackupScope.ALL)
    }

    fun setScope(context: Context, providerId: String, scope: BackupScope) {
        prefs(context).edit().putString(scopeKey(providerId), scope.name).apply()
    }

    fun autoEnabled(context: Context, providerId: String): Boolean =
        prefs(context).getBoolean(autoKey(providerId), false)

    fun setAutoEnabled(context: Context, providerId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(autoKey(providerId), enabled).apply()
    }

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 自动备份调度器（WorkManager）。
 *
 * 开启：24 小时周期任务 + 立即执行一次；关闭：取消唯一任务。
 * 与 T03 收件箱自动备份（HistoryBackupScheduler）同一套模式。
 */
object BackupScheduler {

    const val KEY_TARGET_ID = "backup_target_id"

    private fun periodicName(providerId: String) = "cloud_backup_auto_$providerId"
    private fun immediateName(providerId: String) = "cloud_backup_auto_now_$providerId"

    fun setAutoBackup(context: Context, providerId: String, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(periodicName(providerId))
            workManager.cancelUniqueWork(immediateName(providerId))
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val data = workDataOf(KEY_TARGET_ID to providerId)
        workManager.enqueueUniquePeriodicWork(
            periodicName(providerId),
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(data)
                .build(),
        )
        workManager.enqueueUniqueWork(
            immediateName(providerId),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build(),
        )
    }
}

/**
 * 待备份文件收集器。
 *
 * 支持：内部下载目录（`xinvox://downloads` 默认）/ file:// 路径 / SAF content:// 目录
 * （SAF 文件先复制到 cache 暂存区，供 [com.ed.edqiu.backup.model.BackupTarget.uploadFile] 使用）。
 *
 * 幂等：跳过「已完成（DONE）」任务对应的文件，避免重复上传。
 */
object BackupFiles {

    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val ALL_EXTS = VIDEO_EXTS + IMAGE_EXTS

    fun isBackupMedia(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in ALL_EXTS

    /**
     * 扫描监控目录下全部受支持媒体文件（视频 + 图片），供备份状态页展示。
     * 不做任何去重/账本过滤 —— 展示页需要完整列表来标识「已上传 / 未上传」。
     */
    fun scanMediaFiles(context: Context, monitorUri: String?): List<File> =
        resolveFiles(context, monitorUri)

    fun filterByScope(files: List<File>, scope: BackupScope): List<File> = when (scope) {
        BackupScope.ALL -> files
        BackupScope.VIDEO -> files.filter { it.extension.lowercase() in VIDEO_EXTS }
        BackupScope.IMAGE -> files.filter { it.extension.lowercase() in IMAGE_EXTS }
    }

    /**
     * 收集待备份文件（跳过已完成任务 + 账本中未变化的文件）。
     *
     * @param providerId   目标 provider id（任务幂等 id 用）
     * @param doneTaskIds  已完成任务 id 集合（跳过对应文件）
     * @param ledger       备份账本（可选）：size + mtime 未变的文件直接跳过，避免重复上传
     */
    suspend fun collect(
        context: Context,
        monitorUri: String?,
        scope: BackupScope,
        providerId: String,
        doneTaskIds: Set<String>,
        failedTaskIds: Set<String> = emptySet(),
        ledger: BackupLedgerRepository? = null,
    ): List<File> = withContext(Dispatchers.IO) {
        val raw = resolveFiles(context, monitorUri)
        filterByScope(raw, scope)
            .filter { file ->
                val taskId = BackupTask.computeId(providerId, file.name)
                if (taskId in doneTaskIds) return@filter false
                // 已 FAILED 的历史任务不自动重传（避免每个周期把失败大文件整盘重传），由用户手动重试
                if (taskId in failedTaskIds) return@filter false
                // 账本增量：文件相对账本未变化（size + mtime 一致）→ 跳过
                ledger?.isUnchanged(providerId, file.name, file) != true
            }
    }

    private fun resolveFiles(context: Context, monitorUri: String?): List<File> {
        val uri = monitorUri.orEmpty().trim()
        return when {
            uri.isBlank() || uri == DownloadMonitor.INTERNAL_MONITOR_URI ->
                walkTree(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir)
            uri.startsWith("file://") -> walkTree(File(uri.removePrefix("file://")))
            uri.startsWith("/") -> walkTree(File(uri))
            uri.startsWith("content://") -> walkSafToCache(context, uri)
            else -> emptyList()
        }
    }

    private fun walkTree(root: File?): List<File> {
        if (root == null || !root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && isBackupMedia(it.name) }
            .toList()
    }

    /** SAF 目录：把媒体文件复制到 cache 暂存区后返回本地 File。 */
    private fun walkSafToCache(context: Context, treeUri: String): List<File> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()
        val staging = File(context.cacheDir, "backup_staging").apply { mkdirs() }
        val result = mutableListOf<File>()
        tree.listFiles().forEach { child ->
            if (child.isDirectory) {
                result += walkSafDir(context, child, staging)
            } else if (child.isFile && isBackupMedia(child.name.orEmpty())) {
                copySafFile(context, child, staging)?.let { result += it }
            }
        }
        return result
    }

    private fun walkSafDir(context: Context, dir: DocumentFile, staging: File): List<File> {
        val result = mutableListOf<File>()
        dir.listFiles().forEach { child ->
            when {
                child.isDirectory -> result += walkSafDir(context, child, staging)
                child.isFile && isBackupMedia(child.name.orEmpty()) ->
                    copySafFile(context, child, staging)?.let { result += it }
            }
        }
        return result
    }

    private fun copySafFile(context: Context, child: DocumentFile, staging: File): File? {
        val name = child.name ?: return null
        val target = File(staging, name)
        if (!target.exists() || target.length() != child.length()) {
            runCatching {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
        return target.takeIf { it.isFile && it.length() > 0L }
    }
}

/**
 * 网盘备份后台任务（CoroutineWorker + 前台服务）。
 *
 * - 由 [BackupScheduler]（自动备份）触发；手动备份走 CloudBackupViewModel 内联队列（UI 实时进度）。
 * - `setForeground` 前台服务通知渠道 `backup_progress`（T01 已声明 FOREGROUND_SERVICE_DATA_SYNC 权限与
 *   SystemForegroundService 服务）。
 * - 使用 AppContainer 共享的 [com.ed.edqiu.backup.engine.BackupEngine] 单例，与前台 UI 互斥串行、状态互通。
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? EdqiuApplication ?: return Result.failure()
        val container = application.container
        val targetId = inputData.getString(BackupScheduler.KEY_TARGET_ID)
            ?: return Result.success()
        val target = container.backupProviderRegistry.get(targetId)
            ?: return Result.failure()

        createNotificationChannel()
        setForeground(createForegroundInfo("正在备份到 ${target.displayName}", null))

        val monitorUri = container.settingsRepository.monitorDirUriFlow.first()
        val scope = BackupSettings.scope(applicationContext, targetId)
        val tasks = container.backupTaskStore.load().filter { it.targetId == targetId }
        val doneTaskIds = tasks.filter { it.status == BackupTaskStatus.DONE }.map { it.taskId }.toSet()
        val failedTaskIds = tasks.filter { it.status == BackupTaskStatus.FAILED }.map { it.taskId }.toSet()
        val files = BackupFiles.collect(
            applicationContext, monitorUri, scope, targetId, doneTaskIds, failedTaskIds,
            container.backupLedgerRepository,
        )
        if (files.isNotEmpty()) {
            container.backupEngine.enqueue(targetId, files)
        }

        val summary = container.backupEngine.runQueue { task ->
            notifyProgress(target.displayName, task)
        }
        return if (summary.isSuccess) Result.success() else Result.retry()
    }

    // ---------------- 通知 ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "网盘备份进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示网盘直连备份的进行状态与结果"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createForegroundInfo(title: String, progress: Float?): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(title, progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private fun buildNotification(title: String, progress: Float?): Notification {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(progress?.let { "进度 ${(it * 100).toInt()}%" } ?: "正在备份，请稍候…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress != null) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        }
        return builder.build()
    }

    private fun notifyProgress(title: String, task: BackupTask) {
        val progress = task.progress
        val text = when (task.status) {
            BackupTaskStatus.PENDING -> "等待上传：${task.remotePath}"
            BackupTaskStatus.UPLOADING -> "上传中：${task.remotePath} ${(progress * 100).toInt()}%"
            BackupTaskStatus.DONE -> "已完成：${task.remotePath}"
            BackupTaskStatus.FAILED -> "失败：${task.remotePath}"
            BackupTaskStatus.CANCELLED -> "已取消：${task.remotePath}"
        }
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt(), false)
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    private companion object {
        const val CHANNEL_ID = "backup_progress"
        const val NOTIFICATION_ID = 1001
    }
}
