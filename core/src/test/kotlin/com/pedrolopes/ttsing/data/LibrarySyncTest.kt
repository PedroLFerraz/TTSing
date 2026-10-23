package com.pedrolopes.ttsing.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncTest {

    /** Cached books: a and b in the library folder, c in "Sci-fi", d in "Sci-fi/Classics", e in "Other". */
    private val cached = mapOf("a" to "", "b" to "", "c" to "Sci-fi", "d" to "Sci-fi/Classics", "e" to "Other")

    @Test
    fun unreadableLibraryFolderDropsNothing() {
        assertTrue(booksGone(cached, seen = emptySet(), unlistedFolders = setOf("")).isEmpty())
    }

    @Test
    fun emptyButReadableLibraryFolderDropsEverything() {
        assertEquals(cached.keys, booksGone(cached, seen = emptySet(), unlistedFolders = emptySet()))
    }

    @Test
    fun removedFileIsDropped() {
        assertEquals(setOf("b"), booksGone(cached, seen = setOf("a", "c", "d", "e"), unlistedFolders = emptySet()))
    }

    @Test
    fun nothingRemovedDropsNothing() {
        assertTrue(booksGone(cached, seen = cached.keys, unlistedFolders = emptySet()).isEmpty())
    }

    @Test
    fun failingSubfolderKeepsItsBooksAndTheirSubfolders() {
        val gone = booksGone(cached, seen = setOf("a", "b", "e"), unlistedFolders = setOf("Sci-fi"))
        assertTrue(gone.isEmpty())
    }

    @Test
    fun failingSubfolderStillDropsBooksRemovedFromHealthyFolders() {
        val gone = booksGone(cached, seen = setOf("a", "c"), unlistedFolders = setOf("Sci-fi/Classics"))
        // d is shielded (its folder failed); b and e are really gone.
        assertEquals(setOf("b", "e"), gone)
    }

    @Test
    fun siblingFolderWithSharedPrefixIsNotShielded() {
        val folderOf = mapOf("x" to "Sci-fi 2", "y" to "Sci-fi")
        assertEquals(setOf("x"), booksGone(folderOf, seen = emptySet(), unlistedFolders = setOf("Sci-fi")))
    }

    @Test
    fun moveCollisionIsFriendly() {
        assertTrue(isAlreadyExists("Already exists /storage/emulated/0/Books/Sci-fi/x.epub"))
        assertFalse(isAlreadyExists("Could not move the file"))
        assertFalse(isAlreadyExists(null))
        assertEquals("A file named x.epub is already in Sci-fi", alreadyThereMessage("x.epub", "Sci-fi"))
        assertEquals("A file named x.epub is already in Classics", alreadyThereMessage("x.epub", "Sci-fi/Classics"))
        assertEquals("A file named x.epub is already in Library", alreadyThereMessage("x.epub", ""))
    }
}
