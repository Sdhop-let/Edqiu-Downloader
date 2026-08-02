package com.ed.edqiu.data.repository

import com.ed.edqiu.data.db.SavedLinkDao
import com.ed.edqiu.data.metadata.MetadataFetcher
import com.ed.edqiu.data.metadata.TweetMeta
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import com.ed.edqiu.domain.TweetIdExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SavedLinkRepository(
    private val dao: SavedLinkDao,
    private val downloadMonitor: DownloadMonitor,
    private val metadataFetcher: MetadataFetcher,
    private val downloaderClient: DownloaderClient,
    private val linkHistoryRepository: LinkHistoryRepository
) {

    fun observeAll(): Flow<List<SavedLink>> = dao.observeAll()

    fun countPending(): Flow<Int> = dao.countByStatus(LinkStatus.PENDING)

    suspend fun getByTweetId(tweetId: String): SavedLink? = dao.getByTweetId(tweetId)

    sealed interface CaptureResult {
        data object Added : CaptureResult
        data object Duplicate : CaptureResult
        data object Invalid : CaptureResult
    }

    sealed interface DownloadRequestResult {
        data object Launched : DownloadRequestResult
        data object Downloaded : DownloadRequestResult
        data object AlreadyDownloaded : DownloadRequestResult
        data object Missing : DownloadRequestResult
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

        fetchAndApplyMetadata(tweetId)
        return CaptureResult.Added
    }

    suspend fun requestDownload(
        tweetId: String,
        manual: Boolean = false
    ): DownloadRequestResult = downloadRequestMutex.withLock {
        var link = dao.getByTweetId(tweetId) ?: return DownloadRequestResult.Missing
        if (link.status == LinkStatus.DOWNLOADED) {
            return DownloadRequestResult.AlreadyDownloaded
        }

        if (manual && link.attemptCount >= DownloadRetryPolicy.MAX_ATTEMPTS) {
            dao.resetFailures(listOf(tweetId))
            link = dao.getByTweetId(tweetId) ?: return DownloadRequestResult.Missing
        }

        val now = System.currentTimeMillis()
        val attemptNumber = link.attemptCount + 1
        return when (val result = downloaderClient.launch(link.rawUrl)) {
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
                    downloadedAt = first.downloadedAt
                )
                dao.applyMeta(
                    tweetId = tweetId,
                    authorId = first.authorId ?: link.authorId,
                    authorName = first.authorName ?: link.authorName,
                    caption = first.caption ?: link.caption,
                    thumbnailUrl = first.thumbnailUrl ?: link.thumbnailUrl,
                    avatarUrl = link.avatarUrl
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
        }
    }

    suspend fun requestDownloads(tweetIds: Collection<String>): BatchDownloadResult {
        var launched = 0
        var failed = 0
        var skipped = 0

        tweetIds.distinct().forEach { tweetId ->
            when (requestDownload(tweetId, manual = true)) {
                DownloadRequestResult.Launched,
                DownloadRequestResult.Downloaded -> launched++
                is DownloadRequestResult.Failed -> failed++
                DownloadRequestResult.AlreadyDownloaded,
                DownloadRequestResult.Missing -> skipped++
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
                    downloadedAt = item.foundAt
                )
                if (link.authorId == null || link.caption == null || link.avatarUrl == null) {
                    dao.applyMeta(
                        tweetId = link.tweetId,
                        authorId = item.authorId ?: link.authorId,
                        authorName = item.authorName ?: link.authorName,
                        caption = item.caption ?: link.caption,
                        thumbnailUrl = item.thumbnailUrl ?: link.thumbnailUrl,
                        avatarUrl = link.avatarUrl
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

    suspend fun retryMissingMetadata(limit: Int = 5) {
        dao.getMissingMetadata(limit).forEach { link ->
            fetchAndApplyMetadata(link.tweetId)
        }
    }

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

    private suspend fun fetchAndApplyMetadata(tweetId: String) {
        runCatching {
            val meta: TweetMeta = metadataFetcher.fetchFromTwitter(tweetId) ?: return
            dao.applyMeta(
                tweetId = tweetId,
                authorId = meta.authorId,
                authorName = meta.authorName,
                caption = meta.caption,
                thumbnailUrl = meta.thumbnailUrl,
                avatarUrl = meta.avatarUrl
            )
        }
    }
}
