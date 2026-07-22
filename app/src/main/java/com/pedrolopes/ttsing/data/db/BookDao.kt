package com.pedrolopes.ttsing.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, title ASC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("UPDATE books SET chapterChars = :chapterChars WHERE id = :id")
    suspend fun updateChapterChars(id: String, chapterChars: String)

    @Query(
        """UPDATE books SET chapterIndex = :chapter, blockIndex = :block, sentenceIndex = :sentence,
           progressPercent = :progress, lastOpenedAt = :openedAt WHERE id = :id""",
    )
    suspend fun updatePosition(
        id: String,
        chapter: Int,
        block: Int,
        sentence: Int,
        progress: Float,
        openedAt: Long,
    )
}
