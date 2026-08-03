package com.pedrolopes.ttsing.data.news

import com.pedrolopes.ttsing.data.epub.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Photo handling. News pages lazy-load almost universally, so the naive `src` is frequently a
 * placeholder rather than the picture — these pin the attribute precedence and the filtering
 * that keeps tracking pixels and undecodable formats out of the reader.
 */
class ArticleImageTest {

    private fun images(html: String, baseUri: String = "https://news.example.com/story"): List<Block.Image> =
        ArticleExtractor.extract(html, baseUri).blocks.filterIsInstance<Block.Image>()

    private fun page(body: String) = """
        <html><body><article>
          <p>An opening paragraph long enough that the extractor scores this container as the
             real article body rather than discarding it as furniture.</p>
          $body
          <p>A closing paragraph, also long enough to count towards the content score here.</p>
        </article></body></html>
    """.trimIndent()

    @Test
    fun `a plain image is picked up with its alt text`() {
        val found = images(page("""<img src="https://cdn.example.com/photo.jpg" alt="A harbour at dawn"/>"""))
        assertEquals(1, found.size)
        assertEquals("https://cdn.example.com/photo.jpg", found[0].zipPath)
        assertEquals("A harbour at dawn", found[0].alt)
    }

    @Test
    fun `relative sources are resolved against the article address`() {
        val found = images(page("""<img src="/media/photo.jpg"/>"""))
        assertEquals("https://news.example.com/media/photo.jpg", found.single().zipPath)
    }

    @Test
    fun `the real photo wins over a lazy-load placeholder in src`() {
        val html = page(
            """<img src="https://cdn.example.com/placeholder.gif"
                    data-src="https://cdn.example.com/real-photo.jpg" alt="Real"/>""",
        )
        assertEquals("https://cdn.example.com/real-photo.jpg", images(html).single().zipPath)
    }

    @Test
    fun `the widest candidate in a srcset is chosen`() {
        val html = page(
            """<img srcset="https://cdn.example.com/small.jpg 400w,
                            https://cdn.example.com/large.jpg 1200w,
                            https://cdn.example.com/medium.jpg 800w" alt="Sized"/>""",
        )
        assertEquals("https://cdn.example.com/large.jpg", images(html).single().zipPath)
    }

    @Test
    fun `srcset entries are resolved relative to the page too`() {
        val html = page("""<img srcset="/a/small.jpg 400w, /a/large.jpg 900w"/>""")
        assertEquals("https://news.example.com/a/large.jpg", images(html).single().zipPath)
    }

    @Test
    fun `tracking pixels are left out`() {
        val html = page("""<img src="https://track.example.com/p.gif" width="1" height="1"/>""")
        assertTrue(images(html).isEmpty())
    }

    @Test
    fun `data uris and svgs are skipped because they would not decode`() {
        val dataUri = page("""<img src="data:image/gif;base64,R0lGODlhAQABAAAAACw="/>""")
        assertTrue("data uri", images(dataUri).isEmpty())

        val svg = page("""<img src="https://cdn.example.com/icon.svg"/>""")
        assertTrue("svg", images(svg).isEmpty())
    }

    @Test
    fun `a picture element contributes its image once, not twice`() {
        val html = page(
            """<picture>
                 <source srcset="https://cdn.example.com/photo.webp"/>
                 <img src="https://cdn.example.com/photo.jpg" alt="Once"/>
               </picture>""",
        )
        assertEquals(1, images(html).size)
    }

    @Test
    fun `a figure keeps both its photo and its caption, in order`() {
        val html = page(
            """<figure>
                 <img src="https://cdn.example.com/chart.png" alt="Chart"/>
                 <figcaption>Support has fallen sharply since the spring, according to the survey.</figcaption>
               </figure>""",
        )
        val blocks = ArticleExtractor.extract(html, "https://news.example.com/story").blocks
        val imageIndex = blocks.indexOfFirst { it is Block.Image }
        val captionIndex = blocks.indexOfFirst { it is Block.Text && it.text.startsWith("Support has fallen") }
        assertTrue("photo present", imageIndex >= 0)
        assertTrue("caption present", captionIndex >= 0)
        assertTrue("caption follows the photo", captionIndex > imageIndex)
    }

    @Test
    fun `an image inside a paragraph is pulled out rather than lost`() {
        val html = page(
            """<p><img src="https://cdn.example.com/inline.jpg" alt="Inline"/>
               This paragraph wraps the photo it illustrates, which several publishers do.</p>""",
        )
        val blocks = ArticleExtractor.extract(html, "https://news.example.com/story").blocks
        assertTrue(blocks.any { it is Block.Image && it.zipPath.endsWith("inline.jpg") })
        assertTrue(blocks.any { it is Block.Text && it.text.contains("wraps the photo") })
    }

    @Test
    fun `images from stripped page furniture never appear`() {
        val html = """
            <html><body>
              <header><img src="https://cdn.example.com/logo.png" alt="Logo"/></header>
              <article>
                <p>The article body needs enough prose here for the scoring to settle on this
                   element rather than on the page chrome around it.</p>
                <img src="https://cdn.example.com/story.jpg" alt="Story"/>
              </article>
              <footer><img src="https://cdn.example.com/badge.png" alt="Badge"/></footer>
            </body></html>
        """.trimIndent()
        val urls = images(html).map { it.zipPath }
        assertEquals(listOf("https://cdn.example.com/story.jpg"), urls)
    }

    @Test
    fun `an image with no usable source is skipped instead of becoming a blank block`() {
        assertTrue(images(page("""<img alt="no source"/>""")).isEmpty())
    }
}
