package com.pedrolopes.ttsing.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pedrolopes.ttsing.data.db.BookDao
import com.pedrolopes.ttsing.data.db.BookEntity
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.EpubBook
import com.pedrolopes.ttsing.data.epub.EpubParser
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class BookRepository(
    private val context: Context,
    private val dao: BookDao,
    private val settings: SettingsRepository,
) {

    private val booksCacheDir: File get() = File(context.cacheDir, "books").apply { mkdirs() }
    private val coversDir: File get() = File(context.filesDir, "covers").apply { mkdirs() }

    fun observeBooks(): Flow<List<BookEntity>> = dao.observeAll()

    suspend fun getBook(id: String): BookEntity? = dao.get(id)

    /** Rescans the configured library folder and refreshes the metadata cache. */
    suspend fun syncLibrary(): Unit = withContext(Dispatchers.IO) {
        val folderUri = settings.settings.first().libraryFolderUri ?: return@withContext
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return@withContext
        val existing = dao.getAll().associateBy { it.id }
        val seenIds = mutableSetOf<String>()

        for (doc in tree.listFiles()) {
            val name = doc.name ?: continue
            if (!doc.isFile || !name.endsWith(".epub", ignoreCase = true)) continue
            val id = sha1(doc.uri.toString())
            seenIds.add(id)
            val known = existing[id]
            if (known != null && known.fileSize == doc.length() && known.lastModified == doc.lastModified()) {
                continue
            }
            runCatching { indexBook(id, doc, known) }
        }

        val gone = existing.keys - seenIds
        if (gone.isNotEmpty()) {
            dao.delete(gone.toList())
            for (id in gone) {
                File(booksCacheDir, "$id.epub").delete()
                existing[id]?.coverFile?.let { File(it).delete() }
            }
        }
    }

    private suspend fun indexBook(id: String, doc: DocumentFile, known: BookEntity?) {
        val cached = ensureCachedFile(id, doc.uri)
        EpubParser(cached).use { parser ->
            val epub = parser.parseBook()
            val coverFile = epub.coverPath?.let { coverPath ->
                parser.readEntry(coverPath)?.let { bytes ->
                    File(coversDir, "$id.img").apply { writeBytes(bytes) }.absolutePath
                }
            }
            dao.upsert(
                BookEntity(
                    id = id,
                    uri = doc.uri.toString(),
                    title = epub.title,
                    author = epub.author,
                    language = epub.language,
                    coverFile = coverFile,
                    fileSize = doc.length(),
                    lastModified = doc.lastModified(),
                    spineCount = epub.spine.size,
                    chapterIndex = known?.chapterIndex ?: 0,
                    blockIndex = known?.blockIndex ?: 0,
                    sentenceIndex = known?.sentenceIndex ?: 0,
                    progressPercent = known?.progressPercent ?: 0f,
                    lastOpenedAt = known?.lastOpenedAt ?: 0,
                ),
            )
        }
    }

    /** Opens the EPUB for reading; caller is responsible for closing the parser. */
    suspend fun openEpub(id: String): Pair<EpubParser, EpubBook>? = withContext(Dispatchers.IO) {
        val entity = dao.get(id) ?: return@withContext null
        val file = ensureCachedFile(id, Uri.parse(entity.uri))
        val parser = EpubParser(file)
        parser to parser.parseBook()
    }

    /**
     * Character count of every chapter, used for time-to-finish estimates. Computed once by
     * walking the whole spine, then cached in the database.
     */
    suspend fun chapterCharCounts(id: String): List<Int> = withContext(Dispatchers.IO) {
        val entity = dao.get(id) ?: return@withContext emptyList()
        entity.chapterChars
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == entity.spineCount }
            ?.let { return@withContext it }

        val file = ensureCachedFile(id, Uri.parse(entity.uri))
        val counts = EpubParser(file).use { parser ->
            val book = parser.parseBook()
            book.spine.indices.map { index ->
                runCatching {
                    parser.loadChapter(book, index)
                        .blocks
                        .filterIsInstance<Block.Text>()
                        .sumOf { it.text.length }
                }.getOrDefault(0)
            }
        }
        dao.updateChapterChars(id, counts.joinToString(","))
        counts
    }

    suspend fun savePosition(id: String, position: ReadingPosition, progress: Float) {
        dao.updatePosition(
            id = id,
            chapter = position.chapterIndex,
            block = position.blockIndex,
            sentence = position.sentenceIndex,
            progress = progress.coerceIn(0f, 1f),
            openedAt = System.currentTimeMillis(),
        )
    }

    private fun ensureCachedFile(id: String, uri: Uri): File {
        val file = File(booksCacheDir, "$id.epub")
        if (file.length() > 0L) return file
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open book stream: $uri")
        return file
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
