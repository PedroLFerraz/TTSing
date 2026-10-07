package com.pedrolopes.ttsing.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
import kotlinx.coroutines.CancellationException
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

    /**
     * Rescans the configured library folder and refreshes the metadata cache. Returns false when
     * the folder is set but can't be read (permission lost, storage gone), in which case nothing
     * is dropped from the cache.
     */
    suspend fun syncLibrary(): Boolean = withContext(Dispatchers.IO) {
        val folderSet = settings.settings.first().libraryFolderUri != null
        val tree = libraryTree() ?: return@withContext !folderSet
        val existing = dao.getAll().associateBy { it.id }
        val seenIds = mutableSetOf<String>()
        val unlisted = mutableSetOf<String>()

        for ((doc, folder) in booksIn(tree, unlisted)) {
            val name = doc.name ?: continue
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
                    BookFormat.EPUB -> indexEpub(id, doc, folder, known)
                    BookFormat.PDF -> indexPdf(id, doc, name, folder, known)
                }
            }
        }

        val gone = booksGone(existing.mapValues { it.value.folder }, seenIds, unlisted)
        if (gone.isNotEmpty()) {
            dao.delete(gone.toList())
            settings.forgetBooks(gone)
            for (id in gone) dropFiles(id, existing[id]?.coverFile)
        }
        "" !in unlisted
    }

    private suspend fun libraryTree(): DocumentFile? =
        settings.settings.first().libraryFolderUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }

    /** The folder at [path] under the library folder; "" is the library folder itself. */
    private fun folderAt(tree: DocumentFile, path: String): DocumentFile? =
        path.split('/').filter { it.isNotEmpty() }.fold(tree as DocumentFile?) { dir, name -> dir?.findFile(name) }

    private fun dropFiles(id: String, coverFile: String?) {
        BookFormat.entries.forEach { cachedFile(id, it).delete() }
        coverFile?.let { File(it).delete() }
    }

    /** Every folder in the library, as paths relative to it, the library folder itself ("") first. */
    suspend fun libraryFolders(): List<String> = withContext(Dispatchers.IO) {
        fun walk(dir: DocumentFile, path: String): List<String> = listOf(path) +
            dir.listFiles()
                .filter { it.isDirectory && it.name?.startsWith(".") == false }
                .sortedBy { it.name!!.lowercase() }
                .flatMap { walk(it, if (path.isEmpty()) it.name!! else "$path/${it.name}") }
        val tree = checkNotNull(libraryTree()) { "No library folder" }
        check(tree.exists() && tree.canRead()) { "Can't read the library folder" }
        walk(tree, "")
    }

    /** Deletes the book's file from the library folder, not just from TTSing. */
    suspend fun deleteBook(id: String): Unit = withContext(Dispatchers.IO) {
        val book = dao.get(id) ?: return@withContext
        check(DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(book.uri))) { "Could not delete the file" }
        dao.delete(listOf(id))
        settings.forgetBooks(listOf(id))
        dropFiles(id, book.coverFile)
    }

    /**
     * Moves the book's file to [folder] (a path from [libraryFolders]). The file's address, and
     * so the book's id, changes; its reading position, statistics and per-book settings move
     * with it. Returns the new id, or null if there was nothing to move.
     */
    suspend fun moveBook(id: String, folder: String): String? = withContext(Dispatchers.IO) {
        val book = dao.get(id) ?: return@withContext null
        if (book.folder == folder) return@withContext null
        val tree = checkNotNull(libraryTree()) { "No library folder" }
        val from = checkNotNull(folderAt(tree, book.folder)) { "The book's folder is gone" }
        val to = checkNotNull(folderAt(tree, folder)) { "That folder is gone" }
        val fileName = DocumentFile.fromSingleUri(context, Uri.parse(book.uri))?.name
        if (fileName != null && to.findFile(fileName) != null) error(alreadyThereMessage(fileName, folder))
        // ponytail: needs a provider that supports move (local storage does); copy + delete if others matter.
        val moved = try {
            DocumentsContract.moveDocument(context.contentResolver, Uri.parse(book.uri), from.uri, to.uri)
        } catch (e: Exception) {
            if (e is CancellationException || !isAlreadyExists(e.message)) throw e
            error(alreadyThereMessage(fileName ?: "That file", folder))
        }
        val newUri = checkNotNull(moved) { "Could not move the file" }
        val newId = sha1(newUri.toString())
        BookFormat.entries.forEach { cachedFile(id, it).renameTo(cachedFile(newId, it)) }
        dao.upsert(book.copy(id = newId, uri = newUri.toString(), folder = folder))
        dao.delete(listOf(id))
        settings.moveBookSettings(id, newId)
        newId
    }

    /**
     * A file handed over by another app ("Open with TTSing"), as a library book: copied into the
     * library folder, unless a book of that name and size is already in it, then indexed.
     * Returns its id; fails with a message fit to show when it can't.
     *
     * Copied rather than read in place because the other app's permission to read it lasts
     * only as long as this screen, and the library is the books folder: a book outside it
     * would be dropped on the next sync.
     */
    suspend fun addOpenedFile(uri: Uri, mimeType: String?): String = withContext(Dispatchers.IO) {
        val tree = checkNotNull(libraryTree()) { "Choose a books folder in TTSing first, then open the file again" }
        // Folders chosen before TTSing asked to write to them are read-only until chosen again.
        check(tree.canWrite()) { "TTSing can't save into your books folder yet. Choose the folder again in the library, then open the file again" }
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0)
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        // Some apps hand over "document:1234" with only the type to say what it is.
        val format = name?.let(BookFormat::forFileName)
            ?: BookFormat.entries.firstOrNull { it.mimeType == mimeType }
            ?: error("TTSing opens EPUB and PDF files")
        val fileName = name?.takeIf { BookFormat.forFileName(it) != null }
            ?: "${name ?: "book"}.${format.extension}"

        // Opening a book that is already in the library opens that one.
        dao.getAll().firstOrNull { book ->
            val path = Uri.decode(book.uri)
            book.fileSize == size && (path.endsWith("/$fileName") || path.endsWith(":$fileName"))
        }?.let { return@withContext it.id }

        val existing = tree.findFile(fileName)?.takeIf { it.length() == size }
        if (existing == null) {
            val copy = checkNotNull(tree.createFile(format.mimeType, fileName)) { "Could not create the file" }
            context.contentResolver.openInputStream(uri)?.use { input ->
                context.contentResolver.openOutputStream(copy.uri)?.use { output -> input.copyTo(output) }
            } ?: run {
                copy.delete()
                error("Could not read the file")
            }
        }
        syncLibrary()
        val id = (existing ?: tree.findFile(fileName))?.let { sha1(it.uri.toString()) }
        checkNotNull(id?.takeIf { dao.get(it) != null }) { "Couldn't read that book" }
    }

    /**
     * Every file under [dir], subfolders included, paired with its folder path relative to the
     * top. Folders that list as empty without being readable (listFiles() doesn't throw) are
     * added to [unlisted], so a failed listing isn't taken for a folder with no books.
     */
    private fun booksIn(dir: DocumentFile, unlisted: MutableSet<String>, path: String = ""): List<Pair<DocumentFile, String>> {
        val children = dir.listFiles()
        if (children.isEmpty() && !(dir.exists() && dir.canRead())) unlisted.add(path)
        return children.flatMap { doc ->
            val name = doc.name ?: return@flatMap emptyList()
            when {
                doc.isFile -> listOf(doc to path)
                doc.isDirectory && !name.startsWith(".") ->
                    booksIn(doc, unlisted, if (path.isEmpty()) name else "$path/$name")
                else -> emptyList()
            }
        }
    }

    private suspend fun indexPdf(id: String, doc: DocumentFile, fileName: String, folder: String, known: BookEntity?) {
        val cached = ensureCachedFile(id, doc.uri, BookFormat.PDF)
        PdfDocument.open(cached, titleFromFileName(fileName)).use { pdf ->
            val coverFile = PdfDocument.renderCover(cached)?.let { bytes ->
                File(coversDir, "$id.img").apply { writeBytes(bytes) }.absolutePath
            }
            dao.upsert(
                entityFor(id, doc, known, pdf, coverFile, BookFormat.PDF).copy(folder = folder),
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

    private suspend fun indexEpub(id: String, doc: DocumentFile, folder: String, known: BookEntity?) {
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
                    folder = folder,
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
