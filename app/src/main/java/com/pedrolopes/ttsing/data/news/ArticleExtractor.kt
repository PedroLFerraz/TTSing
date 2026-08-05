package com.pedrolopes.ttsing.data.news

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.SentenceSplitter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

data class ExtractedArticle(
    val title: String?,
    val blocks: List<Block>,
    /**
     * The article body with the page furniture already stripped. Cached instead of the
     * original page so re-opening an article is offline and cheap, and so re-reading it in a
     * different language only has to redo sentence splitting.
     */
    val contentHtml: String,
    /**
     * The page's social-share image (`og:image`/`twitter:image`), for the article-list
     * thumbnail. Far more reliable than the first photo in the body: almost every news page
     * declares one in its `<head>`, and it survives even when the hero image sits outside the
     * scored content root or gets stripped as page furniture.
     */
    val leadImageUrl: String? = null,
) {
    val textLength: Int get() = blocks.filterIsInstance<Block.Text>().sumOf { it.text.length }
}

/**
 * Pulls the actual article out of a news page.
 *
 * A feed's `<description>` is nearly always a truncated teaser, so reading the whole piece
 * means fetching the page and finding the body inside it — surrounded by navigation, cookie
 * banners, related-story rails, comment forms and newsletter prompts.
 *
 * Uses the classic readability approach: score candidate containers by how much
 * prose-shaped text they hold, discount anything that is mostly links (menus and story
 * rails), nudge on class/id names publishers actually use, then take the winner plus its
 * comparable siblings. Pure JVM, so this is exercised by unit tests against realistic page
 * shapes rather than only being discovered against a live site.
 */
object ArticleExtractor {

    fun extract(html: String, baseUri: String = "", locale: Locale = Locale.ENGLISH): ExtractedArticle {
        val doc = Jsoup.parse(html, baseUri)
        val title = extractTitle(doc)
        // Read the social-share image before stripping anything: it lives in <head>, so it
        // survives junk removal, but reading it up front keeps it independent of body scoring.
        val leadImage = extractLeadImage(doc)
        stripJunk(doc)

        val root = findContentRoot(doc)
        val blocks = mutableListOf<Block>()
        if (root != null) walk(root, locale, blocks)

        // A handful of characters means the scoring picked a wrapper with nothing in it;
        // the caller falls back to the feed's own summary in that case.
        return ExtractedArticle(
            title = title,
            blocks = blocks,
            contentHtml = root?.html().orEmpty(),
            leadImageUrl = leadImage,
        )
    }

    // ---- Title ----

    private fun extractTitle(doc: Document): String? {
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trimmed()?.let { return it }
        doc.selectFirst("meta[name=twitter:title]")?.attr("content")?.trimmed()?.let { return it }
        doc.selectFirst("article h1")?.text()?.trimmed()?.let { return it }
        doc.selectFirst("h1")?.text()?.trimmed()?.let { return it }
        // Page titles usually carry the site name after a separator; keep the longer half.
        return doc.title().trimmed()?.split(" | ", " - ", " — ", " · ")?.maxByOrNull { it.length }?.trimmed()
    }

    // ---- Lead image ----

    /**
     * The page's declared share image, resolved to an absolute URL. `absUrl` handles the
     * common cases feeds get wrong on their own — protocol-relative `//cdn…` and site-relative
     * `/media/…` — against the article's base URI. SVGs are skipped, since the reader's bitmap
     * decoder can't render them.
     */
    private fun extractLeadImage(doc: Document): String? {
        for (selector in LEAD_IMAGE_SELECTORS) {
            val url = doc.selectFirst(selector)?.absUrl("content")?.trimmed() ?: continue
            if (isDisplayable(url)) return url
        }
        return null
    }

    private val LEAD_IMAGE_SELECTORS = listOf(
        "meta[property=og:image]",
        "meta[property=og:image:url]",
        "meta[name=og:image]",
        "meta[name=twitter:image]",
        "meta[name=twitter:image:src]",
        "meta[itemprop=image]",
    )

    // ---- Junk removal ----

    private val JUNK_TAGS = listOf(
        "script", "style", "noscript", "iframe", "form", "button", "input", "select",
        "textarea", "svg", "canvas", "video", "audio", "nav", "aside", "header", "footer",
    )

