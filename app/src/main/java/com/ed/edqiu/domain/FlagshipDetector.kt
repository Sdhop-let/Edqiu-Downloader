package com.ed.edqiu.domain

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 2026-10-03 批次F：旗舰机检测 —— 决定「真双播放器」是否可用（滑动翻页时
 * 主播放器 + 静音预览播放器两个 ExoPlayer 实例并存，拖拽中邻页呈现活视频）。
 *
 * 2026-10-03 定案（用户）：达标门槛提高到 **RAM ≥ 16GB**，且是否启用还需
 * 设置里「真双播放器」开关（SettingsRepository.dualPlayerFlow，默认开）——
 * 本检测只回答「硬件够不够」，开关回答「用户要不要」，两者取与才是最终生效条件。
 *
 * 判定标准（三者同时满足，任一不满足都视为非旗舰，整条预览链路不触发）：
 * - RAM ≥ 16GB（ActivityManager.MemoryInfo.totalMem ≥ 16L * 1024 * 1024 * 1024）：
 *   双实例意味着双路硬解码器会话 + 双份缓冲/解码内存，16GB 机型才有充足余量
 *   在不挤压前台体验的前提下承载第二路解码；
 * - !ActivityManager.isLowRamDevice：厂商对低配设备会打低内存标记
 *   （系统属性 ro.config.low_ram，isLowRamDevice 为其公开读取口），
 *   此类设备系统会限制并发解码并激进回收内存，必须在阈值之外再排除一道；
 * - Build.VERSION.SDK_INT ≥ 29（Android 10）：Media3/ExoPlayer 多实例依赖
 *   现代硬解栈（API 29 起 Codec2 全面替代 OMX，MediaCodec 并发行为稳定可控），
 *   低版本双实例极易撞解码器初始化失败。
 *
 * 结果进程内缓存一次（首次调用判定后写入缓存，此后 O(1) 返回）——
 * 检测只读系统属性无副作用，任意 context（内部取 applicationContext）结果恒一致。
 */
object FlagshipDetector {

    /** 达标门槛：16GB RAM（totalMem 为内核口径，通常略小于商品标称运存，取同口径比较） */
    private const val FLAGSHIP_MIN_RAM_BYTES: Long = 16L * 1024 * 1024 * 1024

    @Volatile
    private var cachedResult: Boolean? = null

    /** 设备总内存（bytes；检测失败 -1。设置页据此展示「当前设备运存」状态行） */
    @Volatile
    private var cachedTotalMemBytes: Long = -1L

    fun isFlagship(context: Context): Boolean {
        ensureDetected(context)
        return cachedResult ?: false
    }

    /** 设备总内存的可读文本（如 "16GB"；检测失败返回 "未知"），设置页状态行用 */
    fun totalMemText(context: Context): String {
        ensureDetected(context)
        val bytes = cachedTotalMemBytes
        if (bytes <= 0) return "未知"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        // 内核口径常带小数（如 15.6GB），≥10GB 取整对齐商品标称，<10GB 保留一位小数
        return if (gb >= 10.0) "${gb.toInt()}GB" else "%.1fGB".format(gb)
    }

    private fun ensureDetected(context: Context) {
        if (cachedResult != null) return
        synchronized(this) {
            if (cachedResult != null) return
            val appContext = context.applicationContext
            runCatching {
                val activityManager =
                    appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (activityManager != null) {
                    val memoryInfo = ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(memoryInfo)
                    cachedTotalMemBytes = memoryInfo.totalMem
                    cachedResult = Build.VERSION.SDK_INT >= 29 &&              // Media3 多实例 + 现代硬解栈
                        memoryInfo.totalMem >= FLAGSHIP_MIN_RAM_BYTES &&       // 16GB RAM：双解码器内存余量
                        !activityManager.isLowRamDevice                        // 排除厂商低内存标记设备
                }
            }
            cachedResult = cachedResult ?: false
        }
    }
}
