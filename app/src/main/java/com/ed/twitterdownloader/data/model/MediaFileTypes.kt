package com.ed.twitterdownloader.data.model

import java.io.File
import java.util.Locale

enum class MediaType {
    VIDEO,
    IMAGE
}

object MediaFileTypes {
    val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov")
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
    val supportedExtensions = videoExtensions + imageExtensions

    fun fromExtension(extension: String): MediaType? {
        val ext = extension.trim().removePrefix(".").lowercase(Locale.ROOT)
        return when {
            ext in videoExtensions -> MediaType.VIDEO
            ext in imageExtensions -> MediaType.IMAGE
            else -> null
        }
    }

    fun fromPath(path: String): MediaType? = fromExtension(File(path).extension)

    fun isVideoFile(path: String): Boolean = fromPath(path) == MediaType.VIDEO

    fun isImageFile(path: String): Boolean = fromPath(path) == MediaType.IMAGE

    fun isSupportedMediaFile(file: File): Boolean = file.isFile && fromExtension(file.extension) != null

    fun mimeTypeForExtension(extension: String): String {
        return when (extension.trim().removePrefix(".").lowercase(Locale.ROOT)) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    fun displayLabel(type: MediaType): String = when (type) {
        MediaType.VIDEO -> "瑙嗛"
        MediaType.IMAGE -> "鍥剧墖"
    }
}

