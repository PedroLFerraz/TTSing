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
 * A big newsroom, or a smaller independent source: a writer's newsletter, a nonprofit
 * newsroom, a fact-checker. Shown as separate groups because they listen differently: outlets
 * publish many short stories a day, independents fewer and longer pieces.
 */
enum class SourceKind(val label: String) {
    OUTLET("Outlets"),
    NEWSLETTER("Independent"),
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
 * there is nothing in them to read aloud. So are feeds that are mostly deals and coupons
 * (Wired is listed by section for this reason), advice and essays rather than news, and
 * outlets rated low on factual reporting. Politics groups mix the political spectrum.
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
            add("TechCrunch", "https://techcrunch.com/feed/", "Startups, venture capital and big tech news")
            add("Wired Business", "https://www.wired.com/feed/category/business/latest/rss", "Big tech, AI and the tech economy, from Wired")
            add("Wired Security", "https://www.wired.com/feed/category/security/latest/rss", "Hacks, surveillance and privacy, from Wired")
            add("Wired Science", "https://www.wired.com/feed/category/science/latest/rss", "Science and health, from Wired")
            add("MIT Technology Review", "https://www.technologyreview.com/feed/", "Emerging technology and AI, from MIT")
            add("The Register", "https://www.theregister.com/headlines.atom", "Enterprise IT, security and software, with attitude")
            add("Rest of World", "https://restofworld.org/feed/latest", "Technology's impact beyond the Western bubble")
            add("404 Media", "https://www.404media.co/rss/", "Independent reporting on how technology shapes the world")
            add("BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml", "Technology news from the BBC")
            add("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "In-depth technology, science and policy reporting", teaserOnly = true)
        }
        tech(SourceKind.NEWSLETTER, "en") {
            add("Platformer", "https://www.platformer.news/rss/", "Big tech and democracy — Casey Newton")
            add("Big Technology", "https://www.bigtechnology.com/feed", "The tech giants and AI — Alex Kantrowitz")
            add("Benedict Evans", "https://www.ben-evans.com/benedictevans?format=rss", "Where tech is heading — Benedict Evans")
            add("One Useful Thing", "https://www.oneusefulthing.org/feed", "Living and working with AI — Ethan Mollick")
            add("Import AI", "https://importai.substack.com/feed", "AI research and what it means — Jack Clark")
            add("The Pragmatic Engineer", "https://newsletter.pragmaticengineer.com/feed", "Big tech and startups, from the inside — Gergely Orosz")
        }

        // ---- Technology · Português ----
        tech(SourceKind.OUTLET, "pt-BR") {
            add("Tecnoblog Notícias", "https://tecnoblog.net/noticias/feed/", "Notícias de tecnologia do Tecnoblog")
            add("Canaltech", "https://feeds.feedburner.com/canaltechbr", "Tecnologia, ciência e games")
            add("g1 Tecnologia", "https://g1.globo.com/rss/g1/tecnologia/", "A editoria de tecnologia do g1")
            add("Folha Tec", "https://feeds.folha.uol.com.br/tec/rss091.xml", "A editoria de tecnologia da Folha de S.Paulo")
            add("Estadão Tecnologia", "https://www.estadao.com.br/arc/outboundfeeds/feeds/rss/sections/economia/tecnologia/", "A editoria de tecnologia do Estadão")
            add("Meio Bit", "https://meiobit.com/feed/", "Tecnologia, ciência e cultura geek")
            add("Mobile Time", "https://www.mobiletime.com.br/feed/", "Telecomunicações, mobilidade e o mercado digital")
        }
        tech(SourceKind.NEWSLETTER, "pt-BR") {
            add("Manual do Usuário", "https://manualdousuario.net/feed/", "Tecnologia com olhar crítico — Rodrigo Ghedin")
            add("Núcleo", "https://nucleo.jor.br/feed/", "Jornalismo sobre internet, tecnologia e redes")
        }

