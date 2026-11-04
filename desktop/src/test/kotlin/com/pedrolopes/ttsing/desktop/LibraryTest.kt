package com.pedrolopes.ttsing.desktop

import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.desktop.library.Library
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The library is the books folder: scanning finds its books, and moving one keeps its place. */
class LibraryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sample(name: String): File = File(javaClass.getResource("/$name")!!.toURI())

    private fun setUp(): Triple<Library, File, AppDirs> {
        val root = temp.newFolder("Books")
        File(root, "English").mkdirs()
        sample("the-little-garden.epub").copyTo(File(root, "English/the-little-garden.epub"))
        sample("o-pequeno-jardim.epub").copyTo(File(root, "o-pequeno-jardim.epub"))
        File(root, "notes.txt").writeText("not a book")
        File(root, ".hidden").mkdirs()
        val dirs = AppDirs(temp.newFolder("data"))
        return Triple(Library(dirs), root, dirs)
    }

    @Test
    fun `a scan finds the books and their folders`() = runBlocking {
        val (library, root) = setUp()
        assertTrue(library.sync(root))
        val books = library.books.value.values.sortedBy { it.folder }
        assertEquals(2, books.size)
        assertEquals(listOf("", "English"), books.map { it.folder })
        assertEquals(listOf("", "English"), library.folders.value)
        assertTrue(books.all { it.title.isNotBlank() && it.sectionCount > 0 })
    }

    @Test
    fun `moving a book keeps its reading position`() = runBlocking {
        val (library, root) = setUp()
        library.sync(root)
        val book = library.books.value.values.single { it.folder == "" }
        library.savePosition(book.id, ReadingPosition(1, 2, 3), 0.4f)

        val moved = library.move(book.id, root, "English")

        assertNull(library.book(book.id))
        val record = checkNotNull(library.book(moved))
        assertEquals("English", record.folder)
        assertEquals(ReadingPosition(1, 2, 3), record.position)
        assertTrue(File(root, "English/o-pequeno-jardim.epub").isFile)
        // And a rescan sees the same book, not a new one.
        library.sync(root)
        assertEquals(ReadingPosition(1, 2, 3), library.book(moved)!!.position)
    }

    @Test
    fun `renaming keeps the extension and the place`() = runBlocking {
        val (library, root) = setUp()
        library.sync(root)
        val book = library.books.value.values.single { it.folder == "English" }
        library.savePosition(book.id, ReadingPosition(0, 5, 0), 0.1f)

        val renamed = library.rename(book.id, "Garden")

        assertTrue(File(root, "English/Garden.epub").isFile)
        assertEquals(ReadingPosition(0, 5, 0), library.book(renamed)!!.position)
    }

    @Test
    fun `a move onto a name already taken changes nothing`() = runBlocking {
        val (library, root) = setUp()
        sample("o-pequeno-jardim.epub").copyTo(File(root, "English/o-pequeno-jardim.epub"))
        library.sync(root)
        val book = library.books.value.values.single { it.folder == "" }
        val failed = runCatching { library.move(book.id, root, "English") }
        assertTrue(failed.isFailure)
        assertNotNull(library.book(book.id))
    }

    @Test
    fun `an unreadable folder does not drop its books`() = runBlocking {
        val (library, root) = setUp()
        library.sync(root)
        val english = File(root, "English")
        english.setReadable(false)
        try {
            library.sync(root)
            assertEquals(2, library.books.value.size)
        } finally {
            english.setReadable(true)
        }
    }

    @Test
    fun `the index survives a restart`() = runBlocking {
        val (library, root, dirs) = setUp()
        library.sync(root)
        val id = library.books.value.keys.first()
        library.savePosition(id, ReadingPosition(2, 0, 1), 0.5f)
        assertEquals(ReadingPosition(2, 0, 1), Library(dirs).book(id)!!.position)
    }

    @Test
    fun `a damaged index is set aside, not overwritten`() {
        val dir = temp.newFolder("store")
        val file = File(dir, "x.json")
        file.writeText("{ not json")
        val store = JsonFile(file, String.serializer()) { "empty" }
        assertEquals("empty", store.read())
        assertTrue(File(dir, "x.json.unreadable").isFile)
        assertFalse(file.exists())
    }
}
