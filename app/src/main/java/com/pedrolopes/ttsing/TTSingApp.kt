package com.pedrolopes.ttsing

import android.app.Application
import androidx.room.Room
import com.pedrolopes.ttsing.data.BookRepository
import com.pedrolopes.ttsing.data.db.AppDatabase
import com.pedrolopes.ttsing.data.settings.SettingsRepository

class TTSingApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "ttsing.db")
            // The DB is a rebuildable cache of the books folder, so a schema bump can
            // simply re-scan rather than carry migrations.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    val books: BookRepository by lazy { BookRepository(this, database.bookDao(), settings) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TTSingApp
            private set
    }
}
