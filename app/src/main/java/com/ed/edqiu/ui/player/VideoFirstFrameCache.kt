package com.ed.edqiu.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import kotlin.math.roundToInt

/**
 * 2026-10-03 批次E：视频真实首帧进程内缓存（播放器垂直滑动「邻页真帧预渲染」）。
 *
 * 设计要点：
 * - LruCache<String, Bitmap> 容量 4 条；Bitmap 为 RGB_565（2 字节/像素）且等比降采样到
 *   屏幕宽度 —— 单张 ~2-4MB，杜绝 4K 原帧直接驻留内存；
 * - 抽帧统一在自持 IO 作用域执行（MediaMetadataRetriever，
 *   getFrameAtTime(0, OPTION_CLOSEST_SYNC) 取关键帧，快且够用作封面），绝不阻塞
 *   手势/主线程；retriever 于 finally 中 release；
 * - 同文件并发请求合并（in-flight Deferred 表）：落定预取与页面懒取同路径共享
 *   同一次抽帧，不重复解码；
 * - 调用方协程被取消（页面离开组合）不影响抽帧完成与结果落缓存（作用域自持）；
 * - 会话切换/播放器关闭由 PlayerViewModel 调 [evictExcept] 回收，缓存有上限、有明确回收路径。
 */
object VideoFirstFrameCache {

    private const val MAX_ENTRIES = 4

    /** 单例自持 IO 作用域：与调用方生命周期解耦，页面取消后抽帧仍完成并落缓存 */
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache = LruCache<String, Bitmap>(MAX_ENTRIES)

    /** 进行中的抽帧任务表：同文件并发请求合并为一次 MediaMetadataRetriever 抽帧 */
    private val inFlight = LinkedHashMap<String, Deferred<Bitmap?>>()

    /** 仅查缓存不抽帧（UI 同步读取；未命中返回 null，调用方回退旧封面） */
    fun peek(filePath: String): Bitmap? = cache.get(filePath)

    /**
     * 取视频首帧：命中直返；未命中合并进在飞任务在 IO 抽帧（挂起至完成）。
     * 文件不存在/宽高无效/异常一律返回 null（调用方回退旧封面），本函数不抛异常。
     */
    suspend fun firstFrame(context: Context, filePath: String): Bitmap? {
        cache.get(filePath)?.let { return it }
        if (!File(filePath).exists()) return null
        val deferred = synchronized(inFlight) {
            inFlight.getOrPut(filePath) {
                cacheScope.async { extractFirstFrame(context.applicationContext, filePath) }
            }
        }
        return try {
            deferred.await()
        } catch (_: Throwable) {
            null
        } finally {
            synchronized(inFlight) {
                if (inFlight[filePath] === deferred) inFlight.remove(filePath)
            }
        }
    }

    /** 实际抽帧（IO 线程）：成功结果立即入缓存，与调用方取消与否无关 */
    private fun extractFirstFrame(appContext: Context, filePath: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            if (frame.width <= 0 || frame.height <= 0) {
                frame.recycle()
                return null
            }
            val downsampled = downsampleToScreenWidth(
                frame,
                appContext.resources.displayMetrics.widthPixels
            )
            cache.put(filePath, downsampled)
            return downsampled
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 等比降采样到屏幕宽并统一转 RGB_565；缩放后回收原帧（原帧仅本函数持有） */
    private fun downsampleToScreenWidth(src: Bitmap, screenWidth: Int): Bitmap {
        val targetW = screenWidth.coerceAtLeast(1)
        val result = if (src.width > targetW) {
            val dstH = (src.height * (targetW.toFloat() / src.width)).roundToInt().coerceAtLeast(1)
            Bitmap.createBitmap(targetW, dstH, Bitmap.Config.RGB_565).also { dst ->
                Canvas(dst).drawBitmap(
                    src,
                    null,
                    RectF(0f, 0f, targetW.toFloat(), dstH.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            }
        } else {
            // 已不大于屏宽：仍统一转 RGB_565 控制驻留内存；转换失败退回原帧
            src.copy(Bitmap.Config.RGB_565, false) ?: src
        }
        if (result !== src) src.recycle()
        return result
    }

    /** 会话切换/播放器关闭回收：清掉 keepPaths 之外的缓存帧 */
    fun evictExcept(keepPaths: Set<String>) {
        cache.snapshot().keys.filterNot { it in keepPaths }.forEach { cache.remove(it) }
    }
}
