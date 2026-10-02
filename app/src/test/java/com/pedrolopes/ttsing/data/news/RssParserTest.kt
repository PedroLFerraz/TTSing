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
    fun `html entities in titles are decoded, not read aloud`() {
        // The Verge's Atom shape: type="html" titles with entities inside CDATA.
        val atom = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title type="text">Site</title>
            <entry><title type="html"><![CDATA[Google says it&#8217;s so capable only &#8216;defenders&#8217; get it]]></title>
              <link rel="alternate" href="https://example.com/a"/><id>a</id></entry></feed>"""
        assertEquals("Google says it’s so capable only ‘defenders’ get it", RssParser.parse(atom)!!.items.single().title)

        // WordPress RSS does the same with escaped markup.
        val rss = """<rss><channel><title>Blog &amp;amp; News</title><item>
            <title>Lula &lt;em&gt;sanciona&lt;/em&gt; lei &amp;#8220;X&amp;#8221;</title><link>https://example.com/b</link></item></channel></rss>"""
        val parsed = RssParser.parse(rss)!!
        assertEquals("Blog & News", parsed.title)
        assertEquals("Lula sanciona lei “X”", parsed.items.single().title)
    }

    @Test
    fun `plain titles with ampersands are left alone`() {
        val rss = """<rss><channel><title>AT&amp;T news</title><item>
            <title>Q&amp;A: 3 &lt; 5 &amp; other truths</title><link>https://example.com/c</link></item></channel></rss>"""
        val parsed = RssParser.parse(rss)!!
        assertEquals("AT&T news", parsed.title)
        assertEquals("Q&A: 3 < 5 & other truths", parsed.items.single().title)
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

    @Test
    fun `reads every category of an rss item, including nested ones`() {
        val xml = """<rss version="2.0"><channel><title>Wired</title>
              <item><title>Columbia Promo Codes</title><link>https://example.com/x</link>
                <category>Gear</category><category><![CDATA[Gear / Deals]]></category>
              </item>
              <item><title>No labels</title><link>https://example.com/y</link></item>
            </channel></rss>"""
        val items = RssParser.parse(xml)!!.items
        assertEquals(listOf("Gear", "Gear / Deals"), items[0].categories)
        assertEquals(emptyList<String>(), items[1].categories)
    }

    @Test
    fun `reads atom categories from their label or term`() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title>
              <entry><title>Story</title><link href="https://example.com/a"/>
                <category term="sponsored"/><category term="ai" label="Artificial intelligence"/>
              </entry>
            </feed>"""
        assertEquals(listOf("sponsored", "Artificial intelligence"), RssParser.parse(xml)!!.items.single().categories)
    }
}
