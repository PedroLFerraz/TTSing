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
 * What a refresh found: [newStories] stored, and how many feeds couldn't be read at all
 * ([failedFeeds], with the first [firstError]), so "nothing new" and "couldn't connect" don't
 * look the same.
 */
data class RefreshResult(
    val newStories: Int = 0,
    val failedFeeds: Int = 0,
    val firstError: String? = null,
) {
    operator fun plus(other: RefreshResult) = RefreshResult(
        newStories + other.newStories,
        failedFeeds + other.failedFeeds,
        firstError ?: other.firstError,
    )

    /** One line for a snackbar. */
    fun describe(): String {
        val stories = when (newStories) {
            0 -> "No new stories"
            1 -> "1 new story"
            else -> "$newStories new stories"
        }
        if (failedFeeds == 0) return stories
        val failed = "ouldn't reach $failedFeeds ${if (failedFeeds == 1) "feed" else "feeds"}"
        return if (newStories == 0) "C$failed" else "$stories · c$failed"
    }
}

/**
 * [scope] is used only for the background prefetch in [refresh]/[addFeed]: firing full-text
 * and thumbnail fetches that outlive whichever screen triggered them, so the article list
 * keeps filling in even after the user has moved on. Every other method here is a plain
 * suspend function that runs in the caller's own scope, same as before.
 */
