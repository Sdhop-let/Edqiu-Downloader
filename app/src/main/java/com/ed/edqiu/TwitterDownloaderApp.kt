package com.ed.edqiu

import android.app.Application
import android.util.Log
import com.ed.edqiu.service.DirectoryScanner
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

open class TwitterDownloaderApp : Application() {
    private val initializedMutable = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = initializedMutable.asStateFlow()

    private val initErrorMutable = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = initErrorMutable.asStateFlow()

    /** 16KB 内存页设备（Android 15+ 部分机型）：当前 ffmpeg 依赖（libwebp* 4KB 对齐）无法加载，
     *  yt-dlp 合流/HLS/转码功能不可用，引擎链自动降级（见 YoutubeDLService/MediaQualityUpgrader）。 */
    val is16kPageDevice: Boolean by lazy {
        runCatching {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) == 16384L
        }.getOrDefault(false)
    }

    override fun onCreate() {
        super.onCreate()
        initializeEngine(scanAfterInit = true)
    }

    fun retryInit() {
        initErrorMutable.value = null
        initializeEngine(scanAfterInit = false)
    }

    private fun initializeEngine(scanAfterInit: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@TwitterDownloaderApp)
                FFmpeg.getInstance().init(this@TwitterDownloaderApp)
                initializedMutable.value = true
                if (scanAfterInit) DirectoryScanner.scanAndRebuildHistory(this@TwitterDownloaderApp)
                Log.i("TwitterDownloaderApp", "Downloader engine initialized")
            } catch (error: Exception) {
                Log.e("TwitterDownloaderApp", "Downloader engine init failed", error)
                initErrorMutable.value = error.message ?: "初始化失败"
            }
        }
    }
}
