package com.pedrolopes.ttsing.data.pdf

import com.pedrolopes.ttsing.data.epub.Block
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The PDFBox side, on PDFs made here: fonts and faces, and how bookmarks become sections. */
class PdfExtractTest {

    private fun PDPageContentStream.write(font: PDFont, size: Float, x: Float, y: Float, text: String) {
        beginText()
        setFont(font, size)
        newLineAtOffset(x, y)
        showText(text)
        endText()
    }

    @Test
    fun `bold and fixed-pitch faces are told apart, and a page's furniture is not read`() {
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(504f, 661.5f))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                // PDF y grows upwards: 600 is near the top.
                out.write(PDType1Font.HELVETICA_BOLD, 11f, 72f, 600f, "The early days")
                out.write(PDType1Font.TIMES_ROMAN, 10f, 72f, 580f, "Here is the command to run, and then its output:")
                out.write(PDType1Font.COURIER, 8f, 72f, 560f, "\$ ls -l")
                out.write(PDType1Font.TIMES_ROMAN, 10f, 72f, 530f, "That listing shows every file in the directory, with")
                out.write(PDType1Font.TIMES_ROMAN, 10f, 72f, 517.4f, "its owner and its size, and when it last changed.")
                out.write(PDType1Font.TIMES_BOLD, 9f, 72f, 40f, "4 | Chapter 1: Data Engineering Described")
            }
            val lines = PdfExtract.lines(pdf, 0, 1).single().lines
            val byText = lines.associateBy { it.text }
            assertTrue(byText.getValue("The early days").bold)
            assertTrue(byText.getValue("\$ ls -l").monospace)
            assertFalse(byText.getValue("Here is the command to run, and then its output:").let { it.bold || it.monospace })

            val blocks = PdfReflow.reflow(PdfExtract.lines(pdf, 0, 1), emptySet(), Locale.ENGLISH, 10f)
                .blocks.filterIsInstance<Block.Text>()
            assertEquals(
                listOf(
                    Block.Text.Kind.HEADING_3 to "The early days",
                    Block.Text.Kind.PARAGRAPH to "Here is the command to run, and then its output:",
                    Block.Text.Kind.CODE to "\$ ls -l",
                    Block.Text.Kind.PARAGRAPH to "That listing shows every file in the directory, with its owner and its size, and when it last changed.",
                ),
                blocks.map { it.kind to it.text },
            )
        }
    }

    private fun PDOutlineNode.mark(pdf: PDDocument, title: String, page: Int): PDOutlineItem =
        PDOutlineItem().also { item ->
            item.title = title
            item.destination = PDPageFitDestination().apply { this.page = pdf.getPage(page) }
            addLast(item)
        }

    @Test
    fun `parts give way to their chapters, but a chapter keeps its sub-headings`() {
        PDDocument().use { pdf ->
            repeat(80) { pdf.addPage(PDPage()) }
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            outline.mark(pdf, "Copyright", 0)
            outline.mark(pdf, "Preface", 2).apply {
                mark(pdf, "Who Should Read This Book", 2)
                mark(pdf, "Conventions Used in This Book", 3)
            }
            outline.mark(pdf, "Part I. Foundations", 5).apply {
                mark(pdf, "Chapter 1. Described", 6).apply {
                    mark(pdf, "What Is It?", 6)
                    mark(pdf, "History", 8)
                }
                mark(pdf, "Chapter 2. The Lifecycle", 30)
            }
            outline.mark(pdf, "Chapter 3. Sub-headings Only", 50).apply {
                mark(pdf, "3.1 One", 51)
                mark(pdf, "3.2 Two", 52)
                mark(pdf, "3.3 Three", 54)
            }
            outline.mark(pdf, "Index", 62)

            val sections = PdfDocument.sections(pdf)
            assertEquals(
                listOf(
                    "Copyright" to 0, "Preface" to 2, "Part I. Foundations" to 5, "Chapter 1. Described" to 6,
                    "Chapter 2. The Lifecycle" to 30, "Chapter 3. Sub-headings Only" to 50, "Index" to 62,
                ),
                sections.map { it.title to it.firstPage },
            )
            assertTrue(PdfReflow.titleKey("History") in PdfDocument.outlineTitles(pdf))
        }
    }
}
