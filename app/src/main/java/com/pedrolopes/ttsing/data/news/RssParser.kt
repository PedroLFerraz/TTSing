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
            val summary = entry.childText("summary")
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
