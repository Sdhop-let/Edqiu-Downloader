package com.ed.edqiu.service

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.data.preferences.CloudSyncPreferences
import com.ed.edqiu.backup.model.BackupException
import com.ed.edqiu.backup.provider.WebDavCredential
import com.ed.edqiu.backup.provider.WebDavEngine
import com.ed.edqiu.backup.provider.WebDavSyncPausedSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WebDAV 同步方向（进度展示用）。
 */
enum class WebDavSyncPhase {
    /** 上传阶段：本地 → 云端。 */
    UPLOAD,

    /** 下载阶段：云端 → 本地（仅下载本地不存在的文件）。 */
    DOWNLOAD,
}

/**
 * 「立即同步」单文件进度快照（2026-10 统一 WebDAV 通道，双向扩展）。
 *
 * @property phase 当前所处方向（上传/下载）
 * @property totalFiles    本阶段待处理文件总数
 * @property currentIndex  当前文件序号（1-based；0 = 尚未开始逐文件处理）
 * @property currentFileName 当前正在处理的文件名（仅文件名，不含路径）
 * @property fileProgress  当前文件传输进度 0..1
 */
data class WebDavSyncProgress(
    val phase: WebDavSyncPhase = WebDavSyncPhase.UPLOAD,
    val totalFiles: Int,
    val currentIndex: Int,
    val currentFileName: String = "",
    val fileProgress: Float = 0f,
)

/**
 * 一轮双向同步的结果账面。
 *
 * @property uploaded 上传成功数（含冲突改名后重传的）
 * @property skippedExisting 两端一致而跳过的文件数
 * @property downloaded 从云端下载到本地的文件数
 * @property conflicts 冲突数（两端同名但内容不同；本地始终保留不覆盖，云端旧版本
 *                     尽力改名 `xxx.conflict-<时间戳>.ext` 保留，MOVE 不可用时跳过该文件）
 * @property pausedCount 用户暂停跳过的文件数（队列级跳过 + 传输中中断）
 * @property bytesTransferred 本轮实际传输字节数（上传成功 + 下载成功，2026-10 备份中心历史用）
 */
data class WebDavSyncResult(
    val uploaded: Int = 0,
    val skippedExisting: Int = 0,
    val downloaded: Int = 0,
    val conflicts: Int = 0,
    val pausedCount: Int = 0,
    val bytesTransferred: Long = 0L,
) {
    /** 是否有任何实际数据变动（驱动提示文案与计数器更新）。 */
    val hasChanges: Boolean get() = uploaded > 0 || downloaded > 0
}

/**
 * WebDAV 双向同步服务（薄门面，2026-10 由单向备份升级）。
 *
 * 核心铁律：**本地文件永不被覆盖**。
 *
 * 上传阶段（本地 → 云端）：
 * - 先 PROPFIND 列远端目录一次，得到权威远端清单（存在性 + 尺寸），逐文件对账：
 *   - 远端缺失 → [WebDavEngine.putResumable] 断点续传上传；
 *   - 两端尺寸一致 → 跳过；
 *   - 尺寸冲突 → 先把云端旧版本 MOVE 改名为 `xxx.conflict-<时间戳>.ext` 保留
 *     （Overwrite:F，412/不支持 MOVE 则放弃该文件），再上传本地版——云端数据不静默丢失。
 * - 2026-10 第四批修复：旧实现逐文件 HEAD 三元判定，部分服务器对「不存在」返回非 404
 *   （403/409/500 等）时抛「无法确认远程文件状态」中断整轮同步；且子目录文件远端名
 *   未做平坦化，与对账页「目录_文件名」派生不一致，导致永远显示待上传。
 *   现改为单次 PROPFIND 清单对账 + 全链路统一 [remoteNameFor] 平坦化命名。
 *
 * 下载阶段（云端 → 本地）：
 * - PROPFIND 列远端目录（跳过 `.edqiu-part` 暂存与目录条目）；
 * - 本地不存在（按平坦化名比对）的文件 → GET（Range 断点续传）先写本地 `.edqiu-part`，
 *   完成后 rename 成品；
 * - 本地已存在的文件 → **一律跳过，绝不覆盖**（即使尺寸不同；差异留给上传阶段对账云端）。
 *
 * 传输能力：上传走 putResumable（.part 暂存 + PATCH 追加续传 + MOVE 原子落盘），
 * 下载走 GET Range 续传——>2GB 视频断电/断网后重试只补剩余字节。
 *
 * 「同步情况详情」页的文件级队列同步走 [WebDavSyncQueue]（可暂停/优先上传）；
 * 本服务保留给自动备份（WebDavAutoBackupWorker）、预下载联动与设置页手动同步，
 * 队列运行期间会被跳过以防双重传输。
 */
