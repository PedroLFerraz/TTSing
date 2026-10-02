package com.pedrolopes.ttsing.data.news

import com.pedrolopes.ttsing.data.epub.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Hits every catalogue feed over the network and checks it is actually worth listening to:
 * the feed parses, has stories, and a couple of those stories extract to real text the same
 * way the app does it (inline body first, then the fetched page).
 *
 * Off by default so the normal test run stays offline and deterministic. Run it with
 * `TTSING_LIVE_FEEDS=1 ./gradlew testDebugUnitTest --tests "*FeedCatalogLiveCheck*"`; the
 * pass/fail table lands in `app/build/reports/feed-catalog-live.txt`.
 */
class FeedCatalogLiveCheck {

    private data class Outcome(
        val feed: CatalogFeed,
        val ok: Boolean,
        val detail: String,
    )

    @Test
    fun `every catalogue feed parses and its stories read as full text`() {
        assumeTrue("set TTSING_LIVE_FEEDS=1 to run", System.getenv("TTSING_LIVE_FEEDS") == "1")

        val only = System.getenv("TTSING_LIVE_FEEDS_ONLY")?.lowercase()
        val feeds = FeedCatalog.feeds.filter { only == null || only in it.title.lowercase() }
        val gate = Semaphore(8)
        val outcomes = runBlocking(Dispatchers.IO) {
            feeds.map { feed -> async { gate.withPermit { check(feed) } } }.awaitAll()
        }

        val report = buildString {
            for (o in outcomes) {
                val mark = when {
                    o.ok -> "PASS"
                    o.feed.teaserOnly -> "TEAS"
                    else -> "FAIL"
                }
                appendLine("$mark  ${o.feed.topic.id}/${o.feed.kind.name.lowercase()}/${o.feed.language}  ${o.feed.title}  —  ${o.detail}")
            }
        }
        File("build/reports").mkdirs()
        File("build/reports/feed-catalog-live.txt").writeText(report)
        println(report)

        val failed = outcomes.filter { !it.ok && !it.feed.teaserOnly }
        assertTrue("Feeds that don't work:\n" + failed.joinToString("\n") { "${it.feed.title}: ${it.detail}" }, failed.isEmpty())
    }

    private suspend fun check(feed: CatalogFeed): Outcome {
        val response = HttpFetcher.get(feed.url)
        if (response is HttpFetcher.Result.Failure) return Outcome(feed, false, "feed fetch: ${response.message}")
        val body = (response as HttpFetcher.Result.Success).body
        val parsed = RssParser.parse(body) ?: return Outcome(feed, false, "not RSS/Atom (${body.take(60).replace('\n', ' ')})")
        if (parsed.items.isEmpty()) return Outcome(feed, false, "no items")
        if ('�' in parsed.title || parsed.items.take(5).any { '�' in it.title }) {
            return Outcome(feed, false, "mis-decoded text: ${parsed.items.first().title}")
        }

        // Sampled as the app stores them: shopping and sponsored posts never reach the list.
        val (ads, stories) = parsed.items.partition { NewsRepository.isAd(it) }
        val samples = stories.take(SAMPLES).map { item -> readableLengths(item) }
        val readable = samples.count { maxOf(it.inline, it.page) >= NewsRepository.MIN_FULL_TEXT }
        val detail = "lang=${parsed.language ?: "?"} items=${parsed.items.size} ads=${ads.size} " +
            "inline/page/reopen chars=${samples.joinToString("  ") { "${it.inline}/${it.page}/${it.reopen}" }}"
        // Whatever was extracted must survive being stored and opened again.
        samples.firstOrNull { it.reopen < it.page * REOPEN_KEPT }
            ?.let { return Outcome(feed, false, "reopen loses text: $detail") }
        // One teaser among a few samples is normal (live blogs, galleries, paid posts); none
        // readable means the source as a whole won't read aloud.
        return Outcome(feed, readable >= 1, detail)
    }

    private data class Lengths(val inline: Int, val page: Int, val reopen: Int)

    /**
     * Characters of prose in [item]'s inline body, in its fetched page, and in that page's
     * body once stored and opened again, the way the reader gets it. The app reads the longer
     * of the first two: a feed whose inline text is consistently far shorter than its pages
     * is shipping teasers.
     */
    private suspend fun readableLengths(item: FeedItem): Lengths {
        val inline = item.contentHtml?.takeIf { it.isNotBlank() }
            ?.let { ArticleExtractor.extract(it, item.link).textLength } ?: 0
        val extracted = (HttpFetcher.get(item.link) as? HttpFetcher.Result.Success)
            ?.let { ArticleExtractor.extract(it.body, item.link) }
        val reopen = extracted?.let { ArticleExtractor.blocksOf(it.contentHtml, item.link) }
            ?.filterIsInstance<Block.Text>()?.sumOf { it.text.length } ?: 0
        return Lengths(inline, extracted?.textLength ?: 0, reopen)
    }

    private companion object {
        const val SAMPLES = 3

        /** Share of the first extraction a reopened story must still have. */
        const val REOPEN_KEPT = 0.98
    }
}
