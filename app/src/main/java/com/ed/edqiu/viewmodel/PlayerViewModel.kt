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
    val isMuted: Boolean = false
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
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    syncPlaybackPosition()
                }
            }
        })
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
            _playerState.update { it.copy(currentVideo = null) }
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
            _playerState.update { it.copy(currentVideo = entity) }
            return
        }

        if (_playerState.value.currentVideo?.filePath == filePath && exoPlayer.mediaItemCount > 0) {
            if (!exoPlayer.playWhenReady) exoPlayer.play()
            return
        }

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

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
        _playerState.update { it.copy(currentVideo = entity) }
        playVideo(entity.filePath)
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