object WebDavSyncService {

    private const val TAG = "WebDavSyncService"

    suspend fun syncDownloads(
        context: Context,
        prefs: CloudSyncPreferences,
        onProgress: (WebDavSyncProgress) -> Unit = {},
    ): Result<WebDavSyncResult> {
        // 同步轮互斥（2026-10）：自动备份/设置页手动同步/预下载联动共用一个运行令牌，
        // 已有同步轮在跑时直接跳过，避免双传同一文件；暂停/优先控制集随轮结束一并清空
        if (!SyncTaskControl.tryBeginRun()) {
            Log.i(TAG, "已有同步轮在执行，跳过本轮 syncDownloads")
            return Result.success(WebDavSyncResult())
        }
        // 运行标记（2026-10 备份中心「冻结自愈恢复」判定）：进程被杀/冻结时残留 true，
        // 下一轮开始前读到此值 = 上一轮未正常收尾
        prefs.syncInProgress = true
        try {
            return withContext(Dispatchers.IO) {
                runCatching {
                    // 2026-10 每网盘独立配置：凭证取自当前选中预设（cfg_{pid}_*），切换网盘互不串号
                    val account = prefs.currentAccount()
                    if (!account.isComplete) {
                        throw BackupException("请先填写 WebDAV 地址、账号和密码，并测试连接通过")
                    }
                    val engine = WebDavEngine(
                        allowInsecureTls = { prefs.allowInsecureTls }
                    )
                    val credential = WebDavCredential(
                        serverUrl = account.serverUrl,
                        username = account.username,
                        password = account.password,
                        remotePath = account.remotePath.ifBlank { "Edqiu" },
                    )

                    engine.ensureRemoteDirectories(credential).getOrElse { throw it }

                    val sourceDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: return@runCatching WebDavSyncResult()
                    if (!sourceDir.exists() || !sourceDir.isDirectory) return@runCatching WebDavSyncResult()

                    val up = uploadPhase(engine, credential, sourceDir, prefs.syncSmallFirst, onProgress)
                    val down = downloadPhase(engine, credential, sourceDir, onProgress)
                    WebDavSyncResult(
                        uploaded = up.uploaded,
                        skippedExisting = up.skipped,
                        conflicts = up.conflicts,
                        downloaded = down.downloaded,
                        pausedCount = up.pausedCount,
                        bytesTransferred = up.bytes + down.bytes,
                    )
                }.onFailure { error ->
                    Log.e(TAG, "WebDAV sync failed", error)
                }
            }
        } finally {
            prefs.syncInProgress = false
            SyncTaskControl.endRun()
        }
    }

    /**
     * 平坦化远端名（全链路唯一派生口径：上传 / 下载落位 / 对账）。
     * 子目录文件 = 「目录前缀_文件名」（`a/b/c.mp4` → `a_b_c.mp4`），根目录文件保持原名。
     */
    fun remoteNameFor(root: File, file: File): String {
        val rel = file.parentFile?.relativeToOrNull(root)?.invariantSeparatorsPath
        return if (rel.isNullOrBlank() || rel == ".") file.name
        else rel.replace('/', '_') + "_" + file.name
    }

    /** 扫描本地下载目录的全部受支持媒体文件，返回 (文件, 平坦化远端名)。 */
    fun scanLocalMediaFiles(context: Context): List<Pair<File, String>> {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        return scanMediaUnder(root)
    }

