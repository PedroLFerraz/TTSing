package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Feeds sentences from a [BookDocument] — EPUB or PDF — to the [Narrator], loading and
 * caching sections on demand and transparently crossing block/section boundaries.
 */
class BookContentSource(
    private val document: BookDocument,
) : ReadableContent {

    override val title: String get() = document.title

    override val author: String? get() = document.author

    override val locale: Locale = document.locale()

    override val sectionCount: Int get() = document.sectionCount

    override suspend fun blockCount(sectionIndex: Int): Int = chapter(sectionIndex)?.blocks?.size ?: 0
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Int, Chapter>()

    suspend fun chapter(index: Int): Chapter? {
        if (index !in 0 until document.sectionCount) return null
        return mutex.withLock {
            cache[index] ?: document.loadSection(index, locale).also {
                cache[index] = it
                while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
            }
        }
    }

    override suspend fun firstAtOrAfter(position: ReadingPosition): SentenceRef? {
        var chapterIndex = position.chapterIndex.coerceAtLeast(0)
        var blockIndex = position.blockIndex.coerceAtLeast(0)
        var sentenceIndex = position.sentenceIndex.coerceAtLeast(0)
        while (chapterIndex < document.sectionCount) {
            val chapter = chapter(chapterIndex) ?: return null
            while (blockIndex < chapter.blocks.size) {
                val block = chapter.blocks[blockIndex]
                if (block is Block.Text && sentenceIndex < block.sentences.size) {
                    val span = block.sentences[sentenceIndex]
                    return SentenceRef(
                        position = ReadingPosition(chapterIndex, blockIndex, sentenceIndex),
                        text = block.text.substring(span.start, span.end),
                        startInBlock = span.start,
                    )
                }
                blockIndex++
                sentenceIndex = 0
            }
            chapterIndex++
            blockIndex = 0
            sentenceIndex = 0
        }
        return null
    }

    override suspend fun next(position: ReadingPosition): SentenceRef? =
        firstAtOrAfter(position.copy(sentenceIndex = position.sentenceIndex + 1))

    override suspend fun prev(position: ReadingPosition): SentenceRef? {
        if (position.sentenceIndex > 0) {
            return refAt(position.copy(sentenceIndex = position.sentenceIndex - 1))
                ?: firstAtOrAfter(position.copy(sentenceIndex = 0))
        }
        // Walk backwards to the previous text block (possibly in a previous chapter).
        var chapterIndex = position.chapterIndex
        var blockIndex = position.blockIndex - 1
        while (chapterIndex >= 0) {
            val chapter = chapter(chapterIndex) ?: return null
            if (blockIndex >= chapter.blocks.size) blockIndex = chapter.blocks.size - 1
            while (blockIndex >= 0) {
                val block = chapter.blocks[blockIndex]
                if (block is Block.Text && block.sentences.isNotEmpty()) {
                    return refAt(ReadingPosition(chapterIndex, blockIndex, block.sentences.size - 1))
                }
                blockIndex--
            }
            chapterIndex--
            blockIndex = Int.MAX_VALUE
        }
        return null
    }

    private suspend fun refAt(position: ReadingPosition): SentenceRef? {
        val chapter = chapter(position.chapterIndex) ?: return null
        val block = chapter.blocks.getOrNull(position.blockIndex) as? Block.Text ?: return null
        val span = block.sentences.getOrNull(position.sentenceIndex) ?: return null
        return SentenceRef(position, block.text.substring(span.start, span.end), span.start)
    }

    private companion object {
        const val MAX_CACHED = 3
    }
}
