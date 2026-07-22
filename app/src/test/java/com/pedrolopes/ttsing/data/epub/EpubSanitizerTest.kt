package com.pedrolopes.ttsing.data.epub

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

class EpubSanitizerTest {

    private fun textOf(html: String): List<String> =
        ChapterLoader(Locale.forLanguageTag("pt-BR"))
            .parse(ByteArrayInputStream(html.toByteArray()), "ch.xhtml")
            .filterIsInstance<Block.Text>()
            .map { it.text }

    @Test
    fun `strips superscript footnote markers glued to words`() {
        val html = """
            <html><body>
              <p>A palavra<sup>1</sup> seguinte tem uma nota<sup>23</sup>.</p>
            </body></html>
        """.trimIndent()
        assertEquals(listOf("A palavra seguinte tem uma nota."), textOf(html))
    }

    @Test
    fun `strips numeric anchors pointing at internal notes`() {
        val html = """
            <html><body>
              <p>Uma frase<a href="#nota3">3</a> com nota de rodape.</p>
            </body></html>
        """.trimIndent()
        assertEquals(listOf("Uma frase com nota de rodape."), textOf(html))
    }

    @Test
    fun `removes noteref and footnote bodies`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <p>Texto principal<a epub:type="noteref" href="#n1">[1]</a> continua.</p>
              <aside epub:type="footnote" id="n1"><p>1. Esta e a nota de rodape.</p></aside>
            </body></html>
        """.trimIndent()
        assertEquals(listOf("Texto principal continua."), textOf(html))
    }

    @Test
    fun `removes page break markers`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <p>Antes<span epub:type="pagebreak" id="p12">12</span> depois.</p>
            </body></html>
        """.trimIndent()
        assertEquals(listOf("Antes depois."), textOf(html))
    }

    @Test
    fun `leaves a clean chapter untouched`() {
        val html = """
            <html><body>
              <h1>Capitulo Um</h1>
              <p>Uma frase normal. Outra frase normal.</p>
              <p>Veja a <a href="https://example.com">documentacao</a> para detalhes.</p>
            </body></html>
        """.trimIndent()
        assertEquals(
            listOf(
                "Capitulo Um",
                "Uma frase normal. Outra frase normal.",
                "Veja a documentacao para detalhes.",
            ),
            textOf(html),
        )
    }

    @Test
    fun `keeps subscripts, which are notation rather than footnote markers`() {
        val html = "<html><body><p>A formula e H<sub>2</sub>O aproximadamente.</p></body></html>"
        assertEquals(listOf("A formula e H2O aproximadamente."), textOf(html))
    }

    @Test
    fun `does not strip long numeric text`() {
        val html = "<html><body><p>O ano de <a href=\"#x\">1500</a> foi importante.</p></body></html>"
        assertEquals(listOf("O ano de 1500 foi importante."), textOf(html))
    }
}
