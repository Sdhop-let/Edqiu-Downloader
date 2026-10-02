package com.ed.edqiu.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.ed.edqiu.data.model.MediaFileTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 2026-10 整改：全分辨率抽帧 Bitmap 原为无上限 SnapshotStateMap，大媒体库（数百视频）
// 直接推高内存至 OOM。改为 LruCache（48MB 上限，按 bitmap.byteCount 计量）承载实际位图；
// frameCachePresence 作为可观察的"存在索引"驱动重组（LruCache 本身不可观察），
// LruCache 淘汰时在 entryRemoved 回调同步移除索引，索引规模与缓存条目一致。
private const val FRAME_CACHE_MAX_BYTES = 48 * 1024 * 1024

private val frameLruCache = object : androidx.collection.LruCache<String, Bitmap>(FRAME_CACHE_MAX_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
        if (evicted) frameCachePresence.remove(key)
    }
}
private val frameCachePresence = mutableStateMapOf<String, Unit>()
// 抽帧失败的 key 集合：IO 线程写 / 主线程读，改为并发集合（旧 HashSet 存在并发修改风险）
private val failedLocalFrameKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

/**
 * Shared thumbnail component with stable local media behavior.
 *
 * For local videos it extracts a deterministic frame. For local images it renders the file directly.
 * This avoids media-library items flashing from remote covers into local previews during list recycling.
 */
@Composable
fun ThumbnailWithFallback(
    thumbnailUrl: String,
    videoFilePath: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    frameOffsetSeconds: Float = 1f,
    preferLocalFrame: Boolean = videoFilePath != null
) {
    val remoteLoadFailed = remember(thumbnailUrl) { mutableStateOf(thumbnailUrl.isBlank()) }
    val localFile = remember(videoFilePath) { videoFilePath?.let(::File) }
    val hasLocalFile = localFile?.exists() == true
    val isLocalImage = hasLocalFile && MediaFileTypes.isImageFile(localFile!!.absolutePath)
    val shouldUseLocalFrame = preferLocalFrame && hasLocalFile && !isLocalImage
    val cacheKey = remember(videoFilePath, frameOffsetSeconds) {
        if (videoFilePath == null) "" else "$videoFilePath#${frameOffsetSeconds}"
    }

    LaunchedEffect(shouldUseLocalFrame, cacheKey) {
        if (
            shouldUseLocalFrame &&
            cacheKey.isNotBlank() &&
            frameCachePresence[cacheKey] == null &&
            !failedLocalFrameKeys.contains(cacheKey)
        ) {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(localFile!!.absolutePath)
                        retriever.getFrameAtTime(
                            (frameOffsetSeconds * 1_000_000).toLong(),
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            }

            if (bitmap != null) {
                frameLruCache.put(cacheKey, bitmap)
                frameCachePresence[cacheKey] = Unit
            } else {
                failedLocalFrameKeys += cacheKey
            }
        }
    }

    val cachedLocalFrame = if (cacheKey.isNotBlank() && frameCachePresence.containsKey(cacheKey)) {
        frameLruCache.get(cacheKey)
    } else null
    when {
        isLocalImage -> {
            AsyncImage(
                model = localFile,
                contentDescription = null,
                modifier = modifier,
                contentScale = contentScale,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        remoteLoadFailed.value = true
                    }
                }
            )
        }
        cachedLocalFrame != null -> {
            Image(
                bitmap = cachedLocalFrame.asImageBitmap(),
                contentDescription = null,
                modifier = modifier,
                contentScale = contentScale
            )
        }
        shouldUseLocalFrame && !failedLocalFrameKeys.contains(cacheKey) -> {
            PlaceholderThumbnail(modifier = modifier)
        }
        !remoteLoadFailed.value -> {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                modifier = modifier,
                contentScale = contentScale,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        remoteLoadFailed.value = true
                    }
                }
            )
        }
        else -> PlaceholderThumbnail(
            modifier = modifier,
            icon = if (isLocalImage || MediaFileTypes.isImageFile(thumbnailUrl)) Icons.Outlined.Image else Icons.Outlined.PlayCircle
        )
    }
}

@Composable
fun PlaceholderThumbnail(
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
    icon: ImageVector = Icons.Outlined.PlayCircle
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
