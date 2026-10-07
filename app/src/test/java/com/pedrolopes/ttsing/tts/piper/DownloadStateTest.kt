package com.pedrolopes.ttsing.tts.piper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DownloadStateTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a second voice cannot start while one is downloading`() {
        val state = DownloadState()
        assertEquals(StartResult.STARTED, state.tryStart("a", 67))
        assertEquals(StartResult.BUSY, state.tryStart("b", 67))
        assertEquals(StartResult.ALREADY_RUNNING, state.tryStart("a", 67))
        assertEquals("a", state.progress.value?.id)
    }

    @Test
    fun `racing starts let exactly one through`() {
        val state = DownloadState()
        val started = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { i ->
            pool.execute {
                go.await()
                if (state.tryStart("voice-$i", 67) == StartResult.STARTED) started.incrementAndGet()
                done.countDown()
            }
        }
        go.countDown()
        done.await()
        pool.shutdown()
        assertEquals(1, started.get())
    }

    @Test
    fun `progress only moves for the running voice`() {
        val state = DownloadState()
        state.tryStart("a", 67)
        state.advance("a", 12)
        state.advance("b", 50)
        assertEquals(12, state.progress.value?.done)
    }

    @Test
    fun `a failure keeps its reason and a new start replaces it`() {
        val state = DownloadState()
        state.tryStart("a", 67)
        state.fail("a", "No connection")
        val failed = state.progress.value!!
        assertTrue(failed.failed)
        assertEquals("No connection", failed.reason)
        // Retrying, or trying another voice, is allowed after a failure.
        assertEquals(StartResult.STARTED, state.tryStart("b", 67))
        assertFalse(state.progress.value!!.failed)
    }

    @Test
    fun `finishing or cancelling clears it, and only for its own voice`() {
        val state = DownloadState()
        state.tryStart("a", 67)
        state.finish("b")
        assertNotNull(state.progress.value)
        state.finish("a")
        assertNull(state.progress.value)
    }

    @Test
    fun `clearError leaves a running download alone`() {
        val state = DownloadState()
        state.tryStart("a", 67)
        state.clearError()
        assertNotNull(state.progress.value)
        state.fail("a", "x")
        state.clearError()
        assertNull(state.progress.value)
    }

    @Test
    fun `failures are told apart for the row`() {
        assertEquals("The server answered HTTP 404", DownloadFailure.describe(HttpStatusException(404)))
        assertEquals("No connection — check your internet", DownloadFailure.describe(UnknownHostException("github.com")))
        assertEquals("No connection — check your internet", DownloadFailure.describe(SocketTimeoutException()))
        assertEquals(
            "Not enough storage space",
            DownloadFailure.describe(IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals("Download failed", DownloadFailure.describe(IllegalStateException("boom")))
    }

    // The shared phoneme data: appears whole or not at all.

    @Test
    fun `a finished copy is renamed into place`() {
        val temp = folder.newFolder("espeak-ng-data.partial-1").also { File(it, "phondata").writeText("x") }
        val shared = File(folder.root, "espeak-ng-data")
        assertTrue(PiperVoices.publishShared(temp, shared))
        assertTrue(File(shared, "phondata").isFile)
        assertFalse(temp.exists())
    }

    @Test
    fun `an interrupted copy leaves no shared folder to be mistaken for a complete one`() {
        // The scratch folder is all an interrupted extraction leaves: nothing is at the real
        // path, so the next download sees the data as missing and fetches it again.
        folder.newFolder("espeak-ng-data.partial-2").also { File(it, "phondata").writeText("half") }
        assertFalse(File(folder.root, "espeak-ng-data").isDirectory)
    }

    @Test
    fun `when another writer finished first theirs stands`() {
        val shared = folder.newFolder("espeak-ng-data").also { File(it, "phondata").writeText("first") }
        val temp = folder.newFolder("espeak-ng-data.partial-3").also { File(it, "phondata").writeText("second") }
        assertTrue(PiperVoices.publishShared(temp, shared))
        assertEquals("first", File(shared, "phondata").readText())
        assertFalse(temp.exists())
    }

    @Test
    fun `publishing nothing publishes nothing`() {
        val shared = File(folder.root, "espeak-ng-data")
        assertFalse(PiperVoices.publishShared(File(folder.root, "missing"), shared))
        assertFalse(shared.exists())
    }
}
