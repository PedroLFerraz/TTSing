package com.pedrolopes.ttsing.data.epub

import java.util.Locale

data class EpubBook(
    val title: String,
    val author: String?,
    val language: String?,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
    val coverPath: String?,
) {
    fun locale(): Locale =
        language
            ?.let { Locale.forLanguageTag(it.trim().replace('_', '-')) }
            ?.takeIf { it.language.isNotEmpty() }
            ?: Locale.getDefault()
}

data class SpineItem(
    val idref: String,
    val zipPath: String,
)

data class TocEntry(
    val title: String,
    val spineIndex: Int,
)

/** Half-open range [start, end) into the owning block's text. */
data class SentenceSpan(val start: Int, val end: Int)

sealed interface Block {
    data class Text(
        val text: String,
        val kind: Kind,
        val sentences: List<SentenceSpan>,
    ) : Block {
        enum class Kind { PARAGRAPH, HEADING_1, HEADING_2, HEADING_3, QUOTE }
    }

    data class Image(
        val zipPath: String,
        val alt: String?,
    ) : Block
}

data class Chapter(
    val spineIndex: Int,
    val title: String?,
    val blocks: List<Block>,
)

data class ReadingPosition(
    val chapterIndex: Int,
    val blockIndex: Int,
    val sentenceIndex: Int,
) {
    companion object {
        val START = ReadingPosition(0, 0, 0)
    }
}
