# Plan: whole articles, and a clean topic list

Status: research done 2026-10-02, not started. One coding session.
Audit script and raw output for the catalogue: `docs/plans/evidence/catalog-audit*`.
**Builds on the `news-topics` branch** (catalogue, Discover, topic chips, auto-continue). It was uncommitted when this was written, so commit it first.

## Problem 1: articles show only their first paragraph

### Root cause: our own bug, reproduced

`NewsRepository.loadBody` stores the extracted body (`ExtractedArticle.contentHtml` = `root.html()`) and, on every open, runs `ArticleExtractor.extract()` **again** on that stored HTML. The second pass re-scores the already-trimmed body and often keeps only one sub-container. Measured on live pages (chars of text):

| Article | First pass (stored) | Re-extracted (what the reader shows) |
|---|---|---|
| g1 Política | 4921 / 5102 / 7901 | **299 / 362 / 438** |
| The Verge | 3114 / 2520 / 2038 | **534 / 595 / 458** |
| Canaltech | 1896 / 2109 / 2309 | **238 / 266 / 250** |
| Wired | 12918 / 6047 / 4724 | 1386 / 2781 / 1447 |
| Folha Poder | 3020 / 3142 | 1626 / 1748 |
| BBC | 5010 / 5467 | 2497 / 3567 |

- **The stored HTML is complete.** Wrapping it in `<article>` before re-extracting restores the full length in every case, so only the second scoring pass loses text.
- **The live check misses it.** `FeedCatalogLiveCheck` only measures the first pass.
- **It isn't new.** The same double extraction existed on `master` (`NewsRepository.kt:221`).
- **Evidence:** the measured outputs are in `docs/plans/evidence/rss-reextraction-results*.txt`.

### What other readers do (no magic)

- **Feeder** (`app/.../model/FullTextParser.kt`) and **Read You** (`infrastructure/html/Readability.kt`) fetch the page and run Readability4J.
- **Miniflux** uses per-domain CSS rules (about 50), else a Readability port.
- **NetNewsWire, Feedbin and NewsBlur** use Mercury Parser.
- **Inoreader's** "load full content" fetches the page.
- **Feedly** is closed; its API has `fullContent`, but the engine is undocumented.

In short: fetch the page, run a Readability-style extractor, optionally add per-site rules. Our extractor already matched or beat Readability4J on about 20 of 26 live pages. **We don't need a new library; we need to stop re-extracting.**

### Fix

1. **`ArticleExtractor.kt`.** Add `blocksOf(contentHtml: String, baseUri: String, locale: Locale): List<Block>`. It parses the stored body and calls `walk()` on its `body()` directly, with **no `findContentRoot`, no junk scoring**. Keep `extract()` for raw pages.
2. **`NewsRepository.loadBody`.** Replace `ArticleExtractor.extract(stored, article.link, locale).blocks` with `ArticleExtractor.blocksOf(stored, article.link, locale)`. That is the only place stored HTML is read back. The reader and the playback service both go through `body()`, so they keep getting identical blocks.
   - **No DB migration.** Stored rows already hold `root.html()`.
   - **Check `storeItems`.** Inline feed bodies are stored as `extracted.contentHtml` too, so they're covered.
3. **Unit test** (`ArticleExtractorTest`). For the realistic pages in the test, and a g1-shaped page with a nested `<article>`, the text of `blocksOf(extract(html).contentHtml)` must equal `extract(html).blocks`.
4. **Live check.** In `FeedCatalogLiveCheck.readableLengths`, add a third number: the reopen length, `blocksOf(extract(page).contentHtml)`.
   - **Acceptance:** reopen ≥ 98% of first pass for every sample.
   - **Floors:** g1 ≥ 4500, The Verge ≥ 2000, Canaltech ≥ 1800, Wired ≥ 4000, Folha ≥ 3000.
5. **Optional, only if gaps remain after the fix.** Add a Readability4J fallback when our first pass is under `MIN_FULL_TEXT`: `net.dankito.readability4j:readability4j:1.0.8` (Apache-2.0, 80 KB, last release 2021, works with our jsoup; add `slf4j-nop`). Per-site rules from `fivefilters/ftr-site-config` (CC0): not now.

### Sites that block page fetches: leave as teasers

- **Ars Technica** returns 405.
- **Politico, The Atlantic, Intercept Brasil and NYT** return 403.
- Changing the user agent doesn't help, and we won't spoof crawlers or bypass paywalls. Politico, The Atlantic and Intercept ship full text in the feed itself, so they still read fully.

## Problem 2: topic list has ads and propaganda

