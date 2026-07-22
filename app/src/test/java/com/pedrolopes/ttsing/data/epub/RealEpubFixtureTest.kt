package com.pedrolopes.ttsing.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the same EPUB files that are pushed to the device during manual testing,
 * exercising the real ZIP + OPF + nav + chapter pipeline end to end.
 */
class RealEpubFixtureTest {

    private fun fixture(name: String): File {
        val url = requireNotNull(javaClass.classLoader?.getResource(name)) { "Missing test resource $name" }
        return File(url.toURI())
    }

    @Test
    fun `english fixture parses with chapters and sentences`() {
        EpubParser(fixture("the-little-garden.epub")).use { parser ->
            val book = parser.parseBook()
            assertEquals("The Little Garden", book.title)
            assertEquals("A. Tester", book.author)
            assertEquals("en", book.locale().language)
            assertEquals(2, book.spine.size)
            assertEquals(2, book.toc.size)
            assertEquals("Chapter One: Morning", book.toc[0].title)

            val chapter = parser.loadChapter(book, 0)
            val texts = chapter.blocks.filterIsInstance<Block.Text>()
            assertEquals(Block.Text.Kind.HEADING_1, texts.first().kind)
            // The first paragraph has three sentences.
            val firstParagraph = texts.first { it.kind == Block.Text.Kind.PARAGRAPH }
            assertEquals(3, firstParagraph.sentences.size)
            val s0 = firstParagraph.sentences[0]
            assertEquals("The morning was bright and clear.", firstParagraph.text.substring(s0.start, s0.end))
        }
    }

    @Test
    fun `portuguese fixture parses with correct locale`() {
        EpubParser(fixture("o-pequeno-jardim.epub")).use { parser ->
            val book = parser.parseBook()
            assertEquals("O Pequeno Jardim", book.title)
            assertEquals("pt", book.locale().language)
            assertEquals(2, book.spine.size)

            val chapter = parser.loadChapter(book, 0)
            val paragraphs = chapter.blocks.filterIsInstance<Block.Text>()
                .filter { it.kind == Block.Text.Kind.PARAGRAPH }
            assertTrue(paragraphs.isNotEmpty())
            // Portuguese sentence segmentation should split the first paragraph into three.
            assertEquals(3, paragraphs.first().sentences.size)
        }
    }
}
