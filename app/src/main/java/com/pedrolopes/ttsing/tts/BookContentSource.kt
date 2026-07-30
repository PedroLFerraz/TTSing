package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.EpubBook
import com.pedrolopes.ttsing.data.epub.EpubParser
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Feeds sentences from an [EpubBook] to the [Narrator], loading and caching
 * chapters on demand and transparently crossing block/chapter boundaries.
 */
class BookContentSource(
    private val parser: EpubParser,
    val book: EpubBook,
) : Narrator.ContentSource {

    private val locale = book.locale()
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Int, Chapter>()

    suspend fun chapter(index: Int): Chapter? {
        if (index !in book.spine.indices) return null
        return mutex.withLock {
            cache[index] ?: withContext(Dispatchers.IO) {
                parser.loadChapter(book, index, locale)
            }.also {
                cache[index] = it
                while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
            }
        }
    }

    override suspend fun firstAtOrAfter(position: ReadingPosition): SentenceRef? {
        var chapterIndex = position.chapterIndex.coerceAtLeast(0)
        var blockIndex = position.blockIndex.coerceAtLeast(0)
        var sentenceIndex = position.sentenceIndex.coerceAtLeast(0)
        while (chapterIndex < book.spine.size) {
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