### Drop

| Feed | Why |
|---|---|
| Wired homepage feed | 28 of 50 items are coupons, promo codes and buying guides. Replace with the three section feeds below. |
| Tecnoblog `/feed/` | 13 of 50 are deal posts ("cupom", "% OFF"). Replace with `/noticias/feed/` (0 of 50). |
| Olhar Digital | Affiliate "Ofertas do dia" posts, plus off-topic football and weather. |
| MacMagazine | App Store promos, and pages give only ~400–500 chars. |
| Engadget | Gadget and deal coverage, overlapping The Verge. |
| Lenny's, ByteByteGo, Not Boring, Marcus on AI | Not news (product advice, diagrams, VC essays). |
| Stratechery | Not news, and teaser-only. |
| Fox News Politics | MBFC Mixed factuality. |
| NY Post Politics | MBFC Mixed factuality; ~150–200 chars per item. |
| NYT Politics, WaPo Politics | 0 chars, blocked. |
| Reason | Junk items (titles that are image filenames, "Open Thread"). |
| Racket News, The Weekly Dish, The Bulwark | Teasers or single-voice opinion. |
| CartaCapital | MBFC Left, Mostly Factual, advocacy framing. |
| Gazeta do Povo | No reliable rating, partisan-leaning analysis. |
| Política Global | Paywalled (107–630 chars). |
| O Insight | Stale: last post April 2024. |

### New catalogue

All 0 ads unless noted. **Text:** Y full, P partial (paid posts are teasers), T teaser-only. **†** means the rating is from memory; re-check it.

**Technology, English, outlets**

| Name | Feed URL | Text |
|---|---|---|
| The Verge | `https://www.theverge.com/rss/index.xml` | Y |
| TechCrunch | `https://techcrunch.com/feed/` | Y |
| Wired Business | `https://www.wired.com/feed/category/business/latest/rss` | Y |
| Wired Security | `https://www.wired.com/feed/category/security/latest/rss` | Y |
| Wired Science | `https://www.wired.com/feed/category/science/latest/rss` | Y |
| MIT Technology Review | `https://www.technologyreview.com/feed/` | Y (filter "sponsored") |
| The Register | `https://www.theregister.com/headlines.atom` | Y |
| Rest of World | `https://restofworld.org/feed/latest` | Y |
| 404 Media | `https://www.404media.co/rss/` | Y (free posts) |
| BBC Technology | `https://feeds.bbci.co.uk/news/technology/rss.xml` | Y |
| Ars Technica | `https://feeds.arstechnica.com/arstechnica/index` | T (keep flagged, or drop) |

**Technology, English, newsletters**

| Name | Feed URL | Text |
|---|---|---|
| Platformer | `https://www.platformer.news/rss/` | P |
| Big Technology | `https://www.bigtechnology.com/feed` | Y |
| Benedict Evans | `https://www.ben-evans.com/benedictevans?format=rss` | Y |
| One Useful Thing | `https://www.oneusefulthing.org/feed` | Y |
| Import AI | `https://importai.substack.com/feed` | Y |
| The Pragmatic Engineer | `https://newsletter.pragmaticengineer.com/feed` | P |

**Technology, Portuguese, outlets**

| Name | Feed URL | Text |
|---|---|---|
| Tecnoblog Notícias | `https://tecnoblog.net/noticias/feed/` | Y |
| Canaltech | `https://feeds.feedburner.com/canaltechbr` | Y (1 of 50 ads; filter) |
| g1 Tecnologia | `https://g1.globo.com/rss/g1/tecnologia/` | Y |
| Folha Tec | `https://feeds.folha.uol.com.br/tec/rss091.xml` | Y |
| Estadão Tecnologia | `https://www.estadao.com.br/arc/outboundfeeds/feeds/rss/sections/economia/tecnologia/` | Y |
| Meio Bit | `https://meiobit.com/feed/` | Y |
| Mobile Time | `https://www.mobiletime.com.br/feed/` | Y |

**Technology, Portuguese, newsletters:** Manual do Usuário `https://manualdousuario.net/feed/` (P), Núcleo `https://nucleo.jor.br/feed/` (Y). Only two are worth keeping.

**Politics, English, outlets**

