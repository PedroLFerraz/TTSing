package com.pedrolopes.ttsing.data.news

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.SentenceSplitter
import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import com.pedrolopes.ttsing.data.news.db.FeedEntity
import com.pedrolopes.ttsing.data.news.db.NewsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * [scope] is used only for the background prefetch in [refresh]/[addFeed]: firing full-text
 * and thumbnail fetches that outlive whichever screen triggered them, so the article list
 * keeps filling in even after the user has moved on. Every other method here is a plain
 * suspend function that runs in the caller's own scope, same as before.
 */
class NewsRepository(
    private val dao: NewsDao,
    private val scope: CoroutineScope,
) {

    /** The list the current story was opened from, for playing on into the next one. */
    val queue = NewsQueue()

    fun observeFeeds(): Flow<List<FeedEntity>> = dao.observeFeeds()

    fun observeArticles(feedUrl: String): Flow<List<ArticleEntity>> = dao.observeArticles(feedUrl)

    fun observeLatest(limit: Int = 100): Flow<List<ArticleEntity>> = dao.observeLatest(limit)

    fun observeLatestInTopic(topic: Topic, limit: Int = 100): Flow<List<ArticleEntity>> =
        dao.observeLatestInTopic(topic.id, limit)

    suspend fun article(id: String): ArticleEntity? = dao.article(id)

    suspend fun feed(url: String): FeedEntity? = dao.feed(url)

    /** The language a feed declares, used to pick the reading voice for its articles. */
    suspend fun feedLocale(feedUrl: String): Locale? =
        dao.feed(feedUrl)?.language
            ?.let { Locale.forLanguageTag(it.trim().replace('_', '-')) }
            ?.takeIf { it.language.isNotEmpty() }

    /** The next unheard story in [queue] after [articleId], or null at the end of the list. */
    suspend fun nextUnread(articleId: String): String? =
        queue.nextAfter(articleId) { id -> dao.article(id)?.isRead == false }

    sealed interface AddResult {
        data class Added(val feed: FeedEntity, val articleCount: Int) : AddResult
        data class Failed(val message: String) : AddResult
    }

    /**
     * Subscribes to [rawUrl]. The URL is fetched and parsed up front so a typo or a page that
     * isn't a feed fails immediately with a message, rather than showing an empty feed later.
     *
     * A website's address works too: when the page isn't a feed itself, the feed it advertises
     * (or one at a conventional location like `/feed`) is subscribed instead.
     *
     * [topic] files the feed under a catalogue topic; when omitted, a feed that happens to be
     * in the catalogue still gets its topic.
     */
    suspend fun addFeed(rawUrl: String, topic: Topic? = null): AddResult {
        val url = normalizeUrl(rawUrl) ?: return AddResult.Failed("That doesn't look like a web address")

        val response = HttpFetcher.get(url)
        if (response is HttpFetcher.Result.Failure) return AddResult.Failed("Couldn't load the feed: ${response.message}")
        val page = response as HttpFetcher.Result.Success

        val (feedUrl, parsed) = RssParser.parse(page.body)?.let { url to it }
            ?: discoverFeed(page)
            ?: return AddResult.Failed("That address isn't a feed, and the page doesn't point to one")

        val catalogued = FeedCatalog.find(feedUrl)
        val now = System.currentTimeMillis()
        val feed = FeedEntity(
            url = feedUrl,
            // "Folha Poder" rather than "Folha de S.Paulo - Poder - Principal": the name goes
            // on every story row and the lock screen, where a feed's own title rarely fits.
            title = catalogued?.title ?: parsed.title,
            siteLink = parsed.siteLink,
            language = catalogued?.language ?: parsed.language,
            lastRefreshedAt = now,
            addedAt = now,
            topic = (topic ?: catalogued?.topic)?.id,
        )
        dao.upsertFeed(feed)
        val fresh = storeItems(feedUrl, parsed.items)
        prefetchBodies(feedUrl)
        return AddResult.Added(feed, fresh)
    }

    /**
     * The feed behind a web page: first what the page advertises, then the usual locations on
     * its site. Stops at the first address that parses.
     */
    private suspend fun discoverFeed(page: HttpFetcher.Result.Success): Pair<String, ParsedFeed>? {
        val candidates = (
            FeedDiscovery.advertisedFeeds(page.body, page.finalUrl).take(MAX_ADVERTISED_TRIES) +
                FeedDiscovery.conventionalFeeds(page.finalUrl)
            ).distinct()
        for (candidate in candidates) {
            val response = HttpFetcher.get(candidate) as? HttpFetcher.Result.Success ?: continue
            RssParser.parse(response.body)?.let { return candidate to it }
        }
        return null
    }

    suspend fun removeFeed(url: String) {
        dao.deleteArticlesOfFeed(url)
        dao.deleteFeed(url)
    }

    /** Re-reads every subscribed feed. Returns how many new stories arrived. */
    suspend fun refreshAll(): Int {
        // News goes stale; without this the database only ever grows, a full article body
        // per story. Stories already heard or half-heard go too — a month on, they're history.
        dao.deleteArticlesPublishedBefore(retentionCutoff())
        var added = 0
        for (feed in dao.feeds()) {
            added += refresh(feed.url)
        }
        return added
    }

    suspend fun refresh(feedUrl: String): Int {
        val fresh = when (val response = HttpFetcher.get(feedUrl)) {
            is HttpFetcher.Result.Success -> RssParser.parse(response.body)?.let { parsed ->
                dao.feed(feedUrl)?.let { existing ->
                    val catalogued = FeedCatalog.find(feedUrl)
                    dao.upsertFeed(
                        existing.copy(
                            title = catalogued?.title ?: parsed.title.ifEmpty { existing.title },
                            siteLink = parsed.siteLink ?: existing.siteLink,
                            // The catalogue knows better than feeds that all claim "en".
                            language = catalogued?.language ?: parsed.language ?: existing.language,
                            lastRefreshedAt = System.currentTimeMillis(),
                            // Feeds subscribed before topics existed pick theirs up here.
                            topic = existing.topic ?: catalogued?.topic?.id,
                        ),
                    )
                }
                storeItems(feedUrl, parsed.items)
            } ?: 0
            is HttpFetcher.Result.Failure -> 0
        }
        // Fire regardless of the feed fetch: this also backfills stories stored earlier that
        // still have no thumbnail, which don't depend on the latest feed content.
        prefetchBodies(feedUrl)
        return fresh
    }

    /** Inserts stories not seen before, returning how many; existing ones keep their text and position. */
    private suspend fun storeItems(feedUrl: String, items: List<FeedItem>): Int {
        val known = dao.articleIds(feedUrl).toSet()
        val cutoff = retentionCutoff()
        val fresh = items
            // Older than what refreshAll() keeps: storing it would only bring a deleted story
            // back as unread, to be deleted again next time.
            .filter { it.publishedAt == 0L || it.publishedAt >= cutoff }
            .map { it to articleId(feedUrl, it.guid) }
            .filter { (_, id) -> id !in known }

        if (fresh.isEmpty()) return 0

        dao.upsertArticles(
            fresh.map { (item, id) ->
                // Some feeds ship the whole body inline, which saves fetching the page at all.
                // Others ship a few paragraphs of teaser in the same element, so a short inline
                // body is kept to read from but the page is still fetched (fetchedAt stays 0)
                // and wins if it turns out longer.
                val inline = item.contentHtml?.takeIf { it.isNotBlank() }
                val extracted = inline?.let { ArticleExtractor.extract(it, item.link) }
                val readable = extracted != null && extracted.textLength >= MIN_FULL_TEXT
                val trusted = extracted != null && extracted.textLength >= TRUSTED_INLINE_TEXT
                ArticleEntity(
                    id = id,
                    feedUrl = feedUrl,
                    title = item.title,
                    link = item.link,
                    summary = item.summary?.let { stripHtml(it) },
                    contentHtml = extracted?.contentHtml?.takeIf { readable },
                    imageUrl = leadImageOf(item.imageUrl, extracted),
                    publishedAt = item.publishedAt,
                    fetchedAt = if (trusted) System.currentTimeMillis() else 0,
                    textLength = if (readable) extracted!!.textLength else 0,
                )
            },
        )
        return fresh.size
    }

    /**
     * Fetches and caches each unfetched story's full text and thumbnail in the background, so
     * the article list shows a real photo without the user having to open every story first —
     * which was the only time this ever happened before, and looked like most stories simply
     * had no image. Works from [NewsDao.articlesToPrefetch] rather than only the just-added
     * stories, so a feed the user subscribed to earlier also fills in.
     *
     * Not awaited by [refresh]/[addFeed]: with many stories this could take a while, and the
     * point is for the list to fill in progressively (each `dao.upsertArticle` re-emits the
     * Flow the screen observes) rather than hold the refresh spinner hostage. Capped and
     * sequential, one request at a time — a courtesy background pass, not the user asking to
     * read something, so it must not hammer a single site with concurrent requests.
     */
    private fun prefetchBodies(feedUrl: String) {
        scope.launch {
            val ids = dao.articlesToPrefetch(feedUrl, MAX_PREFETCH_PER_REFRESH)
            for (id in ids) {
                runCatching { prefetchOne(id) }
            }
        }
    }

    private suspend fun prefetchOne(articleId: String) {
        val article = dao.article(articleId) ?: return
        if (article.fetchedAt > 0L) return // already attempted

        when (val response = HttpFetcher.get(article.link)) {
            // Leave fetchedAt at 0 on a transient failure so it is retried next refresh; a
            // site that refuses outright (some block every app) is marked tried, or its
            // stories would be re-requested on every single refresh forever.
            is HttpFetcher.Result.Failure ->
                if (response.isPermanent) dao.upsertArticle(article.copy(fetchedAt = System.currentTimeMillis()))

            // Locale doesn't matter here: only contentHtml and the lead image are kept. body()
            // re-extracts from this cached HTML in the real reading locale when the article is
            // actually opened, which is cheap since it no longer needs the network.
            is HttpFetcher.Result.Success ->
                dao.upsertArticle(withFetchedPage(article, ArticleExtractor.extract(response.body, article.link)))
        }
    }

    /**
     * [article] once its page has been fetched and extracted. The page's body replaces what
     * is stored only if it is real text *and* longer — a feed's inline teaser loses to the
     * full page, but a full inline body never loses to a page the extractor read badly. The
     * thumbnail is taken either way; it doesn't depend on the body being good.
     */
    private fun withFetchedPage(article: ArticleEntity, extracted: ExtractedArticle): ArticleEntity {
        val better = extracted.textLength >= MIN_FULL_TEXT &&
            extracted.blocks.isNotEmpty() &&
            extracted.textLength > article.textLength
        return article.copy(
            contentHtml = if (better) extracted.contentHtml else article.contentHtml,
            fetchedAt = System.currentTimeMillis(),
            textLength = if (better) extracted.textLength else article.textLength,
            // Publishers often give the headline better here than in the feed.
            title = if (better) extracted.title?.takeIf { it.isNotBlank() } ?: article.title else article.title,
            imageUrl = leadImageOf(article.imageUrl, extracted),
        )
    }

    /**
     * The best thumbnail for a story, in descending order of reliability: an image the feed
     * itself named, then the article's own social-share image (`og:image`), then whatever
     * photo the body happens to lead with.
     */
    private fun leadImageOf(existing: String?, extracted: ExtractedArticle?): String? =
        existing
            ?: extracted?.leadImageUrl
            ?: extracted?.blocks?.filterIsInstance<Block.Image>()?.firstOrNull()?.zipPath

    sealed interface ArticleBody {
        data class Ready(val blocks: List<Block>, val truncated: Boolean) : ArticleBody
        data class Failed(val message: String) : ArticleBody
    }

    /** One lock per article, so the reader screen and the playback service never race a fetch. */
    private val bodyLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * The article's blocks, fetching and extracting the full page the first time it is opened.
     *
     * Falls back to the feed's summary when the page can't be reached or the extractor finds
     * too little to be a real article, so opening a story always gives *something* to read —
     * flagged [ArticleBody.Ready.truncated] so the UI can say the text is only the teaser.
     *
     * The reader screen and the playback service both call this for the same story, and the
     * sentence positions they exchange only line up if both get identical blocks. So it is
     * serialised per article, and every path re-extracts from the stored body rather than
     * returning a fresh page extraction directly: whoever comes second sees exactly what the
     * first one saved.
     */
    suspend fun body(articleId: String, locale: Locale): ArticleBody = withContext(Dispatchers.Default) {
        bodyLocks.getOrPut(articleId) { Mutex() }.withLock { loadBody(articleId, locale) }
    }

    private suspend fun loadBody(articleId: String, locale: Locale): ArticleBody {
        var article = dao.article(articleId) ?: return ArticleBody.Failed("Article not found")

        // Not tried yet means the stored body, if any, may only be the feed's teaser: give
        // the page its chance first. The background prefetch usually got here already.
        if (article.fetchedAt == 0L) {
            when (val response = HttpFetcher.get(article.link)) {
                is HttpFetcher.Result.Success -> {
                    article = withFetchedPage(article, ArticleExtractor.extract(response.body, article.link))
                    dao.upsertArticle(article)
                }
                is HttpFetcher.Result.Failure -> if (response.isPermanent) {
                    article = article.copy(fetchedAt = System.currentTimeMillis())
                    dao.upsertArticle(article)
                }
            }
        }

        article.contentHtml?.takeIf { it.isNotBlank() }?.let { stored ->
            val blocks = ArticleExtractor.extract(stored, article.link, locale).blocks
            if (blocks.isNotEmpty()) {
                return ArticleBody.Ready(withHeadline(blocks, article.title, locale), truncated = false)
            }
        }
        return summaryBody(article, locale)
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

        /**
         * An inline feed body at least this long is taken as the whole story without fetching
         * the page. Shorter ones are often teasers (The Verge ships ~650 characters inline for
         * stories three times that), so their page is fetched and the longer text kept.
         */
        const val TRUSTED_INLINE_TEXT = 2_000

        /**
         * Ceiling on how many new stories get their body/thumbnail prefetched per refresh.
         * Protects against a first-ever subscribe to a feed with a long backlog turning into
         * dozens of immediate page fetches; the rest still get fetched normally on open.
         */
        const val MAX_PREFETCH_PER_REFRESH = 20

        /** How long stories are kept, counted from when they were published. */
        val RETENTION_MS = TimeUnit.DAYS.toMillis(30)

        /** A page can advertise a handful of feeds (per category, per author); try the first few. */
        private const val MAX_ADVERTISED_TRIES = 3

        private fun retentionCutoff(): Long = System.currentTimeMillis() - RETENTION_MS

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

        /**
         * [blocks] with the story's headline in front, unless it already opens with it. Pages
         * often keep the headline outside the body the extractor picks, and without it the
         * voice starts mid-story — when stories play back to back, the headline is the only
         * cue that a new one has begun.
         */
        fun withHeadline(blocks: List<Block>, title: String, locale: Locale): List<Block> {
            val headline = title.trim()
            if (headline.isEmpty()) return blocks
            val alreadyThere = blocks.filterIsInstance<Block.Text>()
                .take(LEADING_BLOCKS_CHECKED)
                .filter { it.kind != Block.Text.Kind.PARAGRAPH && it.kind != Block.Text.Kind.QUOTE }
                .any { isSameHeadline(it.text, headline) }
            if (alreadyThere) return blocks
            val heading = Block.Text(headline, Block.Text.Kind.HEADING_1, SentenceSplitter.split(headline, locale))
            return listOf(heading) + blocks
        }

        /**
         * Whether two headlines are the same one, give or take punctuation and a site name
         * tacked on ("Title | The Verge") — one must contain the other and be most of it.
         */
        private fun isSameHeadline(a: String, b: String): Boolean {
            val x = comparable(a)
            val y = comparable(b)
            if (x.isEmpty() || y.isEmpty()) return false
            val (short, long) = if (x.length <= y.length) x to y else y to x
            return short in long && short.length * 2 > long.length
        }

        /** Letters and digits only, lowercased: survives curly quotes, dashes and spacing. */
        private fun comparable(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() }

        private const val LEADING_BLOCKS_CHECKED = 3
    }
}
