package com.ed.twitterdownloader.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ed.twitterdownloader.data.model.MediaType

@Database(
    entities = [DownloadHistoryEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadHistoryDao(): DownloadHistoryDao

    class Converters {
        @TypeConverter
        fun mediaTypeToString(value: MediaType): String = value.name

        @TypeConverter
        fun stringToMediaType(value: String?): MediaType = runCatching {
            MediaType.valueOf(value ?: MediaType.VIDEO.name)
        }.getOrDefault(MediaType.VIDEO)
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                ensureDownloadHistoryColumns(database)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                ensureDownloadHistoryColumns(database)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                ensureDownloadHistoryColumns(database)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "twitter_downloader.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { INSTANCE = it }
            }
        }

        private fun ensureDownloadHistoryColumns(database: SupportSQLiteDatabase) {
            val existingColumns = mutableSetOf<String>()
            database.query("PRAGMA table_info(download_history)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0) existingColumns += cursor.getString(nameIndex)
                }
            }

            if ("mediaIndex" !in existingColumns) {
                database.execSQL("ALTER TABLE download_history ADD COLUMN mediaIndex INTEGER")
            }
            if ("fileSize" !in existingColumns) {
                database.execSQL("ALTER TABLE download_history ADD COLUMN fileSize INTEGER NOT NULL DEFAULT 0")
            }
            if ("duration" !in existingColumns) {
                database.execSQL("ALTER TABLE download_history ADD COLUMN duration INTEGER NOT NULL DEFAULT 0")
            }
            if ("mediaType" !in existingColumns) {
                database.execSQL("ALTER TABLE download_history ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'VIDEO'")
            }
        }
    }
}
