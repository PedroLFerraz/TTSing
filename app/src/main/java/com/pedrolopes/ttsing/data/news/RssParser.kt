package com.pedrolopes.ttsing.data.news

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.ParsePosition
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
    /**
     * A lead image for the story, if the feed offers one — `media:thumbnail`, `media:content`,
     * an image `enclosure`, or an `<img>` embedded in the summary/full content. Lets the
     * article list show a thumbnail before the page has ever been fetched.
     */
    val imageUrl: String? = null,
    /**
     * The publisher's own labels for the story (`<category>`), as given. Nested ones read like
     * "Gear / Deals". Used to leave out the shopping and sponsored posts some feeds mix in.
     */
    val categories: List<String> = emptyList(),
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
            val title = item.childTitle() ?: link
            val summary = item.childText("description")
            // content:encoded is the convention for the full body in RSS.
            val contentHtml = item.childText("content:encoded")
            FeedItem(
                title = title,
                link = link,
                summary = summary,
                contentHtml = contentHtml,
                publishedAt = parseDate(item.childText("pubDate") ?: item.childText("dc:date")),
                guid = item.childText("guid") ?: link,
                imageUrl = imageUrlOf(item, link, summary, contentHtml),
                categories = item.categories(),
            )
        }
        return ParsedFeed(
            title = channel.childTitle().orEmpty().ifEmpty { "Untitled feed" },
            siteLink = channel.childText("link"),
            language = channel.childText("language"),
            items = items,
        )
    }

    // ---- Atom ----

    private fun parseAtom(feed: Element): ParsedFeed {
        val items = feed.getElementsByTag("entry").mapNotNull { entry ->
            val link = entry.atomLink() ?: return@mapNotNull null
            // YouTube keeps a video's description in media:group rather than <summary>: plain
            // text whose line breaks (chapter timestamps, links) are worth keeping.
            val summary = entry.childText("summary")
                ?: entry.getElementsByTag("media:description").firstOrNull()?.wholeText()?.trim()?.takeIf { it.isNotEmpty() }
            val contentHtml = entry.childText("content")
            FeedItem(
                title = entry.childTitle() ?: link,
                link = link,
                summary = summary,
                contentHtml = contentHtml,
                publishedAt = parseDate(entry.childText("published") ?: entry.childText("updated")),
                guid = entry.childText("id") ?: link,
                imageUrl = imageUrlOf(entry, link, summary, contentHtml),
                categories = entry.categories(),
            )
        }
        return ParsedFeed(
            title = feed.childTitle().orEmpty().ifEmpty { "Untitled feed" },
            siteLink = feed.atomLink(),
            language = feed.attr("xml:lang").takeIf { it.isNotEmpty() },
            items = items,
        )
    }

    /**
     * A lead image for [entryOrItem], checked in the order feeds actually use them: explicit
     * media metadata first (reliable, deliberately chosen by the publisher), then an image
     * physically embedded in the story's own HTML as a last resort.
     */
    private fun imageUrlOf(entryOrItem: Element, link: String, summary: String?, contentHtml: String?): String? {
        entryOrItem.getElementsByTag("media:thumbnail").firstOrNull()
            ?.attr("url")?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        entryOrItem.getElementsByTag("media:content")
            .firstOrNull { it.attr("medium").equals("image", ignoreCase = true) || isImageUrl(it.attr("url")) }
            ?.attr("url")?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // RSS: <enclosure url="..." type="image/...">. Atom: <link rel="enclosure" href="..." type="image/...">.
        entryOrItem.getElementsByTag("enclosure")
            .firstOrNull { it.attr("type").startsWith("image/", ignoreCase = true) }
            ?.attr("url")?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        entryOrItem.children()
            .firstOrNull {
                it.normalName() == "link" &&
                    it.attr("rel").equals("enclosure", ignoreCase = true) &&
                    it.attr("type").startsWith("image/", ignoreCase = true)
            }
            ?.attr("href")?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return firstImageIn(contentHtml, link) ?: firstImageIn(summary, link)
    }

    private fun isImageUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return IMAGE_EXTENSIONS.any { path.endsWith(it) }
    }

    /** The first `<img>`'s address in a fragment of story HTML, resolved against [baseUri]. */
    private fun firstImageIn(html: String?, baseUri: String): String? {
        if (html.isNullOrBlank()) return null
        return runCatching {
            Jsoup.parse(html, baseUri).selectFirst("img")?.absUrl("src")?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp")

    /** Atom links live in an attribute; prefer the alternate (human-readable) one. */
    private fun Element.atomLink(): String? {
        val links = children().filter { it.normalName() == "link" && it.hasAttr("href") }
        val alternate = links.firstOrNull { it.attr("rel").let { rel -> rel.isEmpty() || rel == "alternate" } }
        return (alternate ?: links.firstOrNull())?.attr("href")?.takeIf { it.isNotEmpty() }
    }

    /**
     * The `<title>` as plain text. Titles are often HTML in disguise — Atom's `type="html"`,
     * or WordPress RSS putting `it&#8217;s` inside CDATA — and decoding only the XML layer
     * left the entity in place, for the voice to read out as "ampersand hash eight two one
     * seven". Decoded as HTML whenever it carries an entity or a tag.
     */
    private fun Element.childTitle(): String? {
        val raw = childText("title") ?: return null
        if (!HTML_IN_TEXT.containsMatchIn(raw)) return raw
        return Jsoup.parse(raw).text().trim().ifEmpty { raw }
    }

    private val HTML_IN_TEXT = Regex("""&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);|<[a-zA-Z/]""")

    /** RSS puts the label in the element's text, Atom in its `term` (or `label`) attribute. */
    private fun Element.categories(): List<String> =
        children()
            .filter { it.normalName().equals("category", ignoreCase = true) }
            .mapNotNull { category ->
                sequenceOf(category.text(), category.attr("label"), category.attr("term"))
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
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
        "EEE, dd MMM yyyy HH:mm:ss",     // no zone: taken as UTC
        "dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss z",
        "dd MMM yyyy HH:mm Z",
        "dd MMM yyyy HH:mm:ss",
        "dd MMM yyyy",
        "EEE, dd MMM yy HH:mm:ss Z",     // two-digit year; see plausible()
        "yyyy-MM-dd'T'HH:mm:ssXXX",      // ISO-8601, the Atom norm
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssZ",        // offset without the colon
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",         // no zone
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss Z",
        "yyyy-MM-dd HH:mm:ssZ",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "dd/MM/yyyy HH:mm:ss",
    )

    /** Epoch millis, or 0 if the date is missing or in a format we don't recognise. */
    fun parseDate(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0
        val cleaned = text
            .replace(LONG_FRACTION, "$1")       // ".123456Z" -> ".123Z"
            .replace(UT_ZONE, " GMT")           // "UT" isn't a zone Java knows
            .replace(COLON_OFFSET, "$1$2")      // "-03:00" -> "-0300" for the Z patterns
        // A wrong weekday ("Tue, 12 Mar" on a Wednesday) fails a strict parse; the date is still right.
        val withoutWeekday = cleaned.replace(WEEKDAY, "")
        for (candidate in listOf(text, cleaned, withoutWeekday).distinct()) {
            for (pattern in DATE_FORMATS) {
                val time = tryParse(candidate, pattern) ?: continue
                if (time > PLAUSIBLE_AFTER) return time
            }
        }
        return 0
    }

    /** [text] read as [pattern], only if the pattern accounts for all of it (a zone-less one mustn't swallow "+0200" and ignore it). */
    private fun tryParse(text: String, pattern: String): Long? = runCatching {
        val position = ParsePosition(0)
        SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            // No zone in the pattern (or a literal 'Z'): the clock reading is UTC.
            if (!ZONE_LETTERS.containsMatchIn(pattern.replace(QUOTED, "")) || pattern.contains("'Z'")) {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }.parse(text, position)?.takeIf { position.index == text.length }?.time
    }.getOrNull()

    /** Rules out a two-digit year read by a four-digit pattern as year 14 AD. 1990-01-01. */
    private const val PLAUSIBLE_AFTER = 631_152_000_000L
    private val LONG_FRACTION = Regex("""(\.\d{3})\d+""")
    private val UT_ZONE = Regex("""\s+UT$""")
    private val COLON_OFFSET = Regex("""([+-]\d{2}):(\d{2})$""")
    private val WEEKDAY = Regex("""^[A-Za-z]{3,9},?\s+""")
    private val ZONE_LETTERS = Regex("[ZzX]")
    private val QUOTED = Regex("'[^']*'")
}
