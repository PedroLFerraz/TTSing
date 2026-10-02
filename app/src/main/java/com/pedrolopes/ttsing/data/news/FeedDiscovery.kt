package com.pedrolopes.ttsing.data.news

import org.jsoup.Jsoup
import java.net.URL

/**
 * Finds the feed behind a website, so pasting `theverge.com` works as well as pasting the
 * exact feed address — most people know a site, not its feed URL.
 *
 * Pure JVM: the caller fetches, this only reads pages and builds candidate addresses.
 */
object FeedDiscovery {

    /**
     * Feeds a page advertises about itself (`<link rel="alternate" type="application/rss+xml">`
     * and the Atom equivalent), as absolute URLs, best first. Comment feeds — which WordPress
     * advertises right next to the posts feed — are dropped: they hold replies, not stories.
     */
    fun advertisedFeeds(html: String, pageUrl: String): List<String> {
        val doc = runCatching { Jsoup.parse(html, pageUrl) }.getOrNull() ?: return emptyList()
        return doc.select("link[href]")
            .filter { link ->
                link.attr("rel").split(' ').any { it.equals("alternate", ignoreCase = true) } &&
                    FEED_TYPES.any { link.attr("type").contains(it, ignoreCase = true) }
            }
            .filterNot { link -> COMMENTS.containsMatchIn(link.attr("title")) || COMMENTS.containsMatchIn(link.attr("href")) }
            .mapNotNull { it.absUrl("href").takeIf { url -> url.startsWith("http") } }
            .distinct()
    }

    /** Where sites conventionally keep their feed, on [pageUrl]'s own origin. */
    fun conventionalFeeds(pageUrl: String): List<String> {
        val url = runCatching { URL(pageUrl) }.getOrNull() ?: return emptyList()
        val origin = buildString {
            append(url.protocol).append("://").append(url.host)
            if (url.port != -1 && url.port != url.defaultPort) append(':').append(url.port)
        }
        return CONVENTIONAL_PATHS.map { origin + it }
    }

    private val FEED_TYPES = listOf("rss+xml", "atom+xml")
    private val COMMENTS = Regex("comment", RegexOption.IGNORE_CASE)
    private val CONVENTIONAL_PATHS = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml")
}
