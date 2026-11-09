package com.pedrolopes.ttsing.desktop

import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.desktop.tts.Narration
import com.pedrolopes.ttsing.tts.SentenceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The word highlight follows the voice, including after a pause in the middle of a sentence. */
class NarrationTest {

    private fun sentence(index: Int) =
        SentenceRef(ReadingPosition(0, 0, index), "The garden was quiet in the morning.", 0)

    @Test
    fun wordShowsAfterResumingOnTheSentencePausedIn() {
        val state = MutableStateFlow(Narration(sentence = sentence(0)))
        // Resuming reads the paused sentence again, as a new ref equal to the one shown, which
        // the state flow drops: the narrator speaks a ref that isn't the instance it holds.
        val resumed = sentence(0)
        state.update { it.copy(sentence = resumed, word = null) }
        state.update { it.withWord(resumed, 4..9) }
        assertEquals(4..9, state.value.word)
    }

    @Test
    fun wordOfAnotherSentenceIsDropped() {
        // The reader skipped on while the voice was still finishing the previous sentence.
        val shown = Narration(sentence = sentence(1))
        assertNull(shown.withWord(sentence(0), 4..9).word)
    }
}
