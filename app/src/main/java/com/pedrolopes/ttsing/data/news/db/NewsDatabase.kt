package com.pedrolopes.ttsing.data.news.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A feed the user subscribed to. Keyed by URL so re-adding the same feed updates rather than
 * duplicates.
 */
@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey val url: String,
    val title: String,
    val siteLink: String?,
    /** From the feed's own metadata; seeds the reading voice for its articles. */
    val language: String?,
    val lastRefreshedAt: Long = 0,
    val addedAt: Long = 0,
)

/**
 * One story. [contentHtml] holds the full article body once fetched — the feed's own
 * [summary] is nearly always a truncated teaser, so it is only a fallback.
 */
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
    val feedUrl: String,
    val title: String,
    val link: String,
    val summary: String?,
    /** Cleaned article body; null until the full text has been fetched. */
    val contentHtml: String? = null,
    val publishedAt: Long = 0,
    val fetchedAt: Long = 0,
    /** Characters of prose, for the reading-time estimate and to spot failed extractions. */
    val textLength: Int = 0,
    // Reading position, mirroring how books remember where you were.
    val blockIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val lastOpenedAt: Long = 0,
    val isRead: Boolean = false,
)

@Dao
interface NewsDao {

    @Query("SELECT * FROM feeds ORDER BY title ASC")
    fun observeFeeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds")
    suspend fun feeds(): List<FeedEntity>

    @Query("SELECT * FROM feeds WHERE url = :url")
    suspend fun feed(url: String): FeedEntity?

    @Upsert
    suspend fun upsertFeed(feed: FeedEntity)

    @Query("DELETE FROM feeds WHERE url = :url")
    suspend fun deleteFeed(url: String)

    @Query("DELETE FROM articles WHERE feedUrl = :url")
    suspend fun deleteArticlesOfFeed(url: String)

    @Query("SELECT * FROM articles WHERE feedUrl = :feedUrl ORDER BY publishedAt DESC, title ASC")
    fun observeArticles(feedUrl: String): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles ORDER BY publishedAt DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun article(id: String): ArticleEntity?

    @Upsert
    suspend fun upsertArticles(articles: List<ArticleEntity>)

    @Upsert
    suspend fun upsertArticle(article: ArticleEntity)

    @Query("SELECT id FROM articles WHERE feedUrl = :feedUrl")
    suspend fun articleIds(feedUrl: String): List<String>

    @Query(
        """UPDATE articles SET blockIndex = :block, sentenceIndex = :sentence,
           lastOpenedAt = :openedAt, isRead = 1 WHERE id = :id""",
    )
    suspend fun updatePosition(id: String, block: Int, sentence: Int, openedAt: Long)
}

/**
 * Kept separate from the books database on purpose. That one is
 * `fallbackToDestructiveMigration` because it is a rebuildable cache of a folder of EPUBs;
 * feed subscriptions are user-created and cannot be rebuilt, so they need a database that
 * forces real migrations instead of quietly dropping everything on a schema bump.
 */
@Database(entities = [FeedEntity::class, ArticleEntity::class], version = 1, exportSchema = false)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao
}
