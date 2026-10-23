package com.pedrolopes.ttsing.desktop.library

import com.pedrolopes.ttsing.data.alreadyThereMessage
import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.book.BookFormat
import com.pedrolopes.ttsing.data.book.EpubDocument
import com.pedrolopes.ttsing.data.booksGone
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.EpubParser
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.pdf.PdfDocument
import com.pedrolopes.ttsing.desktop.AppDirs
import com.pedrolopes.ttsing.desktop.JsonFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A book in the library, as the index keeps it between runs. */
@Serializable
data class BookRecord(
    val id: String,
    /** Absolute path of the file. */
    val path: String,
    /** The subfolder holding it, relative to the library folder; "" at its top. */
    val folder: String,
    val format: String,
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val coverFile: String? = null,
    val fileSize: Long,
    val lastModified: Long,
    val sectionCount: Int,
    /** Characters per section, for progress and time left; null until counted. */
    val chapterChars: List<Int>? = null,
    val chapter: Int = 0,
    val block: Int = 0,
    val sentence: Int = 0,
    val progress: Float = 0f,
    val lastOpenedAt: Long = 0,
    /** Listening time (normalised to rate 1.0) and characters heard: the book's own pace. */
    val listenedMs: Long = 0,
    val listenedChars: Long = 0,
) {
    val bookFormat: BookFormat get() = BookFormat.fromStored(format)
    val fileName: String get() = File(path).name
    val position: ReadingPosition get() = ReadingPosition(chapter, block, sentence)
}

/**
 * The books folder and what TTSing knows about each book in it. The folder is the library:
 * subfolders are shelves, and moving, renaming or trashing a book does it to the file itself.
 * Titles, covers and reading positions are cached in `library.json` so the library opens at
 * once and a book reopens where it was left.
 */
class Library(private val dirs: AppDirs) {

    private val store = JsonFile(
        File(dirs.root, "library.json"),
        MapSerializer(String.serializer(), BookRecord.serializer()),
    ) { emptyMap() }

    private val _books = MutableStateFlow(store.read())
    val books: StateFlow<Map<String, BookRecord>> = _books.asStateFlow()

    private val _folders = MutableStateFlow(listOf(""))

    /** Every folder in the library as a path relative to it, the library folder itself ("") first. */
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** One scan or file operation at a time: they all rewrite the index. */
    private val mutex = Mutex()

    fun book(id: String): BookRecord? = _books.value[id]

    private fun change(transform: (Map<String, BookRecord>) -> Map<String, BookRecord>) {
        _books.update(transform)
        store.write(_books.value)
    }

    private fun put(record: BookRecord) = change { it + (record.id to record) }

