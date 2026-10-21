package com.ed.edqiu.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步任务队列控制（2026-10 详情页重设计新增）。
 *
 * 供「同步情况详情」页对待上传队列做**逐文件**控制：
 * - [pause]：暂停该文件——队列循环跳过它；PUT 传输中通过进度回调抛
 *   [com.ed.edqiu.backup.provider.WebDavSyncPausedSignal] 立即打断（.part 保留，恢复续传）；
 * - [resume]：解除暂停；
 * - [prioritize]：优先同步——该文件在待处理队列中插队到最前，并在 UI 列表置顶。
 *
 * 状态为进程级单例（object），与应用级运行器 WebDavManualSync 同生命周期；
 * 同步结束后控制集自动清空（暂停=仅对当轮队列生效，避免用户遗忘残留）。
 */
object SyncTaskControl {

    private val _paused = MutableStateFlow<Set<String>>(emptySet())
    val paused: StateFlow<Set<String>> = _paused.asStateFlow()

    private val _prioritized = MutableStateFlow<Set<String>>(emptySet())
    val prioritized: StateFlow<Set<String>> = _prioritized.asStateFlow()

    /** 同步轮运行令牌：防止手动同步与自动备份任务并发双传同一文件。 */
    private val runningFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 本轮已完成的远端文件名集合（实时）——详情页据此把对应条目立即翻成
     * 「已同步」并实时刷新计数，不必等整轮结束再 PROPFIND 重对账。
     */
    private val _completed = MutableStateFlow<Set<String>>(emptySet())
    val completed: StateFlow<Set<String>> = _completed.asStateFlow()

    /** 标记某远端文件本轮已成功落盘（上传完成或下载落位）。 */
    fun markCompleted(remotePath: String) {
        _completed.value = _completed.value + remotePath
    }

    /** 是否有同步轮在执行（自动备份 Worker 轮询据此跳过）。 */

    /** 尝试领取运行令牌：true = 领取成功（本轮由该调用方执行）；false = 已有同步轮在跑。 */
    fun tryBeginRun(): Boolean = runningFlag.compareAndSet(false, true)

    /** 结束同步轮：释放令牌并清空暂停/优先/完成集合（控制仅对当轮生效，防用户遗忘残留）。 */
    fun endRun() {
        _paused.value = emptySet()
        _prioritized.value = emptySet()
        _completed.value = emptySet()
        runningFlag.set(false)
    }

    /** 暂停某文件（remotePath，即远端平坦化文件名）。传输中的文件立即中断。 */
    fun pause(remotePath: String) {
        _paused.value = _paused.value + remotePath
    }

    /** 恢复某文件（解除暂停，下一轮同步/队列继续时正常处理）。 */
    fun resume(remotePath: String) {
        _paused.value = _paused.value - remotePath
    }

    /** 优先同步某文件（插队到待处理队列最前 + UI 置顶）。 */
    fun prioritize(remotePath: String) {
        _prioritized.value = _prioritized.value + remotePath
    }

    /** 取消优先（该文件已处理或用户手动取消置顶）。 */
    fun clearPriority(remotePath: String) {
        _prioritized.value = _prioritized.value - remotePath
    }

    /** 文件当前是否处于暂停。 */
    fun isPaused(remotePath: String): Boolean = _paused.value.contains(remotePath)
}
