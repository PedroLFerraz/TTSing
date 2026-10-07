package com.pedrolopes.ttsing.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDraftTest {

    private fun draft(sentence: String, word: String): CardDraft {
        val start = sentence.indexOf(word)
        return CardDraft(sentence = sentence, targetStart = start, targetEnd = start + word.length)
    }

    @Test
    fun `bolds the target word`() {
        val card = draft("Ele não tinha coração.", "coração")
        assertEquals("Ele não tinha <b>coração</b>.", card.frontHtml())
    }

    @Test
    fun `bolds a word at the start and at the end`() {
        assertEquals("<b>Ele</b> foi embora.", draft("Ele foi embora.", "Ele").frontHtml())
        assertEquals("Ele foi <b>embora</b>", draft("Ele foi embora", "embora").frontHtml())
    }

    @Test
    fun `escapes html around and inside the bolded span`() {
        val sentence = "A < B & C > D"
        val card = draft(sentence, "B")
        assertEquals("A &lt; <b>B</b> &amp; C &gt; D", card.frontHtml())
    }

    @Test
    fun `escapes the sentence even without a selection`() {
        val card = CardDraft(sentence = "Tom & \"Jerry\"")
        assertFalse(card.hasTarget)
        assertEquals("Tom &amp; &quot;Jerry&quot;", card.frontHtml())
    }

    @Test
    fun `first tap selects a word`() {
        val card = CardDraft(sentence = "Ele foi embora.").withWordAt(4, 7)
        assertTrue(card.hasTarget)
        assertEquals("foi", card.targetWord)
    }

    @Test
    fun `second tap extends the selection across the words between them`() {
        val sentence = "Ela deu de ombros."
        val card = CardDraft(sentence = sentence)
            .withWordAt(4, 7)   // deu
            .withWordAt(11, 17) // ombros — the "de" between them is swallowed
        assertEquals("deu de ombros", card.targetWord)
        assertEquals("Ela <b>deu de ombros</b>.", card.frontHtml())
    }

    @Test
    fun `extending backwards works too`() {
        val sentence = "Ela deu de ombros."
        val card = CardDraft(sentence = sentence)
            .withWordAt(11, 17) // ombros first
            .withWordAt(4, 7)   // then deu
        assertEquals("deu de ombros", card.targetWord)
    }

    @Test
    fun `tapping the selected word again clears it`() {
        val card = CardDraft(sentence = "Ele foi embora.").withWordAt(4, 7).withWordAt(4, 7)
        assertFalse(card.hasTarget)
        assertEquals("", card.targetWord)
    }

    @Test
    fun `tags carry the source marker and a slug of the book`() {
        val card = draft("Ele foi embora.", "foi").copy(bookTitle = "O Pequeno Jardim")
        assertEquals(setOf("ttsing", "o-pequeno-jardim"), card.tags())
    }

    @Test
    fun `slug collapses punctuation and drops an empty title`() {
        assertEquals("the-little-garden", CardDraft.slugify("The Little Garden!"))
        assertEquals("a-b", CardDraft.slugify("  A --- B  "))
        assertNull(CardDraft.slugify("   "))
    }

    @Test
    fun `untitled book yields only the source tag`() {
        assertEquals(setOf("ttsing"), draft("Ele foi embora.", "foi").tags())
    }

    @Test
    fun `tapping the first word of a multi-word selection removes it from the start`() {
        val sentence = "Ela deu de ombros."
        val card = CardDraft(sentence = sentence)
            .withWordAt(4, 7)   // deu
            .withWordAt(11, 17) // ombros
            .withWordAt(4, 7)   // tap deu again to remove it
        assertEquals("de ombros", card.targetWord)
        // The space at position 7 is skipped, so the selection starts at position 8.
        assertEquals("Ela deu <b>de ombros</b>.", card.frontHtml())
    }

    @Test
    fun `tapping the last word of a multi-word selection removes it from the end`() {
        val sentence = "Ela deu de ombros."
        val card = CardDraft(sentence = sentence)
            .withWordAt(4, 7)   // deu
            .withWordAt(11, 17) // ombros
            .withWordAt(11, 17) // tap ombros again to remove it
        assertEquals("deu de", card.targetWord)
        // The space at position 10 is skipped (going backward), so the selection ends at position 10.
        assertEquals("Ela <b>deu de</b> ombros.", card.frontHtml())
    }

    @Test
    fun `tapping a word strictly inside shrinks the selection to end at it`() {
        val sentence = "Ela deu de ombros."
        val card = CardDraft(sentence = sentence)
            .withWordAt(4, 7)   // deu
            .withWordAt(11, 17) // ombros
            .withWordAt(8, 10)  // tap "de" in the middle
        assertEquals("deu de", card.targetWord)
        assertEquals("Ela <b>deu de</b> ombros.", card.frontHtml())
    }

    @Test
    fun `meaning is html-escaped`() {
        val card = draft("Ele foi embora.", "foi").copy(meaning = "A < B & C > D")
        // The front is already tested; just verify the meaning field escaping.
        assertEquals("A &lt; B &amp; C &gt; D", CardDraft.escapeHtml(card.meaning))
    }
}
