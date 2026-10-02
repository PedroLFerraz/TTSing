package com.pedrolopes.ttsing.data.pdf

import com.pedrolopes.ttsing.data.epub.Block
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Runs the real extraction and reflow over a folder of PDFs and writes what the voice would
 * read, block by block, to build/pdf-corpus. Not an assertion test: it is how a new kind of
 * PDF gets looked at. Skipped unless run with -PpdfCorpus=<folder>.
 */
class PdfCorpusDump {

    @Test
    fun dump() {
        val dir = System.getProperty("pdfCorpus").orEmpty()
        assumeTrue(dir.isNotEmpty())
        val out = File(System.getProperty("pdfCorpusOut") ?: "build/pdf-corpus").apply { mkdirs() }
        File(dir).listFiles { f -> f.extension.equals("pdf", true) }!!.sortedBy { it.name }.forEach { file ->
            val started = System.currentTimeMillis()
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                val report = StringBuilder()
                val info = pdf.documentInformation
                report.appendLine("FILE ${file.name}")
                report.appendLine("TITLE ${PdfMetadata.title(info?.title, file.nameWithoutExtension)} | AUTHOR ${PdfMetadata.author(info?.author)} (raw ${info?.author}) | LANG ${PdfMetadata.language(pdf.documentCatalog?.language)}")
                val sections = PdfDocument.sections(pdf)
                val titles = PdfDocument.outlineTitles(pdf)
                val count = pdf.numberOfPages
                val step = maxOf(1, count / 40)
                val sample = (0 until count step step).take(40).flatMap { PdfExtract.lines(pdf, it, it + 1) }
                val bookFurniture = PdfReflow.detectFurniture(sample)
                val bodySize = PdfReflow.bodyFontSize(sample)
                report.appendLine("PAGES $count | SECTIONS ${sections.size} | BODY $bodySize | FURNITURE $bookFurniture")
                sections.forEachIndexed { i, s ->
                    val skip = if (PdfMetadata.isApparatus(s.title)) " (skipped when playing on)" else ""
                    report.appendLine("  [$i] p${s.firstPage + 1}-${s.endPage} ${s.title}$skip")
                }
                val locale = Locale.forLanguageTag(PdfMetadata.language(pdf.documentCatalog?.language) ?: "en")
                for ((i, section) in sections.withIndex()) {
                    val pages = PdfExtract.lines(pdf, section.firstPage, section.endPage, withImages = true)
                    val furniture = bookFurniture + PdfReflow.detectFurniture(pages)
                    val reflowed = PdfReflow.reflow(pages, furniture, locale, bodySize, titles)
                    report.appendLine()
                    report.appendLine("==== SECTION $i: ${section.title} (p${section.firstPage + 1}-${section.endPage})")
                    reflowed.blocks.forEachIndexed { b, block ->
                        val page = reflowed.geometry.pageOf(b)?.plus(1)
                        when (block) {
                            is Block.Text -> report.appendLine("[$b p$page ${block.kind}${if (block.sentences.isEmpty()) " SILENT" else ""}] ${block.text.replace("\n", " ⏎ ")}")
                            is Block.Image -> report.appendLine("[$b IMAGE] ${block.zipPath}")
                        }
                    }
                }
                File(out, file.nameWithoutExtension.take(40) + ".txt").writeText(report.toString())
                println("${file.name}: ${System.currentTimeMillis() - started} ms")
            }
        }
    }

    /** The raw lines of chosen pages, with position, size and face: -PpdfRawPages=book:12,book:13 (1-based). */
    @Test
    fun raw() {
        val dir = System.getProperty("pdfCorpus").orEmpty()
        val wanted = System.getProperty("pdfRawPages").orEmpty()
        assumeTrue(dir.isNotEmpty() && wanted.isNotEmpty())
        val out = File(System.getProperty("pdfCorpusOut") ?: "build/pdf-corpus").apply { mkdirs() }
        val report = StringBuilder()
        wanted.split(',').map { it.substringBeforeLast(':') to it.substringAfterLast(':').toInt() }.forEach { (book, page) ->
            PDDocument.load(File(dir, "$book.pdf"), MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                val p = PdfExtract.lines(pdf, page - 1, page, withImages = true).single()
                report.appendLine("#### $book p$page  ${p.width}x${p.height}")
                p.lines.forEach { l ->
                    val flags = (if (l.bold) "B" else "-") + (if (l.monospace) "M" else "-")
                    report.appendLine("x=%6.1f y=%6.1f r=%6.1f s=%5.1f %s | %s".format(l.x, l.y, l.right, l.fontSize, flags, l.text))
                }
                p.images.forEach { report.appendLine("IMAGE ${it.left},${it.top} ${it.width}x${it.height}") }
            }
        }
        File(out, "raw.txt").writeText(report.toString())
    }
}
