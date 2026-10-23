package com.pedrolopes.ttsing.desktop.ui.library

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedrolopes.ttsing.desktop.App
import com.pedrolopes.ttsing.desktop.library.BookRecord
import com.pedrolopes.ttsing.desktop.ui.Hairline
import com.pedrolopes.ttsing.desktop.ui.MonoText
import com.pedrolopes.ttsing.desktop.ui.chooseFolder
import com.pedrolopes.ttsing.desktop.ui.rememberFileImage
import com.pedrolopes.ttsing.desktop.ui.showInFileManager
import com.pedrolopes.ttsing.desktop.ui.theme.AppFonts
import com.pedrolopes.ttsing.desktop.ui.theme.HazardStripe
import com.pedrolopes.ttsing.desktop.ui.theme.Ink
import com.pedrolopes.ttsing.desktop.ui.theme.wordmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Which shelf is open: the books read lately, or a folder of the library. */
private sealed interface Shelf {
    data object Recent : Shelf
    data class Folder(val path: String) : Shelf
}

/**
 * The library as a file explorer: the books folder's tree down the side, the open folder's
 * subfolders and books as covers (or a list) beside it. Moving, renaming and trashing a book
 * does it to the file.
 */
@Composable
fun LibraryScreen(
    app: App,
    message: String?,
    onDismissMessage: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val settings by app.settings.state.collectAsState()
    val books by app.library.books.collectAsState()
    val folders by app.library.folders.collectAsState()
    val scanning by app.library.scanning.collectAsState()
    val scope = rememberCoroutineScope()

    var shelf by remember { mutableStateOf<Shelf>(Shelf.Folder("")) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<BookRecord?>(null) }
    var moving by remember { mutableStateOf<BookRecord?>(null) }
    var trashing by remember { mutableStateOf<BookRecord?>(null) }

    fun pickFolder() {
        scope.launch {
            val chosen = withContext(Dispatchers.IO) { chooseFolder(app.libraryRoot) } ?: return@launch
            shelf = Shelf.Folder("")
            app.settings.update { it.copy(libraryFolder = chosen.absolutePath) }
        }
    }

    fun act(action: suspend () -> Unit) {
        scope.launch { runCatching { action() }.onFailure { error = it.message ?: "That didn't work" } }
    }

    val root = settings.libraryFolder
    if (root == null) {
        EmptyLibrary(onChoose = ::pickFolder)
        return
    }
    // Only this library's books: the index may still hold another folder's until the scan ends.
    val all = books.values.filter { it.path.startsWith("$root/") }

    Row(modifier = Modifier.fillMaxSize().background(Ink.Surface)) {
        Sidebar(
            root = root,
            folders = folders,
            counts = all.groupingBy { it.folder }.eachCount(),
            shelf = shelf,
            scanning = scanning,
            onShelf = {
                shelf = it
                query = ""
            },
            onChangeFolder = ::pickFolder,
            onRescan = { app.syncLibrary() },
        )
        Box(Modifier.fillMaxHeight().width(1.dp).background(Ink.Hairline))
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val searching = query.isNotBlank()
            val shown = when {
                searching -> all.filter { it.matches(query) }.sortedBy { it.title.lowercase() }
                shelf == Shelf.Recent -> all.filter { it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt }.take(RECENT_COUNT)
                else -> all.filter { it.folder == (shelf as Shelf.Folder).path }.sortedBy { it.title.lowercase() }
            }
            val subfolders = (shelf as? Shelf.Folder)?.takeUnless { searching }?.let { open ->
                folders.filter { it.isNotEmpty() && parentOf(it) == open.path }
            }.orEmpty()

            Header(
                shelf = shelf,
                query = query,
                asList = settings.libraryAsList,
                onQuery = { query = it },
                onShelf = { shelf = it },
                onToggleList = { app.settings.update { it.copy(libraryAsList = !it.libraryAsList) } },
            )
            if (scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Ink.Live, trackColor = Ink.Surface)
            else Spacer(Modifier.height(2.dp))

            val bookActions = BookActions(
                onOpen = { onOpen(it.id) },
                onRename = { renaming = it },
                onMove = { moving = it },
                onTrash = { trashing = it },
                onShow = { showInFileManager(File(it.path)) },
            )
            val folderCounts = { path: String -> all.count { it.folder == path || it.folder.startsWith("$path/") } }
            when {
                shown.isEmpty() && subfolders.isEmpty() -> EmptyShelf(
                    text = when {
                        searching -> "Nothing in the library matches “$query”"
                        shelf == Shelf.Recent -> "Books you open show up here"
                        scanning -> "Looking for books…"
                        else -> "No EPUB or PDF files in this folder"
                    },
                )
                settings.libraryAsList -> BookList(subfolders, shown, folderCounts, { shelf = Shelf.Folder(it) }, bookActions)
                else -> BookGrid(subfolders, shown, folderCounts, { shelf = Shelf.Folder(it) }, bookActions)
            }
        }
    }

    (message ?: error)?.let { text ->
        AlertDialog(
            onDismissRequest = {
                error = null
                onDismissMessage()
            },
            confirmButton = {
                TextButton(onClick = {
                    error = null
                    onDismissMessage()
                }) { Text("OK") }
            },
            text = { Text(text) },
            containerColor = Ink.Raised,
        )
    }
    renaming?.let { book ->
        RenameDialog(
            book = book,
            onDismiss = { renaming = null },
            onRename = { name ->
                renaming = null
                act { app.library.rename(book.id, name) }
            },
        )
    }
    moving?.let { book ->
        MoveDialog(
            book = book,
            folders = folders,
            onDismiss = { moving = null },
            onMove = { folder ->
                moving = null
                act { app.library.move(book.id, File(root), folder) }
            },
        )
    }
    trashing?.let { book ->
        AlertDialog(
            onDismissRequest = { trashing = null },
            title = { Text("Move to trash?") },
            text = { Text("${book.fileName} goes to the trash, with its reading position. It can be restored from there.") },
            confirmButton = {
                TextButton(onClick = {
                    trashing = null
                    act { app.library.trash(book.id) }
                }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { trashing = null }) { Text("Cancel") } },
            containerColor = Ink.Raised,
        )
    }
}

