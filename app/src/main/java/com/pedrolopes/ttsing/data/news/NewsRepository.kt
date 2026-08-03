package com.pedrolopes.ttsing.data.news

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.SentenceSplitter
import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import com.pedrolopes.ttsing.data.news.db.FeedEntity
import com.pedrolopes.ttsing.data.news.db.NewsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

class NewsRepository(private val dao: NewsDao) {

    fun observeFeeds(): Flow<List<FeedEntity>> = dao.observeFeeds()

    fun observeArticles(feedUrl: String): Flow<List<ArticleEntity>> = dao.observeArticles(feedUrl)

    fun observeLatest(limit: Int = 100): Flow<List<ArticleEntity>> = dao.observeLatest(limit)

    suspend fun article(id: String): ArticleEntity? = dao.article(id)

    suspend fun feed(url: String): FeedEntity? = dao.feed(url)

    /** The language a feed declares, used to pick the reading voice for its articles. */
    suspend fun feedLocale(feedUrl: String): Locale? =
        dao.feed(feedUrl)?.language
            ?.let { Locale.forLanguageTag(it.trim().replace('_', '-')) }
            ?.takeIf { it.language.isNotEmpty() }

    sealed interface AddResult {
        data class Added(val feed: FeedEntity, val articleCount: Int) : AddResult
        data class Failed(val message: String) : AddResult
    }

    /**
     * Subscribes to [rawUrl]. The URL is fetched and parsed up front so a typo or a page that
     * isn't a feed fails immediately with a message, rather than showing an empty feed later.
     */
    suspend fun addFeed(rawUrl: String): AddResult {
        val url = normalizeUrl(rawUrl) ?: return AddResult.Failed("That doesn't look like a web address")

        return when (val response = HttpFetcher.get(url)) {
            is HttpFetcher.Result.Failure -> AddResult.Failed("Couldn't load the feed: ${response.message}")
            is HttpFetcher.Result.Success -> {
                val parsed = RssParser.parse(response.body)
                    ?: return AddResult.Failed("That address isn't an RSS or Atom feed")
                val now = System.currentTimeMillis()
                val feed = FeedEntity(
                    url = url,
                    title = parsed.title,
                    siteLink = parsed.siteLink,
                    language = parsed.language,
                    lastRefreshedAt = now,
                    addedAt = now,
                )
                dao.upsertFeed(feed)
                val stored = storeItems(url, parsed.items)
                AddResult.Added(feed, stored)
            }
        }
    }

    suspend fun removeFeed(url: String) {
        dao.deleteArticlesOfFeed(url)
        dao.deleteFeed(url)
    }

    /** Re-reads every subscribed feed. Returns how many new stories arrived. */
    suspend fun refreshAll(): Int {
        var added = 0
        for (feed in dao.feeds()) {
            added += refresh(feed.url)
        }
        return added
    }

    suspend fun refresh(feedUrl: String): Int {
        val response = HttpFetcher.get(feedUrl)
        if (response !is HttpFetcher.Result.Success) return 0
        val parsed = RssParser.parse(response.body) ?: return 0

        dao.feed(feedUrl)?.let { existing ->
            dao.upsertFeed(
                existing.copy(
                    title = parsed.title.ifEmpty { existing.title },
                    siteLink = parsed.siteLink ?: existing.siteLink,
                    language = parsed.language ?: existing.language,
                    lastRefreshedAt = System.currentTimeMillis(),
                ),
            )
        }
        return storeItems(feedUrl, parsed.items)
    }

    /** Inserts stories not seen before; existing ones keep their text and reading position. */
    private suspend fun storeItems(feedUrl: String, items: List<FeedItem>): Int {
        val known = dao.articleIds(feedUrl).toSet()
        val fresh = items
            .map { it to articleId(feedUrl, it.guid) }
            .filter { (_, id) -> id !in known }

        if (fresh.isEmpty()) return 0

        dao.upsertArticles(
            fresh.map { (item, id) ->
                // Some feeds ship the whole body inline, which saves fetching the page at all.
                val inline = item.contentHtml?.takeIf { it.isNotBlank() }
                val extracted = inline?.let { ArticleExtractor.extract(it, item.link) }
                ArticleEntity(
                    id = id,
                    feedUrl = feedUrl,
                    title = item.title,
                    link = item.link,
                    summary = item.summary?.let { stripHtml(it) },
                    contentHtml = extracted?.contentHtml?.takeIf { extracted.textLength >= MIN_FULL_TEXT },
                    publishedAt = item.publishedAt,
                    fetchedAt = if (extracted != null) System.currentTimeMillis() else 0,
                    textLength = extracted?.textLength ?: 0,
                )
            },
        )
        return fresh.size
    }

