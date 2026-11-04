package com.pedrolopes.ttsing.data

import java.util.Locale

/**
 * Which *variant* of a language a book is read in.
 *
 * Books rarely say. *Primeiras estórias* declares `<dc:language>pt</dc:language>` and nothing
 * more, and a bare "pt" handed to a TTS engine gets the engine's own idea of Portuguese —
 * on Google's engine that is European Portuguese, which is not how a Brazilian book should
 * sound. So a language with no region is given one: the reader's own, when they speak that
 * language, and otherwise the variant most of its speakers use.
 */
object BookLanguage {

    /** Where most of a language's speakers are, for languages whose variants differ audibly. */
    private val MOST_SPEAKERS = mapOf(
        "pt" to "BR",
        "es" to "MX",
        "en" to "US",
        "zh" to "CN",
    )

    /**
     * [locale] with a region filled in when it has none. A region the book actually states is
     * kept: a book that says `pt-PT` is Portuguese and stays Portuguese.
     */
    fun withRegion(locale: Locale, device: Locale = Locale.getDefault()): Locale {
        if (locale.country.isNotEmpty() || locale.language.isEmpty()) return locale
        val region = device.country.takeIf { it.isNotEmpty() && device.language == locale.language }
            ?: MOST_SPEAKERS[locale.language]
            ?: return locale
        return Locale(locale.language, region)
    }
}
