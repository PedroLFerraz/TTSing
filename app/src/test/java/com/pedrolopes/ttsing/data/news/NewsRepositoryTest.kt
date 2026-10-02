package com.pedrolopes.ttsing.data.news

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The pure helpers around feed subscription and article bodies. The networked parts need a device. */
class NewsRepositoryTest {

    @Test
    fun `adds a scheme when the user pastes a bare host`() {
        assertEquals("https://example.com/feed", NewsRepository.normalizeUrl("example.com/feed"))
        assertEquals("https://example.com/feed", NewsRepository.normalizeUrl("  example.com/feed  "))
    }

    @Test
    fun `keeps an explicit scheme`() {
        assertEquals("http://example.com/rss", NewsRepository.normalizeUrl("http://example.com/rss"))
        assertEquals("https://example.com/rss", NewsRepository.normalizeUrl("https://example.com/rss"))
    }

    @Test
    fun `rewrites the feed scheme that readers hand out`() {
        assertEquals("https://example.com/rss", NewsRepository.normalizeUrl("feed://example.com/rss"))
    }

    @Test
    fun `rejects input that is not an address`() {
        assertNull(NewsRepository.normalizeUrl(""))
        assertNull(NewsRepository.normalizeUrl("   "))
        assertNull(NewsRepository.normalizeUrl("just some words"))
        // No dot means no host worth trying.
        assertNull(NewsRepository.normalizeUrl("localhostfeed"))
    }

    @Test
    fun `article ids are stable across refreshes`() {
        val first = NewsRepository.articleId("https://example.com/feed", "guid-1")
        val second = NewsRepository.articleId("https://example.com/feed", "guid-1")
        assertEquals("a story must keep its id so the reading position survives", first, second)
    }

    @Test
    fun `article ids differ per story and per feed`() {
        val a = NewsRepository.articleId("https://example.com/feed", "guid-1")
        val b = NewsRepository.articleId("https://example.com/feed", "guid-2")
        val c = NewsRepository.articleId("https://other.com/feed", "guid-1")
        assertNotEquals(a, b)
        assertNotEquals("same guid from another feed must not collide", a, c)
    }

    @Test
    fun `article ids are recognisable so the reader can tell them from books`() {
        val id = NewsRepository.articleId("https://example.com/feed", "guid-1")
        assertTrue(NewsRepository.isArticle(id))
        // Book ids are bare sha1 hex from BookRepository.
        assertFalse(NewsRepository.isArticle("da39a3ee5e6b4b0d3255bfef95601890afd80709"))
    }

    @Test
    fun `feed summaries are reduced to plain text`() {
        val html = "<p>A teaser with <b>bold</b> text and <a href='#'>a link</a>.</p>"
        assertEquals("A teaser with bold text and a link.", NewsRepository.stripHtml(html))
    }

    @Test
    fun `stripping html decodes entities`() {
        assertEquals("Tom & Jerry \"quoted\"", NewsRepository.stripHtml("Tom &amp; Jerry &quot;quoted&quot;"))
    }

    private fun text(value: String, kind: Block.Text.Kind) =
        Block.Text(value, kind, SentenceSplitter.split(value, Locale.ENGLISH))

    private val story = listOf<Block>(
        text("The council voted on Tuesday to approve the budget.", Block.Text.Kind.PARAGRAPH),
        text("Opponents argued the plan was optimistic.", Block.Text.Kind.PARAGRAPH),
    )

    @Test
    fun `a body without its headline gets one, so back-to-back stories are announced`() {
        val blocks = NewsRepository.withHeadline(story, "Council approves budget", Locale.ENGLISH)
        val first = blocks.first() as Block.Text
        assertEquals("Council approves budget", first.text)
        assertEquals(Block.Text.Kind.HEADING_1, first.kind)
        assertTrue("the sentence spans must be there to highlight it", first.sentences.isNotEmpty())
        assertEquals(story, blocks.drop(1))
    }

