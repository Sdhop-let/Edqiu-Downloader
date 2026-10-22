package com.ed.edqiu.ui.components

import android.content.Context
import android.app.WallpaperManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统壁纸加载器（2026-10-22「舞台方案」落地：玻璃折射真实照片）。
 *
 * Apple/网易云级玻璃观感的第一杠杆 = 玻璃背后有真实内容与高频细节。
 * 程序化色场无论怎么调都是平滑渐变，磨砂/折射采到的永远是均匀色；
 * 壁纸是用户个性化、全场唯一、自动更新的真实图像源——虚化铺底后，
 * 卡片玻璃与底栏的磨砂第一次作用于真实像素。
 *
 * 设计要点：
 * - **无权限**：WallpaperManager 是公开 API，读取自身壁纸不需要任何授权
 * - **降采样 1/2**：1272×2772 全尺寸 ARGB ≈ 14MB，半尺寸 ≈ 3.5MB，
 *   后续还要过 28dp 高斯虚化，细节损失不可感知
 * - **进程级缓存**：壁纸变更属低频事件，v1 先不做动态监听（杀进程重启即刷新）；
 * - **失败降级**：fastDrawable/drawable 为 null 或异常时返回 null，
 *   GlassBackground 落回程序化色场，观感无缝回退
 */
object WallpaperProvider {

    @Volatile
    private var cached: ImageBitmap? = null

    /** 当前缓存的壁纸（可空 = 尚未加载或无壁纸）。 */
    val current: ImageBitmap? get() = cached

    /**
     * 异步加载系统壁纸（IO 线程，binder + decode 不阻塞重组）。
     * 同进程重复调用直接命中缓存。
     */
    suspend fun load(context: Context): ImageBitmap? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        runCatching {
            val wm = WallpaperManager.getInstance(context)
            val drawable = wm.fastDrawable ?: wm.drawable ?: return@runCatching null
            val dm = context.resources.displayMetrics
            // 半尺寸降采样：虚化后细节无损，内存 14MB → 3.5MB
            val w = (dm.widthPixels / 2).coerceAtLeast(1)
            val h = (dm.heightPixels / 2).coerceAtLeast(1)
            drawable.toBitmap(w, h).asImageBitmap().also { cached = it }
        }.getOrNull()
    }
}
