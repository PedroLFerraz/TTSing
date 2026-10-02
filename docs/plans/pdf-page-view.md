# Plan: show PDFs as real pages, highlight what's being read

Status: research done 2026-10-02, not started. One coding session.

## Problem

PDFs are turned into reflowed text (`data/pdf/PdfDocument.kt` + `PdfReflow.kt`, PdfBox-Android): figures, tables, code blocks, columns and formatting are lost, and the reader looks nothing like the PDF. Confirmed on the emulator: a PDF opens as plain book-style text (the "On Walls and Gardens" sample).

## What other apps do

- **Adobe Acrobat** (Android "Read aloud"): highlights each word **on the original page**. This is the model to follow.
- **Speechify**: original layout plus word highlight, with reflow/OCR as options (from reviews, unverified).
- **@Voice Aloud Reader**: converts to plain text, sentence highlight only. That is what TTSing does today, and why it looks bad.
- **Librera** (open source, MuPDF): takes text per page for TTS and turns pages as the voice moves; no on-page sentence overlay found.
- **Moon+ Reader**, **KOReader**: offer a reflow mode *and* a page mode.

Nobody ships an open-source "PDF + TTS highlight on the page" to copy wholesale, but the pieces are standard: render the page as a bitmap, get text with character boxes from the same engine, and draw rectangles over the spoken range.

## Decision

**Page mode as default for PDFs, using PdfiumAndroidKt for rendering and text boxes. Keep the current reflow as an optional "Text mode".**

| Option | Why / why not |
|---|---|
| **`io.legere:pdfiumandroid:2.0.3`** ✅ | Apache-2.0, maintained (release Jul 2026), minSdk 24, per-character boxes (`textPageGetCharBox`, `textPageGetRectsForRanges`, `getCharIndexAtPos`), and the same engine renders, so boxes line up. Arm64 libs are 16 KB aligned. About 5 MB per ABI; pulls in Guava (check size). |
| Platform `PdfRenderer` + PdfBox `TextPosition` | Fallback if Pdfium is a problem: no new dependency, but two engines means fragile alignment (ligatures, inserted spaces). |
| `androidx.pdf` | Beta, **minSdk 28** (we're on 26), text boxes per run rather than per character. Not yet. |
| MuPDF | Best text structure, but **AGPL**: the whole app would have to be AGPL. Only if Pdfium's reading order turns out unusable. |
| Readium | Its TTS only works on EPUB; its PDF part wraps an unmaintained PdfiumAndroid with no text API. Skip. |
| pdf.js in a WebView | Heavy, and awkward to sync with native TTS. Skip. |

## Spike first (about 30 min, before writing the feature)

1. **Dependency and sizes.** Add the dependency, dump `textPageGetText` for 3 PDFs: a two-column paper, a table-heavy one and a code-heavy one (`sample-books/` and `Platform Engineering`). Judge the **reading order**.
2. **Box alignment.** Draw the char boxes of one page over its rendered bitmap. Check they line up, including a page with `/Rotate 90` and a cropped page (`mapRectToDevice`).
3. **Size and memory.** Note the APK size change (Guava) and memory/latency when opening a 500-page PDF.

If reading order is bad on ordinary PDFs, stop and reconsider (MuPDF `StructuredText`, or keep Text mode as the default).

## Implementation steps

1. **Dependency.** Add `io.legere:pdfiumandroid:2.0.3` to `gradle/libs.versions.toml` and `app/build.gradle.kts`. Keep PdfBox for bookmarks, metadata, cover and Text mode.
2. **New `data/pdf/PdfiumPages.kt`.**
   - Open the file with `PdfiumCore.newDocument(ParcelFileDescriptor)`.
   - `render(page, widthPx): Bitmap`, with an LRU cache of 3–4 pages.
   - `pageText(page)` returns the cleaned text, an `IntArray` mapping each text offset to a Pdfium char index, and the char boxes.
   - While cleaning, normalise ligatures and join `-\n` hyphenation, recording offsets as you go.
   - Keep one text page open at a time, and serialise calls behind a lock, as `PdfDocument` does now.
3. **`PdfDocument.kt`.**
   - Build each page's `Block.Text` blocks from Pdfium text: split paragraphs on line gaps, and drop running headers, footers and page numbers with the existing `PdfReflow.detectFurniture` logic.
   - Add `rectsFor(section, block, range): List<RectF>`, which goes from text offsets to char indices to page rects.
   - Sections can stay outline-based. Each block must know its page.
4. **`data/epub/model.kt`.** Add an optional `source: PageAnchor(page, charStart)?` to `Block.Text`. This is the only change to the shared model, so EPUBs are unaffected.
5. **New `ui/reader/PdfPageScreen.kt`.**
   - A vertical `LazyColumn` (or pager) of page bitmaps, zoomable.
   - A Compose `Canvas` overlay: a translucent rect for the current sentence (`PlaybackState.sentenceRange`) and a stronger one for the word (`wordRange`). For the overlay pattern, see mupdf-android-viewer's `PageView.java` `onDraw`, which draws quads over the page bitmap.
   - Scroll to the page when the voice moves to another page.
   - Tap a word to start reading there: `getCharIndexAtPos` gives the block and sentence, then call `controller.seekTo`.
6. **Reader wiring.**
   - In `ReaderViewModel`, `ReaderScreen` and `ReaderSettingsSheet`, add a **Page / Text** toggle for PDFs, defaulting to Page and stored per book in settings.
   - `ReadingService`, the narrators and `BookContentSource` should not need changes: they already report block, sentence and word.
7. **Keep as they are.** Text mode (today's reflow), covers, time estimates, Anki cards (word lookup via text), and the scanned-PDF message (`unreadableReason`).

## Verify

- **Unit tests:** the offset map (ligature and hyphen joins map back to the right char indices) and the paragraph split, pure JVM, in the style of `PdfReflowTest`.
- **Emulator** (AVD `blocker_test`; the adb tap/screenshot helper approach from this session works):
  - Open a PDF with figures. It looks like the original PDF.
  - Press play. The yellow box follows the sentence and word, and pages turn on their own.
  - Tap a paragraph. Reading jumps there.
  - Toggle to Text mode. You get today's reflow.
- **Big PDF:** a 500-page PDF opens in under 2 s, and scrolling has no out-of-memory crash.
- Run `build-apk.bat` (or copy `app-debug.apk` over `TTSing.apk` at the root) when done.

## Known limits (accept for now)

- **Scanned PDFs** have no text layer. Keep the current message; OCR is a separate feature.
- **Multi-column reading order** depends on Pdfium. The spike decides whether it's good enough.
