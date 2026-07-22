package com.pedrolopes.ttsing.data.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.InputStream
import java.util.Locale

/**
 * Converts a chapter XHTML document into a flat list of [Block]s: text blocks with
 * pre-computed sentence spans (for TTS + highlighting) and inline images.
 */
class ChapterLoader(
    private val locale: Locale,
    private val entryExists: (String) -> Boolean = { true },
) {

    fun parse(input: InputStream, chapterZipPath: String): List<Block> {
        val doc = Jsoup.parse(input, null, "")
        EpubSanitizer.sanitize(doc)
        val baseDir = EpubPaths.parentDir(chapterZipPath)
        val blocks = mutableListOf<Block>()
        walk(doc.body(), baseDir, blocks)
        return blocks
    }

    private fun walk(element: Element, baseDir: String, out: MutableList<Block>) {
        for (child in element.children()) {
            val tag = child.tagName().lowercase()
            when {
                tag in skippedTags -> {}
                tag == "img" -> addImage(child.attr("src"), child.attr("alt"), baseDir, out)
                tag == "image" -> addImage(
                    child.attr("xlink:href").ifEmpty { child.attr("href") },
                    null, baseDir, out,
                )
                tag in headingKinds -> addText(child, headingKinds.getValue(tag), baseDir, out)
                tag == "blockquote" -> {
                    if (child.children().any { it.tagName().lowercase() in nestableInQuote }) {
                        walk(child, baseDir, out)
                    } else {
                        addText(child, Block.Text.Kind.QUOTE, baseDir, out)
                    }
                }
                tag in paragraphTags || tag == "table" ->
                    addText(child, Block.Text.Kind.PARAGRAPH, baseDir, out)
                else -> {
                    // Generic container (div, section, figure, ol, ul, ...): treat as a
                    // paragraph when it only holds inline content, otherwise recurse.
                    val onlyInlineChildren = child.children().all { it.tagName().lowercase() in inlineTags }
                    if (onlyInlineChildren && child.text().isNotBlank()) {
                        addText(child, Block.Text.Kind.PARAGRAPH, baseDir, out)
                    } else {
                        walk(child, baseDir, out)
                    }
                }
            }
        }
    }

    private fun addText(element: Element, kind: Block.Text.Kind, baseDir: String, out: MutableList<Block>) {
        for (img in element.select("img")) {
            addImage(img.attr("src"), img.attr("alt"), baseDir, out)
        }
        for (img in element.select("image")) {
            addImage(img.attr("xlink:href").ifEmpty { img.attr("href") }, null, baseDir, out)
        }
        val text = element.text().trim()
        if (text.isEmpty()) return
        out.add(Block.Text(text, kind, SentenceSplitter.split(text, locale)))
    }

    private fun addImage(src: String, alt: String?, baseDir: String, out: MutableList<Block>) {
        if (src.isEmpty()) return
        val path = EpubPaths.resolve(baseDir, src)
        if (!entryExists(path)) return
        out.add(Block.Image(path, alt?.takeIf { it.isNotBlank() }))
    }

    private companion object {
        val headingKinds = mapOf(
            "h1" to Block.Text.Kind.HEADING_1,
            "h2" to Block.Text.Kind.HEADING_2,
            "h3" to Block.Text.Kind.HEADING_3,
            "h4" to Block.Text.Kind.HEADING_3,
            "h5" to Block.Text.Kind.HEADING_3,
            "h6" to Block.Text.Kind.HEADING_3,
        )
        val paragraphTags = setOf("p", "li", "dd", "dt", "figcaption", "caption", "pre", "address")
        val skippedTags = setOf("script", "style", "template", "audio", "video", "svg")
        val nestableInQuote = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "div")
        val inlineTags = setOf(
            "a", "span", "em", "strong", "i", "b", "u", "s", "small", "sub", "sup", "code",
            "br", "abbr", "cite", "q", "dfn", "kbd", "mark", "samp", "time", "var", "wbr",
            "big", "tt", "font", "ins", "del", "ruby", "rt", "rp",
        )
    }
}
