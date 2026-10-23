package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.ReadingPosition
import java.util.Locale

/**
 * A language the engine can speak. [downloaded] is false when every voice for it is flagged
 * `KEY_FEATURE_NOT_INSTALLED` — the engine still offers it, and selecting it starts the
 * download, so it belongs in the picker rather than being hidden.
 */
data class LanguageOption(
    val locale: Locale,
    val downloaded: Boolean,
) {
    companion object {
        /**
         * One option per language and region among [offered] (a voice's locale, and whether it
         * is ready without a download). A voice with no region joins its language's regions
         * rather than standing as a choice of its own, unless the language has no others.
         */
        fun variantsOf(offered: List<Pair<Locale, Boolean>>): List<LanguageOption> =
            offered
                .filter { (locale, _) -> locale.language.isNotEmpty() }
                .groupBy { (locale, _) -> locale.language }
                .flatMap { (language, voices) ->
                    val regional = voices.filter { (locale, _) -> locale.country.isNotEmpty() }
                    if (regional.isEmpty()) {
                        listOf(LanguageOption(Locale(language), voices.any { it.second }))
                    } else {
                        regional.groupBy { (locale, _) -> locale.country }.map { (country, inRegion) ->
                            LanguageOption(Locale(language, country), inRegion.any { it.second })
                        }
                    }
                }
                .sortedWith(
                    compareByDescending<LanguageOption> { it.downloaded }
                        .thenBy { it.locale.displayLanguage.lowercase() }
                        .thenBy { it.locale.displayCountry.lowercase() },
                )
    }
}

/** A single sentence ready to be spoken. */
data class SentenceRef(
    val position: ReadingPosition,
    val text: String,
    /** Char offset of this sentence inside its block's text. */
    val startInBlock: Int,
)

/** Supplies the sentences to read, in order, across block and chapter boundaries. */
interface ContentSource {
    /** First speakable sentence at or after [position], or null at end of book. */
    suspend fun firstAtOrAfter(position: ReadingPosition): SentenceRef?

    /** Sentence strictly after [position], or null at end of book. */
    suspend fun next(position: ReadingPosition): SentenceRef?

    /** Sentence strictly before [position], or null at start of book. */
    suspend fun prev(position: ReadingPosition): SentenceRef?
}