        // ---- Politics · English ----
        politics(SourceKind.OUTLET, "en") {
            add("NPR Politics", "https://feeds.npr.org/1014/rss.xml", "US politics from National Public Radio")
            add("PBS NewsHour Politics", "https://www.pbs.org/newshour/feeds/rss/politics", "US politics from PBS NewsHour")
            add("BBC Politics", "https://feeds.bbci.co.uk/news/politics/rss.xml", "UK politics from the BBC")
            add("BBC US & Canada", "https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml", "North American news from the BBC")
            add("The Guardian · US politics", "https://www.theguardian.com/us-news/us-politics/rss", "US politics from The Guardian")
            add("Politico · Congress", "https://rss.politico.com/congress.xml", "Capitol Hill politics and policy")
            add("Roll Call", "https://rollcall.com/feed/", "Congress, campaigns and policy, since 1955")
            add("ABC News Politics", "https://abcnews.go.com/abcnews/politicsheadlines", "US politics from ABC News")
        }
        politics(SourceKind.NEWSLETTER, "en") {
            add("Tangle", "https://www.readtangle.com/archive/rss/", "One story a day, with what the left and the right say about it")
            add("Silver Bulletin", "https://www.natesilver.net/feed", "Elections, polling and data — Nate Silver")
            add("Persuasion", "https://www.persuasion.community/feed", "Centrist essays on politics and free speech")
            add("Slow Boring", "https://www.slowboring.com/feed", "Policy and politics — Matthew Yglesias")
            add("The Dispatch", "https://thedispatch.com/feed/", "Fact-based conservative reporting and analysis")
            add("The Free Press", "https://www.thefp.com/feed", "Independent journalism — Bari Weiss")
        }

        // ---- Politics · Português ----
        politics(SourceKind.OUTLET, "pt-BR") {
            add("g1 Política", "https://g1.globo.com/rss/g1/politica/", "A editoria de política do g1")
            add("Folha Poder", "https://feeds.folha.uol.com.br/poder/rss091.xml", "Política na Folha de S.Paulo")
            add("Estadão Política", "https://www.estadao.com.br/arc/outboundfeeds/feeds/rss/sections/politica/", "Política no Estadão")
            add("BBC News Brasil", "https://feeds.bbci.co.uk/portuguese/rss.xml", "Brasil e mundo, da BBC em português")
            add("Agência Brasil Política", "https://agenciabrasil.ebc.com.br/rss/politica/feed.xml", "Política na agência pública de notícias")
            add("Poder360", "https://www.poder360.com.br/feed/", "Política e poder em Brasília")
            add("Congresso em Foco", "https://congressoemfoco.uol.com.br/feed/", "O dia a dia do Congresso Nacional")
            add("Intercept Brasil", "https://www.intercept.com.br/feed/", "Jornalismo investigativo")
        }
        politics(SourceKind.NEWSLETTER, "pt-BR") {
            add("Agência Pública", "https://apublica.org/feed/", "Jornalismo investigativo sem fins lucrativos")
            add("piauí", "https://piaui.folha.uol.com.br/feed/", "Reportagens longas sobre política e sociedade")
            add("Aos Fatos", "https://www.aosfatos.org/noticias/feed/", "Checagem de fatos e desinformação")
            add("Lupa", "https://lupa.news/feed/", "Checagem de fatos")
        }
    }

    private val byUrl: Map<String, CatalogFeed> by lazy { feeds.associateBy { key(it.url) } }

    /** The catalogue entry for [url], ignoring scheme and a trailing slash. */
    fun find(url: String): CatalogFeed? = byUrl[key(url)]

    fun group(topic: Topic, kind: SourceKind, language: String): List<CatalogFeed> =
        feeds.filter { it.topic == topic && it.kind == kind && it.language == language }

    private fun key(url: String): String = NewsRepository.feedKey(url)

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
