package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import java.util.Locale

/**
 * Something the reader can read aloud: an EPUB, or a news article.
 *
 * Books and articles differ in shape — a book has a spine of chapters, an article is one
 * piece — but the service only needs a title, a language and a way to count what is left, so
 * both fit behind this.
 */
interface ReadableContent : Narrator.ContentSource {
    val title: String
    val author: String?
    val locale: Locale

    /** Chapters for a book; always 1 for an article. */
    val sectionCount: Int

    /** Number of blocks in a section, used for the progress fraction. */
    suspend fun blockCount(sectionIndex: Int): Int
}

/**
 * Reads one already-extracted news article. Everything lives in memory as a single section,
 * so [ReadingPosition.chapterIndex] is always 0 and only the block/sentence parts move.
 */
class ArticleContentSource(
    val articleId: String,
    override val title: String,
    override val locale: Locale,
    val blocks: List<Block>,
    /** The feed's name, shown where a book would show its author: notification, lock screen. */
    override val author: String? = null,
) : ReadableContent {

    override val sectionCount: Int = 1

    override suspend fun blockCount(sectionIndex: Int): Int = blocks.size

    override suspend fun firstAtOrAfter(position: ReadingPosition): SentenceRef? {
        var blockIndex = position.blockIndex.coerceAtLeast(0)
        var sentenceIndex = position.sentenceIndex.coerceAtLeast(0)
        while (blockIndex < blocks.size) {
            val block = blocks[blockIndex]
            if (block is Block.Text && sentenceIndex < block.sentences.size) {
                return refAt(blockIndex, sentenceIndex, block)
            }
            blockIndex++
            sentenceIndex = 0
        }
        return null
    }

    override suspend fun next(position: ReadingPosition): SentenceRef? =
        firstAtOrAfter(position.copy(sentenceIndex = position.sentenceIndex + 1))

    override suspend fun prev(position: ReadingPosition): SentenceRef? {
        if (position.sentenceIndex > 0) {
            val block = blocks.getOrNull(position.blockIndex) as? Block.Text
            if (block != null && position.sentenceIndex - 1 < block.sentences.size) {
                return refAt(position.blockIndex, position.sentenceIndex - 1, block)
            }
        }
        var blockIndex = position.blockIndex - 1
        while (blockIndex >= 0) {
            val block = blocks[blockIndex]
            if (block is Block.Text && block.sentences.isNotEmpty()) {
                return refAt(blockIndex, block.sentences.size - 1, block)
            }
            blockIndex--
        }
        return null
    }

    private fun refAt(blockIndex: Int, sentenceIndex: Int, block: Block.Text): SentenceRef {
        val span = block.sentences[sentenceIndex]
        return SentenceRef(
            position = ReadingPosition(0, blockIndex, sentenceIndex),
            text = block.text.substring(span.start, span.end),
            startInBlock = span.start,
        )
    }
}
