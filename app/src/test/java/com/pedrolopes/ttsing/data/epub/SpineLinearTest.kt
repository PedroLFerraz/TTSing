package com.pedrolopes.ttsing.data.epub

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `linear="no"` marks pages outside the reading flow — covers, ad pages. Including them
 * means a book opens on a blank cover page.
 */
class SpineLinearTest {

    private fun epub(spine: String): File {
        val file = File.createTempFile("linear", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
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
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:uuid:1</dc:identifier>
                    <dc:title>Linear Test</dc:title>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    <item id="cover" href="cover.html" media-type="application/xhtml+xml"/>
                    <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.html" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>$spine</spine>
                </package>""",
            )
            put("OEBPS/cover.html", """<html><body><div class="cover"></div></body></html>""")
            put("OEBPS/ch1.html", """<html><body><h1>One</h1><p>First chapter.</p></body></html>""")
            put("OEBPS/ch2.html", """<html><body><h1>Two</h1><p>Second chapter.</p></body></html>""")
        }
        file.deleteOnExit()
        return file
    }

    @Test
    fun `a non-linear cover is left out of the reading order`() {
        val file = epub(
            """<itemref idref="cover" linear="no"/><itemref idref="ch1"/><itemref idref="ch2"/>""",
        )
        EpubParser(file).use { parser ->
            val book = parser.parseBook()
            assertEquals(2, book.spine.size)
            assertEquals("OEBPS/ch1.html", book.spine[0].zipPath)
            // The book now opens on real text rather than an empty cover page.
            val first = parser.loadChapter(book, 0).blocks.filterIsInstance<Block.Text>()
            assertEquals("One", first.first().text)
        }
    }

    @Test
    fun `linear yes and unmarked items are both kept`() {
        val file = epub("""<itemref idref="ch1" linear="yes"/><itemref idref="ch2"/>""")
        EpubParser(file).use { parser ->
            assertEquals(2, parser.parseBook().spine.size)
        }
    }

    @Test
    fun `a book marking everything non-linear still shows its pages`() {
        val file = epub(
            """<itemref idref="ch1" linear="no"/><itemref idref="ch2" linear="no"/>""",
        )
        EpubParser(file).use { parser ->
            assertEquals(2, parser.parseBook().spine.size)
        }
    }
}
