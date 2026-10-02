package com.pedrolopes.ttsing.data.news

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewsQueueTest {

    private fun queueOf(vararg ids: String) = NewsQueue().apply { set(ids.toList()) }

    @Test
    fun `plays on into the next story down the list`() = runBlocking {
        val queue = queueOf("a", "b", "c")
        assertEquals("b", queue.nextAfter("a") { true })
        assertEquals("c", queue.nextAfter("b") { true })
    }

    @Test
    fun `skips stories already heard`() = runBlocking {
        val heard = setOf("b", "c")
        assertEquals("d", queueOf("a", "b", "c", "d").nextAfter("a") { it !in heard })
    }

    @Test
    fun `never goes back up the list`() = runBlocking {
        // "a" is unread but above the current story: the listener chose to skip it.
        assertNull(queueOf("a", "b", "c").nextAfter("c") { true })
        assertNull(queueOf("a", "b").nextAfter("b") { it == "a" })
    }

    @Test
    fun `a story that isn't in the list has no next`() = runBlocking {
        // Opened from somewhere else, e.g. the notification after the list was replaced.
        assertNull(queueOf("a", "b").nextAfter("z") { true })
        assertNull(NewsQueue().nextAfter("a") { true })
    }

    @Test
    fun `a new list replaces the old one`() = runBlocking {
        val queue = queueOf("a", "b")
        queue.set(listOf("x", "a", "y"))
        assertEquals("y", queue.nextAfter("a") { true })
    }
}
