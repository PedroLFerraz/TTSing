package com.pedrolopes.ttsing.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

class ChapterLoaderTest {

    private fun load(html: String, path: String = "OEBPS/text/ch1.xhtml"): List<Block> =
        ChapterLoader(Locale.ENGLISH).parse(ByteArrayInputStream(html.toByteArray()), path)

    @Test
    fun `extracts headings paragraphs and images in order`() {
        val html = """
            <html><body>
              <h1>Chapter One</h1>
              <p>First paragraph. It has two sentences.</p>
              <div class="wrapper">
                <p>Nested paragraph.</p>
              </div>
              <img src="../images/fig%201.png" alt="A figure"/>
              <blockquote>To be or not to be.</blockquote>
            </body></html>
        """.trimIndent()

        val blocks = load(html)
        assertEquals(5, blocks.size)

        val heading = blocks[0] as Block.Text
        assertEquals(Block.Text.Kind.HEADING_1, heading.kind)
        assertEquals("Chapter One", heading.text)

        val para = blocks[1] as Block.Text
        assertEquals(Block.Text.Kind.PARAGRAPH, para.kind)
        assertEquals(2, para.sentences.size)

        val nested = blocks[2] as Block.Text
        assertEquals("Nested paragraph.", nested.text)

        val image = blocks[3] as Block.Image
        assertEquals("OEBPS/images/fig 1.png", image.zipPath)
        assertEquals("A figure", image.alt)

        val quote = blocks[4] as Block.Text
        assertEquals(Block.Text.Kind.QUOTE, quote.kind)
    }

    @Test
    fun `flattens inline markup into block text`() {
        val html = "<html><body><p>He said <em>hello</em> and <strong>left</strong>.</p></body></html>"
        val blocks = load(html)
        assertEquals(1, blocks.size)
        assertEquals("He said hello and left.", (blocks[0] as Block.Text).text)
    }

    @Test
    fun `div with only inline content becomes a paragraph`() {
        val html = "<html><body><div>Loose <b>text</b> in a div.</div></body></html>"
        val blocks = load(html)
        assertEquals(1, blocks.size)
        assertEquals("Loose text in a div.", (blocks[0] as Block.Text).text)
    }

    @Test
    fun `list items become paragraphs`() {
        val html = "<html><body><ul><li>One item.</li><li>Two items.</li></ul></body></html>"
        val blocks = load(html)
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is Block.Text })
    }

    @Test
    fun `skips scripts styles and empty containers`() {
        val html = """
            <html><body>
              <style>p { color: red; }</style>
              <script>alert(1)</script>
              <div></div>
              <p>Real content.</p>
            </body></html>
        """.trimIndent()
        val blocks = load(html)
        assertEquals(1, blocks.size)
        assertEquals("Real content.", (blocks[0] as Block.Text).text)
    }

    @Test
    fun `image inside paragraph is emitted alongside text`() {
        val html = """<html><body><p><img src="pic.jpg"/>Caption text.</p></body></html>"""
        val blocks = load(html)
        assertEquals(2, blocks.size)
        assertEquals("OEBPS/text/pic.jpg", (blocks[0] as Block.Image).zipPath)
        assertEquals("Caption text.", (blocks[1] as Block.Text).text)
    }

    @Test
    fun `missing image entries are dropped`() {
        val loader = ChapterLoader(Locale.ENGLISH, entryExists = { false })
        val html = """<html><body><img src="ghost.png"/><p>Text.</p></body></html>"""
        val blocks = loader.parse(ByteArrayInputStream(html.toByteArray()), "ch.xhtml")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is Block.Text)
    }
}