    /** Class/id fragments that reliably mark page furniture rather than article text. */
    private val NEGATIVE = Regex(
        "comment|share|social|sidebar|side-bar|footer|footnote|nav|menu|breadcrumb|banner|" +
            "promo|advert|advertis|sponsor|related|recommend|popular|trending|newsletter|" +
            "subscri|paywall|cookie|consent|modal|popup|masthead|widget|tag-list|meta-|byline|" +
            "author-box|pagination|skip-link|hidden|teaser|outbrain|taboola",
        RegexOption.IGNORE_CASE,
    )

    /** Class/id fragments publishers use for the real body. */
    private val POSITIVE = Regex(
        "article|articlebody|article-body|story|story-body|storybody|content|main|body|entry|" +
            "post|text|prose|rich-text|paywall-article",
        RegexOption.IGNORE_CASE,
    )

    private fun stripJunk(doc: Document) {
        JUNK_TAGS.forEach { tag -> doc.getElementsByTag(tag).forEach { it.remove() } }
        // Hidden containers are usually alternate layouts or consent overlays full of text
        // that would otherwise outweigh the article.
        doc.select("[hidden], [aria-hidden=true], [style*=display:none]").forEach { it.remove() }
    }

    // ---- Candidate scoring ----

    private fun findContentRoot(doc: Document): Element? {
        val body = doc.body() ?: return null

        // A single <article> is a strong, explicit signal; trust it when it has real text.
        val articles = doc.getElementsByTag("article")
        if (articles.size == 1 && articles.first()!!.text().length >= MIN_ARTICLE_CHARS) {
            return articles.first()
        }

        val scores = mutableMapOf<Element, Double>()
        for (paragraph in body.select("p, pre, li, blockquote")) {
            val text = paragraph.text()
            if (text.length < MIN_PARAGRAPH_CHARS) continue

            // Prose has commas and length; navigation and captions have neither.
            val base = 1.0 + text.count { it == ',' } + minOf(text.length / 100.0, 3.0)
            paragraph.parent()?.let { scores[it] = (scores[it] ?: classWeight(it)) + base }
            paragraph.parent()?.parent()?.let { scores[it] = (scores[it] ?: classWeight(it)) + base / 2 }
        }
        if (scores.isEmpty()) return body

        val best = scores.maxByOrNull { (element, score) -> score * (1 - linkDensity(element)) }?.key
            ?: return body

        // Prefer a parent when it scores nearly as well: publishers often split the body into
        // sibling <div>s (ads between paragraphs), and the parent holds the whole piece.
        var candidate = best
        var candidateScore = (scores[best] ?: 0.0) * (1 - linkDensity(best))
        var parent = candidate.parent()
        while (parent != null && parent.normalName() != "body") {
            val parentScore = (scores[parent] ?: 0.0) * (1 - linkDensity(parent))
            if (parentScore >= candidateScore * PARENT_PREFERENCE) {
                candidate = parent
                candidateScore = parentScore
            }
            parent = parent.parent()
        }
        return candidate
    }

    private fun classWeight(element: Element): Double {
        val identity = "${element.className()} ${element.id()}"
        var weight = 0.0
        if (NEGATIVE.containsMatchIn(identity)) weight -= 25.0
        if (POSITIVE.containsMatchIn(identity)) weight += 25.0
        return weight
    }

    /** Fraction of the text that sits inside links — high means a menu or story rail. */
    private fun linkDensity(element: Element): Double {
        val total = element.text().length
        if (total == 0) return 0.0
        val linked = element.select("a").sumOf { it.text().length }
        return (linked.toDouble() / total).coerceIn(0.0, 1.0)
    }

    // ---- HTML -> blocks ----

