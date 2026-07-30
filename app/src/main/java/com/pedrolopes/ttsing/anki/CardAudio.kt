package com.pedrolopes.ttsing.anki

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Renders a card's sentence to a WAV file with [TextToSpeech.synthesizeToFile].
 *
 * Deliberately keeps its **own** engine instance: [com.pedrolopes.ttsing.tts.AudioTrackNarrator]
 * owns the one used for read-aloud, and sharing it would flush the playback queue
 * mid-sentence when a card is made while the book is being read.
 */
class CardAudio(context: Context) {

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                synchronized(pending) { pending.remove(utteranceId) }?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                synchronized(pending) { pending.remove(utteranceId) }?.complete(false)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                synchronized(pending) { pending.remove(utteranceId) }?.complete(false)
            }
        })
    }

    /**
     * Speaks [text] into a WAV file under `cacheDir/anki-audio`, using the book's [locale]
     * and the voice the user picked for that language. Returns null if the engine failed.
     *
     * Rate is left at 1.0 on purpose: card audio should be natural speed even when the
     * book is being read at 2×.
     */
    suspend fun synthesize(
        text: String,
        locale: Locale,
        voiceName: String?,
        pitch: Float,
    ): File? = withContext(Dispatchers.IO) {
        if (!ready.await()) return@withContext null

        tts.setLanguage(locale)
        voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
        tts.setSpeechRate(1f)
        tts.setPitch(pitch)

        val dir = File(appContext.cacheDir, AUDIO_DIR).apply { mkdirs() }
        val utteranceId = "card-${System.currentTimeMillis()}-${text.hashCode()}"
        val file = File(dir, "$utteranceId.wav")

        val done = CompletableDeferred<Boolean>()
        synchronized(pending) { pending[utteranceId] = done }

        val queued = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            synchronized(pending) { pending.remove(utteranceId) }
            return@withContext null
        }

        if (done.await() && file.length() > 0) {
            file
        } else {
            file.delete()
            null
        }
    }

    /**
     * Plays [text] out loud so the user can hear the card before saving it. Uses this
     * class's own engine, so read-aloud playback is untouched.
     */
    suspend fun speak(text: String, locale: Locale, voiceName: String?, pitch: Float) {
        if (!ready.await()) return
        tts.setLanguage(locale)
        voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
        tts.setSpeechRate(1f)
        tts.setPitch(pitch)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "card-preview")
    }

    /** Clears previously synthesized files; they only exist to be handed to AnkiDroid. */
    fun clearCache() {
        runCatching { File(appContext.cacheDir, AUDIO_DIR).listFiles()?.forEach { it.delete() } }
    }

    fun shutdown() {
        runCatching { tts.shutdown() }
    }

    companion object {
        const val AUDIO_DIR = "anki-audio"
    }
}
