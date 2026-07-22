package com.pedrolopes.ttsing.data.epub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    private lateinit var epubFile: File
    private lateinit var parser: EpubParser

    @Before
    fun createFixture() {
        epubFile = File.createTempFile("fixture", ".epub")
        ZipOutputStream(epubFile.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""",
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:uuid:1234</dc:identifier>
                    <dc:title>Aventuras de Teste</dc:title>
                    <dc:creator>Pedro Autor</dc:creator>
                    <dc:language>pt-BR</dc:language>
                    <meta name="cover" content="cover-img"/>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="cover-img" href="images/cover.jpg" media-type="image/jpeg"/>
                    <item id="ch1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>""",
            )
            put(
                "OEBPS/nav.xhtml",
                """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                  <nav epub:type="toc">
                    <ol>
                      <li><a href="text/ch%201.xhtml">Primeiro Capítulo</a></li>
                      <li><a href="text/ch2.xhtml#start">Segundo Capítulo</a></li>
                    </ol>
                  </nav>
                </body></html>""",
            )
            put(
                "OEBPS/toc.ncx",
                """<?xml version="1.0"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="n1"><navLabel><text>Cap 1 via NCX</text></navLabel><content src="text/ch%201.xhtml"/></navPoint>
                  </navMap>
                </ncx>""",
            )
            put(
                "OEBPS/text/ch 1.xhtml",
                """<html><body>
                  <h1>Primeiro Capítulo</h1>
                  <p>Era uma vez. Fim da primeira frase.</p>
                  <img src="../images/cover.jpg" alt="capa"/>
                </body></html>""",
            )
            put(
                "OEBPS/text/ch2.xhtml",
                "<html><body><p>Second chapter text.</p></body></html>",
            )
            zip.putNextEntry(ZipEntry("OEBPS/images/cover.jpg"))
            zip.write(byteArrayOf(-1, -40, -1, -32))
            zip.closeEntry()
        }
        parser = EpubParser(epubFile)
    }

    @After
    fun cleanup() {
        parser.close()
        epubFile.delete()
    }

    @Test
    fun `parses metadata`() {
        val book = parser.parseBook()
        assertEquals("Aventuras de Teste", book.title)
        assertEquals("Pedro Autor", book.author)
        assertEquals("pt-BR", book.language)
        assertEquals("pt", book.locale().language)
    }

    @Test
    fun `resolves spine with percent-encoded hrefs`() {
        val book = parser.parseBook()
        assertEquals(2, book.spine.size)
        assertEquals("OEBPS/text/ch 1.xhtml", book.spine[0].zipPath)
        assertEquals("OEBPS/text/ch2.xhtml", book.spine[1].zipPath)
    }

    @Test
    fun `finds cover from epub2 meta`() {
        val book = parser.parseBook()
        assertEquals("OEBPS/images/cover.jpg", book.coverPath)
        assertNotNull(parser.readEntry(book.coverPath!!))
    }

    @Test
    fun `prefers epub3 nav toc`() {
        val book = parser.parseBook()
        assertEquals(2, book.toc.size)
        assertEquals(TocEntry("Primeiro Capítulo", 0), book.toc[0])
        assertEquals(TocEntry("Segundo Capítulo", 1), book.toc[1])
    }

    @Test
    fun `loads chapter with blocks and sentence spans`() {
        val book = parser.parseBook()
        val chapter = parser.loadChapter(book, 0)
        assertEquals("Primeiro Capítulo", chapter.title)

        val textBlocks = chapter.blocks.filterIsInstance<Block.Text>()
        val imageBlocks = chapter.blocks.filterIsInstance<Block.Image>()
        assertEquals(2, textBlocks.size)
        assertEquals(1, imageBlocks.size)
        assertEquals("OEBPS/images/cover.jpg", imageBlocks[0].zipPath)

        val paragraph = textBlocks[1]
        assertEquals(2, paragraph.sentences.size)
        val first = paragraph.sentences[0]
        assertEquals("Era uma vez.", paragraph.text.substring(first.start, first.end))
    }

    @Test
    fun `falls back to ncx when nav is absent`() {
        // Rebuild fixture without the nav property
        parser.close()
        epubFile.delete()
        epubFile = File.createTempFile("fixture2", ".epub")
        ZipOutputStream(epubFile.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
            )
            put(
                "content.opf",
                """<package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata><dc:title>NCX Book</dc:title><dc:language>en</dc:language></metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="c1"/></spine>
                </package>""",
            )
            put(
                "toc.ncx",
                """<ncx><navMap><navPoint><navLabel><text>Only Chapter</text></navLabel><content src="c1.xhtml"/></navPoint></navMap></ncx>""",
            )
            put("c1.xhtml", "<html><body><p>Text.</p></body></html>")
        }
        parser = EpubParser(epubFile)
        val book = parser.parseBook()
        assertEquals(listOf(TocEntry("Only Chapter", 0)), book.toc)
        assertTrue(book.coverPath == null)
    }
}
