package com.pedrolopes.ttsing.data.news.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
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
@Entity(tableName = "articles", indices = [Index("feedUrl")])
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
    /**
     * Blocks in the body as the reader indexes them (headline included), recorded when the
     * story is opened; 0 until then. Lets a saved position be read as a fraction of the way
     * through, which is what decides whether the story counts as heard.
     */
    @ColumnInfo(defaultValue = "0") val blockCount: Int = 0,
    /**
     * Why [contentHtml] holds no full text, once a fetch has been tried: [ISSUE_DOWNLOAD_FAILED]
     * (the page couldn't be loaded) or [ISSUE_TOO_SHORT] (it loaded, but the text found was a
     * stub or paywall). Null while the full text is there, or not yet tried.
     */
    val fullTextIssue: String? = null,
) {
    companion object {
        const val ISSUE_DOWNLOAD_FAILED = "download_failed"
        const val ISSUE_TOO_SHORT = "too_short"
    }
}

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

    /** Subscribes unless the exact URL is already there; -1 when it was, with the row untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeedIfAbsent(feed: FeedEntity): Long

    /**
     * What a refresh learned about a feed. An UPDATE, so it can't bring back a feed removed
     * meanwhile or undo a group set meanwhile; what the feed doesn't say is left as it was.
     */
    @Query(
        """UPDATE feeds SET title = :title, siteLink = COALESCE(:siteLink, siteLink),
           language = COALESCE(:language, language), lastRefreshedAt = :refreshedAt,
           topic = COALESCE(topic, :topic) WHERE url = :url""",
    )
    suspend fun updateFeedInfo(url: String, title: String, siteLink: String?, language: String?, refreshedAt: Long, topic: String?)

    @Query("DELETE FROM feeds WHERE url = :url")
    suspend fun deleteFeed(url: String)

    @Query("DELETE FROM articles WHERE feedUrl = :url")
    suspend fun deleteArticlesOfFeed(url: String)

    /** Both in one transaction, so a refresh can't find the feed gone but its stories half there. */
    @Transaction
    suspend fun removeFeed(url: String) {
        deleteArticlesOfFeed(url)
        deleteFeed(url)
    }

    /**
     * Stores [articles] only while [feedUrl] is still subscribed, and says whether it did. A
     * refresh that was mid-download when the feed was removed must not bring its stories back.
     */
    @Transaction
    suspend fun upsertArticlesIfSubscribed(feedUrl: String, articles: List<ArticleEntity>): Boolean {
        if (feed(feedUrl) == null) return false
        upsertArticles(articles)
        return true
    }

    @Query("SELECT * FROM articles WHERE feedUrl = :feedUrl ORDER BY publishedAt DESC, title ASC")
    fun observeArticles(feedUrl: String): Flow<List<ArticleEntity>>

    /**
     * The newest stories across all feeds, but at most [perFeed] from any one, so a feed that
     * posts all day can't push a quiet one's latest story off the list. (A correlated count
     * rather than ROW_NUMBER(): window functions need SQLite 3.25, newer than API 26's.)
     */
    @Query(
        """SELECT a.* FROM articles a WHERE (
             SELECT COUNT(*) FROM articles b WHERE b.feedUrl = a.feedUrl AND
               (b.publishedAt > a.publishedAt OR (b.publishedAt = a.publishedAt AND b.id < a.id))
           ) < :perFeed ORDER BY a.publishedAt DESC LIMIT :limit""",
    )
    fun observeLatest(perFeed: Int, limit: Int): Flow<List<ArticleEntity>>

    @Query(
        """SELECT a.* FROM articles a JOIN feeds f ON a.feedUrl = f.url
           WHERE f.folder = :folder COLLATE NOCASE AND (
             SELECT COUNT(*) FROM articles b WHERE b.feedUrl = a.feedUrl AND
               (b.publishedAt > a.publishedAt OR (b.publishedAt = a.publishedAt AND b.id < a.id))
           ) < :perFeed ORDER BY a.publishedAt DESC LIMIT :limit""",
    )
    fun observeLatestInFolder(folder: String, perFeed: Int, limit: Int): Flow<List<ArticleEntity>>

    @Query("UPDATE feeds SET folder = :folder WHERE url = :url")
    suspend fun setFolder(url: String, folder: String?)

    /** Every spelling of [from] ("science", "Science") becomes [to]. */
    @Query("UPDATE feeds SET folder = :to WHERE folder = :from COLLATE NOCASE")
    suspend fun renameFolder(from: String, to: String)

    /** The feeds in the group become ungrouped. */
    @Query("UPDATE feeds SET folder = NULL WHERE folder = :folder COLLATE NOCASE")
    suspend fun clearFolder(folder: String)

    /** Stories without a usable date (`publishedAt == 0`) are never old enough to go. */
    @Query("DELETE FROM articles WHERE publishedAt > 0 AND publishedAt < :cutoff")
    suspend fun deleteArticlesPublishedBefore(cutoff: Long): Int

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun article(id: String): ArticleEntity?

    @Upsert
    suspend fun upsertArticles(articles: List<ArticleEntity>)

    @Upsert
    suspend fun upsertArticle(article: ArticleEntity)

    /**
     * Writes back what a page fetch learned, and only that. Unlike upserting a whole copy of the
     * row it can't undo a reading position saved while the page was downloading, and it does
     * nothing for a story that has since been deleted with its feed.
     */
    @Query(
        """UPDATE articles SET contentHtml = :contentHtml, fetchedAt = :fetchedAt,
           textLength = :textLength, title = :title, imageUrl = :imageUrl,
           fullTextIssue = :issue WHERE id = :id""",
    )
    suspend fun updateFetched(
        id: String,
        contentHtml: String?,
        fetchedAt: Long,
        textLength: Int,
        title: String,
        imageUrl: String?,
        issue: String?,
    )

    @Query("UPDATE articles SET blockCount = :count WHERE id = :id")
    suspend fun setBlockCount(id: String, count: Int)

    @Query("UPDATE articles SET isRead = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: List<String>, read: Boolean)

    /** Thumbnails still in use, so the cache can drop the rest. */
    @Query("SELECT imageUrl FROM articles WHERE imageUrl IS NOT NULL")
    suspend fun imageUrls(): List<String>

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
           lastOpenedAt = :openedAt, isRead = :read WHERE id = :id""",
    )
    suspend fun updatePosition(id: String, block: Int, sentence: Int, openedAt: Long, read: Boolean)
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
 * Why a story has no full text and how far through it the listener is (see [ArticleEntity]),
 * plus an index on the feed, which the per-feed list and the duplicate check look up by.
 * Stories without a date (they sort last and were never pruned) are dated now, so they age
 * out like the rest.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE articles ADD COLUMN blockCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE articles ADD COLUMN fullTextIssue TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_articles_feedUrl ON articles (feedUrl)")
        db.execSQL("UPDATE articles SET publishedAt = ? WHERE publishedAt = 0", arrayOf<Any>(System.currentTimeMillis()))
    }
}

/**
 * Kept separate from the books database on purpose. That one is
 * `fallbackToDestructiveMigration` because it is a rebuildable cache of a folder of EPUBs;
 * feed subscriptions are user-created and cannot be rebuilt, so they need a database that
 * forces real migrations instead of quietly dropping everything on a schema bump.
 */
@Database(entities = [FeedEntity::class, ArticleEntity::class], version = 5, exportSchema = false)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao
}
