package com.pedrolopes.ttsing.data.news

import com.pedrolopes.ttsing.data.epub.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The point of this class is reading a news story *whole*, so these tests are mostly about
 * what must NOT be lost (later paragraphs) and what must NOT be read aloud (menus, related
 * links, cookie banners).
 */
class ArticleExtractorTest {

    private fun text(article: ExtractedArticle): String =
        article.blocks.filterIsInstance<Block.Text>().joinToString(" ") { it.text }

    private fun paragraphs(article: ExtractedArticle): List<String> =
        article.blocks.filterIsInstance<Block.Text>()
            .filter { it.kind == Block.Text.Kind.PARAGRAPH }
            .map { it.text }

    private val realisticPage = """
        <html><head>
          <title>Council approves budget | Example Times</title>
          <meta property="og:title" content="Council approves budget"/>
        </head><body>
          <header><a href="/">Example Times</a></header>
          <nav class="main-nav"><a href="/world">World</a><a href="/sport">Sport</a></nav>
          <div id="cookie-consent"><p>We use cookies to improve your experience on this site.</p></div>
          <div class="wrapper">
            <article class="article-body">
              <h1>Council approves budget</h1>
              <p>The council voted on Tuesday to approve the budget, ending weeks of debate
                 that had divided members along familiar lines.</p>
              <p>Opponents argued the plan relied on optimistic revenue forecasts, while
                 supporters said delay would cost the city more in the long run.</p>
              <p>The measure passed by a single vote after an amendment on transport spending
                 was withdrawn at the last minute.</p>
              <blockquote>This is a difficult compromise, but it is the right one.</blockquote>
              <p>Implementation begins next month, with the first payments expected in
                 the spring according to officials familiar with the schedule.</p>
            </article>
          </div>
          <aside class="related"><h3>Related</h3><a href="/a">Budget explained</a><a href="/b">Who voted how</a></aside>
          <div class="newsletter-signup"><p>Sign up for our daily newsletter to get this in your inbox.</p></div>
          <footer><p>Copyright Example Times. All rights reserved worldwide.</p></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `keeps every paragraph of the article, not just the first`() {
        val article = ArticleExtractor.extract(realisticPage)
        val body = text(article)
        assertTrue("first paragraph", body.contains("ending weeks of debate"))
        assertTrue("middle paragraph", body.contains("optimistic revenue forecasts"))
        assertTrue("later paragraph", body.contains("passed by a single vote"))
        assertTrue("final paragraph", body.contains("first payments expected"))
    }

    @Test
    fun `leaves out navigation, related links, newsletter prompts and footers`() {
        val body = text(ArticleExtractor.extract(realisticPage))
        assertFalse("nav", body.contains("Sport"))
        assertFalse("cookie banner", body.contains("We use cookies"))
        assertFalse("related rail", body.contains("Who voted how"))
        assertFalse("newsletter", body.contains("daily newsletter"))
        assertFalse("footer", body.contains("All rights reserved"))
    }

    @Test
    fun `takes the title from og-title`() {
        assertEquals("Council approves budget", ArticleExtractor.extract(realisticPage).title)
    }

    @Test
    fun `keeps headings and quotes as their own kinds`() {
        val blocks = ArticleExtractor.extract(realisticPage).blocks.filterIsInstance<Block.Text>()
        assertTrue(blocks.any { it.kind == Block.Text.Kind.HEADING_1 && it.text.contains("Council approves") })
        assertTrue(blocks.any { it.kind == Block.Text.Kind.QUOTE && it.text.contains("difficult compromise") })
    }

    @Test
    fun `blocks carry sentence spans so the reader can highlight them`() {
        val article = ArticleExtractor.extract(realisticPage)
        val paragraph = article.blocks.filterIsInstance<Block.Text>()
            .first { it.kind == Block.Text.Kind.PARAGRAPH }
        assertTrue(paragraph.sentences.isNotEmpty())
        val first = paragraph.sentences.first()
        assertTrue(first.start >= 0 && first.end <= paragraph.text.length)
    }

    @Test
    fun `finds the body when the page has no article tag, only scored divs`() {
        val html = """
            <html><body>
              <div id="nav"><a href="/x">Home</a><a href="/y">News</a></div>
              <div class="story-content">
                <p>Researchers reported on Monday that the trial had met its primary endpoint,
                   a result that surprised several outside experts.</p>
                <p>The study followed two thousand participants over three years, and the effect
                   held across every age group the team examined.</p>
                <p>Regulators are expected to review the findings later this year, though no
                   date has been set for a decision.</p>
              </div>
              <div class="sidebar"><p>Most popular stories this week on our website today.</p></div>
            </body></html>
        """.trimIndent()

        val body = text(ArticleExtractor.extract(html))
        assertTrue(body.contains("primary endpoint"))
        assertTrue(body.contains("two thousand participants"))
        assertTrue(body.contains("Regulators are expected"))
        assertFalse(body.contains("Most popular"))
    }

    @Test
    fun `keeps paragraphs that publishers split across sibling containers`() {
        // Ad slots between paragraphs are common and must not truncate the article.
        val html = """
            <html><body><div class="article-body">
              <div class="para-group"><p>The first section explains the background in some
                 detail, setting out how the dispute began several years ago.</p></div>
              <div class="ad-slot"><p>Advertisement</p></div>
              <div class="para-group"><p>The second section covers the response, including
                 statements from both parties and their legal representatives.</p></div>
              <div class="para-group"><p>The final section looks ahead to the hearing that is
                 scheduled for the autumn, when a judgment is expected.</p></div>
            </div></body></html>
        """.trimIndent()

        val body = text(ArticleExtractor.extract(html))
        assertTrue(body.contains("how the dispute began"))
        assertTrue(body.contains("statements from both parties"))
        assertTrue(body.contains("scheduled for the autumn"))
    }

    @Test
    fun `scripts and styles never reach the spoken text`() {
        val html = """
            <html><body><article>
              <script>var tracking = {id: 42}; console.log("should never be read");</script>
              <style>.headline { font-size: 2rem; }</style>
              <p>The visible article text runs for long enough to be scored as real content
                 by the extractor, which is what we want here.</p>
            </article></body></html>
        """.trimIndent()

        val body = text(ArticleExtractor.extract(html))
        assertTrue(body.contains("visible article text"))
        assertFalse(body.contains("tracking"))
        assertFalse(body.contains("font-size"))
    }

    @Test
    fun `uses the book locale for sentence splitting`() {
        val html = """<html><body><article>
              <p>Era uma vez um menino. Ele morava no Porto. Gostava muito de ler.</p>
            </article></body></html>"""
        val article = ArticleExtractor.extract(html, locale = Locale.forLanguageTag("pt-PT"))
        val paragraph = article.blocks.filterIsInstance<Block.Text>().first()
        assertEquals(3, paragraph.sentences.size)
    }

    /**
     * g1's shape: the story is one <article>, but every embedded video player is wrapped in an
     * <article> of its own, and each paragraph sits in its own chunk <div>.
     */
    private val g1Page = run {
        val chunks = (1..6).joinToString("\n") { n ->
            """<div id="chunk-$n"><div class="mc-column content-text">
                 <p class="content-text__container">Paragraph $n of the story, long enough to read as prose, with a comma.</p>
               </div></div>"""
        }
        """<html><body>
            <div class="mc-article-body"><article itemprop="articleBody">
              $chunks
              <figure><img src="/fotos/plenario.jpg" alt="Plenário"></figure>
              <article class="video-player-wrapper"></article>
            </article></div>
            <article class="video-player-wrapper"></article>
            <article class="video-player-wrapper"></article>
          </body></html>"""
    }

    @Test
    fun `reads the whole story when empty article wrappers sit beside the real one`() {
        val body = paragraphs(ArticleExtractor.extract(g1Page))
        assertEquals((1..6).map { "Paragraph $it of the story, long enough to read as prose, with a comma." }, body)
    }

    @Test
    fun `a stored body reopens as exactly the blocks first extracted`() {
        // The app stores contentHtml and turns it into blocks each time a story is opened.
        // Scoring that already-trimmed body a second time kept only one chunk of it: the g1
        // page reopened as its first paragraph.
        val base = "https://g1.globo.com/politica/noticia/2026/10/02/story.ghtml"
        val pt = Locale.forLanguageTag("pt-BR")
        for ((name, html) in listOf("realistic" to realisticPage, "g1" to g1Page)) {
            val first = ArticleExtractor.extract(html, base, pt)
            assertEquals(name, first.blocks, ArticleExtractor.blocksOf(first.contentHtml, base, pt))
        }
    }

    @Test
    fun `a stored body still resolves relative image addresses against the story`() {
        val base = "https://g1.globo.com/politica/noticia/2026/10/02/story.ghtml"
        val blocks = ArticleExtractor.blocksOf(ArticleExtractor.extract(g1Page, base).contentHtml, base)
        assertEquals("https://g1.globo.com/fotos/plenario.jpg", blocks.filterIsInstance<Block.Image>().single().zipPath)
    }

    @Test
    fun `an empty or contentless page yields no blocks rather than throwing`() {
        assertTrue(ArticleExtractor.extract("").blocks.isEmpty())
        assertTrue(ArticleExtractor.extract("<html><body></body></html>").blocks.isEmpty())
        assertEquals(0, ArticleExtractor.extract("<html><body></body></html>").textLength)
    }

    @Test
    fun `textLength reflects the extracted prose`() {
        val article = ArticleExtractor.extract(realisticPage)
        assertTrue("expected a substantial article, got ${article.textLength}", article.textLength > 300)
    }
}