class NewsRepository(
    private val dao: NewsDao,
    private val scope: CoroutineScope,
    /** Where thumbnails are cached, so those of pruned stories can be dropped. */
    private val images: ArticleImageStore? = null,
) {

    /** The list the current story was opened from, for playing on into the next one. */
    val queue = NewsQueue()

    fun observeFeeds(): Flow<List<FeedEntity>> = dao.observeFeeds()

    fun observeArticles(feedUrl: String): Flow<List<ArticleEntity>> = dao.observeArticles(feedUrl)

    /** Every feed's [LATEST_PER_FEED] newest stories, newest first: the All list and its play-on queue. */
    fun observeLatest(): Flow<List<ArticleEntity>> = dao.observeLatest(LATEST_PER_FEED, LATEST_LIMIT)

    fun observeLatestInGroup(group: String): Flow<List<ArticleEntity>> =
        dao.observeLatestInFolder(group, LATEST_PER_FEED, LATEST_LIMIT)

    private suspend fun existingGroups(): List<String> = dao.feeds().mapNotNull { it.folder }

    /** Files [feedUrl] under [group]; blank takes it out of any group. */
    suspend fun setGroup(feedUrl: String, group: String?) {
        val name = group?.trim()?.takeIf { it.isNotEmpty() }?.let { canonicalGroup(it, existingGroups()) }
        dao.setFolder(feedUrl, name)
    }

    /**
     * Files every feed in [feedUrls] under [group]: how a new group is made. Returns the name
     * it was filed under, which keeps the spelling of a group that already exists ("science"
     * joins "Science"), or null for a blank name.
     */
    suspend fun createGroup(group: String, feedUrls: Collection<String>): String? {
        if (group.isBlank()) return null
        val name = canonicalGroup(group, existingGroups())
        for (url in feedUrls) dao.setFolder(url, name)
        return name
    }

    /** Renames a group, merging it into another if [to] is already one. Blank names are ignored. */
    suspend fun renameGroup(from: String, to: String) {
        if (to.isBlank()) return
        val others = existingGroups().filterNot { it.equals(from, ignoreCase = true) }
        dao.renameFolder(from, canonicalGroup(to, others))
    }

    /** Deletes a group; its feeds stay subscribed, ungrouped. */
    suspend fun deleteGroup(group: String) = dao.clearFolder(group)

    suspend fun article(id: String): ArticleEntity? = dao.article(id)

    suspend fun feed(url: String): FeedEntity? = dao.feed(url)

    /** The language a feed declares, used to pick the reading voice for its articles. */
    suspend fun feedLocale(feedUrl: String): Locale? =
        dao.feed(feedUrl)?.language
            ?.let { Locale.forLanguageTag(it.trim().replace('_', '-')) }
            ?.takeIf { it.language.isNotEmpty() }

    /** The next unheard story in [queue] after [articleId], or null at the end of the list. */
    suspend fun nextUnread(articleId: String): String? =
        // Videos have nothing to read aloud; playing on skips past them.
        queue.nextAfter(articleId) { id -> dao.article(id)?.let { !it.isRead && YouTube.videoId(it.link) == null } == true }

    sealed interface AddResult {
        data class Added(val feed: FeedEntity, val articleCount: Int) : AddResult
        /** The same feed (by [feedKey]) was already subscribed; nothing was changed. */
        data class AlreadySubscribed(val feed: FeedEntity) : AddResult
        data class Failed(val message: String) : AddResult
    }

    /** The subscribed feed with the same identity as [url], whatever its spelling. */
    private suspend fun subscribedAs(url: String): FeedEntity? {
        val key = feedKey(url)
        return dao.feeds().firstOrNull { feedKey(it.url) == key }
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
        subscribedAs(url)?.let { return AddResult.AlreadySubscribed(it) }

        val response = HttpFetcher.get(url)
        if (response is HttpFetcher.Result.Failure) return AddResult.Failed("Couldn't load the feed: ${response.message}")
        val page = response as HttpFetcher.Result.Success

        val (feedUrl, parsed) = RssParser.parse(page.body)?.let { url to it }
            ?: discoverFeed(page)
            ?: return AddResult.Failed("That address isn't a feed, and the page doesn't point to one")

        // A site's address can lead to a feed that is already followed.
        subscribedAs(feedUrl)?.let { return AddResult.AlreadySubscribed(it) }

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
            folder = (topic ?: catalogued?.topic)?.label?.let { canonicalGroup(it, existingGroups()) },
        )
        // Never overwrites: a second add of the same feed (a double tap) keeps the first one's addedAt.
        if (dao.insertFeedIfAbsent(feed) == -1L) return AddResult.AlreadySubscribed(dao.feed(feedUrl) ?: feed)
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

    suspend fun removeFeed(url: String) = dao.removeFeed(url)

    /** Re-reads every subscribed feed: how many stories are new, and how many feeds couldn't be read. */
    suspend fun refreshAll(): RefreshResult {
        // News goes stale; without this the database only ever grows, a full article body
        // per story. Stories already heard or half-heard go too — a month on, they're history.
        dao.deleteArticlesPublishedBefore(retentionCutoff())
        images?.evict(dao.imageUrls().toSet())
        var total = RefreshResult()
        for (feed in dao.feeds()) {
            total += refresh(feed.url)
        }
        return total
    }

    suspend fun refresh(feedUrl: String): RefreshResult {
        if (dao.feed(feedUrl) == null) return RefreshResult()
        val result = when (val response = HttpFetcher.get(feedUrl)) {
            is HttpFetcher.Result.Success -> {
                val parsed = RssParser.parse(response.body)
                if (parsed == null) {
                    RefreshResult(failedFeeds = 1, firstError = "Not a readable feed")
                } else {
                    val catalogued = FeedCatalog.find(feedUrl)
                    // An UPDATE, not an upsert of a copy read before the download: a feed
                    // removed meanwhile stays removed, and a group set meanwhile stays set.
                    dao.updateFeedInfo(
                        url = feedUrl,
                        title = catalogued?.title ?: parsed.title,
                        siteLink = parsed.siteLink,
                        // The catalogue knows better than feeds that all claim "en".
                        language = catalogued?.language ?: parsed.language,
                        refreshedAt = System.currentTimeMillis(),
                        // Feeds subscribed before topics existed pick theirs up here.
                        topic = catalogued?.topic?.id,
                    )
                    RefreshResult(newStories = storeItems(feedUrl, parsed.items))
                }
            }
            is HttpFetcher.Result.Failure -> RefreshResult(failedFeeds = 1, firstError = response.message)
        }
        // Fire regardless of the feed fetch: this also backfills stories stored earlier that
        // still have no thumbnail, which don't depend on the latest feed content.
        prefetchBodies(feedUrl)
        return result
    }

    /** Inserts stories not seen before, returning how many; existing ones keep their text and position. */
    private suspend fun storeItems(feedUrl: String, items: List<FeedItem>): Int {
        val known = dao.articleIds(feedUrl).toSet()
        val cutoff = retentionCutoff()
        val fresh = items
            // Older than what refreshAll() keeps: storing it would only bring a deleted story
            // back as unread, to be deleted again next time.
            .filter { it.publishedAt == 0L || it.publishedAt >= cutoff }
            .filterNot { isAd(it) }
            .map { it to articleId(feedUrl, it.guid) }
            .filter { (_, id) -> id !in known }

        if (fresh.isEmpty()) return 0

        val seenAt = System.currentTimeMillis()
        val stored = dao.upsertArticlesIfSubscribed(
            feedUrl,
            fresh.map { (item, id) ->
                // Some feeds ship the whole body inline, which saves fetching the page at all.
                // Others ship a few paragraphs of teaser in the same element, so a short inline
                // body is kept to read from but the page is still fetched (fetchedAt stays 0)
                // and wins if it turns out longer.
                val inline = item.contentHtml?.takeIf { it.isNotBlank() }
                val extracted = inline?.let { ArticleExtractor.extract(it, item.link) }
                val readable = extracted != null && extracted.textLength >= MIN_FULL_TEXT
                val trusted = extracted != null && extracted.textLength >= TRUSTED_INLINE_TEXT
                val isVideo = YouTube.videoId(item.link) != null
                ArticleEntity(
                    id = id,
                    feedUrl = feedUrl,
                    title = item.title,
                    link = item.link,
                    // A video's description is already plain text, laid out in lines.
                    summary = if (isVideo) item.summary else item.summary?.let { stripHtml(it) },
                    contentHtml = extracted?.contentHtml?.takeIf { readable },
                    imageUrl = leadImageOf(item.imageUrl, extracted),
                    // No usable date: dated by when it was first seen, so it sorts among its
                    // contemporaries and ages out with them instead of lingering undated.
                    publishedAt = if (item.publishedAt > 0) item.publishedAt else seenAt,
                    // A video's watch page has no story in it; marking it fetched keeps the
                    // prefetch from scraping YouTube for nothing.
                    fetchedAt = if (trusted || isVideo) System.currentTimeMillis() else 0,
                    textLength = if (readable) extracted!!.textLength else 0,
                )
            },
        )
        return if (stored) fresh.size else 0
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
        fetchPage(article)
    }

    /**
     * Downloads [article]'s page and stores what came of it, returning the article as stored.
     *
     * Leaves fetchedAt at 0 on a transient failure so it is retried next refresh; a site that
     * refuses outright (some block every app) is marked tried, or its stories would be
     * re-requested on every single refresh forever. Either way the failure is recorded as the
     * reason there is no full text.
     *
     * Written back with [NewsDao.updateFetched], not by upserting the copy: the download takes
     * seconds, and in that time the story may have been read to a new position or deleted with
     * its feed.
     */
    private suspend fun fetchPage(article: ArticleEntity): ArticleEntity {
        val updated = when (val response = HttpFetcher.get(article.link)) {
            is HttpFetcher.Result.Failure -> article.copy(
                fetchedAt = if (response.isPermanent) System.currentTimeMillis() else article.fetchedAt,
                fullTextIssue = fullTextIssue(article.textLength, downloadFailed = true),
            )
            // Locale doesn't matter here: only contentHtml and the lead image are kept. body()
            // turns this cached HTML into blocks in the real reading locale when the article
            // is actually opened, which is cheap since it no longer needs the network.
            is HttpFetcher.Result.Success ->
                withFetchedPage(article, ArticleExtractor.extract(response.body, article.link))
        }
        dao.updateFetched(
            id = updated.id,
            contentHtml = updated.contentHtml,
            fetchedAt = updated.fetchedAt,
            textLength = updated.textLength,
            title = updated.title,
            imageUrl = updated.imageUrl,
            issue = updated.fullTextIssue,
        )
        return updated
    }

    /**
     * Tries again for a story's full text after a failure: forgets that the page was tried,
     * fetches it, and says whether the full text is there now. [ArticleEntity.fullTextIssue]
     * tells the reader why it wasn't, and is updated by this. The caller reloads the body
     * ([body]) afterwards if this returns true.
     */
    suspend fun retryFullText(articleId: String): Boolean = withContext(Dispatchers.Default) {
        bodyLocks.getOrPut(articleId) { Mutex() }.withLock {
            val article = dao.article(articleId) ?: return@withLock false
            val updated = fetchPage(article.copy(fetchedAt = 0, fullTextIssue = null))
            updated.fullTextIssue == null
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
        val textLength = if (better) extracted.textLength else article.textLength
        return article.copy(
            contentHtml = if (better) extracted.contentHtml else article.contentHtml,
            fetchedAt = System.currentTimeMillis(),
            textLength = textLength,
            fullTextIssue = fullTextIssue(textLength, downloadFailed = false),
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
     * serialised per article, and every path reads its blocks from the stored body rather
     * than returning a fresh page extraction directly: whoever comes second sees exactly what
     * the first one saved.
     */
    suspend fun body(articleId: String, locale: Locale): ArticleBody = withContext(Dispatchers.Default) {
        bodyLocks.getOrPut(articleId) { Mutex() }.withLock { loadBody(articleId, locale) }
    }

    private suspend fun loadBody(articleId: String, locale: Locale): ArticleBody {
        var article = dao.article(articleId) ?: return ArticleBody.Failed("Article not found")

        // Not tried yet means the stored body, if any, may only be the feed's teaser: give
        // the page its chance first. The background prefetch usually got here already.
        if (article.fetchedAt == 0L) article = fetchPage(article)

        article.contentHtml?.takeIf { it.isNotBlank() }?.let { stored ->
            val blocks = ArticleExtractor.blocksOf(stored, article.link, locale)
            if (blocks.isNotEmpty()) {
                val all = withHeadline(blocks, article.title, locale)
                recordBlockCount(article, all.size)
                return ArticleBody.Ready(all, truncated = false)
            }
        }
        return summaryBody(article, locale).also { body ->
            if (body is ArticleBody.Ready) recordBlockCount(article, body.blocks.size)
        }
    }

    /** Remembers how long the body is, so a saved position can be read as how far through it is. */
    private suspend fun recordBlockCount(article: ArticleEntity, count: Int) {
        if (article.blockCount != count) dao.setBlockCount(article.id, count)
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

    /**
     * Remembers where the listener is. The story counts as heard only once [isReadAt] says so;
     * position (0, 0) is how a finished story (and a watched video) is reported.
     */
    suspend fun savePosition(articleId: String, blockIndex: Int, sentenceIndex: Int, finished: Boolean = false) {
        val article = dao.article(articleId) ?: return
        val read = finished || isReadAt(article.isRead, blockIndex, article.blockCount)
        dao.updatePosition(articleId, blockIndex, sentenceIndex, System.currentTimeMillis(), read)
    }

    /** Marks stories heard or unheard by hand: a long-press, or "mark all read" on a list. */
    suspend fun setRead(ids: Collection<String>, read: Boolean) {
        // SQLite caps how many values one statement can bind.
        for (chunk in ids.chunked(READ_CHUNK)) dao.setRead(chunk, read)
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

        /** Newest stories kept in view per feed on the All list, so a busy feed can't crowd out a quiet one. */
        const val LATEST_PER_FEED = 40

        /** Ceiling on the All list as a whole. */
        const val LATEST_LIMIT = 2_000

        private const val READ_CHUNK = 500

        /** A story counts as heard once the listener is this far through it. */
        const val READ_FRACTION = 0.8f

        /**
         * Whether a story is heard after its position is saved at [blockIndex]. Stays heard once
         * heard; otherwise it takes being [READ_FRACTION] of the way through the body, so pausing
         * at the third sentence doesn't grey the story out. The fraction is by blocks, counting
         * the one being read. Finishing a story marks it heard separately, since its position is
         * then reset to the start.
         */
        fun isReadAt(wasRead: Boolean, blockIndex: Int, blockCount: Int): Boolean {
            if (wasRead) return true
            if (blockCount <= 0) return false
            return (blockIndex + 1).toFloat() / blockCount >= READ_FRACTION
        }

        /**
         * Why a story with [textLength] characters of stored full text has none worth reading,
         * or null if it has. [downloadFailed] separates a page that couldn't be loaded from one
         * that loaded but held only a stub (a paywall, a nav page).
         */
        fun fullTextIssue(textLength: Int, downloadFailed: Boolean): String? = when {
            textLength >= MIN_FULL_TEXT -> null
            downloadFailed -> ArticleEntity.ISSUE_DOWNLOAD_FAILED
            else -> ArticleEntity.ISSUE_TOO_SHORT
        }

        /**
         * [name] with the spelling of the group it matches among [existing], ignoring case, so
         * "science" files under "Science" instead of making a second chip. A new name is kept
         * as typed, trimmed.
         */
        fun canonicalGroup(name: String, existing: Collection<String>): String {
            val trimmed = name.trim()
            return existing.firstOrNull { it == trimmed }
                ?: existing.firstOrNull { it.equals(trimmed, ignoreCase = true) }
                ?: trimmed
        }

        /**
         * What makes two addresses the same feed: scheme, `www.`, the host's case, a trailing
         * slash and a fragment don't count. One definition for subscribing, for the Discover
         * screen's "already subscribed" and for the catalogue, so they can't disagree. (The
         * path's case does count; servers may care.)
         */
        fun feedKey(url: String): String {
            val rest = url.trim().replaceFirst(SCHEME, "").substringBefore('#')
            val host = rest.substringBefore('/')
            return (host.lowercase().removePrefix("www.") + rest.removePrefix(host)).trimEnd('/')
        }

        private val SCHEME = Regex("^(?:https?|feed)://", RegexOption.IGNORE_CASE)

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

        /**
         * Whether [item] is shopping or a paid post rather than news: coupon codes, deal
         * roundups, buying guides, sponsored content. Some otherwise good feeds mix these in
         * (half of Wired's main feed, at one point), and none is worth listening to.
         */
        fun isAd(item: FeedItem): Boolean =
            AD_TITLE.containsMatchIn(item.title) ||
                item.categories.any { category -> category.split('/', '>', '|', '›').any { AD_CATEGORY.matches(it.trim()) } }

        /**
         * A whole category label, or one level of a nested one ("Gear / Deals"). Matched whole
         * so "Mergers and deals" stays news.
         */
        private val AD_CATEGORY = Regex(
            "coupons?|cupons?|deals?|sponsored|patrocinado|webinars?|whitepapers?|guia de compras|buying guides?|ofertas?",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Words only shopping and paid posts put in a headline. Bare "deal" and "preço" are
         * left out on purpose: trade deals and price rises are news.
         */
        private val AD_TITLE = Regex(
            """\b(promo codes?|coupons?|cupom|cupons|ofertas? do dia|daily deals?|patrocinado|publieditorial)\b""",
            RegexOption.IGNORE_CASE,
        )

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
