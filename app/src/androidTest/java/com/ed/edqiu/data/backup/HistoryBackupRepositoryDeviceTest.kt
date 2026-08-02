package com.ed.edqiu.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ed.edqiu.data.db.EdqiuDatabase
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.data.model.SavedLink
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryBackupRepositoryDeviceTest {

    private lateinit var context: Context
    private lateinit var database: EdqiuDatabase
    private lateinit var repository: HistoryBackupRepository
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, EdqiuDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HistoryBackupRepository(
            context = context,
            database = database,
            savedLinkDao = database.savedLinkDao(),
            historyDao = database.deletedLinkHistoryDao()
        )
        backupDir = File(context.getExternalFilesDir(null), "backup-device-test")
        backupDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        database.close()
        backupDir.deleteRecursively()
    }

    @Test
    fun backupToFileDirectoryWritesReadableBackupWithDownloadedFields() = runBlocking {
        database.savedLinkDao().insert(
            SavedLink(
                tweetId = "1234567890123456789",
                rawUrl = "https://x.com/i/status/1234567890123456789",
                authorId = "@author",
                authorName = "Author",
                caption = "Downloaded item",
                savedAt = 1_000L,
                status = LinkStatus.DOWNLOADED,
                filePath = "/storage/emulated/0/Android/data/com.ed.twitterdownload/files/Download/video.mp4",
                downloadedAt = 2_000L,
                attemptCount = 2
            )
        )

        val backupUri = repository.backupToDirectory(Uri.fromFile(backupDir), retentionCount = 2)
        val backupFile = File(requireNotNull(backupUri.path))
        val validated = repository.readAndValidate(Uri.fromFile(backupFile))
        val restored = validated.envelope.payload.activeLinks.single().toNormalizedSavedLink()

        assertTrue(backupFile.exists())
        assertEquals(LinkStatus.DOWNLOADED, restored.status)
        assertEquals(
            "/storage/emulated/0/Android/data/com.ed.twitterdownload/files/Download/video.mp4",
            restored.filePath
        )
        assertEquals(2_000L, restored.downloadedAt)
        assertEquals(2, restored.attemptCount)
    }
}
