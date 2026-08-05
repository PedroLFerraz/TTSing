package com.pedrolopes.ttsing

import android.app.Application
import androidx.room.Room
import com.pedrolopes.ttsing.data.BookRepository
import com.pedrolopes.ttsing.data.db.AppDatabase
import com.pedrolopes.ttsing.data.news.NewsRepository
import com.pedrolopes.ttsing.data.news.db.NewsDatabase
import com.pedrolopes.ttsing.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TTSingApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "ttsing.db")
            // The DB is a rebuildable cache of the books folder, so a schema bump can
            // simply re-scan rather than carry migrations.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    /**
     * Separate from [database] on purpose: that one is destructively migrated because it only
     * caches the books folder, whereas feed subscriptions are user data that must survive.
     */
    val newsDatabase: NewsDatabase by lazy {
        Room.databaseBuilder(this, NewsDatabase::class.java, "ttsing-news.db")
            .addMigrations(com.pedrolopes.ttsing.data.news.db.MIGRATION_1_2)
            .build()
    }

    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    val books: BookRepository by lazy { BookRepository(this, database.bookDao(), settings) }

    /**
     * Outlives any single screen, for [NewsRepository]'s background thumbnail/full-text
     * prefetch: that work should keep running (and its results still land in Room, updating
     * whichever screen is open via Flow) even if the user has already navigated away from
     * the article list that triggered it.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val news: NewsRepository by lazy { NewsRepository(newsDatabase.newsDao(), appScope) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TTSingApp
            private set
    }
}
