package com.pedrolopes.ttsing.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookEntriesTest {

    private fun lang(id: String) = stringPreferencesKey("booklang_$id")
    private fun view(id: String) = stringPreferencesKey("pdfview_$id")
    private val voice = stringPreferencesKey("voice_pt")

    @Test
    fun movingABookCarriesItsLanguageAndPdfView() {
        val prefs = mutablePreferencesOf(lang("old") to "pt-BR", view("old") to "TEXT", voice to "v1")
        prefs.moveBookEntries("old", "new")
        assertEquals("pt-BR", prefs[lang("new")])
        assertEquals("TEXT", prefs[view("new")])
        assertNull(prefs[lang("old")])
        assertNull(prefs[view("old")])
        assertEquals("v1", prefs[voice])
    }

    @Test
    fun movingABookWithOnlyOneEntryMovesJustThat() {
        val prefs = mutablePreferencesOf(view("old") to "TEXT")
        prefs.moveBookEntries("old", "new")
        assertNull(prefs[lang("new")])
        assertEquals("TEXT", prefs[view("new")])
    }

    @Test
    fun movingABookWithNoEntriesChangesNothing() {
        val prefs = mutablePreferencesOf(lang("other") to "de")
        prefs.moveBookEntries("old", "new")
        assertEquals(mutablePreferencesOf(lang("other") to "de"), prefs)
    }

    @Test
    fun droppedBooksLoseTheirEntriesAndOthersKeepTheirs() {
        val prefs = mutablePreferencesOf(
            lang("a") to "pt", view("a") to "TEXT", lang("b") to "de", view("c") to "TEXT", voice to "v1",
        )
        prefs.dropBookEntries(listOf("a", "c"))
        assertEquals(mutablePreferencesOf(lang("b") to "de", voice to "v1"), prefs)
    }
}
