package com.pedrolopes.ttsing.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Converted EPUBs routinely ship UTF-8 bytes under a `charset=iso-8859-1` declaration.
 * Obeying the declaration mangles every curly quote, dash and accent — on screen and in
 * the voice — so the loader trusts a clean UTF-8 decode over what the document claims.
 */
class ChapterEncodingTest {

    private fun blocks(bytes: ByteArray): List<Block> =
        ChapterLoader(Locale.ENGLISH).parse(ByteArrayInputStream(bytes), "OEBPS/ch.html")

    private fun text(bytes: ByteArray): String =
        blocks(bytes).filterIsInstance<Block.Text>().joinToString(" ") { it.text }

    private fun page(meta: String, body: String): String =
        """<html><head>$meta</head><body><p>$body</p></body></html>"""

    @Test
    fun `utf8 content wins over a lying iso-8859-1 declaration`() {
        val meta = """<meta content="application/xhtml+xml; charset=iso-8859-1" http-equiv="content-type"/>"""
        val bytes = page(meta, "Editors’ Note “Quoted”").toByteArray(Charsets.UTF_8)

        val result = text(bytes)
        assertEquals("Editors’ Note “Quoted”", result)
        assertTrue("must not contain mojibake", !result.contains("â"))
    }

    @Test
    fun `a correct utf8 declaration still works`() {
        val meta = """<meta charset="utf-8"/>"""
        val bytes = page(meta, "café naïve — done").toByteArray(Charsets.UTF_8)
        assertEquals("café naïve — done", text(bytes))
    }

    @Test
    fun `genuine latin-1 bytes still decode through the declared charset`() {
        // 0xE9 alone is not valid UTF-8, so the strict decode fails and Jsoup's sniffing wins.
        val meta = """<meta content="text/html; charset=iso-8859-1" http-equiv="content-type"/>"""
        val bytes = page(meta, "café").toByteArray(Charsets.ISO_8859_1)
        assertEquals("café", text(bytes))
    }

    @Test
    fun `plain ascii is unaffected`() {
        val bytes = page("", "Nothing special here.").toByteArray(Charsets.UTF_8)
        assertEquals("Nothing special here.", text(bytes))
    }

    @Test
    fun `sentence splitting sees real quotes rather than mojibake`() {
        val meta = """<meta content="application/xhtml+xml; charset=iso-8859-1" http-equiv="content-type"/>"""
        val bytes = page(meta, "“Certainly we shall.” And so it was.").toByteArray(Charsets.UTF_8)
        val paragraph = blocks(bytes).filterIsInstance<Block.Text>().first()
        assertEquals(2, paragraph.sentences.size)
    }
}
