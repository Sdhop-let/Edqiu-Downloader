package com.ed.edqiu.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdqiuMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migration1To2PreservesDataAndAddsRetryColumns() {
        openDatabase(
            version = 1,
            onCreate = { db ->
                db.execSQL(CREATE_VERSION_1)
                db.execSQL(
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
            }
        ).close()

        val helper = openDatabase(
            version = 2,
            onCreate = { error("Version 1 database should already exist") },
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(1, oldVersion)
                assertEquals(2, newVersion)
                EdqiuDatabase.MIGRATION_1_2.migrate(db)
            }
        )
        val db = helper.writableDatabase

        db.query(
            """
            SELECT tweetId, attempt_count, last_attempt_at, last_error, next_retry_at
            FROM saved_links
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("1234567890123456789", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
            assertNull(cursor.getString(4))
        }

        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(saved_links)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        assertTrue(
            columns.containsAll(
                setOf("attempt_count", "last_attempt_at", "last_error", "next_retry_at")
            )
        )

        helper.close()
    }

    private fun openDatabase(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit,
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> }
    ): SupportSQLiteOpenHelper {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = onUpgrade(db, oldVersion, newVersion)
                }
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).also {
            it.writableDatabase
        }
    }

    private companion object {
        const val DB_NAME = "edqiu-migration-test.db"
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
