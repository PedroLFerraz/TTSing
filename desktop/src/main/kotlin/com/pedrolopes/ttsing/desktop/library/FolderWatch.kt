package com.pedrolopes.ttsing.desktop.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY

/**
 * Watches the books folder and every folder under it, and calls [onChange] once things settle
 * after a change: a book copied in from the file manager, or renamed there, shows up without
 * asking. The watch is set up again after each change, so new subfolders are watched too.
 */
class FolderWatch(private val scope: CoroutineScope, private val onChange: suspend () -> Unit) {

    private var job: Job? = null

    fun watch(root: File?) {
        job?.cancel()
        if (root == null || !root.isDirectory) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val service = FileSystems.getDefault().newWatchService()
                try {
                    root.walkTopDown()
                        .onEnter { !it.name.startsWith(".") || it == root }
                        .filter { it.isDirectory }
                        .forEach { runCatching { it.toPath().register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) } }
                    // Wait for the first change, then for a quiet moment: a copy is many events.
                    runInterruptible { service.take() }
                    do {
                        delay(SETTLE_MS)
                        var more = false
                        while (true) {
                            val key = service.poll() ?: break
                            key.pollEvents()
                            key.reset()
                            more = true
                        }
                    } while (more)
                    onChange()
                } catch (_: ClosedWatchServiceException) {
                    return@launch
                } finally {
                    service.close()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