    internal fun scanMediaUnder(root: File): List<Pair<File, String>> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { MediaFileTypes.isSupportedMediaFile(it) }
            .map { it to remoteNameFor(root, it) }
            .toList()
    }

    /** 远端根目录 URL（PROPFIND 规范要求带尾斜杠：部分服务器对无斜杠目录返回 301）。 */
    internal fun rootUrlOf(engine: WebDavEngine, credential: WebDavCredential): String =
        engine.buildRemoteUrl(credential, "").let { if (it.endsWith("/")) it else "$it/" }

    /**
     * 上传阶段：本地 → 云端。返回 [UploadOutcome]（账面 + 传输字节）。
     *
     * 2026-10 详情页重设计：顺序遍历升级为**可控队列**——
     * - 优先同步：[SyncTaskControl.prioritized] 中的文件每轮插队到最前；
     * - 逐文件暂停：队列级跳过 + PUT 传输中经进度回调抛 [WebDavSyncPausedSignal] 立即中断
     *   （.part 保留，恢复后断点续传）；
     * - 可选「小文件优先」排序：小文件先清队列，大文件留尾部慢慢磨（感知更快）；
     * - 同步轮结束清空控制状态（暂停/优先仅对当轮生效，防用户遗忘残留）。
     */
    private suspend fun uploadPhase(
        engine: WebDavEngine,
        credential: WebDavCredential,
        sourceDir: File,
        smallFirst: Boolean,
        onProgress: (WebDavSyncProgress) -> Unit,
    ): UploadOutcome {
        val localFiles = if (smallFirst) {
            scanMediaUnder(sourceDir).sortedBy { it.first.length() }
        } else {
            scanMediaUnder(sourceDir)
        }
        val total = localFiles.size
        var uploaded = 0
        var skipped = 0
        var conflicts = 0
        var pausedCount = 0
        var bytes = 0L
        // 单次 PROPFIND 得到权威远端清单（存在性 + 尺寸），替代逐文件 HEAD——
        // 部分服务器对不存在的文件 HEAD 返回非 404（403/409/500），旧实现会误报
        // 「无法确认远程文件状态」并中断整轮同步
        val remoteManifest = engine.listDirectory(rootUrlOf(engine, credential), credential)
            .getOrElse { throw it }
            .filter { !it.isDir }
            .associateBy { it.name }

        val queue = ArrayDeque(localFiles)
        var processed = 0
        while (queue.isNotEmpty()) {
            // 优先文件前置（每轮检查：详情页新置顶的文件立刻插队）
            val prioritized = SyncTaskControl.prioritized.value
            if (prioritized.isNotEmpty()) {
                val idx = queue.indexOfFirst { (_, remoteName) -> remoteName in prioritized }
                if (idx > 0) queue.addFirst(queue.removeAt(idx))
            }
            val (file, remoteName) = queue.removeFirst()
            processed++
            val size = file.length()

            // 队列级暂停：直接跳过（不计失败）
            if (remoteName in SyncTaskControl.paused.value) {
                Log.i(TAG, "队列跳过（已暂停）：$remoteName")
                pausedCount++
                continue
            }

            onProgress(WebDavSyncProgress(WebDavSyncPhase.UPLOAD, total, processed, remoteName, 0f))
            val remoteUrl = engine.buildRemoteUrl(credential, remoteName)
            val remote = lookupRemote(remoteManifest, remoteName, file.name)
            when {
                // 双名兼容（2026-10 修复）：早期版本上传用纯文件名、现行版本用平坦化名——
                // 两个名字任一命中且尺寸一致都判「已同步」，杜绝网盘已有却显示未同步
                remote != null && remote.size == size -> skipped++
                remote == null -> {
                    if (uploadLocalFile(engine, credential, remoteUrl, file, remoteName, total, processed, onProgress)) {
                        uploaded++
                        bytes += size
                    } else pausedCount++
                }
                else -> {
                    // 冲突：本地版为用户设备真源；云端旧版本尽力改名保留，保不住则放弃上传
                    val preserved = preserveRemoteCopy(engine, credential, remoteUrl, remoteName)
                    if (preserved) {
                        if (uploadLocalFile(engine, credential, remoteUrl, file, remoteName, total, processed, onProgress)) {
                            uploaded++
                            bytes += size
                        } else pausedCount++
                    } else {
                        Log.w(TAG, "冲突且云端版本无法改名保留，跳过上传（本地保持不变）：$remoteName")
                    }
                    conflicts++
                }
            }
        }
        return UploadOutcome(uploaded, skipped, conflicts, pausedCount, bytes)
    }

    /** 上传阶段账面 + 传输字节（2026-10 备份中心历史记录用）。 */
    private data class UploadOutcome(
        val uploaded: Int,
        val skipped: Int,
        val conflicts: Int,
        val pausedCount: Int,
        val bytes: Long,
    )

    /**
     * 双名查找：先按现行平坦化名，miss 再按早期版本的纯文件名。
     * 覆盖历史存量：旧版上传的文件在网盘上是纯名，新对账口径不能把它们判成「未同步」。
     */
    private fun lookupRemote(
        remoteManifest: Map<String, com.ed.edqiu.backup.provider.WebDavRemoteFile>,
        remoteName: String,
        plainName: String,
    ): com.ed.edqiu.backup.provider.WebDavRemoteFile? =
        remoteManifest[remoteName] ?: remoteManifest[plainName]

    /**
     * 上传单个文件。返回 true=成功；false=用户暂停中断（.part 保留待续传）。
     * 传输中每 tick 检查暂停标志——命中即抛 [WebDavSyncPausedSignal] 打断 PUT。
     * 成功后写 [SyncTaskControl.markCompleted]：详情页实时把条目翻成「已同步」。
     */
    private suspend fun uploadLocalFile(
        engine: WebDavEngine,
        credential: WebDavCredential,
        remoteUrl: String,
        file: File,
        remoteName: String,
        total: Int,
        index: Int,
        onProgress: (WebDavSyncProgress) -> Unit,
    ): Boolean {
        val size = file.length()
        engine.putResumable(remoteUrl, file, credential) { bytes ->
            if (remoteName in SyncTaskControl.paused.value) {
                throw WebDavSyncPausedSignal()
            }
            val p = if (size <= 0L) 0f else (bytes.toFloat() / size).coerceIn(0f, 1f)
            onProgress(WebDavSyncProgress(WebDavSyncPhase.UPLOAD, total, index, remoteName, p))
        }.getOrElse { error ->
            if (WebDavSyncPausedSignal.inChain(error)) {
                Log.i(TAG, "上传被用户暂停（.part 保留待续传）：$remoteName")
                return false
            }
            throw error
        }
        SyncTaskControl.clearPriority(remoteName) // 已处理，取消置顶
        SyncTaskControl.markCompleted(remoteName) // 实时账面：详情页立即翻绿
        return true
    }

    /**
     * 冲突时保留云端旧版本：MOVE 改名为 `xxx.conflict-<时间戳>.<ext>`。
     * @return true = 已改名成功（可安全上传本地版）；false = 无法保留（放弃上传）
     */
    suspend fun preserveRemoteCopy(
        engine: WebDavEngine,
        credential: WebDavCredential,
        remoteUrl: String,
        fileName: String,
    ): Boolean {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val conflictName = "$base.conflict-$stamp$ext"
        val conflictUrl = engine.buildRemoteUrl(credential, conflictName)
        return engine.move(remoteUrl, conflictUrl, credential, overwrite = false)
            .onSuccess { Log.i(TAG, "云端旧版本已保留为 $conflictName") }
            .isSuccess
    }

    /** 下载阶段：云端 → 本地（仅本地不存在的文件）。返回 [DownloadOutcome]（下载数 + 传输字节）。 */
    private suspend fun downloadPhase(
        engine: WebDavEngine,
        credential: WebDavCredential,
        sourceDir: File,
        onProgress: (WebDavSyncProgress) -> Unit,
    ): DownloadOutcome {
        val remoteFiles = engine.listDirectory(rootUrlOf(engine, credential), credential)
            .getOrElse { throw it }
            .filter { !it.isDir }
        // 本地已有性按平坦化名 + 纯文件名双口径比对：远端旧版上传的纯名文件（`b.mp4` 对应
        // 本地 `a/b.mp4`）也不重复下载
        val localFlatNames = scanMediaUnder(sourceDir)
            .flatMap { (file, flatName) -> listOf(flatName, file.name) }
            .toSet()
        val pending = remoteFiles.filter { it.name !in localFlatNames }
        val total = pending.size
        var downloaded = 0
        var bytes = 0L
        pending.forEachIndexed { index, remote ->
            onProgress(WebDavSyncProgress(WebDavSyncPhase.DOWNLOAD, total, index + 1, remote.name, 0f))
            val local = File(sourceDir, remote.name)
            if (local.exists()) {
                // 铁律：本地已存在一律跳过（即使尺寸不同也绝不覆盖本地；差异留给上传阶段对账云端）
                return@forEachIndexed
            }
            val remoteUrl = engine.buildRemoteUrl(credential, remote.name)
            val partLocal = File(sourceDir, remote.name + WebDavEngine.PART_SUFFIX)
            // 本地断点续传：已有 .part 从其尺寸 Range 续传（engine.get 内部处理）；无则从 0
            val expectedTotal = if (remote.size > 0) remote.size else -1L
            engine.get(remoteUrl, credential, partLocal) { bytes ->
                val p = if (expectedTotal <= 0) 0f else (bytes.toFloat() / expectedTotal).coerceIn(0f, 1f)
                onProgress(WebDavSyncProgress(WebDavSyncPhase.DOWNLOAD, total, index + 1, remote.name, p))
            }.getOrElse { error ->
                // 断电续传的关键：中断时保留本地 .part 现场，下次同步从断点继续
                Log.w(TAG, "下载中断（保留 .part 待续传）：${remote.name}，原因：${error.message}")
                throw error
            }
            // 成品落位：rename 失败（目标被并发创建等）→ 保留 .part 不覆盖
            if (partLocal.renameTo(local)) {
                downloaded++
                bytes += remote.size.coerceAtLeast(0L)
                SyncTaskControl.markCompleted(remote.name) // 实时账面：详情页立即翻绿
                Log.i(TAG, "下载完成：${remote.name}（${partLocal.length()}B）")
            } else {
                Log.w(TAG, "本地落位失败（目标已存在？），保留 .part：${remote.name}")
            }
        }
        return DownloadOutcome(downloaded, bytes)
    }

    /** 下载阶段账面 + 传输字节（2026-10 备份中心历史记录用）。 */
    private data class DownloadOutcome(
        val downloaded: Int,
        val bytes: Long,
    )
}
