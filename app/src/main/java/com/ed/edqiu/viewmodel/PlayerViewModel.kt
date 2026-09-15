package com.ed.edqiu.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.ed.edqiu.data.database.DownloadHistoryEntity
import com.ed.edqiu.data.model.MediaFileTypes
import com.ed.edqiu.data.repository.HistoryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /** 视频真实像素宽高（onVideoSizeChanged，2026-09-15 比例自适应缩放依据；0=未知/图片）。 */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val historyRepository = HistoryRepository(application)

    val videoList: StateFlow<List<DownloadHistoryEntity>> =
        historyRepository.allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // ExoPlayer instance
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

    /** Fullscreen toggle 鈥?used by PlayerScreen and AppNavigation. */
    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    init {
        exoPlayer.addListener(object : Player.Listener {
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
            // 2026-09-15 比例自适应：拿到视频真实像素宽高，供 PlayerScreen 选择缩放模式
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                _playerState.update {
                    it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
                }
            }
        })
        // 2026-09-15 v2 批次3（P1-1 旗舰硬件红利）：邻条自动预载——播放列表化后，
        // ExoPlayer 自动预载相邻播放项（8s 时长预算），上滑切换秒开、无起播黑帧。
        // 预载目标为播放列表邻居，不额外占播放器实例；图片项不在播放列表内（见 videoEntities）。
        runCatching {
            exoPlayer.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(8_000_000L))
        }
        viewModelScope.launch {
            while (true) {
                syncPlaybackPosition()
                delay(350L)
            }
        }
    }

    private fun syncPlaybackPosition() {
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        _playerState.update {
            it.copy(
                position = exoPlayer.currentPosition.coerceAtLeast(0L),
                duration = duration
            )
        }
    }

    fun playVideo(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            _playerState.update { it.copy(currentVideo = null, isReady = false) }
            return
        }

        // 图片文件：跳过 ExoPlayer（图片查看由 PlayerScreen 的 isImageView 分支接管）
        if (MediaFileTypes.isImageFile(filePath)) {
            val entity = videoList.value.find { it.filePath == filePath }
                ?: DownloadHistoryEntity(
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
            _playerState.update { it.copy(currentVideo = entity, isReady = true) }
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
            if (!exoPlayer.playWhenReady) exoPlayer.play()
            return
        }

        // 2026-09-15 v2 批次3（P1-1）：播放列表化切换——整个视频列表进 ExoPlayer 播放列表，
        // 切条 = seekTo(index)（不再 setMediaItem 单条），ExoPlayer 据此自动预载相邻条目。
        // seekTo 同索引时不重置进度（单条→列表的平滑升级路径）。
        val videos = ensurePlaylist()
        var index = videos.indexOfFirst { it.filePath == filePath }
        // 列表内容与播放列表错位（画质升级替换路径/删除/流更新）→ 强制重建一次
        if (index < 0 || index < exoPlayer.mediaItemCount &&
            exoPlayer.getMediaItemAt(index).localConfiguration?.tag != filePath
        ) {
            exoPlayer.clearMediaItems()
            ensurePlaylist()
            index = videos.indexOfFirst { it.filePath == filePath }
        }

        _playerState.update { it.copy(isReady = false, videoWidth = 0, videoHeight = 0) }
        if (index >= 0 && index < exoPlayer.mediaItemCount) {
            if (index != exoPlayer.currentMediaItemIndex || exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.seekTo(index, 0L)
                if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            }
            exoPlayer.playWhenReady = true
        } else {
            // 兜底：列表外文件（如刚下载完尚未出现在 Flow 中）单条播放
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        // Find matching entity or create minimal one
        val entity = videoList.value.find { it.filePath == filePath }
            ?: DownloadHistoryEntity(
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
        _playerState.update { it.copy(currentVideo = entity) }
    }

    fun playVideo(entity: DownloadHistoryEntity) {
        // 2026-09-15 严重 bug 修复：原实现先写 currentVideo 再调 playVideo(filePath)，
        // 导致「同路径早退」误判（以为 ExoPlayer 已在播目标条目）→ 只 play() 不切媒体，
        // 封面显示新条目、实际内容还是旧视频（用户实测：翻页后内容永远是第一条）。
        // 正确顺序：先走完整的媒体切换流程，再同步展示实体。
        playVideo(entity.filePath)
        _playerState.update { it.copy(currentVideo = entity) }
    }

    /**
     * 确保播放列表与当前视频列表一致（2026-09-15 批次3）。
     * 仅视频进列表（图片走查看器分支，避免预载撞上无法解码的图片项）。
     * @return 当前视频实体列表（与播放列表一一对应）。
     */
    private fun ensurePlaylist(): List<DownloadHistoryEntity> {
        val videos = videoList.value.filter { !MediaFileTypes.isImageFile(it.filePath) }
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

    fun pauseVideo() {
        exoPlayer.pause()
    }

    fun togglePlayPause() {
        if (exoPlayer.playWhenReady) exoPlayer.pause() else exoPlayer.play()
    }

    fun seekTo(progress: Float) {
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: return
        val position = (duration * progress.coerceIn(0f, 1f)).toLong()
        exoPlayer.seekTo(position)
        _playerState.update { it.copy(position = position, duration = duration) }
    }

    fun cyclePlaybackSpeed() {
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
        exoPlayer.volume = if (muted) 0f else 1f
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
        exoPlayer.release()
    }

    /** 停止播放但不释放 ExoPlayer（返回后再进还能播） */
    fun stopPlayer() {
        exoPlayer.stop()
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer.release()
    }
}
