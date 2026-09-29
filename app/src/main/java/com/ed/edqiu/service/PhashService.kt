package com.ed.edqiu.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.ed.edqiu.data.database.AppDatabase
import com.ed.edqiu.data.model.MediaFileTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 感知哈希（pHash）服务（2026-09-15 v2 批次3：P1-4① 重复媒体检测）。
 *
 * - 64bit DCT pHash：抽帧/解码 → 32×32 灰度 → 二维 DCT-II → 左上 8×8 低频（去 DC）
 *   → 与中位数比较得 64bit；汉明距离 ≤4 判为"疑似重复"。
 * - 补算：download_history 中 phash 为 NULL 的记录，每轮最多 [LIMIT] 条，
 *   10 分钟节流（与 PublishedAtBackfiller 同风格），随媒体库 ON_RESUME 触发。
 * - 视频抽帧用 MediaMetadataRetriever（1s 处 SYNC 帧），图片直接 BitmapFactory 解码。
 * - 纯 CPU 计算（旗舰机单帧 <50ms），无需 NPU——CLIP 视觉语义另轮落地。
 */
object PhashService {

    private const val PREFS = "phash_service"
    private const val KEY_LAST_RUN = "last_run"
    private const val THROTTLE_MS = 10 * 60_000L
    private const val LIMIT = 10
    private const val SIZE = 32
    private const val LOW_FREQ = 8
    /** 汉明距离阈值：≤4 判疑似重复（pHash 惯例 0-5，取保守值）。 */
    const val HAMMING_THRESHOLD = 4

    /** 补算一轮缺失的 phash（内部 10 分钟节流）。@return 本轮实际补算条数。 */
    suspend fun backfillBatch(context: Context, limit: Int = LIMIT): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_RUN, 0L) < THROTTLE_MS) return@withContext 0
        prefs.edit().putLong(KEY_LAST_RUN, now).apply()

        val dao = AppDatabase.getInstance(context).downloadHistoryDao()
        val candidates = dao.getMissingPhash(limit)
        var updated = 0
        candidates.forEach { entity ->
            val file = File(entity.filePath)
            if (!file.exists()) return@forEach
            val hash = runCatching { computePhash(file) }.getOrNull() ?: return@forEach
            if (hash != 0L) {
                dao.setPhash(entity.filePath, hash)
                updated++
            }
        }
        updated
    }

    /** 计算单文件 pHash；视频抽 1s 处 SYNC 帧，图片直接解码。失败/空帧返回 0L（不入库）。 */
    fun computePhash(file: File): Long {
        val bitmap = if (MediaFileTypes.isImageFile(file.absolutePath)) {
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)
            }.getOrNull()
        } else {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        } ?: return 0L

        return runCatching {
            bitmapToPhash(bitmap)
        }.getOrDefault(0L)
    }

    /** [bitmapToPhash] 核心：缩放 32×32 → 灰度 → 行列 DCT → 8×8 低频中位数比较。 */
    internal fun bitmapToPhash(source: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val gray = DoubleArray(SIZE * SIZE) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (0.299 * r + 0.587 * g + 0.114 * b)
        }

        // 二维 DCT（行列可分离）
        val rows = Array(SIZE) { y -> dct1d(DoubleArray(SIZE) { x -> gray[y * SIZE + x] }) }
        val cols = Array(SIZE) { x -> DoubleArray(SIZE) { y -> rows[y][x] } }
        val dct = Array(SIZE) { y -> dct1d(cols[y]) }

        // 左上 8×8 低频（去 DC[0][0]），与中位数比较 → 64bit
        val low = mutableListOf<Double>()
        for (y in 0 until LOW_FREQ) {
            for (x in 0 until LOW_FREQ) {
                if (x == 0 && y == 0) continue
                low += dct[y][x]
            }
        }
        val sorted = low.sorted()
        val median = (sorted[sorted.size / 2] + sorted[(sorted.size - 1) / 2]) / 2.0

        var hash = 0L
        low.forEachIndexed { i, value ->
            if (value > median) hash = hash or (1L shl i)
        }
        return hash
    }

    /** 汉明距离（疑似重复判定）。 */
    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun dct1d(input: DoubleArray): DoubleArray {
        val n = input.size
        val out = DoubleArray(n)
        val factor = Math.PI / (2.0 * n)
        for (k in 0 until n) {
            var sum = 0.0
            for (x in 0 until n) {
                sum += input[x] * cos(factor * (2 * x + 1) * k)
            }
            out[k] = sum * (if (k == 0) sqrt(1.0 / n) else sqrt(2.0 / n))
        }
        return out
    }
}
