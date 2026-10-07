package com.pedrolopes.ttsing.data.news

import com.pedrolopes.ttsing.data.news.ArticleImageStore.CachedFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class ArticleImageEvictionTest {

    private val now = 1_000_000_000_000L
    private val old = now - TimeUnit.DAYS.toMillis(45)
    private val recent = now - TimeUnit.DAYS.toMillis(2)

    private fun file(url: String, modifiedAt: Long) = CachedFile(ArticleImageStore.cacheName(url), modifiedAt)

    @Test
    fun `a photo of a story that is gone is dropped once it is old`() {
        val gone = file("https://example.com/gone.jpg", old)
        val evicted = ArticleImageStore.evictable(listOf(gone), keepUrls = emptySet(), now = now)
        assertEquals(listOf(gone.name), evicted)
    }

    @Test
    fun `the thumbnail of a remaining story is kept however old`() {
        val kept = file("https://example.com/kept.jpg", old)
        val gone = file("https://example.com/gone.jpg", old)
        val evicted = ArticleImageStore.evictable(listOf(kept, gone), setOf("https://example.com/kept.jpg"), now)
        assertEquals(listOf(gone.name), evicted)
    }

    @Test
    fun `a recent photo is spared, it may be inside a story's body`() {
        val body = file("https://example.com/in-body.jpg", recent)
        assertEquals(emptyList<String>(), ArticleImageStore.evictable(listOf(body), emptySet(), now))
    }

    @Test
    fun `nothing cached, nothing to drop`() {
        assertEquals(emptyList<String>(), ArticleImageStore.evictable(emptyList(), setOf("https://example.com/a.jpg"), now))
    }
}
