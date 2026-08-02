package com.ed.edqiu.backup.upload

import android.content.Context
import com.ed.edqiu.backup.model.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 通用分片上传框架。
 *
 * - [computeSha1]：整文件 SHA-1（阿里秒传用，流式读取防 OOM）。
 * - [split]：按分片大小切分，返回 [Chunk] 列表（分片策略见 [ChunkSizePolicy]）。
 * - [uploadChunks]：逐个分片上传，跳过已确认分片（断点续传），按字节累计上报进度。
 *
 * 断点续传约定：分片收据（md5/etag）写入调用方传入的 [resumeState]（seq → 收据），
 * 由调用方通过 [ChunkReceiptStore] 落盘（`filesDir/backup_chunks/{taskId}.json`）；
 * 重试时先 `load` 恢复状态，已确认分片直接跳过，只有最后一步（百度 create / 阿里 complete）是原子操作。
 */
class ChunkedUploader {

    /** 计算整文件 SHA-1（十六进制小写）。 */
    suspend fun computeSha1(file: File): String = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrElse { throw BackupException("计算文件校验失败：${it.message ?: "未知错误"}", it) }
    }

    /**
     * 按 [chunkSize] 切分文件。
     *
     * - 空文件（size == 0）返回单个空分片，便于调用方统一处理；
     * - 分片数 = ceil(size / chunkSize)，末片长度自动截断。
     */
    fun split(file: File, chunkSize: Long): List<Chunk> {
        require(chunkSize > 0) { "分片大小必须大于 0" }
        val size = file.length()
        if (size == 0L) {
            return listOf(Chunk(seq = 0, offset = 0L, length = 0L, file = file))
        }
        val chunkCount = ((size - 1) / chunkSize + 1).toInt()
        return (0 until chunkCount).map { seq ->
            val offset = seq.toLong() * chunkSize
            val length = minOf(chunkSize, size - offset)
            Chunk(seq = seq, offset = offset, length = length, file = file)
        }
    }

    /**
     * 逐分片上传。
     *
     * @param chunks      分片列表（[split] 产物）
     * @param uploader    单分片上传函数，成功返回该分片收据（md5/etag），失败返回 [Result.failure]
     * @param resumeState 断点续传状态（seq → 收据）；已存在的收据直接跳过该分片；成功后原地写入
     * @param progress    按累计已上传字节数回调
     */
    suspend fun uploadChunks(
        chunks: List<Chunk>,
        uploader: suspend (Chunk) -> Result<String>,
        resumeState: MutableMap<Int, String>,
        progress: (Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            var uploadedBytes = 0L
            chunks.forEach { chunk ->
                val existingReceipt = resumeState[chunk.seq]
                if (!existingReceipt.isNullOrBlank()) {
                    // 上一轮已确认上传成功，跳过
                    uploadedBytes += chunk.length
                    progress(uploadedBytes)
                    return@forEach
                }
                val receipt = uploader(chunk).getOrElse { error ->
                    throw BackupException("分片 ${chunk.seq + 1}/${chunks.size} 上传失败：${error.message ?: "未知错误"}", error)
                }
                resumeState[chunk.seq] = receipt
                uploadedBytes += chunk.length
                progress(uploadedBytes)
            }
            Unit
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}

/** 单个分片：按 [seq] 排序，[offset] 起始偏移，[length] 字节数。 */
data class Chunk(
    val seq: Int,
    val offset: Long,
    val length: Long,
    val file: File,
) {
    /** 惰性读取分片字节（分片大小 ≤ 32MB，安全转 Int）。 */
    fun readBytes(): ByteArray {
        if (length == 0L) return ByteArray(0)
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(length.toInt())
            raf.readFully(buffer)
            buffer
        }
    }
}

/**
 * 分片大小策略（可插拔）。
 *
 * - 百度：[BaiduChunkSizePolicy]（默认 4MB，>4GB 自动升 32MB，PCS 分片数上限约 1024）
 * - 阿里：[AliyunChunkSizePolicy]（10MB，上限 10000 片覆盖约 97GB）
 * - WebDAV 族：不分片（整文件 PUT），如需走本框架可用 [NoChunkSizePolicy]（整文件单分片）
 */
fun interface ChunkSizePolicy {
    /** 返回适合该文件大小的分片字节数。 */
    fun chunkSizeFor(fileSize: Long): Long
}

/** 固定分片大小策略。 */
class FixedChunkSizePolicy(private val chunkSize: Long) : ChunkSizePolicy {
    override fun chunkSizeFor(fileSize: Long): Long = chunkSize
}

/** 百度 PCS 策略：默认 4MB，>4GB 自动升 32MB。 */
object BaiduChunkSizePolicy : ChunkSizePolicy {
    private const val DEFAULT_CHUNK = 4L * 1024 * 1024
    private const val LARGE_CHUNK = 32L * 1024 * 1024
    private const val LARGE_FILE_THRESHOLD = 4L * 1024 * 1024 * 1024

    override fun chunkSizeFor(fileSize: Long): Long =
        if (fileSize > LARGE_FILE_THRESHOLD) LARGE_CHUNK else DEFAULT_CHUNK
}

/** 阿里 openapi 策略：固定 10MB。 */
object AliyunChunkSizePolicy : ChunkSizePolicy {
    private const val CHUNK = 10L * 1024 * 1024

    override fun chunkSizeFor(fileSize: Long): Long = CHUNK
}

/** 不分片策略：整文件作为单个分片（WebDAV 族语义）。 */
object NoChunkSizePolicy : ChunkSizePolicy {
    override fun chunkSizeFor(fileSize: Long): Long = fileSize.coerceAtLeast(1L)
}

/**
 * 分片收据落盘存储（`filesDir/backup_chunks/{taskId}.json`）。
 *
 * 用 Mutex 串行化读写，防止并发覆盖；taskId 为 sha256 十六进制，可安全用作文件名。
 */
class ChunkReceiptStore(private val context: Context) {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** 保存某任务的全部分片收据（覆盖式）。 */
    suspend fun save(taskId: String, receipts: Map<Int, String>): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                require(taskId.isNotBlank()) { "taskId 不能为空" }
                val data = ChunkReceiptsFile(
                    taskId = taskId,
                    receipts = receipts,
                    updatedAt = System.currentTimeMillis(),
                )
                receiptFile(taskId).writeText(json.encodeToString(ChunkReceiptsFile.serializer(), data))
            }
        }
    }

    /** 读取某任务的已上传分片收据；不存在或解析失败返回空 Map。 */
    suspend fun load(taskId: String): Map<Int, String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = receiptFile(taskId)
                if (!file.exists()) {
                    return@withLock emptyMap()
                }
                json.decodeFromString(ChunkReceiptsFile.serializer(), file.readText()).receipts
            }.getOrDefault(emptyMap())
        }
    }

    /** 删除某任务的收据文件（上传完成后清理）。 */
    suspend fun delete(taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = receiptFile(taskId)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    private fun receiptFile(taskId: String): File =
        File(receiptsDir(), "$taskId.json")

    private fun receiptsDir(): File =
        File(context.filesDir, "backup_chunks").apply { if (!exists()) mkdirs() }
}

/** 收据文件结构（kotlinx-serialization 落盘）。 */
@Serializable
private data class ChunkReceiptsFile(
    val taskId: String,
    val receipts: Map<Int, String> = emptyMap(),
    val updatedAt: Long = 0L,
)
