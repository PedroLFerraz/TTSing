package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.epub.SentenceSpan
import com.pedrolopes.ttsing.data.epub.TocEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class BookContentSourceTest {

    /** Sections of one sentence each, after a code block that has none; [skipped] pass by. */
    private class FakeBook(private val names: List<String>, private val skipped: Set<Int>) : BookDocument {
        override val title = "Fake"
        override val author: String? = null
        override val language = "en"
        override val sectionCount get() = names.size
        override val toc = names.mapIndexed { i, name -> TocEntry(name, i) }
        override fun skipsWhenPlayingOn(section: Int) = section in skipped
        override suspend fun loadSection(index: Int, locale: Locale): Chapter {
            val text = "${names[index]} text."
            return Chapter(
                index,
                names[index],
                listOf(
                    Block.Text("$ make all", Block.Text.Kind.CODE, emptyList()),
                    Block.Text(text, Block.Text.Kind.PARAGRAPH, listOf(SentenceSpan(0, text.length))),
                ),
            )
        }
        override suspend fun readImage(key: String): ByteArray? = null
        override fun close() = Unit
    }

    private val book = BookContentSource(FakeBook(listOf("Preface", "Contents", "Index", "Chapter 1", "Colophon"), setOf(1, 2, 4)))

    @Test
    fun `playing on passes over skipped sections and code`() = runBlocking {
        val last = book.firstAtOrAfter(ReadingPosition(0, 0, 0))!!
        assertEquals("Preface text.", last.text)
        val next = book.next(last.position)!!
        assertEquals("Chapter 1 text.", next.text)
        assertEquals(ReadingPosition(3, 1, 0), next.position)
        // The colophon is skipped too, so the book ends after chapter 1.
        assertEquals(null, book.next(next.position))
    }

    @Test
    fun `a skipped section opened on purpose is read`() = runBlocking {
        assertEquals("Contents text.", book.firstAtOrAfter(ReadingPosition(1, 0, 0))!!.text)
    }

    @Test
    fun `going back passes over skipped sections too`() = runBlocking {
        assertEquals("Preface text.", book.prev(ReadingPosition(3, 1, 0))!!.text)
    }
}