    sealed interface ArticleBody {
        data class Ready(val blocks: List<Block>, val truncated: Boolean) : ArticleBody
        data class Failed(val message: String) : ArticleBody
    }

    /**
     * The article's blocks, fetching and extracting the full page the first time it is opened.
     *
     * Falls back to the feed's summary when the page can't be reached or the extractor finds
     * too little to be a real article, so opening a story always gives *something* to read —
     * flagged [ArticleBody.Ready.truncated] so the UI can say the text is only the teaser.
     */
    suspend fun body(articleId: String, locale: Locale): ArticleBody = withContext(Dispatchers.Default) {
        val article = dao.article(articleId) ?: return@withContext ArticleBody.Failed("Article not found")

        article.contentHtml?.takeIf { it.isNotBlank() }?.let { cached ->
            val blocks = ArticleExtractor.extract(cached, article.link, locale).blocks
            if (blocks.isNotEmpty()) return@withContext ArticleBody.Ready(blocks, truncated = false)
        }

        when (val response = HttpFetcher.get(article.link)) {
            is HttpFetcher.Result.Success -> {
                val extracted = ArticleExtractor.extract(response.body, article.link, locale)
                if (extracted.textLength >= MIN_FULL_TEXT && extracted.blocks.isNotEmpty()) {
                    dao.upsertArticle(
                        article.copy(
                            contentHtml = extracted.contentHtml,
                            fetchedAt = System.currentTimeMillis(),
                            textLength = extracted.textLength,
                            // Publishers often give the headline better here than in the feed.
                            title = extracted.title?.takeIf { it.isNotBlank() } ?: article.title,
                        ),
                    )
                    ArticleBody.Ready(extracted.blocks, truncated = false)
                } else {
                    summaryBody(article, locale)
                }
            }
            is HttpFetcher.Result.Failure -> summaryBody(article, locale)
        }
    }

    private fun summaryBody(article: ArticleEntity, locale: Locale): ArticleBody {
        val summary = article.summary?.trim().orEmpty()
        if (summary.isEmpty()) {
            return ArticleBody.Failed("Couldn't load this article's text")
        }
        val blocks = listOf<Block>(
            Block.Text(article.title, Block.Text.Kind.HEADING_1, SentenceSplitter.split(article.title, locale)),
            Block.Text(summary, Block.Text.Kind.PARAGRAPH, SentenceSplitter.split(summary, locale)),
        )
        return ArticleBody.Ready(blocks, truncated = true)
    }

    suspend fun savePosition(articleId: String, blockIndex: Int, sentenceIndex: Int) {
        dao.updatePosition(articleId, blockIndex, sentenceIndex, System.currentTimeMillis())
    }

    companion object {
        /** Below this the "article" is a paywall stub or a nav page, not the story. */
        const val MIN_FULL_TEXT = 400

        /** Ids are stable across refreshes so reading positions survive. */
        fun articleId(feedUrl: String, guid: String): String = ARTICLE_PREFIX + sha1("$feedUrl|$guid")

        const val ARTICLE_PREFIX = "article:"

        fun isArticle(id: String): Boolean = id.startsWith(ARTICLE_PREFIX)

        private fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** Accepts what people actually paste: bare hosts, feed:// links, stray whitespace. */
        fun normalizeUrl(raw: String): String? {
            var url = raw.trim()
            if (url.isEmpty()) return null
            if (url.startsWith("feed://", ignoreCase = true)) url = "https://" + url.removePrefix("feed://")
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "https://$url"
            return runCatching { java.net.URL(url) }.getOrNull()
                ?.takeIf { !it.host.isNullOrBlank() && it.host.contains('.') }
                ?.toString()
        }

        /** Feed summaries are usually HTML; the reader wants plain text. */
        fun stripHtml(value: String): String =
            org.jsoup.Jsoup.parse(value).text().trim()
    }
}
