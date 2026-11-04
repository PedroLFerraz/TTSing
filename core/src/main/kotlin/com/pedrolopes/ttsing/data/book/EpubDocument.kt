package com.pedrolopes.ttsing.data.book

import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.EpubBook
import com.pedrolopes.ttsing.data.epub.EpubParser
import com.pedrolopes.ttsing.data.epub.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** An EPUB as a [BookDocument]: sections are spine items, images come out of the zip. */
class EpubDocument(
    private val parser: EpubParser,
    private val book: EpubBook,
) : BookDocument {

    override val title: String get() = book.title
    override val author: String? get() = book.author
    override val language: String? get() = book.language
    override val sectionCount: Int get() = book.spine.size
    override val toc: List<TocEntry> get() = book.toc

    override fun locale(): Locale = book.locale()

    override suspend fun loadSection(index: Int, locale: Locale): Chapter =
        withContext(Dispatchers.IO) { parser.loadChapter(book, index, locale) }

    override suspend fun readImage(key: String): ByteArray? =
        withContext(Dispatchers.IO) { parser.readEntry(key) }

    override fun close() = parser.close()
}
