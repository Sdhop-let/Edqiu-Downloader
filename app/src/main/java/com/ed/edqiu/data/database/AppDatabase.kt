package com.ed.edqiu.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ed.edqiu.data.model.MediaType

@Database(
    entities = [DownloadHistoryEntity::class, DownloadTaskEntity::class, SubscriptionEntity::class],
    version = 8,
    exportSchema = false
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadHistoryDao(): DownloadHistoryDao

    abstract fun downloadTaskDao(): DownloadTaskDao

    abstract fun subscriptionDao(): SubscriptionDao

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_tasks` (
                        `id` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `thumbnail` TEXT NOT NULL,
                        `uploader` TEXT NOT NULL,
                        `formatId` TEXT NOT NULL,
                        `quality` TEXT NOT NULL,
                        `ext` TEXT NOT NULL,
                        `mediaType` TEXT NOT NULL,
                        `mediaIndex` INTEGER,
                        `progress` REAL NOT NULL,
                        `etaSeconds` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `outputPath` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `downloaderType` TEXT NOT NULL,
                        `isCancelled` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 旧版 download_history 混入了作者残留列（authorName/authorAvatar），
                // 与当前实体列集不符，Room 会因 schema 不一致直接崩溃。
                // 重建为标准列集并保留原有数据，同时补建 download_tasks 表。
                database.execSQL("ALTER TABLE `download_history` RENAME TO `download_history_old`")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_history` (
                        `id` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `thumbnail` TEXT NOT NULL,
                        `uploader` TEXT NOT NULL,
                        `quality` TEXT NOT NULL,
                        `mediaIndex` INTEGER,
                        `mediaType` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `completedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `download_history`
                        (`id`,`url`,`title`,`thumbnail`,`uploader`,`quality`,`mediaIndex`,`mediaType`,`filePath`,`fileSize`,`duration`,`createdAt`,`completedAt`)
                    SELECT
                        `id`,`url`,`title`,`thumbnail`,`uploader`,`quality`,`mediaIndex`,`mediaType`,`filePath`,`fileSize`,`duration`,`createdAt`,`completedAt`
                    FROM `download_history_old`
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `download_history_old`")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_tasks` (
                        `id` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `thumbnail` TEXT NOT NULL,
                        `uploader` TEXT NOT NULL,
                        `formatId` TEXT NOT NULL,
                        `quality` TEXT NOT NULL,
                        `ext` TEXT NOT NULL,
                        `mediaType` TEXT NOT NULL,
                        `mediaIndex` INTEGER,
                        `progress` REAL NOT NULL,
                        `etaSeconds` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `outputPath` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `downloaderType` TEXT NOT NULL,
                        `isCancelled` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                
            }
        }

        /** 2026-09-15：download_history 加推文发布时间（媒体库按发布时间排序，无值垫底）。 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE download_history ADD COLUMN publishedAt INTEGER")
            }
        }

        /**
         * 2026-09-15 v2 批次3/4：
         * ① download_history 加感知哈希 phash（64bit DCT pHash，重复媒体检测）；
         * ② 新建 subscriptions 表（作者订阅自动下载）。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE download_history ADD COLUMN phash INTEGER")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `subscriptions` (
                        `screenName` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `lastCheckedAt` INTEGER,
                        `lastVideoAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`screenName`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "twitter_downloader.db"
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
                )
                .fallbackToDestructiveMigration()
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
