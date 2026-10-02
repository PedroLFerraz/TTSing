package com.pedrolopes.ttsing.tts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguageOptionTest {

    @Test
    fun `regions of one language are separate choices`() {
        val options = LanguageOption.variantsOf(
            listOf(
                Locale("pt", "BR") to true,
                Locale("pt", "PT") to false,
                Locale("pt", "BR") to false,
            ),
        )
        assertEquals(
            listOf(LanguageOption(Locale("pt", "BR"), true), LanguageOption(Locale("pt", "PT"), false)),
            options,
        )
    }

    @Test
    fun `a voice with no region joins its language's regions`() {
        val options = LanguageOption.variantsOf(listOf(Locale("pt") to true, Locale("pt", "BR") to true))
        assertEquals(listOf(LanguageOption(Locale("pt", "BR"), true)), options)
    }

    @Test
    fun `a language with no regions is offered bare`() {
        val options = LanguageOption.variantsOf(listOf(Locale("eo") to false))
        assertEquals(listOf(LanguageOption(Locale("eo"), false)), options)
    }
}
