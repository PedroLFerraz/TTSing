package com.pedrolopes.ttsing.data.news

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Downloads article photos once and keeps them on disk.
 *
 * Article images live on the open web rather than inside a file, so unlike an EPUB's images
 * they cost a network round-trip. Caching them means paging back through a story — or
 * re-opening it later, offline — doesn't re-fetch anything.
 */
class ArticleImageStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir: File get() = File(appContext.cacheDir, DIR).apply { mkdirs() }

    /** One download per URL even if several pages ask for the same photo at once. */
    private val locks = mutableMapOf<String, Mutex>()
    private val locksGuard = Mutex()

    suspend fun bytes(url: String): ByteArray? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        val file = File(dir, fileName(url))

        file.takeIf { it.length() > 0 }?.let { cached ->
            return withContext(Dispatchers.IO) { runCatching { cached.readBytes() }.getOrNull() }
        }

        val lock = locksGuard.withLock { locks.getOrPut(url) { Mutex() } }
        return lock.withLock {
            // Another caller may have finished while this one waited.
            file.takeIf { it.length() > 0 }?.let { cached ->
                return@withLock withContext(Dispatchers.IO) { runCatching { cached.readBytes() }.getOrNull() }
            }
            val downloaded = HttpFetcher.getBytes(url, MAX_IMAGE_BYTES) ?: return@withLock null
            withContext(Dispatchers.IO) {
                runCatching { File(dir, fileName(url)).writeBytes(downloaded) }
            }
            downloaded
        }
    }

    /** Drops every cached photo; the articles themselves are unaffected. */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileName(url: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIR = "article-images"

        /** Generous for a photo, small enough that a mis-linked video never lands here. */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    }
}