    @Test
    fun `a body that already opens with the headline is left alone`() {
        val withHeading = listOf<Block>(text("Council approves budget", Block.Text.Kind.HEADING_1)) + story
        assertEquals(withHeading, NewsRepository.withHeadline(withHeading, "Council approves budget", Locale.ENGLISH))
    }

    @Test
    fun `the headline is recognised despite punctuation, case and a site name`() {
        // A kicker heading before it, curly quotes, and the publisher's name on the feed title.
        val withHeading = listOf<Block>(
            text("POLITICS", Block.Text.Kind.HEADING_3),
            text("Council approves ‘budget’", Block.Text.Kind.HEADING_1),
        ) + story
        assertEquals(withHeading, NewsRepository.withHeadline(withHeading, "Council approves 'budget' | Example Times", Locale.ENGLISH))
    }

    @Test
    fun `a different heading doesn't count as the headline`() {
        val withOtherHeading = listOf<Block>(text("Background", Block.Text.Kind.HEADING_2)) + story
        val blocks = NewsRepository.withHeadline(withOtherHeading, "Council approves budget", Locale.ENGLISH)
        assertEquals("Council approves budget", (blocks.first() as Block.Text).text)
        assertEquals(withOtherHeading.size + 1, blocks.size)
    }

    @Test
    fun `a story with no title gets no empty heading`() {
        assertEquals(story, NewsRepository.withHeadline(story, "  ", Locale.ENGLISH))
    }

    private fun item(title: String, vararg categories: String) =
        FeedItem(title, "https://example.com/x", null, null, 0, "x", categories = categories.toList())

    @Test
    fun `shopping and paid posts are recognised by their headline`() {
        // Shaped like the shopping posts in Wired's, Tecnoblog's and Olhar Digital's feeds; the
        // first, third and fourth are taken from them as they were.
        listOf(
            "Columbia Promo Codes: 15% Off | October 2026",
            "Best Buy Coupon: 20% Off Laptops",
            "iPhone 17 (512 GB) com Apple A19 baixa de preço com cupom",
            "Galaxy S25 (256 GB) com 12 GB de RAM tem maior desconto com cupom",
            "Ofertas do dia: Echo Dot e Kindle com até 40% de desconto",
            "Oferta do dia: SSD de 1 TB pelo menor preço",
            "Conteúdo patrocinado: como a nuvem acelera o varejo",
        ).forEach { assertTrue(it, NewsRepository.isAd(item(it))) }
    }

    @Test
    fun `shopping and paid posts are recognised by their category`() {
        assertTrue(NewsRepository.isAd(item("Columbia jackets, compared", "Gear", "Gear / Deals")))
        assertTrue(NewsRepository.isAd(item("31 Best STEM Toys for Kids (2026)", "Gear", "Gear / Buying Guides")))
        assertTrue(NewsRepository.isAd(item("Making AI an asset, not an expense", "Artificial intelligence", "sponsored")))
        assertTrue(NewsRepository.isAd(item("Os melhores fones de 2026", "Guia de Compras")))
    }

    @Test
    fun `news about deals and prices is still news`() {
        // The first two are from Canaltech's feed; the rest are the kind of headline a looser
        // match on "deal" or "preço" would wrongly catch.
        listOf(
            "SUV da Kia em promoção sai mais barato que rivais de BYD e GWM",
            "Galaxy S26 fica mais caro de novo em meio à crise das memórias",
            "EU and US strike trade deal on steel tariffs",
            "Preço da gasolina sobe pela terceira semana seguida",
            "Microsoft closes its deal for the game studio",
        ).forEach { assertFalse(it, NewsRepository.isAd(item(it, "Business", "Business / Big Tech"))) }
        assertFalse(NewsRepository.isAd(item("Antitrust ruling reshapes app stores", "Mergers and deals", "Policy")))
    }
}
