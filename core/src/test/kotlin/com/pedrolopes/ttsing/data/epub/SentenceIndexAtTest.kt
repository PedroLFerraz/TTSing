package com.pedrolopes.ttsing.data.epub

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SentenceIndexAtTest {

    private val text = "First sentence. Second one here. Third and last."

    private val block = Block.Text(
        text = text,
        kind = Block.Text.Kind.PARAGRAPH,
        sentences = SentenceSplitter.split(text, Locale.ENGLISH),
    )

    @Test
    fun `offset inside each sentence maps to that sentence`() {
        assertEquals(0, block.sentenceIndexAt(text.indexOf("First")))
        assertEquals(1, block.sentenceIndexAt(text.indexOf("one")))
        assertEquals(2, block.sentenceIndexAt(text.indexOf("last")))
    }

    @Test
    fun `offset at a sentence boundary belongs to the sentence starting there`() {
        assertEquals(1, block.sentenceIndexAt(text.indexOf("Second")))
        assertEquals(2, block.sentenceIndexAt(text.indexOf("Third")))
    }

    @Test
    fun `whitespace between sentences belongs to the preceding one`() {
        // The space after "First sentence." — spans are trimmed, so it falls in the gap.
        assertEquals(0, block.sentenceIndexAt(text.indexOf("Second") - 1))
    }

    @Test
    fun `offsets outside the text are clamped`() {
        assertEquals(0, block.sentenceIndexAt(-5))
        assertEquals(2, block.sentenceIndexAt(text.length + 100))
    }

    @Test
    fun `a block with no sentences yields zero`() {
        val empty = Block.Text("", Block.Text.Kind.PARAGRAPH, emptyList())
        assertEquals(0, empty.sentenceIndexAt(0))
        assertEquals(0, empty.sentenceIndexAt(42))
    }
}