| Name | Feed URL | Rating |
|---|---|---|
| NPR Politics | `https://feeds.npr.org/1014/rss.xml` | MBFC Left-Center, High |
| PBS NewsHour Politics | `https://www.pbs.org/newshour/feeds/rss/politics` | MBFC Left-Center, High |
| BBC Politics (UK) | `https://feeds.bbci.co.uk/news/politics/rss.xml` | MBFC Least Biased |
| BBC US & Canada | `https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml` | MBFC Least Biased |
| The Guardian, US politics | `https://www.theguardian.com/us-news/us-politics/rss` | MBFC Left-Center, High |
| Politico, Congress | `https://rss.politico.com/congress.xml` | † (full text in feed) |
| Roll Call | `https://rollcall.com/feed/` | Least Biased, High † |
| ABC News Politics | `https://abcnews.go.com/abcnews/politicsheadlines` | † |

Optional: CBS `https://www.cbsnews.com/latest/rss/politics`, NBC `https://feeds.nbcnews.com/nbcnews/public/politics`, Christian Science Monitor `https://rss.csmonitor.com/feeds/politics`, Vox, The Atlantic.

**Politics, English, newsletters**

| Name | Feed URL | Text / lean |
|---|---|---|
| Tangle | `https://www.readtangle.com/archive/rss/` | Y, both sides |
| Silver Bulletin | `https://www.natesilver.net/feed` | Y |
| Persuasion | `https://www.persuasion.community/feed` | Y, centrist |
| Slow Boring | `https://www.slowboring.com/feed` | P |
| The Dispatch | `https://thedispatch.com/feed/` | P, MBFC Right-Center, High |
| The Free Press | `https://www.thefp.com/feed` | P, center-right |

Clean right-of-center sources readable over plain HTTP are thin: Washington Examiner and Washington Times return 403, and National Review is teaser-only.

**Politics, Portuguese, outlets**

| Name | Feed URL | Text |
|---|---|---|
| g1 Política | `https://g1.globo.com/rss/g1/politica/` | Y |
| Folha Poder | `https://feeds.folha.uol.com.br/poder/rss091.xml` | Y |
| Estadão Política | `https://www.estadao.com.br/arc/outboundfeeds/feeds/rss/sections/politica/` | Y |
| BBC News Brasil | `https://feeds.bbci.co.uk/portuguese/rss.xml` | Y |
| Agência Brasil Política | `https://agenciabrasil.ebc.com.br/rss/politica/feed.xml` | Y (public broadcaster) |
| Poder360 | `https://www.poder360.com.br/feed/` | Y |
| Congresso em Foco | `https://congressoemfoco.uol.com.br/feed/` | Y |
| Intercept Brasil | `https://www.intercept.com.br/feed/` | Y (in feed) |

Optional: Veja Política `https://veja.abril.com.br/politica/feed/`, which would add a right-of-center voice; its rating is unverified.

**Politics, Portuguese, second group:** Agência Pública `https://apublica.org/feed/`, piauí `https://piaui.folha.uol.com.br/feed/`, Aos Fatos `https://www.aosfatos.org/noticias/feed/`, Lupa `https://lupa.news/feed/`. These are independent outlets and fact-checkers rather than newsletters. Rename `SourceKind.NEWSLETTER`'s label to something like "Independent" so it fits both languages.

### Ad filter (cheap, in `NewsRepository.storeItems`)

Skip an item when:
1. **Category** (`<category>`, which `RssParser` would need to read) matches `coupons?|deals?|sponsored|webinar|whitepaper|guia de compras|buying guides?`, or
2. **Title** matches `promo codes?|coupons?|cupom|oferta do dia|daily deal|patrocinado|publieditorial`. Don't match bare "deal" or "preço"; they hit real news.

Put the regexes in `FeedCatalog`/`NewsRepository` companion and unit-test them with real titles.

### Steps

1. **`FeedCatalog.kt`.** Replace the lists with the tables above, keeping `teaserOnly` where Text is T, and rename the second group's label.
2. **`RssParser`.** Parse `<category>` into `FeedItem.categories`, then add the ad filter.
3. **`FeedCatalogTest`.** Should still pass: no duplicates, every group non-empty.
4. **Live check.** Run `TTSING_LIVE_FEEDS=1 ./gradlew testDebugUnitTest --tests "*FeedCatalogLiveCheck*"`. Every non-teaser feed must pass, including the new reopen-length column from Problem 1.
5. **Existing subscribers.** Feeds that leave the catalogue just stop being suggested. Users who subscribed to them keep them, which is fine.

## Order for the session

1. Problem 1 fix, its test, and the live-check column.
2. Run the live check.
3. Catalogue and filter.
4. Run the live check again.
5. Emulator check: open 5 stories from g1, Verge, Canaltech, Folha and BBC. Page count and "min left" should match the article, and the text should reach the end.
6. Refresh `TTSing.apk` (`build-apk.bat`).
