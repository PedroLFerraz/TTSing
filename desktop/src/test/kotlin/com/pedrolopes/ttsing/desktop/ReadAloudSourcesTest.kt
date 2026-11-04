package com.pedrolopes.ttsing.desktop

import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.pdf.PdfDocument
import com.pedrolopes.ttsing.tts.BookContentSource
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What the voice is fed on the desktop: a PDF's reflowed sentences, and settings kept between runs. */
class ReadAloudSourcesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun pdf(): File {
        val file = temp.newFile("talk.pdf")
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                listOf(
                    "The garden was quiet in the morning. Birds sang in",
                    "the hedges, and the dew was still on the grass.",
                    "Maria opened the window. She breathed the cool air.",
                ).forEachIndexed { i, line ->
                    out.beginText()
                    out.setFont(PDType1Font.TIMES_ROMAN, 11f)
                    out.newLineAtOffset(72f, 700f - i * 14f)
                    out.showText(line)
                    out.endText()
                }
            }
            pdf.save(file)
        }
        return file
    }

    @Test
    fun `a PDF is read aloud sentence by sentence`() = runBlocking {
        PdfDocument.open(pdf(), "Talk").use { document ->
            val source = BookContentSource(document)
            val first = assertNotNullAnd(source.firstAtOrAfter(ReadingPosition.START))
            assertEquals("The garden was quiet in the morning.", first.text.trim())
            // The sentence the page broke across a line is read whole.
            val second = assertNotNullAnd(source.next(first.position))
            assertEquals("Birds sang in the hedges, and the dew was still on the grass.", second.text.trim())
            val third = assertNotNullAnd(source.next(second.position))
            assertTrue(third.text, third.text.startsWith("Maria opened the window."))
        }
    }

    @Test
    fun `settings survive a restart`() {
        val dirs = AppDirs(temp.newFolder("data"))
        SettingsStore(dirs).update {
            it.copy(
                libraryFolder = "/books",
                theme = ReaderTheme.SEPIA,
                fontScale = SettingsStore.fontStep(1.1f),
                speechRate = 2f,
                voiceByLanguage = mapOf("pt" to "vits-piper-pt_BR-faber-medium"),
                languageByBook = mapOf("abc" to "pt-BR"),
            )
        }
        val again = SettingsStore(dirs).value
        assertEquals("/books", again.libraryFolder)
        assertEquals(ReaderTheme.SEPIA, again.theme)
        assertEquals(1.1f, again.fontScale, 0.001f)
        assertEquals(2f, again.speechRate, 0f)
        assertEquals("vits-piper-pt_BR-faber-medium", again.voiceByLanguage["pt"])
        assertEquals("pt-BR", again.languageByBook["abc"])
    }

    @Test
    fun `the type size moves in five percent steps, within bounds`() {
        assertEquals(1.05f, SettingsStore.fontStep(1.04f), 0.001f)
        assertEquals(SettingsStore.MIN_FONT_SCALE, SettingsStore.fontStep(0.1f), 0.001f)
        assertEquals(SettingsStore.MAX_FONT_SCALE, SettingsStore.fontStep(9f), 0.001f)
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