private const val RECENT_COUNT = 40

private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

private fun BookRecord.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    return title.lowercase().contains(q) || author?.lowercase()?.contains(q) == true || fileName.lowercase().contains(q)
}

private class BookActions(
    val onOpen: (BookRecord) -> Unit,
    val onRename: (BookRecord) -> Unit,
    val onMove: (BookRecord) -> Unit,
    val onTrash: (BookRecord) -> Unit,
    val onShow: (BookRecord) -> Unit,
)

@Composable
private fun EmptyLibrary(onChoose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink.Surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TTSING", style = wordmark(40f))
        Spacer(Modifier.height(12.dp))
        HazardStripe(modifier = Modifier.width(240.dp))
        Spacer(Modifier.height(32.dp))
        Text("Choose the folder your EPUBs and PDFs are in.", color = Ink.Muted, fontFamily = AppFonts.Grotesk, fontSize = 16.sp)
        Text("Its subfolders become your shelves.", color = Ink.Dim, fontFamily = AppFonts.Grotesk, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Choose books folder", onChoose)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Ink.Live, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        MonoText(text, color = Ink.Surface, size = 12f, weight = FontWeight.Bold)
    }
}

@Composable
private fun Sidebar(
    root: String,
    folders: List<String>,
    counts: Map<String, Int>,
    shelf: Shelf,
    scanning: Boolean,
    onShelf: (Shelf) -> Unit,
    onChangeFolder: () -> Unit,
    onRescan: () -> Unit,
) {
    Column(modifier = Modifier.width(264.dp).fillMaxHeight().background(Ink.Surface)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp)) {
            Text("TTSING", style = wordmark(22f))
        }
        HazardStripe()
        Spacer(Modifier.height(14.dp))
        SidebarRow(
            label = "Recent",
            depth = 0,
            selected = shelf == Shelf.Recent,
            icon = { Icon(Icons.Outlined.History, null, tint = it, modifier = Modifier.size(16.dp)) },
            onClick = { onShelf(Shelf.Recent) },
        )
        Spacer(Modifier.height(10.dp))
        MonoText("Folders", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = Ink.Dim, size = 10f)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(folders) { path ->
                val depth = if (path.isEmpty()) 0 else path.count { it == '/' } + 1
                val inside = counts.filterKeys { it == path || path.isEmpty() || it.startsWith("$path/") }.values.sum()
                SidebarRow(
                    label = if (path.isEmpty()) File(root).name else path.substringAfterLast('/'),
                    depth = depth,
                    count = inside,
                    selected = shelf == Shelf.Folder(path),
                    icon = { Icon(Icons.Outlined.Folder, null, tint = it, modifier = Modifier.size(16.dp)) },
                    onClick = { onShelf(Shelf.Folder(path)) },
                )
            }
        }
        Hairline()
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            MonoText("Books folder", color = Ink.Dim, size = 10f)
            Text(
                root.replaceFirst(System.getProperty("user.home"), "~"),
                color = Ink.Muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onChangeFolder) { MonoText("Change", color = Ink.Live) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRescan, enabled = !scanning) {
                    Icon(Icons.Outlined.Refresh, "Look for new books", tint = if (scanning) Ink.Faint else Ink.Muted)
                }
            }
        }
    }
}

