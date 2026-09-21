package com.pedrolopes.ttsing.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }
}
