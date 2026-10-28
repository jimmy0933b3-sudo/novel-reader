package com.novelreader.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelDao {
    @Query("SELECT * FROM novels ORDER BY lastReadTime DESC")
    fun getAllNovelsFlow(): Flow<List<NovelEntity>>

    @Query("SELECT * FROM novels WHERE id = :novelId")
    suspend fun getNovelById(novelId: String): NovelEntity?

    @Query("SELECT * FROM novels WHERE id = :novelId")
    fun getNovelFlow(novelId: String): Flow<NovelEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNovel(novel: NovelEntity)

    @Update
    suspend fun updateNovel(novel: NovelEntity)

    @Delete
    suspend fun deleteNovel(novel: NovelEntity)

    @Query("UPDATE novels SET lastReadChapterId = :chapterId, lastReadChapterTitle = :title, lastReadChapterIndex = :chapterIndex, lastReadTime = :time WHERE id = :novelId")
    suspend fun updateReadingProgress(novelId: String, chapterId: String, title: String, chapterIndex: Int, time: Long = System.currentTimeMillis())

    @Query("UPDATE novels SET totalChapters = :total WHERE id = :novelId")
    suspend fun updateTotalChapters(novelId: String, total: Int)

    @Query("UPDATE novels SET downloadedChapters = (SELECT COUNT(*) FROM chapters WHERE novelId = :novelId AND isDownloaded = 1) WHERE id = :novelId")
    suspend fun refreshDownloadedCount(novelId: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY chapterIndex ASC")
    fun getChaptersFlow(novelId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY chapterIndex ASC")
    suspend fun getChapters(novelId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND chapterIndex = :index")
    suspend fun getChapterByIndex(novelId: String, index: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND chapterId = :chapterId LIMIT 1")
    suspend fun getChapterById(novelId: String, chapterId: String): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Update
    suspend fun updateChapters(chapters: List<ChapterEntity>)

    // The chapter index can change while catalog synchronization is running.
    @Query("UPDATE chapters SET content = :content, isDownloaded = 1 WHERE novelId = :novelId AND chapterId = :chapterId AND url = :url")
    suspend fun saveChapterContent(novelId: String, chapterId: String, url: String, content: String): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE novelId = :novelId AND isDownloaded = 1")
    fun getDownloadedCountFlow(novelId: String): Flow<Int>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND isDownloaded = 0 ORDER BY chapterIndex ASC")
    suspend fun getPendingDownloadChapters(novelId: String): List<ChapterEntity>

    @Query("DELETE FROM chapters WHERE novelId = :novelId")
    suspend fun deleteChaptersByNovelId(novelId: String)

    @Transaction
    suspend fun replaceAllChapters(novelId: String, newChapters: List<ChapterEntity>) {
        // Here we could just delete and insert, but we want to retain downloaded content.
        // It's safer to let Repository handle it outside of a transaction or by providing an update.
        // For actual room transactions we can just wrap the operation.
    }
}
