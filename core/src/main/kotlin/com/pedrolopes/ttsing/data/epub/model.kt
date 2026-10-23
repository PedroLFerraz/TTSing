package com.pedrolopes.ttsing.data.epub

import com.pedrolopes.ttsing.data.BookLanguage
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
            ?.let { BookLanguage.withRegion(it) }
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

/**
 * A footnote called out from a text block. [start, end) is its marker in the block's text — a
 * superscript number or an asterisk, shown on the page but never spoken — and [text] is the
 * note itself, shown in a popup when the marker is tapped.
 */
data class Note(val start: Int, val end: Int, val text: String)

sealed interface Block {
    data class Text(
        val text: String,
        val kind: Kind,
        val sentences: List<SentenceSpan>,
        /** In text order; their markers never overlap. */
        val notes: List<Note> = emptyList(),
    ) : Block {
        /** [CODE] is shown but not read: a listing has no sentences for the voice. */
        enum class Kind { PARAGRAPH, HEADING_1, HEADING_2, HEADING_3, QUOTE, CODE }

        /**
         * Index of the sentence containing [offset] (a char offset into [text]). Spans are
         * trimmed, so an offset landing in the whitespace between two sentences belongs to
         * the one before it. Clamped, never out of bounds.
         */
        fun sentenceIndexAt(offset: Int): Int {
            if (sentences.isEmpty()) return 0
            val index = sentences.indexOfLast { it.start <= offset }
            return if (index < 0) 0 else index
        }

        /**
         * The text in [start, end) as it is meant to be heard: each note marker becomes a space,
         * so the voice skips "¹" without anything after it moving — word timings and the
         * highlight count characters in the page's text.
         */
        fun spokenText(start: Int, end: Int): String {
            if (notes.none { it.start < end && it.end > start }) return text.substring(start, end)
            val chars = text.substring(start, end).toCharArray()
            for (note in notes) {
                for (i in maxOf(note.start, start) until minOf(note.end, end)) chars[i - start] = ' '
            }
            return String(chars)
        }

        /** The text in [start, end) with its note markers taken out: for copying it elsewhere. */
        fun plainText(start: Int, end: Int): String {
            if (notes.none { it.start < end && it.end > start }) return text.substring(start, end)
            val out = StringBuilder(end - start)
            var at = start
            for (note in notes) {
                if (note.end <= at || note.start >= end) continue
                out.append(text, at, note.start.coerceAtLeast(at))
                at = note.end.coerceAtMost(end)
            }
            return out.append(text, at, end).toString()
        }
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
