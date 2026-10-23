package com.pedrolopes.ttsing.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.pdf.PdfDocument
import com.pedrolopes.ttsing.desktop.library.Library
import com.pedrolopes.ttsing.desktop.ui.reader.ReaderScreen
import com.pedrolopes.ttsing.desktop.ui.theme.TTSingTheme
import com.pedrolopes.ttsing.tts.BookContentSource
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The reader as it really renders, off screen: a PDF shown as its own pages with the sentence
 * marked on the page, switched to text, and the sleep timer set from the panel. Each step is
 * saved under build/ui-shots for a look.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var app: App

    @After
    fun closeApp() {
        if (::app.isInitialized) app.close()
    }

    private val lines = listOf(
        "The garden was quiet in the morning. Birds sang in the hedges,",
        "and the dew was still on the grass. Maria opened the window and",
        "breathed the cool air. Below her the path ran down to the gate,",
        "where the old apple tree leaned over the wall. She had planted",
        "the roses there herself, years ago, when the house was new.",
    )

    private fun pdf(root: File): File {
        val file = File(root, "garden.pdf")
        PDDocument().use { pdf ->
            repeat(3) { n ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText()
                    out.setFont(PDType1Font.TIMES_BOLD, 18f)
                    out.newLineAtOffset(72f, 720f)
                    out.showText("Chapter ${n + 1}")
                    out.endText()
                    repeat(6) { para ->
                        lines.forEachIndexed { i, line ->
                            out.beginText()
                            out.setFont(PDType1Font.TIMES_ROMAN, 11f)
                            out.newLineAtOffset(72f, 680f - para * 90f - i * 14f)
                            out.showText(line)
                            out.endText()
                        }
                    }
                }
            }
            pdf.save(file)
        }
        return file
    }

    private fun shot(name: String): BufferedImage {
        val image = compose.onRoot().captureToImage().toAwtImage()
        val dir = File("build/ui-shots").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, "$name.png"))
        return image
    }

    /** Pixels the highlighter yellow multiplied onto white paper turns into, above the footer (whose play button is yellow too). */
    private fun yellowPixels(image: BufferedImage): Int {
        var count = 0
        for (y in 0 until image.height - FOOTER_PX step 2) for (x in 0 until image.width step 2) {
            val rgb = image.getRGB(x, y)
            val r = rgb shr 16 and 0xFF
            val g = rgb shr 8 and 0xFF
            val b = rgb and 0xFF
            if (r > 230 && g > 210 && b < 215) count++
        }
        return count
    }

    @Test
    fun `a PDF reads as its own pages, marked where the voice is, and switches to text`() {
        val root = temp.newFolder("Books")
        val file = pdf(root)
        app = App(AppDirs(temp.newFolder("data")))
        app.settings.update { it.copy(libraryFolder = root.absolutePath) }
        runBlocking { app.library.sync(root) }
        val id = Library.idFor(file)

        compose.setContent {
            TTSingTheme {
                Box(Modifier.size(1300.dp, 900.dp)) { ReaderScreen(app, id, onBack = {}) }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("PAGE 1 / 3").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        Thread.sleep(1_500)
        compose.waitForIdle()
        val pages = shot("pdf-pages")
        assertEquals("no highlight before the voice is anywhere", 0, yellowPixels(pages))

        // Put the voice on the second sentence: it is marked on the page itself.
        val second = runBlocking {
            PdfDocument.open(file, "garden").use { pdf ->
                val source = BookContentSource(pdf)
                source.next(source.firstAtOrAfter(ReadingPosition.START)!!.position)!!
            }
        }
        app.narrator.park(second)
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.waitForIdle()
        val marked = shot("pdf-pages-marked")
        assertTrue("the sentence isn't marked on the page", yellowPixels(marked) > 200)

        // The same book as reflowed text.
        app.settings.update { it.copy(pdfAsText = it.pdfAsText + id) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Maria opened", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("pdf-text")

        // And back, from the panel's own switch.
        compose.onNodeWithContentDescription("Page and voice").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PAGES").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("PAGE 1 / 3").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(id !in app.settings.value.pdfAsText)
        shot("pdf-pages-panel")
    }

    @Test
    fun `the sleep timer is set from the panel and shown in the footer`() {
        val root = temp.newFolder("Books")
        val book = File(root, "the-little-garden.epub")
        File(javaClass.getResource("/the-little-garden.epub")!!.toURI()).copyTo(book)
        app = App(AppDirs(temp.newFolder("data")))
        app.settings.update { it.copy(libraryFolder = root.absolutePath) }
        runBlocking { app.library.sync(root) }

        compose.setContent {
            TTSingTheme {
                Box(Modifier.size(1300.dp, 900.dp)) { ReaderScreen(app, Library.idFor(book), onBack = {}) }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescriptionCount("Page and voice") > 0 }
        compose.onNodeWithContentDescription("Page and voice").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("30").performClick()
        compose.waitForIdle()
        assertEquals(30, app.narrator.state.value.sleepMinutes)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("SLEEP 30").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("STOPS IN 30 MIN").assertExists()

        compose.onNodeWithText("CHAPTER").performClick()
        compose.waitForIdle()
        assertTrue(app.narrator.state.value.sleepAtChapterEnd)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("SLEEP CH").fetchSemanticsNodes().isNotEmpty() }
        shot("sleep-timer")

        compose.onNodeWithText("OFF").performClick()
        compose.waitForIdle()
        assertEquals(null, app.narrator.state.value.sleepAt)
        assertTrue(!app.narrator.state.value.sleepAtChapterEnd)
    }

    private companion object {
        const val FOOTER_PX = 90
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(label: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes().size
}
