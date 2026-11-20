package com.ed.edqiu.data.db

import java.nio.file.Files
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdqiuMigrationSqlTest {

    @Test
    fun migrationPreservesRowsAndAddsRetryColumns() {
        val dbFile = Files.createTempFile("edqiu-migration-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(CREATE_VERSION_1)
                    statement.execute(
                        """
                        INSERT INTO saved_links (
                            tweetId, raw_url, saved_at, status
                        ) VALUES (
                            '1234567890123456789',
                            'https://x.com/i/status/1234567890123456789',
                            1000,
                            'PENDING'
                        )
                        """.trimIndent()
                    )
                    EdqiuDatabase.MIGRATION_1_2_SQL.forEach(statement::execute)
                }

                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT tweetId, attempt_count, last_attempt_at, last_error, next_retry_at
                        FROM saved_links
                        """.trimIndent()
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("1234567890123456789", result.getString("tweetId"))
                        assertEquals(0, result.getInt("attempt_count"))
                        assertNull(result.getObject("last_attempt_at"))
                        assertNull(result.getObject("last_error"))
                        assertNull(result.getObject("next_retry_at"))
                    }
                }

                val columns = mutableSetOf<String>()
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(saved_links)").use { result ->
                        while (result.next()) columns += result.getString("name")
                    }
                }
                assertTrue(
                    columns.containsAll(
                        setOf(
                            "attempt_count",
                            "last_attempt_at",
                            "last_error",
                            "next_retry_at"
                        )
                    )
                )
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    @Test
    fun migrationAddsAvatarColumnsToActiveLinksAndHistory() {
        val dbFile = Files.createTempFile("edqiu-avatar-migration-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(CREATE_VERSION_1)
                    EdqiuDatabase.MIGRATION_1_2_SQL.forEach(statement::execute)
                    EdqiuDatabase.MIGRATION_2_3_SQL.forEach(statement::execute)
                    EdqiuDatabase.MIGRATION_3_4_SQL.forEach(statement::execute)
                }

                assertTrue(readColumns(connectionUrl = "jdbc:sqlite:${dbFile.toAbsolutePath()}", table = "saved_links").contains("avatar_url"))
                assertTrue(readColumns(connectionUrl = "jdbc:sqlite:${dbFile.toAbsolutePath()}", table = "deleted_link_history").contains("avatar_url"))
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    /** 2026-11-21 v12：deleted_link_history 补 origin 列——回收站快照保留来源线，
     *  订阅作品删除→恢复后归位订阅页（双线隔离），存量快照默认 user。 */
    @Test
    fun migrationAddsOriginToDeletedLinkHistory() {
        val dbFile = Files.createTempFile("edqiu-deleted-origin-migration-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(CREATE_VERSION_11_DELETED_HISTORY)
                    statement.execute(
                        """
                        INSERT INTO deleted_link_history (
                            archive_id, tweet_id, raw_url, saved_at, status, attempt_count, deleted_at, deletion_reason
                        ) VALUES (
                            'arch-1',
                            '1234567890123456789',
                            'https://x.com/i/status/1234567890123456789',
                            1000,
                            'DOWNLOADED',
                            0,
                            2000,
                            'user-delete'
                        )
                        """.trimIndent()
                    )
                    EdqiuDatabase.MIGRATION_11_12_SQL.forEach(statement::execute)
                }

                val columns = mutableSetOf<String>()
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(deleted_link_history)").use { result ->
                        while (result.next()) columns += result.getString("name")
                    }
                }
                assertTrue("origin 列应存在", columns.contains("origin"))

                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT origin FROM deleted_link_history WHERE archive_id = 'arch-1'"
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("user", result.getString("origin"))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    /** 2026-10 v9：saved_links 补 (status, next_retry_at) 复合索引，重试轮询不再全表扫描。 */
    @Test
    fun migrationAddsRetryIndexToSavedLinks() {
        val dbFile = Files.createTempFile("edqiu-retry-index-migration-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(CREATE_VERSION_1)
                    EdqiuDatabase.MIGRATION_1_2_SQL.forEach(statement::execute)
                    EdqiuDatabase.MIGRATION_8_9_SQL.forEach(statement::execute)

                    statement.execute(
                        """
                        INSERT INTO saved_links (tweetId, raw_url, saved_at, status, attempt_count, next_retry_at)
                        VALUES ('1234567890123456789', 'https://x.com/i/status/1234567890123456789', 1000, 'FAILED', 1, 2000)
                        """.trimIndent()
                    )
                }

                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_saved_links_status_next_retry_at'"
                    ).use { result ->
                        assertTrue(result.next())
                    }
                }
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    /** 2026-10 v8：回收站表补 published_at，恢复链接不再丢失发布时间。 */
    @Test
    fun migrationAddsPublishedAtToDeletedLinkHistory() {
        val dbFile = Files.createTempFile("edqiu-published-migration-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(CREATE_VERSION_1)
                    EdqiuDatabase.MIGRATION_1_2_SQL.forEach(statement::execute)
                    EdqiuDatabase.MIGRATION_2_3_SQL.forEach(statement::execute)
                    EdqiuDatabase.MIGRATION_7_8_SQL.forEach(statement::execute)

                    statement.execute(
                        """
                        INSERT INTO deleted_link_history (
                            archive_id, tweet_id, raw_url, saved_at, status,
                            attempt_count, deleted_at, deletion_reason, published_at
                        ) VALUES (
                            'archive-1', '1234567890123456789',
                            'https://x.com/i/status/1234567890123456789',
                            1000, 'PENDING', 0, 2000, 'user', 1500
                        )
                        """.trimIndent()
                    )
                }

                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT published_at FROM deleted_link_history WHERE archive_id = 'archive-1'"
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1500L, result.getLong("published_at"))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    private fun readColumns(connectionUrl: String, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        DriverManager.getConnection(connectionUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($table)").use { result ->
                    while (result.next()) columns += result.getString("name")
                }
            }
        }
        return columns
    }

    private companion object {
        const val CREATE_VERSION_1 =
            """
            CREATE TABLE IF NOT EXISTS saved_links (
                tweetId TEXT NOT NULL,
                raw_url TEXT NOT NULL,
                author_id TEXT,
                author_name TEXT,
                caption TEXT,
                thumbnail_url TEXT,
                saved_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                file_path TEXT,
                downloaded_at INTEGER,
                PRIMARY KEY(tweetId)
            )
            """

        /** v11 形态的回收站快照表（无 origin 列，MIGRATION_11_12_SQL 的迁移起点）。 */
        const val CREATE_VERSION_11_DELETED_HISTORY =
            """
            CREATE TABLE IF NOT EXISTS deleted_link_history (
                archive_id TEXT NOT NULL,
                tweet_id TEXT NOT NULL,
                raw_url TEXT NOT NULL,
                author_id TEXT,
                author_name TEXT,
                caption TEXT,
                thumbnail_url TEXT,
                avatar_url TEXT,
                author_bio TEXT,
                published_at INTEGER,
                saved_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                file_path TEXT,
                downloaded_at INTEGER,
                attempt_count INTEGER NOT NULL,
                last_attempt_at INTEGER,
                last_error TEXT,
                next_retry_at INTEGER,
                deleted_at INTEGER NOT NULL,
                deletion_reason TEXT NOT NULL,
                PRIMARY KEY(archive_id)
            )
            """
    }
}