@Composable
private fun SidebarRow(
    label: String,
    depth: Int,
    selected: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    count: Int? = null,
) {
    val tint = if (selected) Ink.Live else Ink.Muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Ink.Well else Color.Transparent)
            .padding(start = 20.dp + 14.dp * depth, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(tint)
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (selected) Ink.Text else Ink.ReaderText,
            fontFamily = AppFonts.Grotesk,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        count?.takeIf { it > 0 }?.let { MonoText("$it", color = Ink.Dim, size = 10f) }
    }
}

@Composable
private fun Header(
    shelf: Shelf,
    query: String,
    asList: Boolean,
    onQuery: (String) -> Unit,
    onShelf: (Shelf) -> Unit,
    onToggleList: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            when (shelf) {
                Shelf.Recent -> Crumb("Recent", current = true) {}
                is Shelf.Folder -> {
                    Crumb("Library", current = shelf.path.isEmpty()) { onShelf(Shelf.Folder("")) }
                    val parts = shelf.path.split('/').filter { it.isNotEmpty() }
                    parts.forEachIndexed { i, part ->
                        MonoText("/", color = Ink.Faint, modifier = Modifier.padding(horizontal = 8.dp))
                        Crumb(part, current = i == parts.lastIndex) { onShelf(Shelf.Folder(parts.take(i + 1).joinToString("/"))) }
                    }
                }
            }
        }
        SearchField(query, onQuery)
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onToggleList) {
            Icon(if (asList) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList, if (asList) "Show covers" else "Show as a list", tint = Ink.Muted)
        }
    }
}

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (current) Ink.Text else Ink.Muted,
        fontFamily = AppFonts.Grotesk,
        fontSize = if (current) 22.sp else 18.sp,
        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier.clickable(enabled = !current, onClick = onClick),
    )
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        modifier = Modifier.width(260.dp).border(1.dp, Ink.Edge).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = Ink.Dim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search the library", color = Ink.Dim, fontSize = 13.sp, fontFamily = AppFonts.Grotesk)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                cursorBrush = SolidColor(Ink.Live),
                textStyle = TextStyle(color = Ink.Text, fontSize = 13.sp, fontFamily = AppFonts.Grotesk),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptyShelf(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Ink.Dim, fontFamily = AppFonts.Grotesk, fontSize = 15.sp)
    }
}

@Composable
private fun BookGrid(
    folders: List<String>,
    books: List<BookRecord>,
    countIn: (String) -> Int,
    onFolder: (String) -> Unit,
    actions: BookActions,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        if (folders.isNotEmpty()) {
            items(folders, key = { "folder:$it" }) { path -> FolderTile(path, countIn(path)) { onFolder(path) } }
            if (books.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Hairline() }
        }
        items(books, key = { it.id }) { book -> BookCard(book, actions) }
    }
}

