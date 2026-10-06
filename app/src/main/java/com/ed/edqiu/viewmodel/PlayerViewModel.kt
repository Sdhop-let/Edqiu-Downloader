package com.ed.edqiu.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.domain.FlagshipDetector
import com.ed.edqiu.data.repository.HistoryRepository
import com.ed.edqiu.ui.player.VideoFirstFrameCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import java.io.File

data class PlayerUiState(
    val currentVideo: DownloadHistoryEntity? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val isFullscreen: Boolean = false,
    val playbackSpeed: Float = 1f,
    val isMuted: Boolean = false,
    /** 当前媒体已就绪（STATE_READY）：2026-09-15 垂直翻页时封面淡出时机依据。 */
    val isReady: Boolean = false,
    /**
     * 当前媒体首帧已渲染到画面（onRenderedFirstFrame，2026-09-30 v1.6.9）。
     * 封面揭示以它为准：STATE_READY 只代表解码器就绪，第一帧可能还没上屏——
     * 旧逻辑在 READY 即揭示封面，滑动切条时会闪一帧黑屏（用户反馈「先闪一下屏幕」）。
     */
    val hasFirstFrame: Boolean = false
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val historyRepository = HistoryRepository(application)

    val videoList: StateFlow<List<DownloadHistoryEntity>> =
        historyRepository.allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 播放会话列表（2026-09-30 v1.6.9）：openPlayer 进入时按入口素材类型一次性快照——
    // 视频会话只含视频、图片会话只含图片（优化1：滑动不穿插异类素材）。
    // 会话内列表冻结：后台补拉发布时间/画质升级导致的库重排不再「在手指下换页」，
    // ExoPlayer 播放列表与 pager 页序全程一致，上滑预载与翻页才可能无缝衔接。
    private val _playerPlaylist = MutableStateFlow<List<DownloadHistoryEntity>>(emptyList())
    val playerPlaylist: StateFlow<List<DownloadHistoryEntity>> = _playerPlaylist.asStateFlow()

    // 监听器与预载配置提取为可复用构建块：releaseIfIdle 释放实例后再次进入播放页时
    // obtainPlayer() 据此重建，避免任何公开入口撞上已 release 的 ExoPlayer（use-after-release 崩溃）
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playerState.update { it.copy(isPlaying = isPlaying) }
        }
        override fun onPlaybackStateChanged(state: Int) {
            // 就绪状态外露：垂直翻页落定后封面在媒体真正可播时才淡出，避免黑屏闪烁
            _playerState.update { it.copy(isReady = state == Player.STATE_READY) }
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                syncPlaybackPosition()
            }
        }
        // 2026-09-30 v1.6.9：首帧渲染回调——封面揭示等真画面上屏，消灭 READY 与
        // 首帧之间的黑屏窗口（修复2：滑动下一条先闪屏再变比例再复原）
        override fun onRenderedFirstFrame() {
            _playerState.update { it.copy(hasFirstFrame = true) }
        }

        // 2026-10-03 批次C：视频实测尺寸回调 —— 播放侧宽高写回（数据自愈）。
        // unappliedRotationDegrees 为 90/270 时交换宽高得到显示尺寸（与批次B 实体列
        // mediaWidth/mediaHeight 的口径一致：显示宽高 px；该字段虽被标记 @Deprecated，
        // 但仍是 API <34 判定未应用旋转的唯一来源，suppress 见方法注解）；实体为 null
        //（无 currentVideo）或尺寸无效时静默跳过，缺失/偏差的判定与去重见
        // persistMediaDimensionsIfNeeded。
        @Suppress("DEPRECATION")
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val rotated = videoSize.unappliedRotationDegrees == 90 ||
                videoSize.unappliedRotationDegrees == 270
            val displayW = if (rotated) videoSize.height else videoSize.width
            val displayH = if (rotated) videoSize.width else videoSize.height
            val filePath = _playerState.value.currentVideo?.filePath ?: return
            persistMediaDimensionsIfNeeded(filePath, displayW, displayH)
        }
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(getApplication<Application>()).build().apply {
        addListener(playerListener)
        // 2026-09-15 v2 批次3（P1-1 旗舰硬件红利）：邻条自动预载——播放列表化后，
        // ExoPlayer 自动预载相邻播放项（8s 时长预算），上滑切换秒开、无起播黑帧。
        // 预载目标为播放列表邻居，不额外占播放器实例；图片项不在播放列表内（见 ensurePlaylist）。
        runCatching {
            setPreloadConfiguration(ExoPlayer.PreloadConfiguration(8_000_000L))
        }
    }

    private var playerReleased = false

    // ExoPlayer instance（releaseIfIdle 后由 obtainPlayer 按需重建）
    var exoPlayer: ExoPlayer = buildPlayer()
        private set

    /** 取可用播放器；曾被 releaseIfIdle 释放则重建（返回后再次进入播放页的兜底）。 */
    private fun obtainPlayer(): ExoPlayer {
        if (playerReleased) {
            playerReleased = false
            exoPlayer = buildPlayer()
        }
        return exoPlayer
    }

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

    /** Fullscreen toggle — used by PlayerScreen and AppNavigation. */
    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                // 仅在播放中轮询（2026-09-30 v1.6.9）：暂停/图片查看时 position 不变，
                // 停止 350ms 高频 update，消除播放器 UI 空转重组（图片查看稳定性）
                if (!playerReleased && exoPlayer.playWhenReady) {
                    syncPlaybackPosition()
                    delay(350L)
                } else {
                    // 2026-10 整改：空闲态降频（350ms→2s）——进程常驻循环不再持续唤醒 CPU
                    delay(2_000L)
                }
            }
        }
    }

    private fun syncPlaybackPosition() {
        // releaseIfIdle 释放期间静默跳过；读已释放实例会抛 IllegalStateException
        if (playerReleased) return
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        _playerState.update {
            it.copy(
                position = exoPlayer.currentPosition.coerceAtLeast(0L),
                duration = duration
            )
        }
    }

    /**
     * 进入播放会话（2026-09-30 v1.6.9）：以入口素材类型建立本次会话的稳定列表。
     *
     * - 类型分流（优化1）：入口是视频 → 会话列表只含视频，滑动只切视频；
     *   入口是图片 → 会话列表只含图片。视频/图片不再在同一个翻页流里穿插。
     * - 首次进入兜底：playerViewModel 自己的库流是冷的（WhileSubscribed），
     *   首次 playVideo 可能撞上空列表走「列表外单条」路径，pager 无页可对齐
     *   （首进时间条走、声画对不上条目的来源之一）——这里先等库列表首帧（≤1.5s）
     *   再建会话、再定位；入口条目不在库中（扫描滞后）时补一条最小实体保证有页可站。
     */
    fun openPlayer(entryFilePath: String) {
        // 2026-10-03 批次F：新会话开始前回收可能残留的预览播放器（跨会话实例隔离）
        releasePreviewPlayer()
        val entryIsImage = MediaFileTypes.isImageFile(entryFilePath)
        viewModelScope.launch {
            val base = withTimeoutOrNull(1500L) {
                videoList.first { it.isNotEmpty() }
            } ?: videoList.value
            val filtered = base.filter {
                if (entryIsImage) MediaFileTypes.isImageFile(it.filePath)
                else !MediaFileTypes.isImageFile(it.filePath)
            }
            _playerPlaylist.value = if (filtered.any { it.filePath == entryFilePath }) {
                filtered
            } else {
                listOf(minimalEntity(entryFilePath)) + filtered
            }
            // 2026-10-03 批次E：会话切换即回收旧会话的首帧缓存（保留新会话条目路径，
            // 重开同一视频可复用）；上限兜底见 VideoFirstFrameCache.LruCache
            VideoFirstFrameCache.evictExcept(_playerPlaylist.value.mapTo(mutableSetOf()) { it.filePath })
            // 2026-10-03 终验排查日志：占位兜底命中时打印入口串与库列表规模
            if (_playerPlaylist.value.any { it.id == it.filePath }) {
                android.util.Log.w("PlayerSession", "openPlayer placeholder hit; entry=$entryFilePath, base=${base.size}, filtered=${filtered.size}")
            }
            playVideo(entryFilePath, autoStart = false)
            // 2026-10-03 终验修复：库流冷启动超时（首装扫描刚入库时常见）会让会话落入
            // minimalEntity 占位——信息条显示「未知作者」、宽高写回按占位 id 匹配不到真实行。
            // 扫描中途的过期快照也可能缺行，所以 10s 窗口内持续监听库流，每次发射都尝试
            // 就地修补占位页：页序不变、ExoPlayer 不动，仅替换元数据实体。
            if (_playerPlaylist.value.any { it.id == it.filePath }) {
                launch {
                    withTimeoutOrNull(10_000L) {
                        var repaired = false
                        videoList.collect { library ->
                            if (repaired || library.isEmpty()) return@collect
                            val byPath = library.associateBy { it.filePath }
                            _playerPlaylist.value = _playerPlaylist.value.map { entity ->
                                if (entity.id == entity.filePath) byPath[entity.filePath] ?: entity else entity
                            }
                            _playerState.value.currentVideo?.filePath?.let { path ->
                                _playerState.update { it.copy(currentVideo = findEntity(path)) }
                            }
                            if (_playerPlaylist.value.none { it.id == it.filePath }) repaired = true
                        }
                    }
                }
            }
        }
    }

    /** 库中找不到的条目（扫描滞后）用最小实体占位，保证会话列表有该页可对齐。 */
    private fun minimalEntity(filePath: String): DownloadHistoryEntity {
        val file = File(filePath)
        return DownloadHistoryEntity(
            id = filePath,
            url = "",
            title = file.nameWithoutExtension,
            thumbnail = "",
            uploader = "",
            quality = "",
            filePath = filePath,
            fileSize = file.length(),
            duration = 0,
            createdAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis()
        )
    }

    /**
     * 播放指定文件。autoStart=false 时只定位/预载不起播（2026-09-30 播放页动画规格：
     * 宿主在导航时预载，PlayerScreen 封面飞入到位后再调 play()，避免动画期间出声）。
     */
    fun playVideo(filePath: String, autoStart: Boolean = true) {
        val exoPlayer = obtainPlayer()
        val file = File(filePath)
        if (!file.exists()) {
            _playerState.update { it.copy(currentVideo = null, isReady = false) }
            return
        }

        // 图片文件：跳过 ExoPlayer（图片查看由 PlayerScreen 的图片会话分支接管）
        // 2026-09-16 修复「滑到图片仍有声音」：图片条目必须立即暂停 ExoPlayer——
        // 此前只切 currentVideo，上一个视频的音频继续播放泄漏到图片页。
        // pause 而非 stop：保留列表位置与播放进度，滑回视频页 seekTo 同 index 无缝恢复。
        if (MediaFileTypes.isImageFile(filePath)) {
            if (exoPlayer.playWhenReady) exoPlayer.pause()
            _playerState.update {
                it.copy(
                    currentVideo = findEntity(filePath),
                    isReady = true,
                    hasFirstFrame = false
                )
            }
            return
        }

        // 同路径早退检查（2026-09-14 修复"立即返回后再点无法播放"）：
        // 立即返回会触发 stopPlayer() → ExoPlayer 回 STATE_IDLE（stop 清空已准备内容），
        // 此时不允许直接 play()（IDLE 下无已准备媒体，play() 永远不会开始），
        // 必须落到底部的重新定位 + prepare 流程
        if (_playerState.value.currentVideo?.filePath == filePath &&
            exoPlayer.mediaItemCount > 0 &&
            exoPlayer.playbackState != Player.STATE_IDLE
        ) {
            // autoStart=false（进场预载/返回定位）时同步暂停：会话可能从「播放中」被重新打开
            if (!autoStart && exoPlayer.playWhenReady) exoPlayer.pause()
            if (autoStart && !exoPlayer.playWhenReady) exoPlayer.play()
            return
        }

        // 2026-09-15 v2 批次3（P1-1）：播放列表化切换——整个会话列表进 ExoPlayer 播放列表，
        // 切条 = seekTo(index)（不再 setMediaItem 单条），ExoPlayer 据此自动预载相邻条目。
        // seekTo 同索引时不重置进度（单条→列表的平滑升级路径）。
        // 2026-09-30 v1.6.9：列表源改为「播放会话列表」（openPlayer 类型分流快照），
        // 会话内与 pager 页序严格一致；库重排不再影响正在进行的翻页。
        val videos = ensurePlaylist(exoPlayer)
        var index = videos.indexOfFirst { it.filePath == filePath }
        // 列表内容与播放列表错位（画质升级替换路径/删除/流更新）→ 强制重建一次
        if (index < 0 || index < exoPlayer.mediaItemCount &&
            exoPlayer.getMediaItemAt(index).localConfiguration?.tag != filePath
        ) {
            exoPlayer.clearMediaItems()
            ensurePlaylist(exoPlayer)
            index = videos.indexOfFirst { it.filePath == filePath }
        }

        // 切条后首帧/就绪状态必须重置：封面保持到新条目真画面渲染完成（修复2）
        _playerState.update { it.copy(isReady = false, hasFirstFrame = false) }
        if (index >= 0 && index < exoPlayer.mediaItemCount) {
            if (index != exoPlayer.currentMediaItemIndex || exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.seekTo(index, 0L)
                if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            }
            exoPlayer.playWhenReady = autoStart
        } else {
            // 兜底：列表外文件（如刚下载完尚未出现在 Flow 中）单条播放
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = autoStart
        }

        // Find matching entity or create minimal one（优先会话列表，库列表兜底补全字段）
        val entity = findEntity(filePath)
        _playerState.update { it.copy(currentVideo = entity) }
    }

    fun playVideo(entity: DownloadHistoryEntity, autoStart: Boolean = true) {
        // 2026-09-15 严重 bug 修复：原实现先写 currentVideo 再调 playVideo(filePath)，
        // 导致「同路径早退」误判（以为 ExoPlayer 已在播目标条目）→ 只 play() 不切媒体，
        // 封面显示新条目、实际内容还是旧视频（用户实测：翻页后内容永远是第一条）。
        // 正确顺序：先走完整的媒体切换流程，再同步展示实体。
        playVideo(entity.filePath, autoStart)
        _playerState.update { it.copy(currentVideo = entity) }
    }

    /** 按路径找实体：会话列表优先（与 pager 页序一致），库列表兜底，最后最小占位。 */
    private fun findEntity(filePath: String): DownloadHistoryEntity =
        _playerPlaylist.value.find { it.filePath == filePath }
            ?: videoList.value.find { it.filePath == filePath }
            ?: minimalEntity(filePath)

    /**
     * 2026-10-03 批次C：媒体显示宽高写回（数据自愈）—— 视频与图片共用链路。
     *
     * 写入条件：实体宽高为 null（旧记录/来源缺失）或与实测差 ≥2px 才写，同值不重复写。
     * 顺序：先更新内存（_playerPlaylist + currentVideo 的实体 —— UI 媒体框比例随即
     * 用上真实比例，PlayerScreen 的比例引擎经 playlist 变化重启后取到新目标），
     * 再于 IO 协程写库（runCatching 兜底，写库失败不影响播放；与 playerReleased 无关）。
     * 防御：会话外条目（playlist 与 currentVideo 都找不到实体）不写，防 entity 为 null。
     */
    private fun persistMediaDimensionsIfNeeded(filePath: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val entity = _playerPlaylist.value.find { it.filePath == filePath }
            ?: _playerState.value.currentVideo?.takeIf { it.filePath == filePath }
            ?: return
        val oldW = entity.mediaWidth
        val oldH = entity.mediaHeight
        val unchanged = oldW != null && oldH != null &&
            abs(oldW - width) < 2 && abs(oldH - height) < 2
        if (unchanged) return
        val updated = entity.copy(mediaWidth = width, mediaHeight = height)
        _playerPlaylist.value = _playerPlaylist.value.map {
            if (it.filePath == filePath) updated else it
        }
        _playerState.update { st ->
            if (st.currentVideo?.filePath == filePath) st.copy(currentVideo = updated) else st
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { historyRepository.updateMediaDimensions(entity.id, width, height) }
        }
    }

    /**
     * 2026-10-03 批次E：图片页 Coil 加载成功后回传实测宽高（PlayerScreen 的
     * ZoomableImageViewerPage onState 回调调用），写回与内存更新链路同视频
     * （persistMediaDimensionsIfNeeded：同值去重 + IO 写库 + 即时更新实体）。
     */
    fun reportImageDimensions(filePath: String, width: Int, height: Int) {
        persistMediaDimensionsIfNeeded(filePath, width, height)
    }

    // ===================== 视频首帧预渲染（2026-10-03 批次E） =====================

    /**
     * 查询/抽取视频真实首帧（进程内 LruCache，见 VideoFirstFrameCache）：命中直返，
     * 未命中在 IO 抽帧后落缓存；文件不存在/失败返回 null（调用方回退旧封面）。
     * 同文件并发请求在缓存内合并，不重复解码。
     */
    suspend fun firstFrameFor(filePath: String): Bitmap? =
        VideoFirstFrameCache.firstFrame(getApplication(), filePath)

    /** 仅查缓存不抽帧（播放页封面 peek：不为当前页额外抽帧，PlayerView 自会渲染真画面） */
    fun peekFirstFrame(filePath: String): Bitmap? = VideoFirstFrameCache.peek(filePath)

    /**
     * 落定预取邻页首帧：settledPage 变化时对 ±1 页的视频条目预热缓存（忽略图片条目与
     * 不存在的文件），邻页预组合渲染时缓存大概率已就绪 —— 滑入页直接显示真帧。
     * viewModelScope 后台执行，与去抖切播链路、比例动画流互不干扰。
     */
    fun prefetchNeighborFirstFrames(page: Int) {
        val playlist = _playerPlaylist.value
        listOf(page - 1, page + 1)
            .filter { it in playlist.indices }
            .map { playlist[it] }
            .filter { !MediaFileTypes.isImageFile(it.filePath) && File(it.filePath).exists() }
            .forEach { entity ->
                viewModelScope.launch { firstFrameFor(entity.filePath) }
            }
    }

    // ===================== 真双播放器（2026-10-03 批次F） =====================

    /**
     * 旗舰机「真双播放器」总开关（2026-10-03 批次F；2026-10-03 定案升级为设置开关）：
     * 最终生效 = 设置「真双播放器」开关（SettingsRepository.dualPlayerFlow，默认开）
     * ∧ FlagshipDetector 硬件达标（RAM ≥ 16GB）——任一不满足即 false，整条预览链路
     * 不触发，完全走批次E 真帧封面路径。StateFlow 随设置实时变化，拖拽预览的触发
     * effect 与 requestPreviewPlayer 均以此为准。
     */
    private val settingsRepository =
        (getApplication<Application>() as com.ed.edqiu.EdqiuApplication).container.settingsRepository
    val isDualPlayerEnabled: StateFlow<Boolean> =
        settingsRepository.dualPlayerFlow
            .map { enabled -> enabled && FlagshipDetector.isFlagship(getApplication()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _previewFilePath = MutableStateFlow<String?>(null)

    /** 当前挂着静音预览的文件路径（null = 无预览；非旗舰机恒 null） */
    val previewFilePath: StateFlow<String?> = _previewFilePath.asStateFlow()

    private val _previewHasFirstFrame = MutableStateFlow<String?>(null)

    /** 预览首帧已上屏的文件路径（PlayerScreen 封面层据此从真帧/卡片封面淡出交接活视频） */
    val previewHasFirstFrame: StateFlow<String?> = _previewHasFirstFrame.asStateFlow()

    /**
     * 预览播放器实例（全 App 第二个也是最后一个 ExoPlayer，「主 + 预览 ≤ 2 实例」铁律）。
     * 独立 build/release，不走 obtainPlayer/playerReleased 主实例机制；UI 侧由
     * PlayerScreen 的 AndroidView.update 按页角色读取重绑。
     */
    var previewPlayer: ExoPlayer? = null
        private set

    /** 预览监听：只关心首帧上屏与错误自清（预览恒静音，不参与任何主播放器状态） */
    private val previewListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            // 预览首帧上屏：记录该路径，PlayerScreen 据此淡出邻页封面露出活视频
            _previewHasFirstFrame.value = _previewFilePath.value
        }

        override fun onPlayerError(error: PlaybackException) {
            // 解码器耗尽等预览错误：静默释放、回退批次E 真帧路径，绝不 crash 也绝不
            // 影响主播放（release 延迟到主队列下一拍，不在监听回调内同步重入）
            viewModelScope.launch { releasePreviewPlayer() }
        }
    }

    /**
     * 预览播放器构建：与主播放器同 Builder 风格，但**不加 PreloadConfiguration**
     *（预览只播单条，且不能为它再预载第三路媒体 —— 守住 ≤2 实例与内存底线）。
     */
    private fun buildPreviewPlayer(): ExoPlayer =
        ExoPlayer.Builder(getApplication<Application>()).build().apply {
            addListener(previewListener)
            // 恒静音：拖拽期间只呈现活画面，绝不与主播放器出现双声
            volume = 0f
        }

    /**
     * 请求为「拖拽目标页」启动静音预览播放（PlayerScreen 拖拽越过 15% 时调用）。
     * 拒绝条件（直接返回，调用方自动回退批次E 真帧路径）：双播放未开启 / 图片路径 /
     * 是当前主播放条目 / 已是该路径 / 文件不存在。已有预览实例时换目标复用同实例
     *（永远只有一个预览实例）；构建失败（runCatching 兜底，如解码器耗尽）静默置空，
     * 绝不 crash。释放后再次请求可惰性重建。
     */
    fun requestPreviewPlayer(filePath: String) {
        if (!isDualPlayerEnabled.value) return
        if (MediaFileTypes.isImageFile(filePath)) return
        if (_playerState.value.currentVideo?.filePath == filePath) return
        if (_previewFilePath.value == filePath) return
        val file = File(filePath)
        if (!file.exists()) return

        if (previewPlayer == null) {
            val built = runCatching { buildPreviewPlayer() }.getOrNull() ?: return
            previewPlayer = built
        }
        // 状态先清后立：封面层在预览首帧上屏前保持显示（previewHasFirstFrame 归零）
        _previewHasFirstFrame.value = null
        _previewFilePath.value = filePath
        val player = previewPlayer
        if (player != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            // 静音起播（volume 构建时已置 0）：拖拽中呈现活画面
            player.playWhenReady = true
        }
    }

    /** 释放预览播放器（幂等）：release 实例 + 清两个状态，UI 自动回退批次E 路径。 */
    fun releasePreviewPlayer() {
        val player = previewPlayer
        previewPlayer = null
        _previewFilePath.value = null
        _previewHasFirstFrame.value = null
        if (player != null) {
            runCatching { player.release() }
        }
    }

    /**
     * 确保播放列表与会话列表一致（2026-09-15 批次3；2026-09-30 v1.6.9 改为会话列表）。
     * 会话列表已按类型分流（视频会话不含图片），避免预载撞上无法解码的图片项。
     * @return 当前会话实体列表（与播放列表一一对应）。
     */
    private fun ensurePlaylist(exoPlayer: ExoPlayer): List<DownloadHistoryEntity> {
        val videos = _playerPlaylist.value
        if (exoPlayer.mediaItemCount != videos.size) {
            exoPlayer.clearMediaItems()
            videos.forEach { entity ->
                val item = MediaItem.fromUri(Uri.fromFile(File(entity.filePath)))
                    .buildUpon()
                    .setTag(entity.filePath)
                    .build()
                exoPlayer.addMediaItem(item)
            }
        }
        return videos
    }

    /** 起播（PlayerScreen 封面飞入到位后调用；动画期间只预载不出声）。 */
    fun play() {
        if (!playerReleased) exoPlayer.play()
    }

    fun pauseVideo() {
        if (!playerReleased) exoPlayer.pause()
    }

    fun togglePlayPause() {
        val exoPlayer = obtainPlayer()
        if (exoPlayer.playWhenReady) exoPlayer.pause() else exoPlayer.play()
    }

    fun seekTo(progress: Float) {
        val exoPlayer = obtainPlayer()
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: return
        val position = (duration * progress.coerceIn(0f, 1f)).toLong()
        exoPlayer.seekTo(position)
        _playerState.update { it.copy(position = position, duration = duration) }
    }

    fun cyclePlaybackSpeed() {
        val exoPlayer = obtainPlayer()
        val nextSpeed = when (_playerState.value.playbackSpeed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        exoPlayer.playbackParameters = PlaybackParameters(nextSpeed)
        _playerState.update { it.copy(playbackSpeed = nextSpeed) }
    }

    fun toggleMute() {
        val muted = !_playerState.value.isMuted
        obtainPlayer().volume = if (muted) 0f else 1f
        _playerState.update { it.copy(isMuted = muted) }
    }

    /** Toggle fullscreen mode on/off. */
    fun toggleFullscreen() {
        _isFullscreen.update { !it }
        _playerState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    /** Exit fullscreen mode (used by BackHandler). */
    fun exitFullscreen() {
        _isFullscreen.update { false }
        _playerState.update { it.copy(isFullscreen = false) }
    }

    fun releasePlayer() {
        if (!playerReleased) {
            playerReleased = true
            exoPlayer.release()
        }
        // 2026-10-03 批次F：全量释放路径一并回收预览实例（≤2 实例铁律）
        releasePreviewPlayer()
        // 2026-10-03 批次E：播放器实例释放，首帧缓存一并归还
        VideoFirstFrameCache.evictExcept(emptySet())
    }

    /** 停止播放但不释放 ExoPlayer（返回后再进还能播） */
    fun stopPlayer() {
        if (!playerReleased) exoPlayer.stop()
        // 2026-10-03 批次F：离开播放器（PlayerScreen onDispose）即回收预览实例
        releasePreviewPlayer()
        // 2026-10-03 批次E：播放器关闭（离开播放页）清空首帧缓存，归还位图内存
        VideoFirstFrameCache.evictExcept(emptySet())
    }

    /**
     * 仅在闲置（未起播且无已准备媒体）时释放实例，归还解码器资源。
     * 在播续播场景（已 prepare/在播）调用为 no-op，不会打断后台播放；
     * 释放后再次进入播放页由 obtainPlayer() 按需重建。
     */
    fun releaseIfIdle() {
        if (playerReleased) return
        if (!exoPlayer.playWhenReady && exoPlayer.playbackState == Player.STATE_IDLE) {
            playerReleased = true
            exoPlayer.release()
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (!playerReleased) {
            playerReleased = true
            exoPlayer.release()
        }
        // 2026-10-03 批次F：ViewModel 销毁兜底回收预览实例
        releasePreviewPlayer()
        // 2026-10-03 批次E：ViewModel 销毁兜底回收首帧缓存
        VideoFirstFrameCache.evictExcept(emptySet())
    }
}
