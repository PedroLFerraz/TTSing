package com.pedrolopes.ttsing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssParserTest {

    @Test
    fun `parses an rss 2 feed`() {
        val xml = """<?xml version="1.0"?>
            <rss version="2.0">
              <channel>
                <title>Example News</title>
                <link>https://example.com</link>
                <language>pt-PT</language>
                <item>
                  <title>First story</title>
                  <link>https://example.com/1</link>
                  <description>A teaser that stops mid-…</description>
                  <pubDate>Wed, 02 Oct 2002 13:00:00 GMT</pubDate>
                  <guid>tag:example.com,2002:1</guid>
                </item>
                <item>
                  <title>Second story</title>
                  <link>https://example.com/2</link>
                </item>
              </channel>
            </rss>"""

        val feed = RssParser.parse(xml)
        assertNotNull(feed)
        assertEquals("Example News", feed!!.title)
        assertEquals("https://example.com", feed.siteLink)
        assertEquals("pt-PT", feed.language)
        assertEquals(2, feed.items.size)
        assertEquals("First story", feed.items[0].title)
        assertEquals("https://example.com/1", feed.items[0].link)
        assertEquals("tag:example.com,2002:1", feed.items[0].guid)
        assertTrue(feed.items[0].publishedAt > 0)
    }

    @Test
    fun `picks up the full body from content encoded`() {
        val xml = """<?xml version="1.0"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Full text feed</title>
                <item>
                  <title>Story</title>
                  <link>https://example.com/1</link>
                  <description>Short teaser</description>
                  <content:encoded><![CDATA[<p>The complete article body.</p>]]></content:encoded>
                </item>
              </channel>
            </rss>"""

        val item = RssParser.parse(xml)!!.items.single()
        assertEquals("Short teaser", item.summary)
        assertTrue(item.contentHtml!!.contains("The complete article body."))
    }

    @Test
    fun `parses an atom feed including the alternate link`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="de">
              <title>Atom Example</title>
              <link rel="self" href="https://example.com/feed.xml"/>
              <link rel="alternate" href="https://example.com"/>
              <entry>
                <title>Atom story</title>
                <link rel="alternate" href="https://example.com/atom-1"/>
                <id>urn:uuid:1</id>
                <updated>2024-03-05T14:30:00Z</updated>
                <summary>Summary text</summary>
              </entry>
            </feed>"""

        val feed = RssParser.parse(xml)
        assertNotNull(feed)
        assertEquals("Atom Example", feed!!.title)
        assertEquals("de", feed.language)
        // The self link must not win over the alternate one.
        assertEquals("https://example.com", feed.siteLink)

        val entry = feed.items.single()
        assertEquals("Atom story", entry.title)
        assertEquals("https://example.com/atom-1", entry.link)
        assertEquals("urn:uuid:1", entry.guid)
        assertTrue(entry.publishedAt > 0)
    }

    @Test
    fun `an item without a link is skipped rather than breaking the feed`() {
        val xml = """<rss version="2.0"><channel><title>T</title>
              <item><title>No link here</title></item>
              <item><title>Fine</title><link>https://example.com/ok</link></item>
            </channel></rss>"""
        val items = RssParser.parse(xml)!!.items
        assertEquals(1, items.size)
        assertEquals("https://example.com/ok", items[0].link)
    }

    @Test
    fun `falls back to the link when guid is missing`() {
        val xml = """<rss version="2.0"><channel><title>T</title>
              <item><title>A</title><link>https://example.com/a</link></item>
            </channel></rss>"""
        assertEquals("https://example.com/a", RssParser.parse(xml)!!.items[0].guid)
    }

    @Test
    fun `non-feed xml and junk return null instead of throwing`() {
        assertNull(RssParser.parse("<html><body>not a feed</body></html>"))
        assertNull(RssParser.parse(""))
        assertNull(RssParser.parse("{\"json\": true}"))
    }

    @Test
    fun `understands the common date formats`() {
        assertTrue(RssParser.parseDate("Wed, 02 Oct 2002 15:00:00 +0200") > 0)
        assertTrue(RssParser.parseDate("Wed, 02 Oct 2002 15:00:00 GMT") > 0)
        assertTrue(RssParser.parseDate("2024-03-05T14:30:00Z") > 0)
        assertTrue(RssParser.parseDate("2024-03-05T14:30:00+01:00") > 0)
        assertTrue(RssParser.parseDate("2024-03-05") > 0)
    }

    @Test
    fun `an unparseable date is zero rather than an exception`() {
        assertEquals(0L, RssParser.parseDate("last Tuesday"))
        assertEquals(0L, RssParser.parseDate(null))
        assertEquals(0L, RssParser.parseDate(""))
    }

    @Test
    fun `a feed title tag inside an item does not overwrite the channel title`() {
        val xml = """<rss version="2.0"><channel>
              <title>Channel Title</title>
              <item><title>Item Title</title><link>https://example.com/x</link></item>
            </channel></rss>"""
        val feed = RssParser.parse(xml)!!
        assertEquals("Channel Title", feed.title)
        assertEquals("Item Title", feed.items[0].title)
    }
}
