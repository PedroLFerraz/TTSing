package com.pedrolopes.ttsing.ui.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.data.book.BookFormat
import com.pedrolopes.ttsing.data.db.BookEntity
import com.pedrolopes.ttsing.ui.common.Hairline
import com.pedrolopes.ttsing.ui.common.HeaderIcon
import com.pedrolopes.ttsing.ui.common.LocalImage
import com.pedrolopes.ttsing.ui.common.MonoText
import com.pedrolopes.ttsing.ui.common.PillButton
import com.pedrolopes.ttsing.ui.common.ScreenHeader
import com.pedrolopes.ttsing.ui.common.ScreenPadding
import com.pedrolopes.ttsing.ui.common.StatusStrip
import com.pedrolopes.ttsing.ui.common.ThinProgress
import com.pedrolopes.ttsing.ui.common.simpleFactory
import com.pedrolopes.ttsing.ui.theme.AppFonts
import com.pedrolopes.ttsing.ui.theme.Ink
import java.io.File

@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onOpenNews: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = simpleFactory { LibraryViewModel.create() }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // The app only reads EPUBs; persisting read access avoids losing the grant
            // on providers that don't offer write.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.onFolderPicked(uri.toString())
        }
    }

    Scaffold(containerColor = Ink.Surface) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = "TTSING",
                actions = {
                    HeaderIcon(Icons.Outlined.RssFeed, "News", onClick = onOpenNews)
                    HeaderIcon(Icons.Outlined.FolderOpen, "Choose books folder") { folderPicker.launch(null) }
                },
            )

            when {
                !state.hasFolder -> EmptyLibrary(
                    modifier = Modifier.weight(1f),
                    headline = "No library yet",
                    message = "Point TTSing at the folder that holds your EPUB and PDF files. It reads them " +
                        "where they are — nothing is copied or uploaded.",
                    buttonText = "Choose folder",
                    onClick = { folderPicker.launch(null) },
                )
                state.books.isEmpty() && !state.isScanning -> EmptyLibrary(
                    modifier = Modifier.weight(1f),
                    headline = "Nothing to read here",
                    message = "No EPUB or PDF files were found in the folder you chose. Pick another one and " +
                        "TTSing will scan it again.",
                    buttonText = "Choose a different folder",
                    onClick = { folderPicker.launch(null) },
                )
                else -> {
                    val inProgress = state.books.count { it.progressPercent > 0f }
                    val parts = listOfNotNull(
                        "Library",
                        "${state.books.size} ${if (state.books.size == 1) "book" else "books"}",
                        "$inProgress in progress".takeIf { inProgress > 0 },
                        "Scanning".takeIf { state.isScanning },
                    )
                    // The size of the library is the live number, unless a scan is running —
                    // then that is the thing actually changing.
                    StatusStrip(
                        parts = parts,
                        highlightIndex = if (state.isScanning) parts.lastIndex else 1,
                    )
                    Hairline()
                    BookGrid(
                        books = state.books,
                        onOpenBook = onOpenBook,
                        modifier = Modifier.weight(1f),
                    )
                    Hairline()
                    PillButton(
                        text = "Choose folder",
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookGrid(books: List<BookEntity>, onOpenBook: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(books, key = { it.id }) { book ->
            BookCard(book = book, onClick = { onOpenBook(book.id) })
        }
    }
}

@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .background(Ink.Raised)
                .border(1.dp, Ink.Edge),
        ) {
            if (book.coverFile != null) {
                LocalImage(
                    key = book.id,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    loadBytes = { runCatching { File(book.coverFile).readBytes() }.getOrNull() },
                )
            } else {
                TypographicCover(title = book.title, author = book.author)
            }
            // PDFs are reflowed rather than shown as pages; the tag says which kind this is,
            // since a rendered first page otherwise looks just like an EPUB's cover.
            if (BookFormat.fromStored(book.format) == BookFormat.PDF) {
                MonoText(
                    text = "PDF",
                    size = 9f,
                    tracking = 0.16f,
                    color = Ink.Surface,
                    weight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Ink.Live)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            text = book.title,
            fontFamily = AppFonts.Grotesk,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (book.author != null) {
            MonoText(
                text = book.author,
                size = 10f,
                tracking = 0.1f,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (book.progressPercent > 0f) {
            ThinProgress(
                fraction = book.progressPercent,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * What a book without an embedded cover gets: its own title set in the reading serif, with
 * the author's surname as a mono footer. Reads as a designed jacket rather than a placeholder.
 */
@Composable
private fun TypographicCover(title: String, author: String?) {
    if (title.isBlank()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Ink.Handle,
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Text(
            text = title,
            fontFamily = AppFonts.Serif,
            fontSize = 17.sp,
            lineHeight = 20.sp,
            color = Ink.Text,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        author?.let { MonoText(text = surname(it), size = 9f, tracking = 0.16f, color = Ink.Dim) }
    }
}

/**
 * The name a jacket would print: the last word, plus the particle in front of it when there
 * is one, so "Ursula K. Le Guin" comes back as "Le Guin" rather than "Guin".
 */
private fun surname(author: String): String {
    val parts = author.trim().split(' ').filter { it.isNotBlank() }
    if (parts.size < 2) return author.trim()
    val particles = setOf("le", "la", "van", "von", "de", "del", "della", "da", "di", "dos", "das", "du", "den", "ter")
    val last = parts.last()
    val preceding = parts[parts.size - 2]
    return if (preceding.lowercase() in particles) "$preceding $last" else last
}

@Composable
private fun EmptyLibrary(
    headline: String,
    message: String,
    buttonText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Ink.Live,
            )
            Spacer(Modifier.height(18.dp))
            MonoText(text = headline, size = 12f, tracking = 0.2f, color = Ink.Live)
            Spacer(Modifier.height(14.dp))
            Text(
                text = message,
                fontFamily = AppFonts.Grotesk,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = Ink.Muted,
                textAlign = TextAlign.Center,
            )
        }
        PillButton(
            text = buttonText,
            onClick = onClick,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp),
        )
    }
}
