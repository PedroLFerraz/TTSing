package com.pedrolopes.ttsing.desktop

import com.pedrolopes.ttsing.data.pdf.PdfDocument
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
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
import java.nio.ByteBuffer
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
    fun jbig2PageIsDrawn() {
        val file = scannedText()
        PdfDocument.open(file, "scan").use { pdf ->
            val page = runBlocking { pdf.renderPage(0, 400) }!!
            assertTrue("the page came out white", !page.isBlank())
            // Black where the picture is black, white around it: decoded, not just filled in.
            assertEquals(0x000000, page.getRGB(page.width / 2, page.height / 2) and 0xffffff)
            assertEquals(0xffffff, page.getRGB(2, 2) and 0xffffff)
        }
    }

    /**
     * One page whose only content is a JBIG2 picture of a black square, as a scanned book's
     * text pages are. Nothing writes JBIG2 here, but its MMR coding is fax G4, which PDFBox
     * writes: so the square goes through PDFBox's fax encoder and into JBIG2 segments by hand.
     */
    private fun scannedText(): File {
        val width = 200
        val height = 300
        val picture = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
        picture.createGraphics().apply {
            color = Color.WHITE; fillRect(0, 0, width, height)
            color = Color.BLACK; fillRect(50, 75, 100, 150)
            dispose()
        }
        val file = temp.newFile("text.pdf")
        PDDocument().use { pdf ->
            val g4 = CCITTFactory.createFromImage(pdf, picture).cosObject.createRawInputStream().use { it.readBytes() }
            val jbig2 = ByteArrayOutputStream().also { out ->
                // Page information: size, unknown resolution, default pixel white, no striping.
                out.segment(0, type = 48, ByteBuffer.allocate(19).putInt(width).putInt(height).putInt(0).putInt(0).put(0).putShort(0))
                // An immediate lossless generic region covering the page, MMR coded.
                out.segment(1, type = 39, ByteBuffer.allocate(18 + g4.size).putInt(width).putInt(height).putInt(0).putInt(0).put(0).put(1).put(g4))
                out.segment(2, type = 49, ByteBuffer.allocate(0))
            }.toByteArray()
            val page = PDPage()
            pdf.addPage(page)
            val image = PDImageXObject(pdf, ByteArrayInputStream(jbig2), COSName.JBIG2_DECODE, width, height, 1, PDDeviceGray.INSTANCE)
            PDPageContentStream(pdf, page).use { it.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
            pdf.save(file)
        }
        return file
    }

    /** A JBIG2 segment of page 1, referring to no other: header, then [data]. */
    private fun ByteArrayOutputStream.segment(number: Int, type: Int, data: ByteBuffer) {
        write(ByteBuffer.allocate(11).putInt(number).put(type.toByte()).put(0).put(1).putInt(data.capacity()).array())
        write(data.array())
    }
}
