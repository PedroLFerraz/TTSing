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
)
