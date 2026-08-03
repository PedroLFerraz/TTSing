package com.pedrolopes.ttsing.data.news

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One entry in a feed. */
data class FeedItem(
    val title: String,
    val link: String,
    /** The feed's own summary. Usually truncated, which is why articles are fetched in full. */
    val summary: String?,
    /**
     * Full article HTML when the feed inlines it (`content:encoded`, Atom `<content>`).
     * Feeds that do this save a network round-trip and give the most reliable text.
     */
    val contentHtml: String?,
    /** Epoch millis, or 0 when the feed gives no usable date. */
    val publishedAt: Long,
    /** Stable identity for de-duplication across refreshes. */
    val guid: String,
)

data class ParsedFeed(
    val title: String,
    val siteLink: String?,
    val language: String?,
    val items: List<FeedItem>,
)

/**
 * Parses RSS 2.0 and Atom feeds.
 *
 * Deliberately lenient: feeds in the wild break the specs constantly (missing guids, dates in
 * half a dozen formats, summaries in whichever of four elements the publisher preferred), and
 * dropping an entry because one field is malformed is worse than reading it without that field.
 * Pure JVM so the format handling is unit-tested rather than discovered against a live feed.
 */
object RssParser {

    fun parse(xml: String): ParsedFeed? {
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return null

        val channel = doc.getElementsByTag("channel").firstOrNull()
        if (channel != null) return parseRss(channel)

        val feed = doc.getElementsByTag("feed").firstOrNull()
        if (feed != null) return parseAtom(feed)

        return null
    }

    // ---- RSS 2.0 ----

    private fun parseRss(channel: Element): ParsedFeed {
        val items = channel.getElementsByTag("item").mapNotNull { item ->
            val link = item.childText("link")
                ?: item.getElementsByTag("guid").firstOrNull()?.text()?.takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val title = item.childText("title") ?: link
            FeedItem(
                title = title,
                link = link,
                summary = item.childText("description"),
                // content:encoded is the convention for the full body in RSS.
                contentHtml = item.childText("content:encoded"),
                publishedAt = parseDate(item.childText("pubDate") ?: item.childText("dc:date")),
                guid = item.childText("guid") ?: link,
            )
        }
        return ParsedFeed(
            title = channel.childText("title").orEmpty().ifEmpty { "Untitled feed" },
            siteLink = channel.childText("link"),
            language = channel.childText("language"),
            items = items,
        )
    }

    // ---- Atom ----

    private fun parseAtom(feed: Element): ParsedFeed {
        val items = feed.getElementsByTag("entry").mapNotNull { entry ->
            val link = entry.atomLink() ?: return@mapNotNull null
            FeedItem(
                title = entry.childText("title") ?: link,
                link = link,
                summary = entry.childText("summary"),
                contentHtml = entry.childText("content"),
                publishedAt = parseDate(entry.childText("published") ?: entry.childText("updated")),
                guid = entry.childText("id") ?: link,
            )
        }
        return ParsedFeed(
            title = feed.childText("title").orEmpty().ifEmpty { "Untitled feed" },
            siteLink = feed.atomLink(),
            language = feed.attr("xml:lang").takeIf { it.isNotEmpty() },
            items = items,
        )
    }

    /** Atom links live in an attribute; prefer the alternate (human-readable) one. */
    private fun Element.atomLink(): String? {
        val links = children().filter { it.normalName() == "link" && it.hasAttr("href") }
        val alternate = links.firstOrNull { it.attr("rel").let { rel -> rel.isEmpty() || rel == "alternate" } }
        return (alternate ?: links.firstOrNull())?.attr("href")?.takeIf { it.isNotEmpty() }
    }

    /** Direct child by tag name — avoids picking up a same-named tag from a nested element. */
    private fun Element.childText(tag: String): String? =
        children()
            .firstOrNull { it.normalName().equals(tag, ignoreCase = true) }
            ?.let { if (it.childNodeSize() > 0 || it.text().isNotEmpty()) it.text().trim() else null }
            ?.takeIf { it.isNotEmpty() }

    // ---- Dates ----

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",   // RFC-822, the RSS norm
        "EEE, dd MMM yyyy HH:mm:ss z",
        "EEE, dd MMM yyyy HH:mm Z",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",      // ISO-8601, the Atom norm
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd",
    )

    /** Epoch millis, or 0 if the date is missing or in a format we don't recognise. */
    fun parseDate(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0
        for (pattern in DATE_FORMATS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    if (pattern.endsWith("'Z'") || pattern == "yyyy-MM-dd") {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                }.parse(text)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0
    }
}
