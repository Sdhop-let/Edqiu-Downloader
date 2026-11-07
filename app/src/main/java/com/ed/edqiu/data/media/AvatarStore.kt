package com.ed.edqiu.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 我的页自定义头像落盘（2026-11-07）。套 CoverStore 目录与原子写模式：
 * 应用私有 avatars/ 目录（getExternalFilesDir 优先）+ .part 临时文件 + rename；
 * 相册 picker URI → 采样解码 ≤512px → JPEG 90 → 落盘成功才清理旧头像。
 */
object AvatarStore {
    private const val DIR_NAME = "avatars"
    private const val MAX_DIMEN = 512
    private val writeMutex = Mutex()

    fun avatarsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)

    /**
     * 从相册 picker URI 保存头像，返回新头像绝对路径；失败返回 null
     * （不触碰现有状态，调用方保持旧头像不变）。
     */
    suspend fun saveFromUri(context: Context, uri: Uri, previousPath: String?): String? =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                runCatching {
                    val resolver = context.contentResolver
                    val dir = avatarsDir(context).apply { mkdirs() }

                    // 先只读尺寸算 inSampleSize（大图直接整张解码会推高内存）。
                    // 2026-11-07 审计修复：inJustDecodeBounds=true 时 decodeStream 恒返回
                    // null（只写 bounds）——旧实现把"use 返回 null"当"流打不开"，任何有效
                    // 图片都会走 `?: return null`，头像保存 100% 失效；成败只看 bounds 尺寸
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching { resolver.openInputStream(uri) }.getOrNull()?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    } ?: return@runCatching null
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    var sample = 1
                    var w = bounds.outWidth
                    var h = bounds.outHeight
                    while (w / 2 >= MAX_DIMEN && h / 2 >= MAX_DIMEN) {
                        sample *= 2
                        w /= 2
                        h /= 2
                    }
                    val bitmap = resolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: return@runCatching null

                    val target = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
                    val tmp = File(dir, "${target.name}.part")
                    FileOutputStream(tmp).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        return@runCatching null
                    }
                    // 落盘成功才清理旧头像文件；DataStore 路径切换由调用方负责
                    deleteAvatarFile(previousPath)
                    target.absolutePath
                }.getOrNull()
            }
        }

    /** 恢复默认头像：删除自定义文件（DataStore 路径清空由调用方负责）。 */
    suspend fun deleteAvatar(path: String?) = withContext(Dispatchers.IO) {
        deleteAvatarFile(path)
    }

    private fun deleteAvatarFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            // 只删本目录内的文件，防误删外部路径
            if (file.exists() && file.parentFile?.name == DIR_NAME) file.delete()
        }
    }
}
