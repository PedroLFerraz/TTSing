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

    /**
     * Drops cached photos no story needs any more. [keepUrls] are the thumbnails of the stories
     * that remain; a photo cached from inside a story's body isn't in that set, so a file is
     * only dropped once it is also older than the stories are kept for (see [evictable]).
     */
    suspend fun evict(keepUrls: Set<String>) = withContext(Dispatchers.IO) {
        runCatching {
            val files = dir.listFiles()?.map { CachedFile(it.name, it.lastModified()) }.orEmpty()
            for (name in evictable(files, keepUrls, System.currentTimeMillis())) File(dir, name).delete()
        }
    }

    private fun fileName(url: String): String = cacheName(url)

    internal class CachedFile(val name: String, val modifiedAt: Long)

    internal companion object {
        const val DIR = "article-images"

        fun cacheName(url: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /**
         * The names of [files] to delete: not the thumbnail of any of [keepUrls] and older than
         * [maxAgeMs] (a month by default, as long as stories live). The age test is what spares
         * the photos inside a still-listed story's body, which nothing here lists.
         */
        fun evictable(
            files: List<CachedFile>,
            keepUrls: Set<String>,
            now: Long,
            maxAgeMs: Long = NewsRepository.RETENTION_MS,
        ): List<String> {
            val keep = keepUrls.mapTo(HashSet(), ::cacheName)
            return files.filter { it.name !in keep && now - it.modifiedAt > maxAgeMs }.map { it.name }
        }

        /** Generous for a photo, small enough that a mis-linked video never lands here. */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    }
}
