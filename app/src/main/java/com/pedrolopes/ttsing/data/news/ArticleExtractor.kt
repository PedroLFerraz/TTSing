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
        "big", "tt", "font", "ins", "del", "ruby", "rt", "rp", "picture", "img",
    )

    private fun walk(element: Element, locale: Locale, out: MutableList<Block>) {
        for (child in element.children()) {
            val tag = child.normalName()
            when {
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
        val text = element.text().trim()
        if (text.isEmpty()) return
        // Leftover link clusters ("Read more", tag lists) survive scoring but shouldn't be read
        // aloud. Headings are exempt: a linked headline is still the headline.
        if (kind !in HEADINGS.values && text.length < LINK_CLUSTER_CHARS && linkDensity(element) > 0.5) return
        out.add(Block.Text(text, kind, SentenceSplitter.split(text, locale)))
    }

    private fun String.trimmed(): String? = trim().takeIf { it.isNotEmpty() }

    private const val MIN_PARAGRAPH_CHARS = 25
    private const val MIN_ARTICLE_CHARS = 200
    private const val LINK_CLUSTER_CHARS = 120
    private const val PARENT_PREFERENCE = 0.95
}
