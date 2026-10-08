package com.pedrolopes.ttsing.anki

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.api.AddContentApi
import com.pedrolopes.ttsing.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adds [CardDraft]s to AnkiDroid through its content-provider API.
 *
 * The note type is created once and its id cached in settings; on a fresh install the
 * deck/model are found again by name, so re-installing TTSing doesn't duplicate them.
 */
class AnkiExporter(
    context: Context,
    private val settings: SettingsRepository,
) {

    private val appContext = context.applicationContext
    private val api by lazy { AddContentApi(appContext) }

    sealed interface Result {
        /** [audioAttached] is false when the note was saved but its audio could not be. */
        data class Added(val audioAttached: Boolean) : Result

        data object AnkiNotInstalled : Result

        data object PermissionDenied : Result

        data class Failed(val message: String) : Result
    }

    /** The installed AnkiDroid package (release, beta and debug builds differ), or null. */
    fun ankiPackage(): String? = AddContentApi.getAnkiDroidPackageName(appContext)

    fun isAnkiInstalled(): Boolean = ankiPackage() != null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, AddContentApi.READ_WRITE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun addCard(draft: CardDraft, audio: File?): Result = withContext(Dispatchers.IO) {
        val ankiPackage = ankiPackage() ?: return@withContext Result.AnkiNotInstalled
        if (!hasPermission()) return@withContext Result.PermissionDenied

        try {
            val deckId = resolveDeckId()
                ?: return@withContext Result.Failed("Could not create the $DECK_NAME deck")
            val modelId = resolveModelId(deckId)
                ?: return@withContext Result.Failed("Could not create the note type")
            settings.setAnkiIds(deckId, modelId)

            // Media is best-effort: a card without audio still beats losing the card.
            val sound = audio?.let { attachMedia(it, ankiPackage, draft) }
            val fields = arrayOf(draft.frontHtml(), sound.orEmpty(), draft.meaningHtml())

            if (api.addNote(modelId, deckId, fields, draft.tags()) == null) {
                return@withContext Result.Failed("AnkiDroid rejected the note")
            }
            Result.Added(audioAttached = sound != null)
        } catch (security: SecurityException) {
            // The permission was revoked between the check above and the write.
            Result.PermissionDenied
        } catch (error: Exception) {
            Result.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    /**
     * Copies the WAV into AnkiDroid's collection.media and returns its `[sound:…]` tag.
     *
     * AnkiDroid opens the file through its own resolver, so this must be a `content://`
     * URI it has been granted read access to — a `file://` URI silently fails.
     */
    private fun attachMedia(audio: File, ankiPackage: String, draft: CardDraft): String? = runCatching {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", audio)
        appContext.grantUriPermission(ankiPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val name = mediaName(draft)
            api.addMediaFromUri(uri, name, "audio")
        } finally {
            appContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()

    /** A readable filename stem, e.g. `ttsing-coracao`; AnkiDroid appends its own suffix. */
    private fun mediaName(draft: CardDraft): String {
        val word = CardDraft.slugify(draft.targetWord)?.take(32)
        return if (word == null) "ttsing" else "ttsing-$word"
    }

    private suspend fun resolveDeckId(): Long? {
        val cached = settings.settings.first().ankiDeckId
        if (cached != null && runCatching { api.getDeckName(cached) }.getOrNull() != null) return cached
        // Not cached, or the deck was deleted in Anki: find it by name before making a new one,
        // so reinstalling TTSing doesn't leave a trail of duplicate decks.
        runCatching { api.deckList }.getOrNull()
            ?.entries?.firstOrNull { it.value == DECK_NAME }
            ?.let { return it.key }
        return api.addNewDeck(DECK_NAME)
    }

    private suspend fun resolveModelId(deckId: Long): Long? {
        val cached = settings.settings.first().ankiModelId
        if (cached != null && runCatching { api.getModelName(cached) }.getOrNull() != null) return cached
        runCatching { api.modelList }.getOrNull()
            ?.entries?.firstOrNull { it.value == MODEL_NAME }
            ?.let { return it.key }
        return api.addNewCustomModel(
            MODEL_NAME,
            FIELDS,
            arrayOf(CARD_NAME),
            arrayOf(QFMT),
            arrayOf(AFMT),
            null,
            deckId,
            0,
        )
    }

    companion object {
        const val DECK_NAME = "TTSing"
        const val MODEL_NAME = "TTSing Sentence"
        const val CARD_NAME = "Card 1"

        val FIELDS = arrayOf("Frente", "Audio", "Verso")

        val QFMT = """
            {{Frente}}
            <br>
            {{Audio}}
        """.trimIndent()

        val AFMT = """
            {{FrontSide}}

            <hr id=answer>

            {{Verso}}
        """.trimIndent()
    }
}
