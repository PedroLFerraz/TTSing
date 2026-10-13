package com.pedrolopes.ttsing.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.pedrolopes.ttsing.data.epub.Note
import com.pedrolopes.ttsing.ui.common.Hairline
import com.pedrolopes.ttsing.ui.common.MonoText
import com.pedrolopes.ttsing.ui.theme.AppFonts

/**
 * A footnote, opened from its marker: a panel along the foot of the page, in the page's own
 * colours, the way KOReader shows one. A tap anywhere else or Back puts it away; a long note
 * scrolls inside it rather than covering the page.
 */
@Composable
fun NotePopup(
    note: Note,
    fontScale: Float,
    palette: ReaderPalette,
    onDismiss: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .background(palette.background),
        ) {
            Hairline(inset = 0.dp, color = palette.hairline)
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                MonoText("Note", color = palette.secondaryText, modifier = Modifier.padding(bottom = 8.dp))
                val size = 16f * fontScale
                Text(
                    text = note.text,
                    color = palette.text,
                    fontFamily = AppFonts.Serif,
                    fontSize = size.sp,
                    lineHeight = (size * 1.5f).sp,
                )
            }
        }
    }
}

/**
 * The note whose marker a tap at [point] was meant for. Markers are a character or two of
 * superscript, too small to hit squarely, so a tap within [reachPx] of one counts; between two,
 * the nearer wins. [notes] are the block's, [sliceStart] where the laid-out text begins in it.
 */
internal fun noteAt(
    notes: List<Note>,
    sliceStart: Int,
    sliceEnd: Int,
    layout: TextLayoutResult,
    point: Offset,
    reachPx: Float,
): Note? = notes
    .filter { it.start >= sliceStart && it.end <= sliceEnd && it.end > it.start }
    .map { note ->
        val first = layout.getBoundingBox(note.start - sliceStart)
        val last = layout.getBoundingBox(note.end - 1 - sliceStart)
        note to Rect(
            left = minOf(first.left, last.left),
            top = minOf(first.top, last.top),
            right = maxOf(first.right, last.right),
            bottom = maxOf(first.bottom, last.bottom),
        )
    }
    .filter { (_, box) -> box.inflate(reachPx).contains(point) }
    .minByOrNull { (_, box) -> (box.center - point).getDistance() }
    ?.first
