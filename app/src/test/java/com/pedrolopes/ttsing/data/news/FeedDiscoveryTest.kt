package com.pedrolopes.ttsing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedDiscoveryTest {

    @Test
    fun `finds the feed a page advertises, resolving a relative address`() {
        val html = """<html><head>
            <link rel="alternate" type="application/rss+xml" title="Example" href="/rss/index.xml">
          </head><body></body></html>"""
        assertEquals(
            listOf("https://www.example.com/rss/index.xml"),
            FeedDiscovery.advertisedFeeds(html, "https://www.example.com/tech/some-story"),
        )
    }

    @Test
    fun `accepts Atom and absolute addresses, in page order`() {
        val html = """<html><head>
            <link rel="alternate" type="application/atom+xml" href="https://feeds.example.com/atom">
            <link rel="alternate" type="application/rss+xml" href="https://feeds.example.com/rss">
          </head></html>"""
        assertEquals(
            listOf("https://feeds.example.com/atom", "https://feeds.example.com/rss"),
            FeedDiscovery.advertisedFeeds(html, "https://example.com/"),
        )
    }

    @Test
    fun `skips comment feeds and links that aren't feeds`() {
        // What a typical WordPress page advertises.
        val html = """<html><head>
            <link rel="alternate" type="application/rss+xml" title="Blog &raquo; Feed" href="https://blog.example.com/feed/">
            <link rel="alternate" type="application/rss+xml" title="Blog &raquo; Comments Feed" href="https://blog.example.com/comments/feed/">
            <link rel="alternate" hreflang="pt" href="https://blog.example.com/pt/">
            <link rel="stylesheet" href="/style.css">
          </head></html>"""
        assertEquals(
            listOf("https://blog.example.com/feed/"),
            FeedDiscovery.advertisedFeeds(html, "https://blog.example.com/"),
        )
    }

    @Test
    fun `a page that advertises nothing yields nothing`() {
        assertTrue(FeedDiscovery.advertisedFeeds("<html><body><p>Hi</p></body></html>", "https://example.com/").isEmpty())
        assertTrue(FeedDiscovery.advertisedFeeds("", "https://example.com/").isEmpty())
    }

    @Test
    fun `conventional locations are tried on the page's own origin`() {
        val candidates = FeedDiscovery.conventionalFeeds("https://example.com/2026/09/a-story?ref=home")
        assertEquals("https://example.com/feed", candidates.first())
        assertTrue(candidates.all { it.startsWith("https://example.com/") })
        assertTrue("https://example.com/rss.xml" in candidates)
    }

    @Test
    fun `keeps a non-standard port`() {
        assertEquals("http://example.com:8080/feed", FeedDiscovery.conventionalFeeds("http://example.com:8080/x").first())
    }
}
