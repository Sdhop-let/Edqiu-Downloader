package com.ed.edqiu.data.backup

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackupCodec {
    private val canonicalJson = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }
    private val outputJson = Json(canonicalJson) {
        prettyPrint = true
    }

    fun encode(
        payload: BackupPayload,
        appVersion: String,
        databaseVersion: Int,
        createdAt: Long
    ): String {
        val envelope = BackupEnvelope(
            formatVersion = FORMAT_VERSION,
            appVersion = appVersion,
            databaseVersion = databaseVersion,
            createdAt = createdAt,
            payload = payload,
            checksumSha256 = checksum(payload)
        )
        return outputJson.encodeToString(envelope)
    }

    fun decodeAndValidate(text: String): ValidatedBackup {
        val envelope = runCatching {
            canonicalJson.decodeFromString<BackupEnvelope>(text)
        }.getOrElse { throw BackupValidationException("备份文件不是有效的 Edqiu JSON", it) }

        if (envelope.formatVersion != FORMAT_VERSION) {
            throw BackupValidationException("不支持的备份格式版本：${envelope.formatVersion}")
        }
        if (!envelope.checksumSha256.equals(checksum(envelope.payload), ignoreCase = true)) {
            throw BackupValidationException("备份校验失败，文件可能已损坏或被修改")
        }
        requireUnique(
            values = envelope.payload.activeLinks.map { it.tweetId },
            label = "收件箱记录 tweetId"
        )
        requireUnique(
            values = envelope.payload.deletedHistory.map { it.archiveId },
            label = "回收站 archiveId"
        )
        envelope.payload.activeLinks.forEach { link ->
            if (link.tweetId.isBlank() || link.rawUrl.isBlank() || link.savedAt < 0L) {
                throw BackupValidationException("备份包含无效收件箱记录")
            }
        }
        envelope.payload.deletedHistory.forEach { entry ->
            if (entry.archiveId.isBlank() || entry.deletedAt < 0L ||
                entry.link.tweetId.isBlank() || entry.link.rawUrl.isBlank()
            ) {
                throw BackupValidationException("备份包含无效回收站记录")
            }
        }

        return ValidatedBackup(
            envelope = envelope,
            preview = BackupPreview(
                createdAt = envelope.createdAt,
                appVersion = envelope.appVersion,
                activeCount = envelope.payload.activeLinks.size,
                historyCount = envelope.payload.deletedHistory.size
            )
        )
    }

    private fun checksum(payload: BackupPayload): String {
        val bytes = canonicalJson.encodeToString(payload).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun requireUnique(values: List<String>, label: String) {
        if (values.size != values.toSet().size) {
            throw BackupValidationException("备份中的 $label 重复")
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

class BackupValidationException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)
