package com.pedrolopes.ttsing.data.news.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
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
    /**
     * A [com.pedrolopes.ttsing.data.news.Topic] id when the feed came from the topic
     * catalogue (or matches one of its feeds); null for a feed added by hand. Drives the
     * News list's topic filter.
     */
    val topic: String? = null,
    /**
     * The user's own group for the feed ("Science", "Friends' blogs"), or null for none. Starts
     * as the catalogue topic's name and is the user's to change; drives the News list's filter.
     */
    val folder: String? = null,
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
    /**
     * A thumbnail for the article list. Filled from the feed's own media metadata when it
     * offers one; otherwise backfilled from the first photo in the article once its full
     * text has been fetched.
     */
    val imageUrl: String? = null,
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

    @Query(
        """SELECT articles.* FROM articles JOIN feeds ON articles.feedUrl = feeds.url
           WHERE feeds.folder = :folder ORDER BY articles.publishedAt DESC LIMIT :limit""",
    )
    fun observeLatestInFolder(folder: String, limit: Int): Flow<List<ArticleEntity>>

    @Query("UPDATE feeds SET folder = :folder WHERE url = :url")
    suspend fun setFolder(url: String, folder: String?)

    /** Stories without a usable date (`publishedAt == 0`) are never old enough to go. */
    @Query("DELETE FROM articles WHERE publishedAt > 0 AND publishedAt < :cutoff")
    suspend fun deleteArticlesPublishedBefore(cutoff: Long): Int

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun article(id: String): ArticleEntity?

    @Upsert
    suspend fun upsertArticles(articles: List<ArticleEntity>)

    @Upsert
    suspend fun upsertArticle(article: ArticleEntity)

    @Query("SELECT id FROM articles WHERE feedUrl = :feedUrl")
    suspend fun articleIds(feedUrl: String): List<String>

    /**
     * Newest stories in a feed whose page hasn't been fetched yet (`fetchedAt == 0`) — i.e.
     * that still need their body and/or thumbnail. Drives the background prefetch, and crucially
     * includes articles stored before the prefetch existed, not only ones from the latest
     * refresh, which is what lets thumbnails appear for a feed already subscribed to.
     *
     * Keyed on `fetchedAt` rather than `contentHtml` so it converges even for a page that
     * yields a usable thumbnail but too little body text to store: that page is still marked
     * fetched and won't be retried every refresh. A genuinely failed fetch leaves `fetchedAt`
     * at 0, so transient errors are retried next time.
     */
    @Query(
        """SELECT id FROM articles WHERE feedUrl = :feedUrl AND fetchedAt = 0
           ORDER BY publishedAt DESC LIMIT :limit""",
    )
    suspend fun articlesToPrefetch(feedUrl: String, limit: Int): List<String>

    @Query(
        """UPDATE articles SET blockIndex = :block, sentenceIndex = :sentence,
           lastOpenedAt = :openedAt, isRead = 1 WHERE id = :id""",
    )
    suspend fun updatePosition(id: String, block: Int, sentence: Int, openedAt: Long)
}

/** Adds the article thumbnail column; existing feeds and reading positions are untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE articles ADD COLUMN imageUrl TEXT")
    }
}

/**
 * Adds the feed's topic; every existing feed starts without one, as if added by hand (a
 * refresh then files catalogue feeds under theirs).
 *
 * Also gives short stored stories one more look at their page. Earlier versions took any
 * inline feed body of a few hundred characters as the whole story and never fetched the page,
 * so feeds that ship teasers inline (The Verge, for one) read only the teaser. Marking them
 * unfetched lets the next open, or the background prefetch, fetch the page and keep it if it
 * is longer — see NewsRepository.TRUSTED_INLINE_TEXT, which this threshold mirrors.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feeds ADD COLUMN topic TEXT")
        db.execSQL("UPDATE articles SET fetchedAt = 0 WHERE textLength < 2000")
    }
}

/**
 * Adds user-made groups. Feeds from the topic catalogue start in a group named after their
 * topic, so the filter chips the News list had before are still there, now editable.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feeds ADD COLUMN folder TEXT")
        for (topic in com.pedrolopes.ttsing.data.news.Topic.entries) {
            db.execSQL("UPDATE feeds SET folder = ? WHERE topic = ?", arrayOf(topic.label, topic.id))
        }
    }
}

/**
 * Kept separate from the books database on purpose. That one is
 * `fallbackToDestructiveMigration` because it is a rebuildable cache of a folder of EPUBs;
 * feed subscriptions are user-created and cannot be rebuilt, so they need a database that
 * forces real migrations instead of quietly dropping everything on a schema bump.
 */
@Database(entities = [FeedEntity::class, ArticleEntity::class], version = 4, exportSchema = false)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao
}
