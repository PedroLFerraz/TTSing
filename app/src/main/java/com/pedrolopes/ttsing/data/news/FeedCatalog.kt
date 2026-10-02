package com.pedrolopes.ttsing.data.news

/** What a feed in the catalogue is about; stored on the feed so the News list can filter by it. */
enum class Topic(val id: String, val label: String) {
    TECHNOLOGY("technology", "Technology"),
    POLITICS("politics", "Politics"),
    ;

    companion object {
        fun fromId(id: String?): Topic? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A publication's newsroom, or one writer's newsletter. Shown as separate groups because they
 * listen differently: outlets publish many short stories a day, newsletters a few long essays
 * a week.
 */
enum class SourceKind(val label: String) {
    OUTLET("Outlets"),
    NEWSLETTER("Newsletters"),
}

data class CatalogFeed(
    val title: String,
    val url: String,
    val topic: Topic,
    val kind: SourceKind,
    /**
     * BCP-47 tag of the language the stories are written in. Overrides whatever the feed
     * itself declares: Substack, for one, labels every newsletter "en", which would have a
     * Portuguese newsletter read aloud by an English voice.
     */
    val language: String,
    /** One line on what the source is, for the Discover list. */
    val blurb: String,
    /**
     * Only a teaser can be read aloud for most stories: the full text is behind a paywall, or
     * the site refuses apps that fetch its pages. Still listed because these are among the
     * biggest sources for the topic, but flagged so nobody is surprised by a short story.
     */
    val teaserOnly: Boolean = false,
)

/**
 * The biggest sources per topic, so a reader can subscribe without hunting for feed URLs.
 *
 * Drawn from published rankings — Substack's paid leaderboards for newsletters, Feedspot's
 * topic rankings and the Plenary reader's curated OPML lists for outlets — then kept only if
 * the feed parses and its stories extract to real, readable text (checked by
 * FeedCatalogLiveCheck). Podcast, video and link-aggregator feeds are left out on purpose:
 * there is nothing in them to read aloud. Politics groups mix the political spectrum.
 *
 * Within a group, order is roughly by size, biggest first.
 */
object FeedCatalog {

    val languages: List<String> = listOf("en", "pt-BR")

    fun languageLabel(language: String): String = when (language) {
        "pt-BR" -> "Português"
        else -> "English"
    }

    val feeds: List<CatalogFeed> = buildList {
        // ---- Technology · English ----
        tech(SourceKind.OUTLET, "en") {
            add("The Verge", "https://www.theverge.com/rss/index.xml", "Consumer tech, gadgets and the companies behind them")
            add("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "In-depth technology, science and policy reporting", teaserOnly = true)
            add("TechCrunch", "https://techcrunch.com/feed/", "Startups, venture capital and big tech news")
            add("Wired", "https://www.wired.com/feed/rss", "How technology is changing culture, business and politics")
            add("Engadget", "https://www.engadget.com/rss.xml", "Gadget news and reviews")
            add("MIT Technology Review", "https://www.technologyreview.com/feed/", "Emerging technology and AI, from MIT")
            add("The Register", "https://www.theregister.com/headlines.atom", "Enterprise IT, security and software, with attitude")
            add("Rest of World", "https://restofworld.org/feed/latest", "Technology's impact beyond the Western bubble")
        }
        tech(SourceKind.NEWSLETTER, "en") {
            add("The Pragmatic Engineer", "https://newsletter.pragmaticengineer.com/feed", "Big tech and startups, from the inside — Gergely Orosz")
            add("Platformer", "https://www.platformer.news/rss/", "Big tech and democracy — Casey Newton")
            add("Exponential View", "https://www.exponentialview.co/feed", "AI and exponential technologies — Azeem Azhar")
            add("ByteByteGo", "https://blog.bytebytego.com/feed", "System design explained — Alex Xu")
            add("Lenny's Newsletter", "https://www.lennysnewsletter.com/feed", "Product, growth and careers — Lenny Rachitsky")
            add("Not Boring", "https://www.notboring.co/feed", "Tech strategy deep dives — Packy McCormick")
            add("One Useful Thing", "https://www.oneusefulthing.org/feed", "Living and working with AI — Ethan Mollick")
            add("Marcus on AI", "https://garymarcus.substack.com/feed", "A sceptic's view of the AI boom — Gary Marcus")
            add("Stratechery", "https://stratechery.com/feed/", "The business and strategy of tech — Ben Thompson", teaserOnly = true)
        }

        // ---- Technology · Português ----
        tech(SourceKind.OUTLET, "pt-BR") {
            add("Tecnoblog", "https://tecnoblog.net/feed/", "Notícias, análises e reviews de tecnologia")
            add("Canaltech", "https://feeds.feedburner.com/canaltechbr", "Tecnologia, ciência e games")
            add("Olhar Digital", "https://olhardigital.com.br/feed/", "Tecnologia, ciência e inovação")
            add("g1 Tecnologia", "https://g1.globo.com/rss/g1/tecnologia/", "A editoria de tecnologia do g1")
            add("MacMagazine", "https://macmagazine.com.br/feed/", "Tudo sobre a Apple")
            add("Folha Tec", "https://feeds.folha.uol.com.br/tec/rss091.xml", "A editoria de tecnologia da Folha de S.Paulo")
        }
        tech(SourceKind.NEWSLETTER, "pt-BR") {
            add("Manual do Usuário", "https://manualdousuario.net/feed/", "Tecnologia com olhar crítico — Rodrigo Ghedin")
            add("Núcleo", "https://nucleo.jor.br/feed/", "Jornalismo sobre internet, tecnologia e redes")
        }

        // ---- Politics · English ----
        politics(SourceKind.OUTLET, "en") {
            add("NPR Politics", "https://feeds.npr.org/1014/rss.xml", "US politics from National Public Radio")
            add("Politico · Congress", "https://rss.politico.com/congress.xml", "Capitol Hill politics and policy")
            add("Fox News Politics", "https://moxie.foxnews.com/google-publisher/politics.xml", "US politics from Fox News")
            add("BBC Politics", "https://feeds.bbci.co.uk/news/politics/rss.xml", "UK politics from the BBC")
            add("The Guardian · US politics", "https://www.theguardian.com/us-news/us-politics/rss", "US politics from The Guardian")
            add("New York Post Politics", "https://nypost.com/politics/feed/", "US politics from the New York Post")
            add("Vox Politics", "https://www.vox.com/rss/politics/index.xml", "Politics and policy, explained")
            add("The Atlantic · Politics", "https://www.theatlantic.com/feed/channel/politics/", "Political analysis and long reads")
            add("Reason", "https://reason.com/feed/", "Libertarian news and commentary")
            add("The New York Times · Politics", "https://rss.nytimes.com/services/xml/rss/nyt/Politics.xml", "US politics from the Times", teaserOnly = true)
            add("The Washington Post · Politics", "https://feeds.washingtonpost.com/rss/politics", "US politics from the Post", teaserOnly = true)
        }
        politics(SourceKind.NEWSLETTER, "en") {
            add("The Free Press", "https://www.thefp.com/feed", "Independent journalism — Bari Weiss")
            add("The Bulwark", "https://www.thebulwark.com/feed", "Centre-right, pro-democracy commentary")
            add("Letters from an American", "https://heathercoxrichardson.substack.com/feed", "Daily political history — Heather Cox Richardson")
            add("Silver Bulletin", "https://www.natesilver.net/feed", "Elections, polling and data — Nate Silver")
            add("Slow Boring", "https://www.slowboring.com/feed", "Policy and politics — Matthew Yglesias")
            add("Paul Krugman", "https://paulkrugman.substack.com/feed", "Economics and politics — Paul Krugman")
            add("The Dispatch", "https://thedispatch.com/feed/", "Fact-based conservative reporting and analysis", teaserOnly = true)
            add("Racket News", "https://www.racket.news/feed", "Media and politics — Matt Taibbi")
            add("The Weekly Dish", "https://andrewsullivan.substack.com/feed", "Politics and culture — Andrew Sullivan")
        }

        // ---- Politics · Português ----
        politics(SourceKind.OUTLET, "pt-BR") {
            add("g1 Política", "https://g1.globo.com/rss/g1/politica/", "A editoria de política do g1")
            add("Folha Poder", "https://feeds.folha.uol.com.br/poder/rss091.xml", "Política na Folha de S.Paulo")
            add("Poder360", "https://www.poder360.com.br/feed/", "Política e poder em Brasília")
            add("BBC News Brasil", "https://feeds.bbci.co.uk/portuguese/rss.xml", "Brasil e mundo, da BBC em português")
            add("Gazeta do Povo · República", "https://www.gazetadopovo.com.br/feed/rss/republica.xml", "Política na Gazeta do Povo")
            add("CartaCapital Política", "https://www.cartacapital.com.br/politica/feed/", "Política na CartaCapital")
            add("Congresso em Foco", "https://congressoemfoco.uol.com.br/feed/", "O dia a dia do Congresso Nacional")
            add("Intercept Brasil", "https://www.intercept.com.br/feed/", "Jornalismo investigativo")
            add("Estadão Política", "https://www.estadao.com.br/arc/outboundfeeds/feeds/rss/sections/politica/", "Política no Estadão")
        }
        politics(SourceKind.NEWSLETTER, "pt-BR") {
            add("Política Global", "https://oliverstuenkel.substack.com/feed", "Geopolítica e o Brasil no mundo — Oliver Stuenkel", teaserOnly = true)
            add("O Insight", "https://oinsight.substack.com/feed", "Política, tecnologia e negócios dos EUA com impacto no Brasil")
            add("Agência Pública", "https://apublica.org/feed/", "Jornalismo investigativo sem fins lucrativos")
            add("piauí", "https://piaui.folha.uol.com.br/feed/", "Reportagens longas sobre política e sociedade")
        }
    }

    private val byUrl: Map<String, CatalogFeed> by lazy { feeds.associateBy { key(it.url) } }

    /** The catalogue entry for [url], ignoring scheme and a trailing slash. */
    fun find(url: String): CatalogFeed? = byUrl[key(url)]

    fun group(topic: Topic, kind: SourceKind, language: String): List<CatalogFeed> =
        feeds.filter { it.topic == topic && it.kind == kind && it.language == language }

    private fun key(url: String): String =
        url.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .trimEnd('/')

    // ---- builders, so each group reads as a plain list ----

    private class GroupBuilder(
        private val into: MutableList<CatalogFeed>,
        private val topic: Topic,
        private val kind: SourceKind,
        private val language: String,
    ) {
        fun add(title: String, url: String, blurb: String, teaserOnly: Boolean = false) {
            into += CatalogFeed(title, url, topic, kind, language, blurb, teaserOnly)
        }
    }

    private fun MutableList<CatalogFeed>.tech(kind: SourceKind, language: String, block: GroupBuilder.() -> Unit) =
        GroupBuilder(this, Topic.TECHNOLOGY, kind, language).block()

    private fun MutableList<CatalogFeed>.politics(kind: SourceKind, language: String, block: GroupBuilder.() -> Unit) =
        GroupBuilder(this, Topic.POLITICS, kind, language).block()
}
