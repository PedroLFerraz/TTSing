package com.pedrolopes.ttsing.tts

import java.util.Locale

/** What the choice below needs to know about one of the engine's voices. */
data class VoiceInfo(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val needsDownload: Boolean,
)

/**
 * Which voice reads a book, when the reader hasn't picked one.
 *
 * The rule that used to apply — "the engine's default voice, as long as it speaks the right
 * *language*" — read a Brazilian book in European Portuguese, because Google's default
 * Portuguese voice is `pt-pt-language` while its Brazilian ones are only downloaded on first
 * use. The region matters more than that: a voice from the book's own country comes first,
 * and only then the ones already on the device.
 *
 * Pure, so the rule is unit-tested even though the voices it picks from only exist on a device.
 */
object VoiceChoice {

    /** The name of the voice to use for [locale], or null when the engine has none for it. */
    fun pick(locale: Locale, voices: List<VoiceInfo>, engineDefault: VoiceInfo?): String? {
        val sameLanguage = voices.filter { it.locale.language == locale.language }
        if (sameLanguage.isEmpty()) {
            return engineDefault?.takeIf { it.locale.language == locale.language }?.name
        }
        val wanted = locale.country
        val sameCountry = { voice: VoiceInfo ->
            wanted.isEmpty() || voice.locale.country.equals(wanted, ignoreCase = true)
        }
        // The engine's own default is kept when it is from the right country and is already
        // on the device: no reason to override a choice the engine made for itself.
        engineDefault
            ?.takeIf { it.locale.language == locale.language && sameCountry(it) && !it.needsDownload }
            ?.let { return it.name }
        return sameLanguage
            .minWithOrNull(
                compareBy(
                    { !sameCountry(it) },
                    { it.needsDownload },
                    { -it.quality },
                    { it.name },
                ),
            )
            ?.name
    }
}
