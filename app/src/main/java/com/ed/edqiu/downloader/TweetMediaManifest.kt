package com.ed.edqiu.downloader

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** 私有任务清单：预期媒体先落盘，再逐项提交成品，扫描不得用单个文件冒充整帖完成。 */
internal class TweetMediaManifest(private val directory: File) {
    enum class State { UNKNOWN, INCOMPLETE, COMPLETE }

    fun begin(tweetId: String, keys: List<String>) = synchronized(lock) {
        require(keys.isNotEmpty())
        val previous = read(tweetId)
        val props = Properties()
        props.setProperty("count", keys.size.toString())
        keys.forEachIndexed { index, key ->
            props.setProperty("key.$index", key)
            if (previous?.getProperty("key.$index") == key) {
                validFile(previous, index)?.let { file -> putFile(props, index, file) }
            }
        }
        save(tweetId, props)
    }

    fun completedFile(tweetId: String, index: Int): File? = synchronized(lock) {
        read(tweetId)?.let { validFile(it, index) }
    }

    fun record(tweetId: String, index: Int, file: File, expectedKey: String? = null) = synchronized(lock) {
        require(file.isFile && file.length() > 0L)
        val props = requireNotNull(read(tweetId)) { "缺少媒体清单" }
        require(index in 0 until props.getProperty("count").toInt())
        // 2026-11-21 审计修复：bgimg 补图按原序号回写时校验清单键——键不匹配
        // （旧会话清单/媒体构成已变）绝不写，防把图片记录成视频项导致假完成
        if (expectedKey != null && props.getProperty("key.$index") != expectedKey) return@synchronized
        putFile(props, index, file)
        save(tweetId, props)
    }

    fun finish(tweetId: String) = synchronized(lock) {
        val props = requireNotNull(read(tweetId))
        val count = props.getProperty("count").toInt()
        check((0 until count).all { validFile(props, it) != null }) { "仍有媒体未完成" }
        props.setProperty("finished", "true")
        save(tweetId, props)
    }

    fun state(tweetId: String): State = synchronized(lock) {
        if (!file(tweetId).exists()) return@synchronized State.UNKNOWN
        val props = read(tweetId) ?: return@synchronized State.INCOMPLETE
        // 成功后的用户删文件不自动复活；已完成记录继续由原有目录/媒体库语义处理。
        if (props.getProperty("finished") == "true") return@synchronized State.COMPLETE
        val count = props.getProperty("count")?.toIntOrNull() ?: 0
        if (count > 0 && (0 until count).all { validFile(props, it) != null }) State.COMPLETE
        else State.INCOMPLETE
    }

    private fun putFile(props: Properties, index: Int, file: File) {
        props.setProperty("path.$index", file.absolutePath)
        props.setProperty("size.$index", file.length().toString())
    }

    private fun validFile(props: Properties, index: Int): File? {
        val path = props.getProperty("path.$index") ?: return null
        val size = props.getProperty("size.$index")?.toLongOrNull() ?: return null
        return File(path).takeIf { size > 0L && it.isFile && it.length() == size }
    }

    private fun file(tweetId: String): File {
        require(tweetId.matches(Regex("[0-9]+")))
        return File(directory, "$tweetId.properties")
    }

    private fun read(tweetId: String): Properties? = runCatching {
        Properties().apply { file(tweetId).inputStream().use { load(it) } }
    }.getOrNull()

    private fun save(tweetId: String, props: Properties) {
        check(directory.isDirectory || directory.mkdirs()) { "无法保存媒体任务清单" }
        val temp = File.createTempFile("manifest-", ".tmp", directory)
        try {
            temp.outputStream().use { props.store(it, null) }
            Files.move(temp.toPath(), file(tweetId).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    companion object { private val lock = Any() }
}
