package com.pedrolopes.ttsing.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class VoiceLabelsTest {

    private val en = Locale.ENGLISH

    private fun voice(name: String, tag: String, quality: Int = 400, online: Boolean = false, download: Boolean = false) =
        DeviceVoice(name, Locale.forLanguageTag(tag), quality, online, download)

    private val google = listOf(
        voice("en-gb-x-gbb-local", "en-GB"),
        voice("en-gb-x-gba-local", "en-GB"),
        voice("en-gb-x-gbc-network", "en-GB", online = true),
        voice("en-US-language", "en-US", quality = 300),
        voice("en-us-x-iom-local", "en-US", quality = 500),
    )

    @Test
    fun `regions read as names, not ids`() {
        assertEquals("English (UK)", VoiceLabels.region(Locale("en", "GB"), en))
        assertEquals("English (US)", VoiceLabels.region(Locale("en", "US"), en))
        assertEquals("Portuguese (Brazil)", VoiceLabels.region(Locale("pt", "BR"), en))
        assertEquals("German", VoiceLabels.region(Locale("de"), en))
    }

    @Test
    fun `voices are lettered by name within their region, hidden ones included`() {
        val gba = VoiceLabels.line(google[1], google, en)
        val gbb = VoiceLabels.line(google[0], google, en)
        val gbc = VoiceLabels.line(google[2], google, en)
        assertEquals("Voice A", gba.short)
        assertEquals("Voice B", gbb.short)
        assertEquals("Voice C", gbc.short)
        assertEquals("English (UK) · voice A", gba.full)
        // The US voices count from A again.
        assertEquals("Voice A", VoiceLabels.line(google[3], google, en).short)
    }

    @Test
    fun `detail names quality and whether it needs a connection`() {
        assertEquals("High quality · Offline", VoiceLabels.line(google[0], google, en).detail)
        assertEquals("High quality · Online", VoiceLabels.line(google[2], google, en).detail)
        assertEquals("Very high quality · Offline", VoiceLabels.line(google[4], google, en).detail)
        assertEquals(
            "Standard quality · Offline · Downloads on first use",
            VoiceLabels.line(voice("x", "en-US", quality = 300, download = true), google, en).detail,
        )
    }

    @Test
    fun `online voices are held back unless asked for or in use`() {
        val hidden = VoiceLabels.groups(google, showOnline = false, display = en)
        assertEquals(listOf("English (UK)", "English (US)"), hidden.map { it.region })
        assertEquals(2, hidden.first().voices.size)
        assertEquals(1, VoiceLabels.hiddenOnline(google))

        val shown = VoiceLabels.groups(google, showOnline = true, display = en)
        assertEquals(3, shown.first().voices.size)

        // The chosen voice must not vanish because it happens to need a connection.
        val kept = VoiceLabels.groups(google, showOnline = false, keep = { it.name == "en-gb-x-gbc-network" }, display = en)
        assertEquals(3, kept.first().voices.size)
        assertEquals(0, VoiceLabels.hiddenOnline(google, keep = { it.name == "en-gb-x-gbc-network" }))
    }

    @Test
    fun `filtering does not renumber the letters`() {
        val groups = VoiceLabels.groups(google, showOnline = false, display = en)
        val names = groups.first().voices.associate { it.name to it.short }
        assertEquals("Voice A", names["en-gb-x-gba-local"])
        assertEquals("Voice B", names["en-gb-x-gbb-local"])
    }

    @Test
    fun `the default row names the voice that will really read, not the engine's own pick`() {
        val pt = listOf(
            voice("pt-pt-language", "pt-PT"),
            voice("pt-br-x-afs-local", "pt-BR", download = true),
            voice("pt-br-language", "pt-BR", download = true),
        )
        val chosen = VoiceLabels.effectiveDefault(Locale("pt", "BR"), pt, engineDefaultName = "pt-pt-language")
        assertEquals("pt-br-language", chosen?.name)
        assertEquals("Portuguese (Brazil) · voice A", VoiceLabels.line(chosen!!, pt, en).full)
    }

    @Test
    fun `no voice for the language means no default to name`() {
        assertNull(VoiceLabels.effectiveDefault(Locale("de"), google, engineDefaultName = null))
    }
}
