package com.pedrolopes.ttsing.desktop

import com.pedrolopes.ttsing.data.pdf.PdfDocument
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * A scanned PDF is its pages as pictures, often JPEG 2000 (covers) and JBIG2 (text). PDFBox
 * reads neither by itself: without their ImageIO decoders it draws the page white and only logs it.
 */
class ScannedPdfTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** One page whose only content is a red JPEG 2000 picture, as a scanned book's cover is. */
    private fun scannedCover(): File {
        val picture = BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB)
        picture.createGraphics().apply { color = Color(180, 30, 30); fillRect(0, 0, 200, 300); dispose() }
        val jp2 = ByteArrayOutputStream().also { assertTrue(ImageIO.write(picture, "jpeg2000", it)) }.toByteArray()
        val file = temp.newFile("scan.pdf")
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            val image = PDImageXObject(pdf, ByteArrayInputStream(jp2), COSName.JPX_DECODE, 200, 300, 8, PDDeviceRGB.INSTANCE)
            PDPageContentStream(pdf, page).use { it.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
            pdf.save(file)
        }
        return file
    }

    private fun BufferedImage.isBlank(): Boolean =
        (0 until height step 4).all { y -> (0 until width step 4).all { x -> getRGB(x, y) and 0xffffff == 0xffffff } }

    @Test
    fun scannedPagesAndCoverAreDrawn() {
        val file = scannedCover()
        PdfDocument.open(file, "scan").use { pdf ->
            val page = runBlocking { pdf.renderPage(0, 400) }
            assertNotNull(page)
            assertTrue("the page came out white", !page!!.isBlank())
        }
        val cover = ImageIO.read(ByteArrayInputStream(PdfDocument.renderCover(file)!!))
        assertTrue("the cover came out white", !cover.isBlank())
    }

    @Test
    fun jbig2CanBeRead() {
        // Encoding JBIG2 needs another library; that PDFBox can find a reader is what matters.
        assertTrue(ImageIO.getImageReadersByFormatName("JBIG2").hasNext())
    }
}
