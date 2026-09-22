package com.pedrolopes.ttsing.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val title: String,
    val author: String?,
    val language: String?,
    val coverFile: String?,
    val fileSize: Long,
    val lastModified: Long,
    val spineCount: Int,
    /** Character count per spine item, comma-separated; null until computed. */
    val chapterChars: String? = null,
    val chapterIndex: Int = 0,
    val blockIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val progressPercent: Float = 0f,
    val lastOpenedAt: Long = 0,
    /** [com.pedrolopes.ttsing.data.book.BookFormat] name; decides how the file is opened. */
    val format: String = "EPUB",
    /** Pages per section at [pageLayoutKey]'s screen size and font scale, comma-separated. */
    val pageCounts: String? = null,
    /** The layout [pageCounts] were measured for; any other layout recounts. */
    val pageLayoutKey: String? = null,
    /**
     * How long this book has been listened to and how much of it, summed over every sentence
     * the voice has read — KOReader's per-book reading statistics, for listening. Time is
     * normalised to speech rate 1.0.
     */
    val listenedMs: Long = 0,
    val listenedChars: Long = 0,
)
