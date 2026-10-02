package com.pedrolopes.ttsing.data.news

import org.junit.Assert.assertEquals
import org.junit.Test

/** Feeds that only declare their encoding inside the document, as many Brazilian ones do. */
class HttpFetcherCharsetTest {

    private val latin1Feed =
        """<?xml version="1.0" encoding="ISO-8859-1"?><rss><channel><title>Política</title></channel></rss>"""

    @Test
    fun `honours the encoding an XML prolog declares when the header names none`() {
        val text = HttpFetcher.decodeText(latin1Feed.toByteArray(Charsets.ISO_8859_1), "text/xml")
        assertEquals("Política", RssParser.parse(text)?.title)
    }

    @Test
    fun `honours an HTML meta charset`() {
        val html = """<html><head><meta charset="windows-1252"></head><body><p>Ação</p></body></html>"""
        val text = HttpFetcher.decodeText(html.toByteArray(charset("windows-1252")), "text/html")
        assertEquals(html, text)
    }

    @Test
    fun `honours an http-equiv content type`() {
        val html = """<html><head><meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1"></head><body>Eleição</body></html>"""
        assertEquals(html, HttpFetcher.decodeText(html.toByteArray(Charsets.ISO_8859_1), null))
    }

    @Test
    fun `the header wins over what the document says`() {
        // The server converted it and said so; the stale prolog is wrong.
        val feed = latin1Feed
        val text = HttpFetcher.decodeText(feed.toByteArray(Charsets.UTF_8), "application/rss+xml; charset=UTF-8")
        assertEquals("Política", RssParser.parse(text)?.title)
    }

    @Test
    fun `falls back to UTF-8 when nobody says anything`() {
        val feed = """<rss><channel><title>Política</title></channel></rss>"""
        assertEquals(feed, HttpFetcher.decodeText(feed.toByteArray(Charsets.UTF_8), null))
    }

    @Test
    fun `ignores a charset name nobody can decode`() {
        val feed = """<?xml version="1.0" encoding="no-such-charset"?><rss/>"""
        assertEquals(feed, HttpFetcher.decodeText(feed.toByteArray(Charsets.UTF_8), "text/xml; charset=bogus"))
    }
}
