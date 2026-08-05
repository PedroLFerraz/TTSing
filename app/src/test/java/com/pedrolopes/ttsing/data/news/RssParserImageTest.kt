package com.pedrolopes.ttsing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Picking a lead image straight out of feed data, so the article list can show a thumbnail
 * before the page has ever been fetched. Checked in the order real feeds actually use these:
 * explicit media metadata first, an image embedded in the story's own HTML as a last resort.
 */
class RssParserImageTest {

    private fun rssImage(itemXml: String): String? {
        val xml = """<rss version="2.0"><channel><title>T</title>
              $itemXml
            </channel></rss>"""
        return RssParser.parse(xml)!!.items.single().imageUrl
    }

    private fun atomImage(entryXml: String): String? {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>T</title>
              $entryXml
            </feed>"""
        return RssParser.parse(xml)!!.items.single().imageUrl
    }

    @Test
    fun `media thumbnail wins first`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/a</link>
                 <media:thumbnail url="https://cdn.example.com/thumb.jpg"/>
               </item>""",
        )
        assertEquals("https://cdn.example.com/thumb.jpg", url)
    }

    @Test
    fun `media content marked as an image is used`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/a</link>
                 <media:content url="https://cdn.example.com/photo.jpg" medium="image"/>
               </item>""",
        )
        assertEquals("https://cdn.example.com/photo.jpg", url)
    }

    @Test
    fun `media content without a medium is still recognised by its extension`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/a</link>
                 <media:content url="https://cdn.example.com/photo.jpg"/>
               </item>""",
        )
        assertEquals("https://cdn.example.com/photo.jpg", url)
    }

    @Test
    fun `a non-image media content is ignored`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/a</link>
                 <media:content url="https://cdn.example.com/clip.mp4" medium="video"/>
               </item>""",
        )
        assertNull(url)
    }

    @Test
    fun `an image enclosure is used`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/a</link>
                 <enclosure url="https://cdn.example.com/cover.png" type="image/png"/>
               </item>""",
        )
        assertEquals("https://cdn.example.com/cover.png", url)
    }

    @Test
    fun `a non-image enclosure is ignored`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/a</link>
                 <enclosure url="https://cdn.example.com/episode.mp3" type="audio/mpeg"/>
               </item>""",
        )
        assertNull(url)
    }

    @Test
    fun `falls back to an image embedded in content encoded`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/a</link>
                 <content:encoded><![CDATA[<p>Text</p><img src="https://cdn.example.com/inline.jpg"/>]]></content:encoded>
               </item>""",
        )
        assertEquals("https://cdn.example.com/inline.jpg", url)
    }

    @Test
    fun `falls back to an image embedded in the description as a last resort`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/a</link>
                 <description><![CDATA[<img src="https://cdn.example.com/desc.jpg"/> Teaser text]]></description>
               </item>""",
        )
        assertEquals("https://cdn.example.com/desc.jpg", url)
    }

    @Test
    fun `a relative image in the description resolves against the article link`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/news/a</link>
                 <description><![CDATA[<img src="/media/photo.jpg"/>]]></description>
               </item>""",
        )
        assertEquals("https://example.com/media/photo.jpg", url)
    }

    @Test
    fun `explicit metadata wins over an embedded image`() {
        val url = rssImage(
            """<item><title>A</title><link>https://example.com/a</link>
                 <media:thumbnail url="https://cdn.example.com/thumb.jpg"/>
                 <description><![CDATA[<img src="https://cdn.example.com/desc.jpg"/>]]></description>
               </item>""",
        )
        assertEquals("https://cdn.example.com/thumb.jpg", url)
    }

    @Test
    fun `a story with no image metadata at all yields null rather than throwing`() {
        assertNull(rssImage("""<item><title>A</title><link>https://example.com/a</link></item>"""))
    }

    @Test
    fun `atom picks up media thumbnail the same way`() {
        val url = atomImage(
            """<entry><title>A</title><link rel="alternate" href="https://example.com/a"/>
                 <id>1</id>
                 <media:thumbnail url="https://cdn.example.com/thumb.jpg"/>
               </entry>""",
        )
        assertEquals("https://cdn.example.com/thumb.jpg", url)
    }

    @Test
    fun `atom recognises an image enclosure link`() {
        val url = atomImage(
            """<entry><title>A</title><link rel="alternate" href="https://example.com/a"/>
                 <id>1</id>
                 <link rel="enclosure" href="https://cdn.example.com/cover.jpg" type="image/jpeg"/>
               </entry>""",
        )
        assertEquals("https://cdn.example.com/cover.jpg", url)
    }

    @Test
    fun `atom falls back to an image inside its own content`() {
        val url = atomImage(
            """<entry><title>A</title><link rel="alternate" href="https://example.com/a"/>
                 <id>1</id>
                 <content><![CDATA[<p>Text</p><img src="https://cdn.example.com/inline.jpg"/>]]></content>
               </entry>""",
        )
        assertEquals("https://cdn.example.com/inline.jpg", url)
    }
}
