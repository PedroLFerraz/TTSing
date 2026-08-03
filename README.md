# TTSing

An Android app (Kotlin + Jetpack Compose) that reads your EPUB library aloud with
text-to-speech, highlighting the current sentence and word karaoke-style and
auto-scrolling to follow along. Built for Portuguese and English books, with
background playback and media-notification controls.

## Features

- **Library** — pick any folder of `.epub` files (Storage Access Framework); covers,
  titles, authors and reading progress are cached so the list opens instantly.
- **Reader** — native Compose rendering of chapters (headings, paragraphs, quotes,
  inline images) laid out as **swipeable pages** (left/right), like a real e-reader, with
  a table-of-contents drawer, adjustable font size, and light / sepia / dark themes.
  Chapter text is paginated to the screen with `TextMeasurer`; swiping past the last page
  rolls into the next chapter.
- **Read-aloud TTS** — Android `TextToSpeech`, one utterance per sentence with a small
  look-ahead queue for smooth speech. The sentence being read gets a soft highlight and
  the exact word gets a strong highlight (`onRangeStart` word callbacks). Pages turn
  automatically to keep up with the voice.
- **Language picker** — books often declare the wrong `dc:language` (or none), which used to
  mean a German book read aloud in English with no way to fix it. The reader's settings sheet
  now lets you pick the reading language first, from the languages your TTS engine actually
  has voices for; the voice list below then follows that choice. The override is remembered
  **per book**, and the chosen voice is remembered **per language**.
- **Voice picker** — in the reader's settings sheet, voices are grouped by region
  (US / GB / …), the currently-speaking voice is marked, each shows quality and
  offline/online, and a "Device default" option returns to the engine's built-in voice.
  Speed goes up to **2.5×**.
- **Time to finish** — the bottom bar shows time left in the chapter and in the book. The
  estimate uses the app's *measured* speaking speed (characters/second, smoothed over real
  sentences and normalised to rate 1.0), so it adapts to your device, voice and speed.
- **Messy-EPUB clean-up** — footnote call-outs, note bodies and page-break markers are
  stripped before rendering, so the voice doesn't read "palavra um" for a footnote number.
  Conservative by design: a well-formed book is left untouched.
  Two more real-world defects are worked around: chapters whose bytes are UTF-8 but whose
  `<meta>` claims `iso-8859-1` (obeying the declaration turns every `’` into `â€™`, on screen
  *and* in the voice), and `linear="no"` spine items, which are skipped so a book doesn't
  open on a blank cover page.
- **Tap to start** — tap any paragraph to begin reading from there (from the sentence you
  actually touched, not the top of the page).
- **News feeds** — subscribe to any RSS or Atom feed and read its stories the same way you
  read a book. Articles are fetched and stripped down to the actual text (navigation, cookie
  banners, related-story rails and newsletter prompts are scored out), so the **whole** piece
  is read aloud rather than the truncated summary the feed ships. Feeds that inline their
  full text are used directly, and if a page can't be reached the reader falls back to the
  summary and says so.
- **Anki flashcards** — long-press a sentence, tap the word you didn't know, type what it
  means, and the card goes straight into AnkiDroid. The front is the sentence with the word
  in bold plus the sentence spoken by the book's own voice; the back is your definition.
  Cards land in a `TTSing` deck, tagged with the book.
- **Background playback** — a foreground media service keeps playing with the screen off,
  with play/pause and ±sentence controls in the notification and on the lock screen.
- **Any language your engine speaks** — the reading language starts from the EPUB's
  `dc:language` and can be overridden per book (see above). Speed and pitch are global;
  the voice is saved per language.

## Project location note

This project (code + git repo) lives at `C:\Users\Pedro Lopes\AndroidStudioProjects\TTSing`.
It was moved off the original Desktop path because that path contains an invisible
`U+2800` character in the `⠀` folder, and the Android Gradle Plugin rejects non-ASCII
project paths on Windows. See `LEIA-ME.txt` left in the old Desktop folder for how to
turn that location into a junction pointing here, if you want it to appear there again.

## Requirements

- Android Studio (uses its bundled JDK 21 — `gradle.properties` pins
  `org.gradle.java.home` to `C:\Program Files\Android\Android Studio\jbr`).
- Android SDK platform 36 + build-tools 36.0.0 (already installed).
- A device or emulator running Android 8.0 (API 26) or newer, with a TTS engine and the
  Portuguese/English voice data installed (Settings → System → Languages →
  Text-to-speech). **Note:** stock emulator images often ship English only — for
  Portuguese, test on a physical phone or install the pt voice via Google TTS.
- For the flashcard feature: [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki)
  installed on the same device. The first card asks for AnkiDroid's
  `READ_WRITE_DATABASE` permission. Without AnkiDroid the rest of the app works
  normally — adding a card just reports that it isn't installed. The API comes from
  JitPack (`com.github.ankidroid:Anki-Android:api-v1.1.0`), so the first sync needs
  network access.

## Build & run

Open the folder in Android Studio and Run, or from a terminal:

```powershell
cd "C:\Users\Pedro Lopes\AndroidStudioProjects\TTSing"
.\gradlew.bat assembleDebug     # build the APK
.\gradlew.bat test              # run the JVM unit tests
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`. Copies handed
off for install/testing are kept in `releases/` (git-ignored — it's build output, not
source).

## Trying it out

Sample books are in `sample-books/` (`the-little-garden.epub` in English and
`o-pequeno-jardim.epub` in Portuguese). Copy them onto the device/emulator (e.g. into
`Download/`), then in the app tap the folder icon and choose that folder. Open a book and
press play to see the sentence/word highlighting and auto-scroll.

## Architecture

```
data/epub/    EpubParser (ZIP + OPF + nav/NCX), ChapterLoader (XHTML → blocks via Jsoup),
              BreakIterator sentence segmentation. Pure JVM, unit-tested.
data/db/      Room cache of book metadata + reading position.
data/news/    RssParser (RSS 2.0 + Atom), ArticleExtractor (readability-style scoring to pull
              the article body out of a news page), HttpFetcher, NewsRepository. Its own Room
              database (`ttsing-news.db`) with real migrations — feed subscriptions are user
              data, unlike the books cache.
data/settings DataStore: folder URI, speed, pitch, font size, theme, voice per language
              (`voice_<lang>`), language override per book (`booklang_<id>`).
data/         BookRepository — SAF folder scan, cover extraction, position persistence.
tts/          SpeechEngine (sentence queue + word callbacks), BookContentSource
              (sentence stream across block/chapter boundaries), ReadingService
              (foreground media service, MediaSession, notification, audio focus),
              ReadingController (binds the UI to the service).
anki/         CardDraft (sentence + target word → Anki HTML), CardAudio (its own
              TextToSpeech for synthesizeToFile, so playback is never interrupted),
              AnkiExporter (AnkiDroid AddContentApi: deck, note type, media, note).
ui/library    Folder picker + cover grid.
ui/reader     Chapter rendering, karaoke highlight, transport bar, TOC, settings sheet,
              flashcard sheet.
```

A reading position is `(chapterIndex, blockIndex, sentenceIndex)`; TTS utterance IDs encode
it, so every speech callback maps directly to what to highlight and where to scroll.

## Tests

Pure-JVM unit tests under `app/src/test` cover EPUB parsing (EPUB 2 NCX + EPUB 3 nav,
percent-encoded hrefs, cover detection), the XHTML→blocks conversion, Portuguese/English
sentence segmentation, and end-to-end parsing of the real sample EPUBs. Run with
`.\gradlew.bat test`.
