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
    }
}
