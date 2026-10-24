package com.ed.edqiu.ui.components

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * - **热更新（2026-10-24）**：[registerChangeListener] 监听系统壁纸变更
 *   （ACTION_WALLPAPER_CHANGED 广播 + OnColorsChangedListener 兜底），
 *   变更即失效缓存并递增 [version]，GlassBackground 采集版本号强制重载——
 *   换壁纸不再需要杀进程
 * - **失败降级**：fastDrawable/drawable 为 null 或异常时返回 null，
 *   GlassBackground 落回程序化色场，观感无缝回退
 */
object WallpaperProvider {

    private const val TAG = "WallpaperProvider"

    @Volatile
    private var cached: ImageBitmap? = null

    /** 壁纸数据版本：每次变更失效递增，GlassBackground 采集它触发重载。 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** 当前缓存的壁纸（可空 = 尚未加载或无壁纸）。 */
    val current: ImageBitmap? get() = cached

    private var lastInvalidateAtMs = 0L

    /**
     * 异步加载系统壁纸（IO 线程，binder + decode 不阻塞重组）。
     * [force] = false 时重复调用直接命中缓存；true 跳过缓存重新解码
     * （壁纸变更后的重载路径）。
     */
    suspend fun load(context: Context, force: Boolean = false): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (!force) cached?.let { return@withContext it }
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

    /**
     * 失效缓存并递增版本（壁纸变更回调入口）。500ms 去重：
     * 同一次换壁纸，广播与 OnColorsChangedListener 可能先后各触发一次。
     */
    fun invalidate() {
        val now = SystemClock.uptimeMillis()
        synchronized(this) {
            if (now - lastInvalidateAtMs < 500L) return
            lastInvalidateAtMs = now
        }
        cached = null
        _version.value += 1
        android.util.Log.i(TAG, "壁纸变更：缓存已失效，version=${_version.value}")
    }

    /**
     * 注册壁纸变更监听（Application 级调用一次，进程存续期生效）：
     * - ACTION_WALLPAPER_CHANGED：WallpaperManagerService 标准系统广播；
     * - OnColorsChangedListener（API 27+）：兜底不走标准广播的 OEM 换肤路径。
     * 二者最终都走 [invalidate]。
     */
    fun registerChangeListener(context: Context) {
        val appContext = context.applicationContext
        // Intent.ACTION_WALLPAPER_CHANGED 已标 deprecated 但广播仍由系统正常发出
        //（WallpaperManagerService 每次 set 都发），用字面量注册
        val wallpaperChangedAction = "android.intent.action.WALLPAPER_CHANGED"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == wallpaperChangedAction) invalidate()
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(wallpaperChangedAction),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= 27) {
            runCatching {
                WallpaperManager.getInstance(appContext).addOnColorsChangedListener(
                    { _, which ->
                        if (which and WallpaperManager.FLAG_SYSTEM != 0) invalidate()
                    },
                    Handler(Looper.getMainLooper())
                )
            }
        }
    }
}
