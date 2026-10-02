package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.SentenceSplitter
import java.util.Locale

/**
 * A sentence as the voice should hear it, rather than as the page shows it.
 *
 * [SentenceSplitter] already keeps "Dr. Silva" in one sentence, but the text still reaches
 * the engine with its dot, and espeak — the neural voices' phonemizer — takes every full stop
 * as a clause end: "a Sra. Ana chegou" came out as "a senhora… Ana chegou". Here the
 * abbreviations the splitter recognises are spelled out ("Senhora") or, when there is no
 * spelling to give, lose their dot. The sentence's own final stop is left alone.
 *
 * Only the engine sees this text. The highlight keeps working from the page's text, so the
 * few extra letters change its timing within a clause and nothing else.
 */
object SpokenText {

    fun normalize(text: String, locale: Locale): String {
        val tokens = TOKEN.findAll(text).toList()
        if (tokens.size < 2) return text
        val expansions = EXPANSIONS[locale.language].orEmpty()
        val out = StringBuilder(text.length + 16)
        var copied = 0
        // The last word carries the sentence's own stop, which is a pause we want.
        for (i in 0 until tokens.lastIndex) {
            val match = tokens[i]
            val token = match.value
            if (!token.endsWith('.')) continue
            val lead = token.takeWhile { it in SentenceSplitter.OPENING_PUNCTUATION }
            val core = token.substring(lead.length).removeSuffix(".")
            if (core.isEmpty() || core.endsWith('.')) continue
            val previous = tokens.getOrNull(i - 1)?.value
            val next = tokens[i + 1].value
            val spoken = spokenFor(core, previous, next, locale.language, expansions) ?: continue
            out.append(text, copied, match.range.first).append(lead).append(spoken)
            copied = match.range.last + 1
        }
        if (copied == 0) return text
        return out.append(text, copied, text.length).toString()
    }

    /** What [core] (an abbreviation with its dot removed) should be said as, or null to leave it. */
    private fun spokenFor(
        core: String,
        previous: String?,
        next: String,
        language: String,
        expansions: Map<String, String>,
    ): String? {
        val lower = core.lowercase()
        // A capital on its own is an initial — "J. P. Morgan" — never "p." for page.
        if (core.length == 1 && core[0].isUpperCase()) {
            if (language == "pt" && startsName(previous) && next.firstOrNull()?.isUpperCase() == true) {
                when (core) {
                    "D" -> return if (isFeminine(next)) "Dona" else "Dom"
                    "S" -> return "São"
                }
            }
            // An initial: "J. R. R. Tolkien". The letter alone reads as itself.
            return core
        }
        expansions[lower]?.let { return it.matchCase(core) }
        if (lower in SentenceSplitter.ABBREVIATIONS) return core
        // "I" is the pronoun far more often than a numeral; its stop stays.
        if (core.length > 1 && SentenceSplitter.ROMAN_NUMERAL.matches(core)) return core
        return null
    }

    /**
     * Whether a title can start here — the beginning of the sentence or after an ordinary
     * word — rather than an initial in the middle of a name, "José S. Lima".
     */
    private fun startsName(previous: String?): Boolean {
        val word = previous?.trimStart(*SentenceSplitter.OPENING_PUNCTUATION) ?: return true
        val first = word.firstOrNull() ?: return true
        return !first.isUpperCase() || word.last() in ",;:"
    }

    /** Portuguese given names are feminine when they end in "a", bar a handful. */
    private fun isFeminine(name: String): Boolean {
        val word = name.trimEnd(',', '.', ';', ':', '!', '?').lowercase()
        return word.endsWith("a") || word in FEMININE_NAMES
    }

    private fun String.matchCase(original: String): String =
        if (original.first().isUpperCase()) replaceFirstChar { it.uppercaseChar() } else this

    private val TOKEN = Regex("\\S+")

    private val FEMININE_NAMES = setOf("isabel", "inês", "leonor", "beatriz", "raquel", "carmen", "ester", "rute")

    /** Spelled-out forms, lower case and keyed without the dot. Anything else just loses its dot. */
    private val EXPANSIONS: Map<String, Map<String, String>> = mapOf(
        "pt" to mapOf(
            "dr" to "doutor", "dra" to "doutora", "drs" to "doutores", "dras" to "doutoras",
            "sr" to "senhor", "sra" to "senhora", "srs" to "senhores", "sras" to "senhoras",
            "srta" to "senhorita", "srtas" to "senhoritas",
            "prof" to "professor", "profa" to "professora", "profs" to "professores",
            "sto" to "santo", "sta" to "santa", "pe" to "padre",
            "exmo" to "excelentíssimo", "exma" to "excelentíssima",
            "ilmo" to "ilustríssimo", "ilma" to "ilustríssima",
            "eng" to "engenheiro", "arq" to "arquiteto",
            "pág" to "página", "págs" to "páginas", "pag" to "página", "pags" to "páginas",
            "cap" to "capítulo", "caps" to "capítulos", "vol" to "volume", "vols" to "volumes",
            "fig" to "figura", "figs" to "figuras", "art" to "artigo", "arts" to "artigos",
            "séc" to "século", "núm" to "número", "av" to "avenida", "aprox" to "aproximadamente",
            "tel" to "telefone", "obs" to "observação",
        ),
        "en" to mapOf(
            "dr" to "doctor", "mr" to "mister", "mrs" to "missus", "ms" to "miz",
            "prof" to "professor", "rev" to "reverend", "st" to "saint", "jr" to "junior",
            "gen" to "general", "col" to "colonel", "capt" to "captain", "lt" to "lieutenant",
            "sgt" to "sergeant", "gov" to "governor", "sen" to "senator",
            "p" to "page", "pp" to "pages", "ch" to "chapter", "chap" to "chapter",
            "vol" to "volume", "vols" to "volumes", "fig" to "figure", "figs" to "figures",
            "vs" to "versus", "approx" to "approximately", "dept" to "department",
            "e.g" to "for example", "i.e" to "that is", "cf" to "compare",
        ),
        "es" to mapOf(
            "dr" to "doctor", "dra" to "doctora", "sr" to "señor", "sra" to "señora",
            "srta" to "señorita", "ud" to "usted", "uds" to "ustedes", "prof" to "profesor",
        ),
    )
}