@Composable
private fun FolderTile(path: String, count: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().border(1.dp, Ink.Hairline).clickable(onClick = onClick).padding(14.dp),
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Ink.Live, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            path.substringAfterLast('/'),
            color = Ink.Text,
            fontFamily = AppFonts.Grotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MonoText(if (count == 1) "1 book" else "$count books", color = Ink.Dim, size = 10f)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookMenu(book: BookRecord, actions: BookActions, content: @Composable () -> Unit) {
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("Open") { actions.onOpen(book) },
                ContextMenuItem("Rename…") { actions.onRename(book) },
                ContextMenuItem("Move to folder…") { actions.onMove(book) },
                ContextMenuItem("Show in file manager") { actions.onShow(book) },
                ContextMenuItem("Move to trash") { actions.onTrash(book) },
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: BookRecord, actions: BookActions) {
    BookMenu(book, actions) {
        Column(modifier = Modifier.fillMaxWidth().combinedClickable(onDoubleClick = { actions.onOpen(book) }, onClick = { actions.onOpen(book) })) {
            Cover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Spacer(Modifier.height(10.dp))
            Text(
                book.title,
                color = Ink.Text,
                fontFamily = AppFonts.Grotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            book.author?.let { MonoText(it, color = Ink.Muted, size = 10f, modifier = Modifier.padding(top = 3.dp)) }
            Progress(book.progress, Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Cover(book: BookRecord, modifier: Modifier) {
    val image = rememberFileImage(book.coverFile)
    Box(modifier = modifier.background(Ink.Raised).border(1.dp, Ink.Hairline), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MonoText(book.bookFormat.extension, color = Ink.Live, size = 10f)
                Spacer(Modifier.height(10.dp))
                Text(book.title, color = Ink.ReaderText, fontFamily = AppFonts.Serif, fontSize = 15.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Progress(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f) {
        MonoText("New", color = Ink.Dim, size = 10f, modifier = modifier)
        return
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(3.dp).background(Ink.Track)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Ink.Live))
        }
        Spacer(Modifier.width(8.dp))
        MonoText("${(progress * 100).roundToInt()}%", color = Ink.Muted, size = 10f)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookList(
    folders: List<String>,
    books: List<BookRecord>,
    countIn: (String) -> Int,
    onFolder: (String) -> Unit,
    actions: BookActions,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
        items(folders, key = { "folder:$it" }) { path ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onFolder(path) }.padding(horizontal = 28.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Folder, null, tint = Ink.Live, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(14.dp))
                Text(path.substringAfterLast('/'), color = Ink.Text, fontFamily = AppFonts.Grotesk, fontSize = 15.sp, modifier = Modifier.weight(1f))
                MonoText("${countIn(path)}", color = Ink.Dim, size = 10f)
            }
            Hairline(inset = 28.dp)
        }
        items(books, key = { it.id }) { book ->
            BookMenu(book, actions) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .combinedClickable(onDoubleClick = { actions.onOpen(book) }, onClick = { actions.onOpen(book) })
                        .padding(horizontal = 28.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(book, Modifier.width(36.dp).aspectRatio(2f / 3f))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, color = Ink.Text, fontFamily = AppFonts.Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MonoText(listOfNotNull(book.author, book.bookFormat.extension).joinToString(" · "), color = Ink.Muted, size = 10f)
                    }
                    Progress(book.progress, Modifier.width(160.dp))
                }
            }
            Hairline(inset = 28.dp)
        }
    }
}

@Composable
private fun RenameDialog(book: BookRecord, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(book.fileName.substringBeforeLast('.')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                suffix = { Text(".${book.bookFormat.extension}", color = Ink.Dim) },
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Ink.Raised,
    )
}

@Composable
private fun MoveDialog(book: BookRecord, folders: List<String>, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${book.title}") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                items(folders) { path ->
                    val here = path == book.folder
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !here) { onMove(path) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val depth = if (path.isEmpty()) 0 else path.count { it == '/' } + 1
                        Spacer(Modifier.width(14.dp * depth))
                        Icon(Icons.Outlined.Folder, null, tint = if (here) Ink.Faint else Ink.Live, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (path.isEmpty()) "Library" else path.substringAfterLast('/'),
                            color = if (here) Ink.Faint else Ink.Text,
                            fontFamily = AppFonts.Grotesk,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Ink.Raised,
    )
}
