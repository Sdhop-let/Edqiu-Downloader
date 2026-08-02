package com.ed.edqiu.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ed.edqiu.data.model.DeletedLinkHistory
import com.ed.edqiu.data.model.SavedLink

@Database(
    entities = [SavedLink::class, DeletedLinkHistory::class],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class EdqiuDatabase : RoomDatabase() {

    abstract fun savedLinkDao(): SavedLinkDao

    abstract fun deletedLinkHistoryDao(): DeletedLinkHistoryDao

    companion object {
        private const val DB_NAME = "xinvox.db"
        private val lock = Any()
        @Volatile
        private var INSTANCE: EdqiuDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_1_2_SQL = listOf(
            "ALTER TABLE saved_links ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE saved_links ADD COLUMN last_attempt_at INTEGER DEFAULT NULL",
            "ALTER TABLE saved_links ADD COLUMN last_error TEXT DEFAULT NULL",
            "ALTER TABLE saved_links ADD COLUMN next_retry_at INTEGER DEFAULT NULL"
        )

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_2_3_SQL = listOf(
            """
            CREATE TABLE IF NOT EXISTS deleted_link_history (
                archive_id TEXT NOT NULL,
                tweet_id TEXT NOT NULL,
                raw_url TEXT NOT NULL,
                author_id TEXT,
                author_name TEXT,
                caption TEXT,
                thumbnail_url TEXT,
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
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_deleted_link_history_tweet_id ON deleted_link_history(tweet_id)",
            "CREATE INDEX IF NOT EXISTS index_deleted_link_history_deleted_at ON deleted_link_history(deleted_at)"
        )

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_3_4_SQL = listOf(
            "ALTER TABLE saved_links ADD COLUMN avatar_url TEXT DEFAULT NULL",
            "ALTER TABLE deleted_link_history ADD COLUMN avatar_url TEXT DEFAULT NULL"
        )

        fun getDatabase(context: Context): EdqiuDatabase {
            return INSTANCE ?: synchronized(lock) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EdqiuDatabase::class.java,
                    DB_NAME
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
