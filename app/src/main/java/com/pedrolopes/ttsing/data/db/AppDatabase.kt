package com.pedrolopes.ttsing.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

/**
 * Adds the file format and the cached book-wide page counts. A real migration rather than the
 * destructive fallback, because this table also holds every book's reading position: dropping
 * it would send every book back to page one.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'EPUB'")
        db.execSQL("ALTER TABLE books ADD COLUMN pageCounts TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN pageLayoutKey TEXT")
    }
}
