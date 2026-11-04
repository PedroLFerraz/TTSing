package com.pedrolopes.ttsing.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMetadataTest {

    @Test
    fun `a real title is kept as it is`() {
        assertEquals("On Walls and Gardens", PdfMetadata.title("  On Walls and Gardens ", "walls"))
    }

    @Test
    fun `office noise is stripped down to the document's name`() {
        assertEquals("Thesis final", PdfMetadata.title("Microsoft Word - Thesis final.docx", "file"))
        assertEquals("Q3 review", PdfMetadata.title("Microsoft PowerPoint - Q3 review.pptx", "file"))
    }

    @Test
    fun `titles nobody chose fall back to the file name`() {
        listOf(null, "", "   ", "Untitled", "Document1", "tmp1234", "https://imagemagick.org").forEach {
            assertEquals(it.toString(), "from-file", PdfMetadata.title(it, "from-file"))
        }
    }

    @Test
    fun `placeholder and URL authors are dropped`() {
        listOf(null, "", "User", "admin", "https://imagemagick.org", "www.example.com").forEach {
            assertNull(it.toString(), PdfMetadata.author(it))
        }
        assertEquals("Ursula K. Le Guin", PdfMetadata.author("Ursula K. Le Guin"))
        assertEquals("Camille Fournier, Ian Nowland", PdfMetadata.author("Camille Fournier;Ian Nowland"))
    }

    @Test
    fun `catalogue-style authors are read the way people say them`() {
        assertEquals("Joe Reis, Matt Housley", PdfMetadata.author("Reis, Joe;Housley, Matt;"))
        assertEquals("Brian Ward", PdfMetadata.author("Brian Ward(Author)"))
        assertEquals("Nick Singh & Kevin Huo", PdfMetadata.author("Nick Singh & Kevin Huo"))
        assertEquals("Alice Zheng & Amanda Casari", PdfMetadata.author("Alice Zheng & Amanda Casari"))
    }

    @Test
    fun `a declared language is kept only when it is a language tag`() {
        assertEquals("pt-BR", PdfMetadata.language("pt-BR"))
        assertEquals("en-US", PdfMetadata.language(" en_US "))
        listOf(null, "", "HÓ", "ôÊ", "English language").forEach { assertNull(it.toString(), PdfMetadata.language(it)) }
    }

    @Test
    fun `copyright, contents, index and colophon are passed over when playing on`() {
        listOf("Copyright", "Table of Contents", "Brief Contents", "Contents in Detail", "Index", "Colophon", "Sumário", "Índice")
            .forEach { assertTrue(it, PdfMetadata.isApparatus(it)) }
        listOf("Preface", "Chapter 1. Data Engineering Described", "Introduction", "Acknowledgments", "Index Funds Explained")
            .forEach { assertFalse(it, PdfMetadata.isApparatus(it)) }
    }
}
