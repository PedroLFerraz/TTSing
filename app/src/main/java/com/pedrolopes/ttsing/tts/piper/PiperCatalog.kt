package com.pedrolopes.ttsing.tts.piper

import java.util.Locale

/**
 * The neural voices the app knows how to get.
 *
 * One of them ships inside the app; the rest are a tap away, fetched from the sherpa-onnx
 * model releases. They are Piper voices (MIT), the same models the reader already runs, so
 * "download" only means "put the model where the reader can find it".
 *
 * Measured on a desktop CPU, a *medium* voice synthesizes about twenty times faster than it
 * speaks, and a *high* one about five — which is why this list stays with medium voices: a
 * high-quality model is what makes a phone fall behind and leave gaps between sentences.
 */
data class CatalogVoice(
    val id: String,
    val locale: Locale,
    /** What a reader should see: "Faber", "Lessac". */
    val displayName: String,
    val megabytes: Int,
    /** True for the one that ships inside the app. */
    val bundled: Boolean = false,
) {
    val downloadUrl: String get() = "$RELEASE_BASE/$id.tar.bz2"

    companion object {
        private const val RELEASE_BASE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    }
}

object PiperCatalog {

    val voices: List<CatalogVoice> = listOf(
        CatalogVoice("vits-piper-pt_BR-faber-medium", Locale("pt", "BR"), "Faber", 67, bundled = true),
        CatalogVoice("vits-piper-pt_BR-cadu-medium", Locale("pt", "BR"), "Cadu", 67),
        CatalogVoice("vits-piper-pt_BR-jeff-medium", Locale("pt", "BR"), "Jeff", 67),
        CatalogVoice("vits-piper-pt_BR-edresson-low", Locale("pt", "BR"), "Edresson", 67),
        CatalogVoice("vits-piper-en_US-lessac-medium", Locale("en", "US"), "Lessac", 67),
        CatalogVoice("vits-piper-en_US-hfc_female-medium", Locale("en", "US"), "HFC female", 67),
        CatalogVoice("vits-piper-en_US-amy-medium", Locale("en", "US"), "Amy", 67),
        CatalogVoice("vits-piper-en_GB-alba-medium", Locale("en", "GB"), "Alba", 67),
        CatalogVoice("vits-piper-es_ES-davefx-medium", Locale("es", "ES"), "Davefx", 67),
        CatalogVoice("vits-piper-fr_FR-siwis-medium", Locale("fr", "FR"), "Siwis", 67),
        CatalogVoice("vits-piper-de_DE-thorsten-medium", Locale("de", "DE"), "Thorsten", 67),
        CatalogVoice("vits-piper-it_IT-paola-medium", Locale("it", "IT"), "Paola", 67),
    )

    fun find(id: String): CatalogVoice? = voices.firstOrNull { it.id == id }

    /** The catalogue's voices for [locale]'s language, the app's own first. */
    fun forLanguage(locale: Locale): List<CatalogVoice> =
        voices.filter { it.locale.language == locale.language }
            .sortedWith(compareByDescending<CatalogVoice> { it.bundled }.thenBy { it.displayName })

    /**
     * What to call a voice the reader has but the catalogue doesn't, e.g. one added by hand:
     * `vits-piper-pt_BR-faber-medium` reads "Faber (medium)".
     */
    fun nameFor(id: String): String {
        find(id)?.let { return it.displayName }
        val parts = id.removePrefix("vits-piper-").split('-')
        if (parts.size < 2) return id
        val name = parts.getOrNull(1).orEmpty().replace('_', ' ')
            .replaceFirstChar { it.titlecase(Locale.ROOT) }
        val tier = parts.getOrNull(2)
        return if (tier.isNullOrEmpty()) name else "$name ($tier)"
    }
}