    private val HEADINGS = mapOf(
        "h1" to Block.Text.Kind.HEADING_1,
        "h2" to Block.Text.Kind.HEADING_2,
        "h3" to Block.Text.Kind.HEADING_3,
        "h4" to Block.Text.Kind.HEADING_3,
        "h5" to Block.Text.Kind.HEADING_3,
        "h6" to Block.Text.Kind.HEADING_3,
    )
    private val PARAGRAPH_TAGS = setOf("p", "li", "dd", "dt", "figcaption", "pre", "address")
    private val NESTABLE_IN_QUOTE = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "div")
    private val INLINE_TAGS = setOf(
        "a", "span", "em", "strong", "i", "b", "u", "s", "small", "sub", "sup", "code",
        "br", "abbr", "cite", "q", "dfn", "kbd", "mark", "samp", "time", "var", "wbr",
        "big", "tt", "font", "ins", "del", "ruby", "rt", "rp",
    )

    private fun walk(element: Element, locale: Locale, out: MutableList<Block>) {
        for (child in element.children()) {
            val tag = child.normalName()
            when {
                tag == "img" -> addImage(child, out)
                tag in HEADINGS -> addText(child, HEADINGS.getValue(tag), locale, out)
                tag == "blockquote" ->
                    if (child.children().any { it.normalName() in NESTABLE_IN_QUOTE }) {
                        walk(child, locale, out)
                    } else {
                        addText(child, Block.Text.Kind.QUOTE, locale, out)
                    }
                tag in PARAGRAPH_TAGS -> addText(child, Block.Text.Kind.PARAGRAPH, locale, out)
                else -> {
                    val onlyInline = child.children().all { it.normalName() in INLINE_TAGS }
                    if (onlyInline && child.text().isNotBlank()) {
                        addText(child, Block.Text.Kind.PARAGRAPH, locale, out)
                    } else {
                        walk(child, locale, out)
                    }
                }
            }
        }
    }

    private fun addText(
        element: Element,
        kind: Block.Text.Kind,
        locale: Locale,
        out: MutableList<Block>,
    ) {
        // Photos are often wrapped inside the paragraph they illustrate, so pull them out
        // ahead of the text rather than losing them.
        element.select("img").forEach { addImage(it, out) }

        val text = element.text().trim()
        if (text.isEmpty()) return
        // Leftover link clusters ("Read more", tag lists) survive scoring but shouldn't be read
        // aloud. Headings are exempt: a linked headline is still the headline.
        if (kind !in HEADINGS.values && text.length < LINK_CLUSTER_CHARS && linkDensity(element) > 0.5) return
        out.add(Block.Text(text, kind, SentenceSplitter.split(text, locale)))
    }

    private fun addImage(img: Element, out: MutableList<Block>) {
        val url = imageUrl(img) ?: return
        // The same photo can appear in both a <picture> source and its fallback <img>.
        if (out.any { it is Block.Image && it.zipPath == url }) return
        val alt = img.attr("alt").trimmed() ?: img.attr("title").trimmed()
        out.add(Block.Image(url, alt))
    }

    /**
     * The image's real address, as an absolute URL.
     *
     * News sites lazy-load almost universally, so `src` is frequently a placeholder (a grey
     * spacer or an inline data URI) while the actual photo sits in `data-src` or a `srcset`.
     * Those are checked first, and the widest candidate in a `srcset` wins.
     */
    private fun imageUrl(img: Element): String? = sequenceOf(
        img.absUrl("data-src"),
        img.absUrl("data-original"),
        img.absUrl("data-lazy-src"),
        widestFromSrcset(img, "data-srcset"),
        widestFromSrcset(img, "srcset"),
        img.absUrl("src"),
    ).firstOrNull { it.isNotEmpty() && isDisplayable(it) && !isSpacer(img) }

    private fun widestFromSrcset(img: Element, attribute: String): String {
        val raw = img.attr(attribute).ifEmpty { return "" }
        // "photo-400.jpg 400w, photo-800.jpg 800w" — take the largest declared width.
        val best = raw.split(',')
            .mapNotNull { candidate ->
                val parts = candidate.trim().split(Regex("\\s+"))
                val url = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val width = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
                url to width
            }
            .maxByOrNull { it.second }
            ?.first
            ?: return ""
        return runCatching { java.net.URL(java.net.URL(img.baseUri()), best).toString() }.getOrDefault(best)
    }

    /** Skips what the bitmap decoder can't render anyway, plus lazy-load placeholders. */
    private fun isDisplayable(url: String): Boolean =
        !url.startsWith("data:", ignoreCase = true) &&
            !url.substringBefore('?').endsWith(".svg", ignoreCase = true)

    /** Tracking pixels and 1x1 spacers declare their size; real photos rarely do so tiny. */
    private fun isSpacer(img: Element): Boolean {
        val width = img.attr("width").toIntOrNull()
        val height = img.attr("height").toIntOrNull()
        return (width != null && width <= SPACER_MAX_PX) || (height != null && height <= SPACER_MAX_PX)
    }

    private fun String.trimmed(): String? = trim().takeIf { it.isNotEmpty() }

    private const val MIN_PARAGRAPH_CHARS = 25
    private const val MIN_ARTICLE_CHARS = 200
    private const val LINK_CLUSTER_CHARS = 120
    private const val PARENT_PREFERENCE = 0.95
    private const val SPACER_MAX_PX = 2
}
