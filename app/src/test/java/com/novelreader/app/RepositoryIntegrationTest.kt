package com.novelreader.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.novelreader.app.data.local.AppDatabase
import com.novelreader.app.data.local.ChapterEntity
import com.novelreader.app.data.local.NovelEntity
import com.novelreader.app.data.repository.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

@RunWith(RobolectricTestRunner::class)
class RepositoryIntegrationTest {

    private lateinit var repository: NovelRepository
    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        repository = NovelRepository(context)

        mockWebServer = MockWebServer()
        mockWebServer.start()

        runBlocking {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context).clearAllTables()
            }
        }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        runBlocking {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context).clearAllTables()
            }
        }
    }

    @Test
    fun testDownloadSingleChapter_MockSuccessAndFailure() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        val novel = NovelEntity(
            id = "test_novel_integration",
            title = "Test Novel",
            author = "Author",
            coverUrl = "",
            intro = "",
            sourceUrl = "http://localhost/test",
            totalChapters = 1,
            downloadedChapters = 0,
            lastReadChapterIndex = 0
        )

        val url = mockWebServer.url("/test/0").toString()

        withContext(Dispatchers.IO) {
            db.novelDao().insertNovel(novel)

            val chapter = ChapterEntity(
                novelId = "test_novel_integration",
                chapterIndex = 0,
                chapterId = "ch_0",
                title = "Chapter 0",
                url = url,
                content = null,
                isDownloaded = false
            )
            db.chapterDao().insertChapters(listOf(chapter))
        }

        // Mock 404 response
        mockWebServer.enqueue(MockResponse().setResponseCode(404))
        var result = repository.downloadSingleChapter("test_novel_integration", 0)
        assertTrue(result.isFailure)

        // Mock Success response with actual HTML
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html><body><div id='content'><p>Hello World</p></div></body></html>"))
        result = repository.downloadSingleChapter("test_novel_integration", 0)
        assertTrue(result.isSuccess)

        // Verify saved
        val saved = db.chapterDao().getChapterByIndex("test_novel_integration", 0)
        assertTrue(saved?.isDownloaded == true)
        assertEquals("Hello World", saved?.content)
    }
    @Test
    fun forceRefreshReplacesOnlyTheRequestedChapter() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        val id = "refresh_book"
        val url = mockWebServer.url("/chapter/1").toString()
        withContext(Dispatchers.IO) {
            db.novelDao().insertNovel(
                NovelEntity(id, "Book", "Author", "", "", url, totalChapters = 1)
            )
            db.chapterDao().insertChapters(listOf(
                ChapterEntity(id, 0, "chapter-1", "第1章", url, "舊版不正確內容", true)
            ))
        }

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("<html><body><h1>第1章</h1><div id='content'><p>網頁的正確新內容</p></div></body></html>")
        )
        val result = repository.downloadSingleChapter(id, 0, forceRefresh = true)
        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals("網頁的正確新內容", db.chapterDao().getChapterById(id, "chapter-1")?.content)
    }

    @Test
    fun wrongChapterPageNeverOverwritesOfflineContent() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        val id = "mismatched_book"
        val url = mockWebServer.url("/chapter/1").toString()
        withContext(Dispatchers.IO) {
            db.novelDao().insertNovel(
                NovelEntity(id, "Book", "Author", "", "", url, totalChapters = 1)
            )
            db.chapterDao().insertChapters(listOf(
                ChapterEntity(id, 0, "chapter-1", "第1章", url, "已儲存文字", true)
            ))
        }
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("<html><body><h1>第2章</h1><div id='content'>抓到別章的內容</div></body></html>")
        )
        val result = repository.downloadSingleChapter(id, 0, forceRefresh = true)
        assertTrue(result.isFailure)
        assertEquals("已儲存文字", db.chapterDao().getChapterById(id, "chapter-1")?.content)
    }

    @Test
    fun catalogReorderDuringDownloadDoesNotWriteToTheWrongChapter() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        val id = "reorder_book"
        val url = mockWebServer.url("/chapter/old").toString()
        withContext(Dispatchers.IO) {
            db.novelDao().insertNovel(
                NovelEntity(id, "Book", "Author", "", "", url, totalChapters = 1)
            )
            db.chapterDao().insertChapters(listOf(
                ChapterEntity(id, 0, "old-id", "第1章", url)
            ))
        }
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200)
                .setBodyDelay(800, TimeUnit.MILLISECONDS)
                .setBody("<html><body><h1>第1章</h1><div id='content'>舊章節正文</div></body></html>")
        )
        val task = async(Dispatchers.IO) { repository.downloadChapterById(id, "old-id") }
        val request = withContext(Dispatchers.IO) {
            mockWebServer.takeRequest(3, TimeUnit.SECONDS)
        }
        assertNotNull("Chapter request never reached the server", request)

        withContext(Dispatchers.IO) {
            val old = db.chapterDao().getChapterById(id, "old-id")!!
            db.chapterDao().updateChapters(listOf(old.copy(chapterIndex = 1)))
            db.chapterDao().insertChapters(listOf(
                ChapterEntity(id, 0, "new-id", "新插入章", mockWebServer.url("/chapter/new").toString())
            ))
        }
        val result = task.await()
        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals("舊章節正文", db.chapterDao().getChapterById(id, "old-id")?.content)
        assertNull(db.chapterDao().getChapterById(id, "new-id")?.content)
    }

}