    /**
     * Rescans [root] and refreshes the index. A book missing from the scan is only dropped if
     * the folder it lived in was really listed, so an unmounted drive or a folder that can't be
     * read never wipes reading positions. Returns false when [root] itself can't be read.
     */
    suspend fun sync(root: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            _scanning.value = true
            try {
                scan(root)
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun scan(root: File): Boolean {
        val unlisted = mutableSetOf<String>()
        val folders = mutableListOf<String>()
        val found = mutableListOf<Pair<File, String>>()
        fun walk(dir: File, path: String) {
            folders.add(path)
            val children = dir.listFiles()
            if (children == null) {
                unlisted.add(path)
                return
            }
            children.sortedBy { it.name.lowercase() }.forEach { child ->
                when {
                    child.name.startsWith(".") -> Unit
                    child.isDirectory -> walk(child, if (path.isEmpty()) child.name else "$path/${child.name}")
                    child.isFile && BookFormat.forFileName(child.name) != null -> found.add(child to path)
                }
            }
        }
        walk(root, "")
        _folders.value = folders

        val existing = _books.value
        val seen = mutableSetOf<String>()
        for ((file, folder) in found) {
            val id = idFor(file)
            seen.add(id)
            val known = existing[id]
            if (known != null && known.fileSize == file.length() && known.lastModified == file.lastModified()) {
                if (known.folder != folder) put(known.copy(folder = folder))
                continue
            }
            runCatching { index(id, file, folder, known) }.onSuccess(::put)
        }
        val gone = booksGone(existing.mapValues { it.value.folder }, seen, unlisted)
        if (gone.isNotEmpty()) {
            gone.forEach { id -> existing[id]?.coverFile?.let { File(it).delete() } }
            change { it - gone }
        }
        return "" !in unlisted
    }

    /** Reads [file]'s title, author and cover, keeping [known]'s place in it. */
    private fun index(id: String, file: File, folder: String, known: BookRecord?): BookRecord {
        val format = BookFormat.forFileName(file.name) ?: error("Not a book: ${file.name}")
        val cover = File(dirs.covers, "$id.img")
        val (title, author, language, sections) = when (format) {
            BookFormat.EPUB -> EpubParser(file).use { parser ->
                val epub = parser.parseBook()
                epub.coverPath?.let(parser::readEntry)?.let(cover::writeBytes)
                Meta(epub.title, epub.author, epub.language, epub.spine.size)
            }
            BookFormat.PDF -> PdfDocument.open(file, titleFromFileName(file.name)).use { pdf ->
                PdfDocument.renderCover(file)?.let(cover::writeBytes)
                Meta(pdf.title, pdf.author, pdf.language, pdf.sectionCount)
            }
        }
        val base = known ?: BookRecord(
            id = id,
            path = file.absolutePath,
            folder = folder,
            format = format.name,
            title = title,
            fileSize = 0,
            lastModified = 0,
            sectionCount = sections,
        )
        return base.copy(
            path = file.absolutePath,
            folder = folder,
            format = format.name,
            title = title,
            author = author,
            language = language,
            coverFile = cover.takeIf { it.isFile }?.absolutePath,
            fileSize = file.length(),
            lastModified = file.lastModified(),
            sectionCount = sections,
            // Counted against the old file; a changed file is counted again.
            chapterChars = known?.chapterChars?.takeIf { known.fileSize == file.length() && it.size == sections },
        )
    }

    private data class Meta(val title: String, val author: String?, val language: String?, val sections: Int)

    /** Opens a book for reading; the caller closes it. */
    suspend fun open(id: String): BookDocument = withContext(Dispatchers.IO) {
        val record = checkNotNull(book(id)) { "That book is no longer in the library" }
        val file = File(record.path)
        check(file.isFile) { "${record.fileName} is gone from the books folder" }
        val document = when (record.bookFormat) {
            BookFormat.EPUB -> EpubParser(file).let { parser ->
                runCatching { EpubDocument(parser, parser.parseBook()) }
                    .onFailure { parser.close() }
                    .getOrThrow()
            }
            BookFormat.PDF -> PdfDocument.open(file, record.title)
        }
        if (document.sectionCount != record.sectionCount) {
            put(record.copy(sectionCount = document.sectionCount, chapterChars = null))
        }
        document
    }

    /**
     * Characters per section, counted once by walking the whole book and then kept. The voice
     * reads [Block.Text] with sentences; that is what is counted.
     */
    suspend fun chapterChars(id: String, document: BookDocument): List<Int> = withContext(Dispatchers.IO) {
        book(id)?.chapterChars?.takeIf { it.size == document.sectionCount }?.let { return@withContext it }
        val counts = (0 until document.sectionCount).map { index ->
            runCatching {
                document.loadSection(index).blocks
                    .filterIsInstance<Block.Text>()
                    .filter { it.sentences.isNotEmpty() }
                    .sumOf { it.text.length }
            }.getOrDefault(0)
        }
        book(id)?.let { put(it.copy(chapterChars = counts)) }
        counts
    }

    /** The book was opened: it goes to the top of Recent. */
    fun markOpened(id: String) {
        val record = book(id) ?: return
        put(record.copy(lastOpenedAt = System.currentTimeMillis()))
    }

    fun savePosition(id: String, position: ReadingPosition, progress: Float) {
        val record = book(id) ?: return
        put(
            record.copy(
                chapter = position.chapterIndex,
                block = position.blockIndex,
                sentence = position.sentenceIndex,
                progress = progress.coerceIn(0f, 1f),
                lastOpenedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun addListening(id: String, ms: Long, chars: Long) {
        if (ms <= 0 || chars <= 0) return
        val record = book(id) ?: return
        put(record.copy(listenedMs = record.listenedMs + ms, listenedChars = record.listenedChars + chars))
    }

    /**
     * Moves the book's file into [folder] (a path from [folders]) under [root]. Its id follows
     * its path, so it changes; the reading position and cover go with it. Returns the new id.
     */
    suspend fun move(id: String, root: File, folder: String): String = relocate(id) { record ->
        val target = File(if (folder.isEmpty()) root else File(root, folder), record.fileName)
        if (target.exists()) error(alreadyThereMessage(record.fileName, folder))
        target to folder
    }

    /** Renames the book's file, keeping its extension. Returns the new id. */
    suspend fun rename(id: String, newName: String): String = relocate(id) { record ->
        val extension = record.bookFormat.extension
        val clean = newName.trim().removeSuffix(".$extension").trim()
        require(clean.isNotEmpty() && '/' !in clean) { "That isn't a file name" }
        val target = File(File(record.path).parentFile, "$clean.$extension")
        if (target.exists() && target.absolutePath != record.path) error("A file named ${target.name} is already there")
        target to record.folder
    }

    private suspend fun relocate(id: String, destination: (BookRecord) -> Pair<File, String>): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val record = checkNotNull(book(id)) { "That book is no longer in the library" }
                val (target, folder) = destination(record)
                if (target.absolutePath == record.path) return@withLock id
                target.parentFile?.mkdirs()
                Files.move(File(record.path).toPath(), target.toPath())
                val newId = idFor(target)
                val cover = record.coverFile?.let { old ->
                    val moved = File(dirs.covers, "$newId.img")
                    if (File(old).renameTo(moved)) moved.absolutePath else old
                }
                change {
                    it - id + (newId to record.copy(
                        id = newId,
                        path = target.absolutePath,
                        folder = folder,
                        coverFile = cover,
                        lastModified = target.lastModified(),
                    ))
                }
                newId
            }
        }

    /** Moves the book's file to the desktop's trash (`gio trash`), from where it can be restored. */
    suspend fun trash(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = checkNotNull(book(id)) { "That book is no longer in the library" }
            val process = runCatching {
                ProcessBuilder("gio", "trash", record.path).redirectErrorStream(true).start()
            }.getOrElse { throw IOException("Moving to the trash needs `gio` (from GLib)") }
            val output = process.inputStream.bufferedReader().readText()
            // gio says "gio: file:///…/book.epub: <reason>"; the reason is what's worth showing.
            if (process.waitFor() != 0) throw IOException("Couldn't move ${record.fileName} to the trash: ${output.trim().substringAfterLast(": ").ifEmpty { "gio failed" }}")
            record.coverFile?.let { File(it).delete() }
            change { it - id }
        }
    }

    /**
     * A file opened from elsewhere ("Open with TTSing"), as a library book: copied into [root]
     * unless it is already in the library, then indexed. Returns its id.
     */
    suspend fun add(file: File, root: File): String = withContext(Dispatchers.IO) {
        require(BookFormat.forFileName(file.name) != null) { "TTSing opens EPUB and PDF files" }
        val canonical = file.canonicalFile
        val inLibrary = canonical.path.startsWith(root.canonicalPath + File.separator)
        val target = if (inLibrary) {
            canonical
        } else {
            _books.value.values.firstOrNull { it.fileName == file.name && it.fileSize == file.length() }
                ?.let { return@withContext it.id }
            File(root, file.name).also { copy ->
                if (copy.exists()) error(alreadyThereMessage(file.name, ""))
                Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
        sync(root)
        val id = idFor(target)
        checkNotNull(book(id)?.id) { "Couldn't read that book" }
    }

    companion object {
        fun idFor(file: File): String =
            MessageDigest.getInstance("SHA-1")
                .digest(file.absolutePath.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** "the_dispossessed-le_guin.pdf" → "the dispossessed-le guin", for PDFs with no title. */
        fun titleFromFileName(name: String): String =
            name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { name }
    }
}
