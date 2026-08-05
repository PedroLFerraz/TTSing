package com.pedrolopes.ttsing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The article-list thumbnail's most reliable source: the page's own social-share image, which
 * almost every news article declares in its `<head>` and which survives even when the hero
 * photo sits outside the scored article body. This is what makes thumbnails appear without the
 * user opening every story.
 */
class ArticleLeadImageTest {

    private val base = "https://news.example.com/2026/07/story"

    private fun leadImage(head: String): String? =
        ArticleExtractor.extract(
            "<html><head>$head</head><body><article>" +
                "<p>Body text long enough that the extractor treats this as a real article " +
                "rather than discarding it as page furniture with nothing to read.</p>" +
                "</article></body></html>",
            base,
        ).leadImageUrl

    @Test
    fun `og image is used`() {
        assertEquals(
            "https://cdn.example.com/share.jpg",
            leadImage("""<meta property="og:image" content="https://cdn.example.com/share.jpg"/>"""),
        )
    }

    @Test
    fun `og image url variant is used`() {
        assertEquals(
            "https://cdn.example.com/share.jpg",
            leadImage("""<meta property="og:image:url" content="https://cdn.example.com/share.jpg"/>"""),
        )
    }

    @Test
    fun `twitter image is used when there is no og image`() {
        assertEquals(
            "https://cdn.example.com/tw.jpg",
            leadImage("""<meta name="twitter:image" content="https://cdn.example.com/tw.jpg"/>"""),
        )
    }

    @Test
    fun `og image wins over twitter image`() {
        val head = """<meta property="og:image" content="https://cdn.example.com/og.jpg"/>
                      <meta name="twitter:image" content="https://cdn.example.com/tw.jpg"/>"""
        assertEquals("https://cdn.example.com/og.jpg", leadImage(head))
    }

    @Test
    fun `a protocol-relative url resolves against the page`() {
        assertEquals(
            "https://cdn.example.com/x.jpg",
            leadImage("""<meta property="og:image" content="//cdn.example.com/x.jpg"/>"""),
        )
    }

    @Test
    fun `a site-relative url resolves against the page`() {
        assertEquals(
            "https://news.example.com/media/x.jpg",
            leadImage("""<meta property="og:image" content="/media/x.jpg"/>"""),
        )
    }

    @Test
    fun `an svg share image is rejected because it cannot be decoded`() {
        assertNull(leadImage("""<meta property="og:image" content="https://cdn.example.com/logo.svg"/>"""))
    }

    @Test
    fun `a page with no share image yields null`() {
        assertNull(leadImage("""<meta name="description" content="no image here"/>"""))
    }

    @Test
    fun `itemprop image is a last resort`() {
        assertEquals(
            "https://cdn.example.com/schema.jpg",
            leadImage("""<meta itemprop="image" content="https://cdn.example.com/schema.jpg"/>"""),
        )
    }
}
