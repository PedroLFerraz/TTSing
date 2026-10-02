package com.pedrolopes.ttsing.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.book.BookFormat
import com.pedrolopes.ttsing.data.book.EpubDocument
import com.pedrolopes.ttsing.data.db.BookDao
import com.pedrolopes.ttsing.data.db.BookEntity
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.EpubParser
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.pdf.PdfDocument
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
            if (!doc.isFile) continue
            val format = BookFormat.forFileName(name) ?: continue
            val id = sha1(doc.uri.toString())
            seenIds.add(id)
            val known = existing[id]
            if (known != null && known.fileSize == doc.length() && known.lastModified == doc.lastModified()) {
                continue
            }
            // A changed file must not be read from the stale cached copy.
            if (known != null) cachedFile(id, format).delete()
            runCatching {
                when (format) {
                    BookFormat.EPUB -> indexEpub(id, doc, known)
                    BookFormat.PDF -> indexPdf(id, doc, name, known)
                }
            }
        }

        val gone = existing.keys - seenIds
        if (gone.isNotEmpty()) {
            dao.delete(gone.toList())
            for (id in gone) {
                BookFormat.entries.forEach { cachedFile(id, it).delete() }
                existing[id]?.coverFile?.let { File(it).delete() }
            }
        }
    }

    private suspend fun indexPdf(id: String, doc: DocumentFile, fileName: String, known: BookEntity?) {
        val cached = ensureCachedFile(id, doc.uri, BookFormat.PDF)
        PdfDocument.open(cached, titleFromFileName(fileName)).use { pdf ->
            val coverFile = PdfDocument.renderCover(cached)?.let { bytes ->
                File(coversDir, "$id.img").apply { writeBytes(bytes) }.absolutePath
            }
            dao.upsert(
                entityFor(id, doc, known, pdf, coverFile, BookFormat.PDF),
            )
        }
    }

    /** "the_dispossessed-le_guin.pdf" → "the dispossessed-le guin", for PDFs with no title. */
    private fun titleFromFileName(name: String): String =
        name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { name }

    private fun entityFor(
        id: String,
        doc: DocumentFile,
        known: BookEntity?,
        book: BookDocument,
        coverFile: String?,
        format: BookFormat,
    ) = BookEntity(
        id = id,
        uri = doc.uri.toString(),
        title = book.title,
        author = book.author,
        language = book.language,
        coverFile = coverFile,
        fileSize = doc.length(),
        lastModified = doc.lastModified(),
        spineCount = book.sectionCount,
        chapterIndex = known?.chapterIndex ?: 0,
        blockIndex = known?.blockIndex ?: 0,
        sentenceIndex = known?.sentenceIndex ?: 0,
        progressPercent = known?.progressPercent ?: 0f,
        lastOpenedAt = known?.lastOpenedAt ?: 0,
        format = format.name,
    )

    private suspend fun indexEpub(id: String, doc: DocumentFile, known: BookEntity?) {
        val cached = ensureCachedFile(id, doc.uri, BookFormat.EPUB)
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
                    format = BookFormat.EPUB.name,
                ),
            )
        }
    }

    /** Opens a book for reading, whatever its format; the caller closes it. */
    suspend fun openBook(id: String): BookDocument? = withContext(Dispatchers.IO) {
        val entity = dao.get(id) ?: return@withContext null
        val format = BookFormat.fromStored(entity.format)
        val file = ensureCachedFile(id, Uri.parse(entity.uri), format)
        val document = when (format) {
            BookFormat.EPUB -> EpubParser(file).let { parser ->
                runCatching { EpubDocument(parser, parser.parseBook()) }
                    .onFailure { parser.close() }
                    .getOrThrow()
            }
            BookFormat.PDF -> PdfDocument.open(file, entity.title)
        }
        // Per-section caches are only valid for the sections they were counted over; if the
        // way a book is split has changed since it was indexed, count again.
        if (document.sectionCount != entity.spineCount) dao.resetSections(id, document.sectionCount)
        // Likewise the title and author, when how they are read from the file has improved.
        if (document.title != entity.title || document.author != entity.author) {
            dao.updateTitleAndAuthor(id, document.title, document.author)
        }
        document
    }

    /** The cached per-chapter character counts, or null if nobody has computed them yet. */
    suspend fun cachedChapterCharCounts(id: String): List<Int>? {
        val entity = dao.get(id) ?: return null
        return entity.chapterChars
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == entity.spineCount }
    }

    /**
     * Character count of every chapter, used for time-to-finish estimates. Computed once by
     * walking the whole spine, then cached in the database.
     */
    suspend fun chapterCharCounts(id: String): List<Int> = withContext(Dispatchers.IO) {
        cachedChapterCharCounts(id)?.let { return@withContext it }

        val counts = openBook(id)?.use { book ->
            (0 until book.sectionCount).map { index ->
                runCatching {
                    book.loadSection(index)
                        .blocks
                        .filterIsInstance<Block.Text>()
                        .filter { it.sentences.isNotEmpty() }
                        .sumOf { it.text.length }
                }.getOrDefault(0)
            }
        } ?: return@withContext emptyList()
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

    /**
     * Book pages per section for [layoutKey], if they were counted for exactly that screen
     * size and font scale; null means they need counting.
     */
    suspend fun pageCounts(id: String, layoutKey: String): List<Int>? {
        val entity = dao.get(id) ?: return null
        if (entity.pageLayoutKey != layoutKey) return null
        return entity.pageCounts
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == entity.spineCount }
    }

    suspend fun addListening(id: String, ms: Long, chars: Long) {
        if (ms > 0 && chars > 0) dao.addListening(id, ms, chars)
    }

    suspend fun savePageCounts(id: String, layoutKey: String, counts: List<Int>) {
        dao.updatePageCounts(id, counts.joinToString(","), layoutKey)
    }

    private fun cachedFile(id: String, format: BookFormat) = File(booksCacheDir, "$id.${format.extension}")

    private fun ensureCachedFile(id: String, uri: Uri, format: BookFormat): File {
        val file = cachedFile(id, format)
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
