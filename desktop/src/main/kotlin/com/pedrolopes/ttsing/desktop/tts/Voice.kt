package com.pedrolopes.ttsing.desktop.tts

import com.pedrolopes.ttsing.tts.piper.PiperVoice
import java.util.Locale

/** A voice the desktop reader can speak with, whichever engine runs it. */
sealed interface Voice {
    /** What the settings keep as the chosen voice for its language. */
    val id: String
    val locale: Locale

    /** A Piper voice, downloaded into the app's own folder and run here by sherpa-onnx. */
    data class Piper(val voice: PiperVoice) : Voice {
        override val id: String get() = voice.id
        override val locale: Locale get() = voice.locale
    }

    /** Google Translate's voice: online, in about any language, nothing to download. */
    data class Google(override val locale: Locale) : Voice {
        override val id: String get() = idFor(locale)

        companion object {
            fun idFor(locale: Locale) = "google-${locale.language}"
        }
    }

    /** Coqui's Tacotron 2 (LJSpeech), English only, run here by the helper [CoquiSetup] installs. */
    data object Tacotron2 : Voice {
        override val id = "coqui-tacotron2-en"
        override val locale: Locale = Locale.US
    }
}
