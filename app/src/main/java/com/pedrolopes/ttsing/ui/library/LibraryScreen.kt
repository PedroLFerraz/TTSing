package com.pedrolopes.ttsing.ui.library

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            // Write access is for deleting and moving books; providers that don't offer it
            // still get read access, so reading never depends on it.
            val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, read) }
            viewModel.onFolderPicked(uri.toString())
        }
    }

    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    var pressed by remember { mutableStateOf<Pair<BookEntity, BookAction>?>(null) }

    Scaffold(containerColor = Ink.Surface, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
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
                state.totalBooks == 0 && !state.isScanning -> EmptyLibrary(
                    modifier = Modifier.weight(1f),
                    headline = "Nothing to read here",
                    message = "No EPUB or PDF files were found in the folder you chose. Pick another one and " +
                        "TTSing will scan it again.",
                    buttonText = "Choose a different folder",
                    onClick = { folderPicker.launch(null) },
                )
                else -> {
                    BackHandler(enabled = state.folder.isNotEmpty()) { viewModel.upFolder() }
                    val shown = state.books.size + state.subfolders.sumOf { it.bookCount }
                    val inProgress = state.books.count { it.progressPercent > 0f }
                    // While a scan runs, "Scanning" takes the place of "Library" and is the live
                    // word; appended at the end it ran off the line and left a stray dot.
                    val parts = listOfNotNull(
                        if (state.isScanning) "Scanning" else state.folder.substringAfterLast('/').ifEmpty { "Library" },
                        "$shown ${if (shown == 1) "book" else "books"}",
                        "$inProgress in progress".takeIf { inProgress > 0 },
                    )
                    StatusStrip(parts = parts, highlightIndex = if (state.isScanning) 0 else 1)
                    Hairline()
                    BookGrid(
                        books = state.books,
                        subfolders = state.subfolders,
                        parentName = if (state.folder.isEmpty()) null
                            else state.folder.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { "Library" },
                        onOpenFolder = viewModel::openFolder,
                        onUp = viewModel::upFolder,
                        onOpenBook = onOpenBook,
                        onLongPressBook = { pressed = it to BookAction.Menu },
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

    pressed?.let { (book, action) ->
        BookActionDialog(
            book = book,
            action = action,
            loadFolders = viewModel::folders,
            onAction = { pressed = book to it },
            onMove = { viewModel.moveBook(book.id, it); pressed = null },
            onDelete = { viewModel.deleteBook(book.id); pressed = null },
            onDismiss = { pressed = null },
        )
    }
}

private enum class BookAction { Menu, Move, Delete }

/** What a long press on a book offers: moving its file to another folder, or deleting it. */
@Composable
private fun BookActionDialog(
    book: BookEntity,
    action: BookAction,
    loadFolders: suspend () -> List<String>,
    onAction: (BookAction) -> Unit,
    onMove: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (action) {
        BookAction.Menu -> book.title
        BookAction.Move -> "Move to"
        BookAction.Delete -> "Delete book?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = {
            MonoText(title, size = 12f, tracking = 0.18f, color = Ink.Text, weight = FontWeight.Bold)
        },
        text = {
            when (action) {
                BookAction.Menu -> Column {
                    FolderRow(Icons.Outlined.DriveFileMove, "Move to folder", null) { onAction(BookAction.Move) }
                    Spacer(Modifier.height(8.dp))
                    FolderRow(Icons.Outlined.Delete, "Delete", null) { onAction(BookAction.Delete) }
                }
                BookAction.Move -> {
                    val folders by produceState<List<String>?>(null) { value = loadFolders() - book.folder }
                    when {
                        folders == null -> MonoText("Looking for folders", size = 11f, tracking = 0.16f)
                        folders!!.isEmpty() -> MonoText("No other folders", size = 11f, tracking = 0.16f)
                        else -> LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 360.dp),
                        ) {
                            listItems(folders!!) { path ->
                                FolderRow(Icons.Outlined.Folder, path.ifEmpty { "Library" }, null) { onMove(path) }
                            }
                        }
                    }
                }
                BookAction.Delete -> Text(
                    "\"${book.title}\" will be deleted from the books folder on this device, not just from TTSing.",
                    fontFamily = AppFonts.Grotesk,
                    fontSize = 14.sp,
                    color = Ink.Muted,
                )
            }
        },
        confirmButton = {
            if (action == BookAction.Delete) {
                TextButton(onClick = onDelete) {
                    MonoText("Delete", size = 11f, tracking = 0.16f, color = Ink.Live, weight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { MonoText("Cancel", size = 11f, tracking = 0.16f, color = Ink.Muted) }
        },
    )
}

@Composable
private fun BookGrid(
    books: List<BookEntity>,
    subfolders: List<LibraryFolder>,
    /** The folder one level up, or null at the top of the library. */
    parentName: String?,
    onOpenFolder: (String) -> Unit,
    onUp: () -> Unit,
    onOpenBook: (String) -> Unit,
    onLongPressBook: (BookEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        // Folders sit above the books as full-width rows, so they never mix with them.
        if (parentName != null) {
            item(key = "up", span = { GridItemSpan(maxLineSpan) }) {
                FolderRow(icon = Icons.AutoMirrored.Outlined.ArrowBack, name = parentName, detail = null, onClick = onUp)
            }
        }
        items(subfolders, key = { "folder:" + it.path }, span = { GridItemSpan(maxLineSpan) }) { folder ->
            FolderRow(
                icon = Icons.Outlined.Folder,
                name = folder.name,
                detail = "${folder.bookCount} ${if (folder.bookCount == 1) "book" else "books"}",
                onClick = { onOpenFolder(folder.path) },
            )
        }
        items(books, key = { it.id }) { book ->
            BookCard(book = book, onClick = { onOpenBook(book.id) }, onLongClick = { onLongPressBook(book) })
        }
    }
}

@Composable
private fun FolderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    detail: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Ink.Edge)
            .background(Ink.Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = Ink.Live)
        Text(
            text = name,
            fontFamily = AppFonts.Grotesk,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        detail?.let { MonoText(text = it, size = 10f, tracking = 0.1f) }
    }
}

@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
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
