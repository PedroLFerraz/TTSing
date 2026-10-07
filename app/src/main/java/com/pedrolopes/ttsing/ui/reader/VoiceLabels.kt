package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.tts.VoiceChoice
import com.pedrolopes.ttsing.tts.VoiceInfo
import java.util.Locale

/** One of the device engine's voices, reduced to what the list needs (and to what a test can build). */
internal data class DeviceVoice(
    val name: String,
    val locale: Locale,
    val quality: Int,
    /** True when the voice only works with a connection. */
    val online: Boolean,
    val needsDownload: Boolean,
) {
    fun info() = VoiceInfo(name, locale, quality, needsDownload)
}

/** How one device voice reads in the list. */
internal data class VoiceLine(
    val name: String,
    /** "Voice A" — the engine's own ids ("en-gb-x-gba-local") mean nothing to a reader. */
    val short: String,
    /** "English (UK)". */
    val region: String,
    /** "High quality · Offline". */
    val detail: String,
) {
    /** "English (UK) · voice A", for a row that stands outside a region group. */
    val full: String get() = "$region · ${short.replaceFirstChar { it.lowercase() }}"
}

internal data class VoiceGroup(val region: String, val voices: List<VoiceLine>)

internal object VoiceLabels {

    // Voice.QUALITY_*, which the pure code would otherwise need Android to know.
    private const val VERY_HIGH = 500
    private const val HIGH = 400
    private const val LOW = 200

    /** "English (UK)", "Portuguese (Brazil)": the variant is the choice, so it is shown. */
    fun region(locale: Locale, display: Locale = Locale.getDefault()): String {
        val language = locale.getDisplayLanguage(display).replaceFirstChar { it.uppercase() }
            .ifBlank { locale.language }
        val country = when (locale.country) {
            "" -> return language
            "GB" -> "UK"
            "US" -> "US"
            else -> locale.getDisplayCountry(display).ifBlank { locale.country }
        }
        return "$language ($country)"
    }

    private fun sameRegion(a: Locale, b: Locale) = a.language == b.language && a.country == b.country

    /**
     * The voice as a reader should see it. Letters follow the order of the region's voices in
     * [all] by name, and [all] must hold every voice of the region (hidden ones too), so a
     * voice keeps its letter when the list is filtered.
     */
    fun line(voice: DeviceVoice, all: List<DeviceVoice>, display: Locale = Locale.getDefault()): VoiceLine {
        val peers = all.filter { sameRegion(it.locale, voice.locale) }.map { it.name }.distinct().sorted()
        val index = peers.indexOf(voice.name).coerceAtLeast(0)
        val short = "Voice " + if (index < 26) ('A' + index).toString() else (index + 1).toString()
        val quality = when {
            voice.quality >= VERY_HIGH -> "Very high quality"
            voice.quality >= HIGH -> "High quality"
            voice.quality <= LOW -> "Low quality"
            else -> "Standard quality"
        }
        val detail = listOfNotNull(
            quality,
            if (voice.online) "Online" else "Offline",
            "Downloads on first use".takeIf { voice.needsDownload },
        ).joinToString(" · ")
        return VoiceLine(voice.name, short, region(voice.locale, display), detail)
    }

    /**
     * [voices] grouped by region in the order they come, with the online-only ones left out
     * unless [showOnline] or [keep] says otherwise (the chosen voice must never vanish).
     */
    fun groups(
        voices: List<DeviceVoice>,
        all: List<DeviceVoice> = voices,
        showOnline: Boolean,
        keep: (DeviceVoice) -> Boolean = { false },
        display: Locale = Locale.getDefault(),
    ): List<VoiceGroup> =
        voices.filter { showOnline || !it.online || keep(it) }
            .groupBy { region(it.locale, display) }
            .map { (region, list) -> VoiceGroup(region, list.map { line(it, all, display) }) }

    /** How many voices [groups] holds back for needing a connection. */
    fun hiddenOnline(voices: List<DeviceVoice>, keep: (DeviceVoice) -> Boolean = { false }): Int =
        voices.count { it.online && !keep(it) }

    /**
     * The voice that actually reads when none is chosen. Not the engine's own default: that is
     * overridden when it is from the wrong country (see [VoiceChoice]), and the list should
     * name the voice a reader will hear.
     */
    fun effectiveDefault(locale: Locale, voices: List<DeviceVoice>, engineDefaultName: String?): DeviceVoice? {
        val engineDefault = voices.firstOrNull { it.name == engineDefaultName }?.info()
        val name = VoiceChoice.pick(locale, voices.map { it.info() }, engineDefault) ?: return null
        return voices.firstOrNull { it.name == name }
    }
}
