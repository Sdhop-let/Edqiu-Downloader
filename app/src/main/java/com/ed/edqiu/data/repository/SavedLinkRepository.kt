package com.ed.edqiu.data.repository

import com.ed.edqiu.data.db.SavedLinkDao
import com.ed.edqiu.data.metadata.MetadataFetcher
import com.ed.edqiu.data.metadata.TweetMeta
import android.util.Log
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.ProxySettings
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.domain.TweetIdExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SavedLinkRepository(
    private val dao: SavedLinkDao,
    private val downloadMonitor: DownloadMonitor,
    private val metadataFetcher: MetadataFetcher,
    private val downloaderClient: DownloaderClient,
    private val linkHistoryRepository: LinkHistoryRepository,
    private val metadataScope: CoroutineScope? = null
) {

    fun observeAll(): Flow<List<SavedLink>> = dao.observeAll()

    fun countPending(): Flow<Int> = dao.countByStatus(LinkStatus.PENDING)

    /** 当前 PENDING 数量（一次性查询，自动预下载攒批判断用）。 */
    suspend fun pendingCountNow(): Int = dao.countByStatus(LinkStatus.PENDING).first()

    /** 当前 PENDING 条目的 tweetId 列表（自动预下载批量处理用）。 */
    suspend fun pendingTweetIds(): List<String> = dao.getTweetIdsByStatus(LinkStatus.PENDING)

    suspend fun getByTweetId(tweetId: String): SavedLink? = dao.getByTweetId(tweetId)

    sealed interface CaptureResult {
        /** 入库成功，携带 tweetId 供调用方继续拉取作者元数据。 */
        data class Added(val tweetId: String) : CaptureResult
        data object Duplicate : CaptureResult
        data object Invalid : CaptureResult
    }

    sealed interface DownloadRequestResult {
        data object Launched : DownloadRequestResult
        data object Downloaded : DownloadRequestResult
        data object AlreadyDownloaded : DownloadRequestResult
        data object Missing : DownloadRequestResult
        /** 推文已不存在（被删/私密/未公开）——已标记 DELETED，永久失败。 */
        data object Gone : DownloadRequestResult
        data class Failed(
            val reason: String,
            val nextRetryAt: Long?
        ) : DownloadRequestResult
    }

    data class BatchDownloadResult(
        val launched: Int,
        val failed: Int,
        val skipped: Int
    )

    private val downloadRequestMutex = Mutex()

    /**
     * 捕获成功回调（自动预下载挂载点）。
     * 由 AppContainer 在装配 [PreDownloadManager] 后挂载，避免仓库反向依赖预下载模块。
     */
    var onCaptured: ((String) -> Unit)? = null

    /**
     * 下载请求登记回调（2026-09-15 P0-4 持久队列挂载点）：
     * 每次 [requestDownload] 执行时把任务登记进 WorkManager 持久队列，
     * 进程被杀后由 Worker 自动续跑。由 AppContainer 挂载到 DownloadQueue.enqueue。
     */
    var onDownloadRequested: ((String) -> Unit)? = null

    suspend fun capture(text: String): CaptureResult {
        val canonicalUrl = TweetIdExtractor.canonicalUrlFromText(text)
            ?: return CaptureResult.Invalid
        val tweetId = TweetIdExtractor.fromUrl(canonicalUrl)
            ?: return CaptureResult.Invalid

        val insertedRowId = dao.insert(
            SavedLink(
                tweetId = tweetId,
                rawUrl = canonicalUrl,
                savedAt = System.currentTimeMillis()
            )
        )
        if (insertedRowId == -1L) return CaptureResult.Duplicate

        // 元数据抓取不阻塞捕获返回：后台异步补全（修复分享保存时前台卡顿）
        val scope = metadataScope
        if (scope != null) {
            scope.launch { fetchAndApplyMetadata(tweetId) }
        } else {
            fetchAndApplyMetadata(tweetId)
        }
        onCaptured?.invoke(tweetId)
        return CaptureResult.Added(tweetId)
    }

    /**
     * 拉取并落库单条推文的作者/文案/封面元数据。
     * @return 是否成功获取到元数据（供捕获结果页的「重试」判断）
     */
    suspend fun fetchMetadata(tweetId: String): Boolean =
        dao.getByTweetId(tweetId)?.let { fetchAndApplyMetadata(tweetId) } ?: false

    suspend fun requestDownload(
        tweetId: String,
        manual: Boolean = false,
        proxy: ProxySettings? = null,
        /** Worker 续跑路径传 true：不再触发登记钩子（避免自我递归）。 */
        viaQueue: Boolean = false
    ): DownloadRequestResult = downloadRequestMutex.withLock {
        var link = dao.getByTweetId(tweetId) ?: return DownloadRequestResult.Missing
        if (link.status == LinkStatus.DOWNLOADED) {
            return DownloadRequestResult.AlreadyDownloaded
        }

        if (manual && link.attemptCount >= DownloadRetryPolicy.MAX_ATTEMPTS) {
            dao.resetFailures(listOf(tweetId))
            link = dao.getByTweetId(tweetId) ?: return DownloadRequestResult.Missing
        }

        // P0-4：登记进 WorkManager 持久队列（幂等 KEEP）——执行中途进程被杀也能续跑
        if (!viaQueue) onDownloadRequested?.invoke(tweetId)

        val now = System.currentTimeMillis()
        val attemptNumber = link.attemptCount + 1
        return when (val result = downloaderClient.launch(link.rawUrl, proxy)) {
            DownloaderClient.LaunchResult.LaunchedExternal -> {
                dao.markDownloadDispatched(tweetId, now)
                DownloadRequestResult.Launched
            }
            is DownloaderClient.LaunchResult.Downloaded -> {
                val first = result.files.first()
                dao.applyScanResult(
                    tweetId = tweetId,
                    status = LinkStatus.DOWNLOADED,
                    filePath = first.filePath,
                    downloadedAt = first.downloadedAt,
                    publishedAt = first.publishedAt
                )
                dao.applyMeta(
                    tweetId = tweetId,
                    authorId = first.authorId ?: link.authorId,
                    authorName = first.authorName ?: link.authorName,
                    caption = first.caption ?: link.caption,
                    thumbnailUrl = first.thumbnailUrl ?: link.thumbnailUrl,
                    avatarUrl = link.avatarUrl,
                    authorBio = link.authorBio
                )
                DownloadRequestResult.Downloaded
            }
            is DownloaderClient.LaunchResult.Failed -> {
                val nextRetryAt = DownloadRetryPolicy.nextRetryAt(attemptNumber, now)
                dao.markDownloadFailed(
                    tweetId = tweetId,
                    attemptCount = attemptNumber,
                    attemptedAt = now,
                    error = result.reason,
                    nextRetryAt = nextRetryAt
                )
                DownloadRequestResult.Failed(result.reason, nextRetryAt)
            }
            DownloaderClient.LaunchResult.TweetGone -> {
                dao.markTweetGone(tweetId, now)
                DownloadRequestResult.Gone
            }
        }
    }

    suspend fun requestDownloads(tweetIds: Collection<String>, proxy: ProxySettings? = null): BatchDownloadResult {
        var launched = 0
        var failed = 0
        var skipped = 0

        tweetIds.distinct().forEach { tweetId ->
            when (requestDownload(tweetId, manual = true, proxy = proxy)) {
                DownloadRequestResult.Launched,
                DownloadRequestResult.Downloaded -> launched++
                is DownloadRequestResult.Failed -> failed++
                DownloadRequestResult.AlreadyDownloaded,
                DownloadRequestResult.Missing,
                DownloadRequestResult.Gone -> skipped++
            }
        }
        return BatchDownloadResult(launched, failed, skipped)
    }

    suspend fun retryDueDownloads(now: Long = System.currentTimeMillis()): Int {
        val failures = dao.getRetryableFailures(now, DownloadRetryPolicy.MAX_ATTEMPTS)
        var launched = 0
        failures.forEach { link ->
            val result = requestDownload(link.tweetId)
            if (result == DownloadRequestResult.Launched || result == DownloadRequestResult.Downloaded) launched++
        }
        return launched
    }

    suspend fun refreshStatuses(monitorUri: String?) {
        val scanned = when (val result = downloadMonitor.scan(monitorUri)) {
            is DownloadMonitor.ScanResult.Success -> result.items
            is DownloadMonitor.ScanResult.Failure -> return
        }
        val all = dao.observeAll().first()

        all.forEach { link ->
            val item = scanned[link.tweetId]
            if (item != null) {
                dao.applyScanResult(
                    tweetId = link.tweetId,
                    status = LinkStatus.DOWNLOADED,
                    filePath = item.filePath,
                    downloadedAt = item.foundAt,
                    publishedAt = item.publishedAt
                )
                if (link.authorId == null || link.caption == null || link.avatarUrl == null) {
                    dao.applyMeta(
                        tweetId = link.tweetId,
                        authorId = item.authorId ?: link.authorId,
                        authorName = item.authorName ?: link.authorName,
                        caption = item.caption ?: link.caption,
                        thumbnailUrl = item.thumbnailUrl ?: link.thumbnailUrl,
                        avatarUrl = link.avatarUrl,
                        authorBio = link.authorBio
                    )
                }
            } else if (link.status == LinkStatus.DOWNLOADED) {
                dao.applyScanResult(
                    tweetId = link.tweetId,
                    status = LinkStatus.PENDING,
                    filePath = null,
                    downloadedAt = null
                )
            }
        }
    }

    /**
     * 扫描用户自定义或内置监控目录，把磁盘上已有、但收件箱尚未收录的已下载媒体
     * 以 DOWNLOADED 状态导入收件箱。
     * 仅导入文件名或 sidecar .meta.json 中能识别出 tweet ID 的文件，
     * 无法归属到推文的文件仍由媒体库/下载历史（DirectoryScanner）管理。
     *
     * @return 新导入的收件箱条目数
     */
    suspend fun importScannedDownloads(monitorUri: String?): Int {
        val scanned = downloadMonitor.scanMonitorAndDownloadDirs(monitorUri)
        var imported = 0
        scanned.values.forEach { item ->
            if (dao.exists(item.tweetId)) return@forEach
            val insertedRowId = dao.insert(
                SavedLink(
                    tweetId = item.tweetId,
                    rawUrl = "https://x.com/i/status/${item.tweetId}",
                    authorId = item.authorId,
                    authorName = item.authorName,
                    caption = item.caption,
                    thumbnailUrl = item.thumbnailUrl,
                    savedAt = item.foundAt,
                    status = LinkStatus.DOWNLOADED,
                    filePath = item.filePath,
                    downloadedAt = item.foundAt,
                    publishedAt = item.publishedAt
                )
            )
            if (insertedRowId != -1L) imported++
        }
        if (imported > 0) {
            Log.i("SavedLinkRepository", "Imported $imported downloaded media into inbox")
        }
        return imported
    }

    suspend fun retryMissingMetadata(limit: Int = 5) {
        dao.getMissingMetadata(limit).forEach { link ->
            fetchAndApplyMetadata(link.tweetId)
        }
    }

    /**
     * 清理**已修复 bug 期间**残留的错误数据：旧版本下载器曾用
     * `startActivity(自身 MainActivity)` 触发下载，因 applicationId 与 namespace
     * 不一致而抛 `Unable to find explicit activity class {com.ed.edqiu/com.ed.edqiu.MainActivity}`，
     * 该错误被写入 `last_error` 并持续显示在详情页。该 bug 已修复（下载器不再自启 Activity），
     * 启动时统一把匹配模式的记录重置回 PENDING。
     *
     * @return 被清理的条目数
     */
    suspend fun clearKnownFixedErrors(): Int =
        dao.resetFailuresByErrorPattern(KNOWN_FIXED_ERROR_PATTERN)

    suspend fun delete(tweetId: String): LinkHistoryRepository.ArchiveResult =
        linkHistoryRepository.archiveAndDelete(listOf(tweetId), reason = "single_delete")

    suspend fun deleteMany(tweetIds: Collection<String>): LinkHistoryRepository.ArchiveResult =
        linkHistoryRepository.archiveAndDelete(tweetIds, reason = "batch_delete")

    /**
     * 从回收站恢复记录回收件箱。
     * 用于撤销删除操作。
     */
    suspend fun undoDelete(archiveIds: Collection<String>): LinkHistoryRepository.RestoreResult =
        linkHistoryRepository.restore(archiveIds)

/** 拉取并落库元数据；@return 是否成功获取（fxtwitter 解析成功即视为成功）。 */
    private suspend fun fetchAndApplyMetadata(tweetId: String): Boolean {
        val link = dao.getByTweetId(tweetId) ?: return false
        val meta = runCatching { metadataFetcher.fetchFromTwitter(tweetId) }.getOrNull()
            ?: return false
        dao.applyMeta(
            tweetId = tweetId,
            authorId = meta.authorId ?: link.authorId,
            authorName = meta.authorName ?: link.authorName,
            caption = meta.caption ?: link.caption,
            thumbnailUrl = meta.thumbnailUrl ?: link.thumbnailUrl,
            avatarUrl = meta.avatarUrl ?: link.avatarUrl,
            authorBio = meta.authorBio ?: link.authorBio
        )
        return true
    }

    private companion object {
        /**
         * 旧版本 launchExternal 触发 ActivityNotFoundException 的特征串（精确匹配
         * `applicationId ≠ namespace` 时启动自己包名 MainActivity 的失败信息）。
         * 启动时把匹配这些 last_error 的记录重置回 PENDING，让用户重新下载。
         */
        const val KNOWN_FIXED_ERROR_PATTERN =
            "%Unable to find explicit activity class%com.ed.edqiu/com.ed.edqiu.MainActivity%"
    }
}
