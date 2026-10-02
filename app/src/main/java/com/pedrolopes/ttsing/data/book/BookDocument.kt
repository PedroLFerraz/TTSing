package com.pedrolopes.ttsing.data.book

import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.TocEntry
import java.io.Closeable
import com.pedrolopes.ttsing.data.BookLanguage
import java.util.Locale

/**
 * An open book, whatever file it came from. Everything past this point — the reader, the
 * voice, the time estimates, flashcards — works on [Chapter]s of blocks, so a format only has
 * to say how many sections it has and turn each one into blocks.
 */
interface BookDocument : Closeable {
    val title: String
    val author: String?

    /** BCP-47 tag as the file declares it, if it does. */
    val language: String?

    /** Spine items for an EPUB; outline sections (or runs of pages) for a PDF. */
    val sectionCount: Int
    val toc: List<TocEntry>

    /**
     * Set when the file opened but has nothing to read aloud — a scanned PDF with no text
     * layer — so the reader can say so instead of showing empty pages.
     */
    val unreadableReason: String? get() = null

    fun locale(): Locale = localeForTag(language)

    /**
     * Whether reading straight on from the section before should pass over [section]: the
     * copyright page, the contents, the index. Opened on purpose, it still reads.
     */
    fun skipsWhenPlayingOn(section: Int): Boolean = false

    suspend fun loadSection(index: Int, locale: Locale = locale()): Chapter

    /** Bytes for an image block's key, or null when the format carries no images. */
    suspend fun readImage(key: String): ByteArray?

    companion object {
        fun localeForTag(tag: String?): Locale =
            tag?.let { Locale.forLanguageTag(it.trim().replace('_', '-')) }
                ?.takeIf { it.language.isNotEmpty() }
                ?.let { BookLanguage.withRegion(it) }
                ?: Locale.getDefault()
    }
}

/** What kind of file a library entry is; stored on the book so it opens the right way. */
enum class BookFormat(val extension: String) {
    EPUB("epub"),
    PDF("pdf"),
    ;

    companion object {
        fun forFileName(name: String): BookFormat? =
            entries.firstOrNull { name.endsWith(".${it.extension}", ignoreCase = true) }

        fun fromStored(value: String?): BookFormat =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: EPUB
    }
}
