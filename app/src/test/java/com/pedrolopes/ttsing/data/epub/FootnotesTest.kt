package com.pedrolopes.ttsing.data.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

class FootnotesTest {

    private fun parse(html: String, files: Map<String, String> = emptyMap()): List<Block.Text> =
        ChapterLoader(
            Locale.forLanguageTag("pt-BR"),
            loadDocument = { path -> files[path]?.let { Jsoup.parse(it) as Document } },
        )
            .parse(ByteArrayInputStream(html.toByteArray()), "OEBPS/ch.xhtml")
            .filterIsInstance<Block.Text>()

    private fun Block.Text.marker(note: Note) = text.substring(note.start, note.end)

    // ---- Linked notes ----

    @Test
    fun `a noteref becomes a superscript marker carrying its aside`() {
        val blocks = parse(
            """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <p>Texto principal<a epub:type="noteref" href="#n1">[1]</a> continua.</p>
              <aside epub:type="footnote" id="n1"><p>1. Esta é a nota.</p><p>Segue aqui.</p></aside>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(1, blocks.size)
        val block = blocks.single()
        assertEquals("Texto principal¹ continua.", block.text)
        val note = block.notes.single()
        assertEquals("¹", block.marker(note))
        assertEquals("Esta é a nota.\n\nSegue aqui.", note.text)
    }

    @Test
    fun `a call-out into another file finds its note there`() {
        val notes = """
            <html><body>
              <p><a id="n1" href="ch.xhtml#r1">1</a> A primeira nota.</p>
              <p><a id="n2" href="ch.xhtml#r2">2</a> A segunda.</p>
            </body></html>
        """.trimIndent()
        val block = parse(
            """
            <html><body>
              <p>Um<a id="r1" href="notas.xhtml#n1"><sup>1</sup></a> e dois<sup><a id="r2" href="notas.xhtml#n2">2</a></sup>.</p>
            </body></html>
            """.trimIndent(),
            files = mapOf("OEBPS/notas.xhtml" to notes),
        ).single()
        assertEquals("Um¹ e dois².", block.text)
        assertEquals(listOf("A primeira nota.", "A segunda."), block.notes.map { it.text })
        assertEquals(listOf("¹", "²"), block.notes.map { block.marker(it) })
    }

    @Test
    fun `a note kept at the end of the chapter leaves the flow`() {
        val blocks = parse(
            """
            <html><body>
              <p>Uma frase<a id="r1" href="#n1">1</a> com nota.</p>
              <p>Outra frase.</p>
              <p class="nota"><a id="n1" href="#r1">1</a> O que a nota diz.</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(listOf("Uma frase¹ com nota.", "Outra frase."), blocks.map { it.text })
        assertEquals("O que a nota diz.", blocks[0].notes.single().text)
    }

    @Test
    fun `the numbers on a page of notes are links back, not call-outs`() {
        val blocks = parse(
            """
            <html><body>
              <p><a id="n1" href="cap1.xhtml#r1">1</a> A primeira nota.</p>
              <p><a id="n2" href="cap1.xhtml#r2">2</a> A segunda.</p>
            </body></html>
            """.trimIndent(),
            files = mapOf("OEBPS/cap1.xhtml" to """<html><body><p>Um<a id="r1" href="ch.xhtml#n1">1</a> texto.</p></body></html>"""),
        )
        assertEquals(listOf("A primeira nota.", "A segunda."), blocks.map { it.text })
        assertTrue(blocks.all { it.notes.isEmpty() })
    }

    /** As a converter splits a long chapter: the notes of every part gathered after the last. */
    @Test
    fun `notes called out from earlier files leave the end of the chapter`() {
        val blocks = parse(
            """
            <html><body>
              <p>E partiu. Encontrou-os no fim do primeiro <em>rubber</em><a id="r5" href="ch.xhtml#n5">5</a>.</p>
              <hr/>
              <p class="footnoteline"></p>
              <p class="c21"><a id="n1" href="parte1.xhtml#r1">1</a> Jornal mais antigo da Rússia.</p>
              <p class="c21"><a id="n2" href="parte2.xhtml#r2">2</a> Escolas de jurisprudência.</p>
              <p class="c21"><em><a id="n5" href="ch.xhtml#r5">5</a></em> No jogo de <em>vint</em>, três partidas.</p>
            </body></html>
            """.trimIndent(),
            files = mapOf(
                "OEBPS/parte1.xhtml" to """<html><body><p>O <em>Viédomosti</em><sup><a id="r1" href="ch.xhtml#n1">1</a></sup>, que chegara.</p></body></html>""",
                "OEBPS/parte2.xhtml" to """<html><body><p>A escola<sup><a id="r2" href="ch.xhtml#n2">2</a></sup> de direito.</p></body></html>""",
            ),
        )
        assertEquals(listOf("E partiu. Encontrou-os no fim do primeiro rubber⁵."), blocks.map { it.text })
        assertEquals("No jogo de vint, três partidas.", blocks.single().notes.single().text)
    }

    @Test
    fun `a numbered list of contents is not taken for notes`() {
        val texts = parse(
            """
            <html><body>
              <h1>Sumário</h1>
              <p><a href="cap1.xhtml#c1">1</a> O começo</p>
              <p><a href="cap2.xhtml#c2">2</a> O fim</p>
            </body></html>
            """.trimIndent(),
            files = mapOf(
                "OEBPS/cap1.xhtml" to """<html><body><h2 id="c1">O começo</h2><p>Texto.</p></body></html>""",
                "OEBPS/cap2.xhtml" to """<html><body><h2 id="c2">O fim</h2><p>Texto.</p></body></html>""",
            ),
        ).map { it.text }
        assertEquals(listOf("Sumário", "O começo", "O fim"), texts)
    }

    @Test
    fun `a link to another part of the chapter is not a note`() {
        val texts = parse(
            """
            <html><body>
              <p>Veja a seção <a href="#s2">2</a> adiante.</p>
              <section id="s2"><h2>2. O jardim</h2><p>O jardim era grande.</p></section>
            </body></html>
            """.trimIndent(),
        ).map { it.text }
        assertEquals(listOf("Veja a seção adiante.", "2. O jardim", "O jardim era grande."), texts)
    }

    // ---- Unlinked notes, as a printed book's conversion leaves them ----

    @Test
    fun `an asterisk note is lifted and the sentence it cut is joined again`() {
        val blocks = parse(
            """
            <html><body>
              <p>Um cigano de mãos de pardal,* que se apresentou, fez uma demonstração pela concentração</p>
              <p></p>
              <p>* No original manos de gorrión.</p>
              <p></p>
              <p></p>
              <p>dos raios solares. Ninguém entendeu.</p>
              <p>Depois veio outro.</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "Um cigano de mãos de pardal,* que se apresentou, fez uma demonstração pela concentração dos raios solares. Ninguém entendeu.",
                "Depois veio outro.",
            ),
            blocks.map { it.text },
        )
        val note = blocks[0].notes.single()
        assertEquals("*", blocks[0].marker(note))
        assertEquals(blocks[0].text.indexOf("pardal,*") + "pardal,".length, note.start)
        assertEquals("No original manos de gorrión.", note.text)
    }

    @Test
    fun `a note running over paragraphs ends at the page break`() {
        val blocks = parse(
            """
            <html><body>
              <p>O goiabal de amor que arrastava até a morte.* E a ociosidade não</p>
              <p></p>
              <p>* Tendo sido impossível encontrar uma expressão equivalente ao excelente</p>
              <p>achado do autor, mantivemos a imagem.</p>
              <p>A goiaba bicha com frequência. (N. T.)</p>
              <p></p>
              <p></p>
              <p>tornasse mais longa a espera.</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(listOf("O goiabal de amor que arrastava até a morte.* E a ociosidade não tornasse mais longa a espera."), blocks.map { it.text })
        assertEquals(
            "Tendo sido impossível encontrar uma expressão equivalente ao excelente\n\nachado do autor, mantivemos a imagem.\n\nA goiaba bicha com frequência. (N. T.)",
            blocks[0].notes.single().text,
        )
    }

    @Test
    fun `text run on after a signed-off note goes back to the book`() {
        val blocks = parse(
            """
            <html><body>
              <p>Arrastava para a cumbia* quanto ser humano encontrava. Não era trituradora de bois,</p>
              <p></p>
              <p>* Cumbia é a dança popular colombiana. ( N. T.) nem mulher barbada num circo.</p>
              <p>A sua teoria se fundamentava.</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "Arrastava para a cumbia* quanto ser humano encontrava. Não era trituradora de bois, nem mulher barbada num circo.",
                "A sua teoria se fundamentava.",
            ),
            blocks.map { it.text },
        )
        assertEquals("Cumbia é a dança popular colombiana. ( N. T.)", blocks[0].notes.single().text)
    }

    @Test
    fun `notes printed together go to their call-outs in order`() {
        val blocks = parse(
            """
            <html><body>
              <p>Com a sua camisa de inválido* e as folhas de garranchinhos roxos*, escrevia.</p>
              <p></p>
              <p>* A camisa parecia de inválido.</p>
              <p>* Garrapatas é carrapato.</p>
              <p></p>
              <p>Depois partiu.</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(2, blocks.size)
        assertEquals(listOf("A camisa parecia de inválido.", "Garrapatas é carrapato."), blocks[0].notes.map { it.text })
        assertTrue(blocks[0].notes[0].start < blocks[0].notes[1].start)
    }

    @Test
    fun `a note whose call-out is not in the chapter stays where it is`() {
        val texts = parse(
            """
            <html><body>
              <p>* Explicação do autor: uma lenda popular.</p>
              <p></p>
              <p>Disso vivia.</p>
            </body></html>
            """.trimIndent(),
        ).map { it.text }
        assertEquals(listOf("* Explicação do autor: uma lenda popular.", "Disso vivia."), texts)
    }

    @Test
    fun `a scene break of asterisks is left alone`() {
        val texts = parse(
            """
            <html><body>
              <p>Terminou assim.*</p>
              <p>***</p>
              <p>Muitos anos depois.</p>
            </body></html>
            """.trimIndent(),
        ).map { it.text }
        assertEquals(listOf("Terminou assim.*", "***", "Muitos anos depois."), texts)
    }

    // ---- Markers in sentences and speech ----

    @Test
    fun `a marker after a full stop stays with its sentence`() {
        val block = parse(
            """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <p>Foi assim.<a epub:type="noteref" href="#n1">1</a> Depois mudou.</p>
              <aside epub:type="footnote" id="n1"><p>Nota.</p></aside>
            </body></html>
            """.trimIndent(),
        ).single()
        assertEquals("Foi assim.¹ Depois mudou.", block.text)
        assertEquals(listOf("Foi assim.¹", "Depois mudou."), block.sentences.map { block.text.substring(it.start, it.end) })
    }

    @Test
    fun `the voice hears no marker and copies leave it out`() {
        val block = Block.Text(
            "Um¹ e dois²³.",
            Block.Text.Kind.PARAGRAPH,
            listOf(SentenceSpan(0, 13)),
            listOf(Note(2, 3, "a"), Note(10, 12, "b")),
        )
        assertEquals("Um  e dois  .", block.spokenText(0, 13))
        assertEquals(13, block.spokenText(0, 13).length)
        assertEquals("Um e dois.", block.plainText(0, 13))
        assertEquals("e dois", block.plainText(4, 12).trim())
    }
}
