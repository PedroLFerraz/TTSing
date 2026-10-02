package com.pedrolopes.ttsing.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.data.news.ArticleImageStore
import com.pedrolopes.ttsing.data.news.CatalogFeed
import com.pedrolopes.ttsing.data.news.FeedCatalog
import com.pedrolopes.ttsing.data.news.NewsRepository
import com.pedrolopes.ttsing.data.news.Topic
import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import com.pedrolopes.ttsing.data.news.db.FeedEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class FeedsViewModel(private val news: NewsRepository) : ViewModel() {

    val feeds: StateFlow<List<FeedEntity>> =
        news.observeFeeds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun addFeed(url: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _message.value = when (val result = news.addFeed(url)) {
                is NewsRepository.AddResult.Added ->
                    "Added ${result.feed.title} · ${result.articleCount} stories"
                is NewsRepository.AddResult.Failed -> result.message
            }
            _busy.value = false
        }
    }

    fun refreshAll() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val added = news.refreshAll()
            _message.value = if (added == 0) "No new stories" else "$added new stories"
            _busy.value = false
        }
    }

    fun removeFeed(url: String) {
        viewModelScope.launch { news.removeFeed(url) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun create(): FeedsViewModel = FeedsViewModel(TTSingApp.instance.news)
    }
}

/**
 * Backs both the "News" button's direct, all-feeds view and a single feed's article list.
 * [feedUrl] is null for the former, so the two share one screen and one code path rather than
 * maintaining a near-duplicate for each. Only the all-feeds view filters by topic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticlesViewModel(
    private val feedUrl: String?,
    private val news: NewsRepository,
    private val images: ArticleImageStore,
) : ViewModel() {

    private val _topic = MutableStateFlow<Topic?>(null)
    /** The topic the all-feeds list is narrowed to, or null for every story. */
    val topic: StateFlow<Topic?> = _topic.asStateFlow()

    val articles: StateFlow<List<ArticleEntity>> =
        (
            if (feedUrl != null) {
                news.observeArticles(feedUrl)
            } else {
                _topic.flatMapLatest { topic -> if (topic == null) news.observeLatest() else news.observeLatestInTopic(topic) }
            }
            )
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val feeds: StateFlow<List<FeedEntity>> =
        news.observeFeeds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Topics with at least one subscribed feed — the only ones worth a filter chip. */
    val topics: StateFlow<List<Topic>> =
        news.observeFeeds()
            .map { feeds -> Topic.entries.filter { topic -> feeds.any { it.topic == topic.id } } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _feedTitle = MutableStateFlow("")
    val feedTitle: StateFlow<String> = _feedTitle.asStateFlow()

    init {
        if (feedUrl != null) {
            viewModelScope.launch { _feedTitle.value = news.feed(feedUrl)?.title.orEmpty() }
        }
        refresh()
    }

    fun selectTopic(topic: Topic?) {
        _topic.value = topic
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            if (feedUrl != null) news.refresh(feedUrl) else news.refreshAll()
            _refreshing.value = false
        }
    }

    /**
     * Remembers this list, in the order shown, as what to play on into when the story being
     * opened finishes.
     */
    fun onOpen() {
        news.queue.set(articles.value.map { it.id })
    }

    /** Article thumbnails, downloaded once and cached — the same store the reader uses. */
    suspend fun imageBytes(url: String): ByteArray? = images.bytes(url)

    companion object {
        fun create(feedUrl: String?): ArticlesViewModel {
            val app = TTSingApp.instance
            return ArticlesViewModel(feedUrl, app.news, ArticleImageStore(app))
        }
    }
}

/** The topic catalogue: the biggest sources per topic, one tap to subscribe. */
class DiscoverViewModel(private val news: NewsRepository) : ViewModel() {

    private val _topic = MutableStateFlow(Topic.entries.first())
    val topic: StateFlow<Topic> = _topic.asStateFlow()

    private val _language = MutableStateFlow(defaultLanguage())
    val language: StateFlow<String> = _language.asStateFlow()

    /** Subscribed feeds by catalogue URL, so a catalogue row can find the feed it became. */
    val subscribed: StateFlow<Map<String, FeedEntity>> =
        news.observeFeeds()
            .map { feeds -> feeds.mapNotNull { feed -> FeedCatalog.find(feed.url)?.let { it.url to feed } }.toMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    /** Catalogue URLs being checked and subscribed right now. */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectTopic(topic: Topic) {
        _topic.value = topic
    }

    fun selectLanguage(language: String) {
        _language.value = language
    }

    fun add(feed: CatalogFeed) {
        if (feed.url in _pending.value || feed.url in subscribed.value) return
        viewModelScope.launch {
            _message.value = when (val result = subscribe(feed)) {
                is NewsRepository.AddResult.Added -> "Added ${feed.title} · ${result.articleCount} stories"
                is NewsRepository.AddResult.Failed -> "${feed.title}: ${result.message}"
            }
        }
    }

    /**
     * Subscribes to every feed in [feeds] not already followed, one after another — each one
     * is fetched to check it, and a dozen at once would stall on a slow connection.
     */
    fun addAll(feeds: List<CatalogFeed>) {
        val todo = feeds.filter { it.url !in subscribed.value && it.url !in _pending.value }
        if (todo.isEmpty()) return
        _pending.update { it + todo.map(CatalogFeed::url) }
        viewModelScope.launch {
            var added = 0
            val failed = mutableListOf<String>()
            for (feed in todo) {
                when (subscribe(feed)) {
                    is NewsRepository.AddResult.Added -> added++
                    is NewsRepository.AddResult.Failed -> failed += feed.title
                }
            }
            _message.value = buildString {
                append("Added $added ${if (added == 1) "feed" else "feeds"}")
                if (failed.isNotEmpty()) append(" · couldn't load ${failed.joinToString()}")
            }
        }
    }

    private suspend fun subscribe(feed: CatalogFeed): NewsRepository.AddResult {
        _pending.update { it + feed.url }
        return try {
            news.addFeed(feed.url, feed.topic)
        } finally {
            _pending.update { it - feed.url }
        }
    }

    fun remove(feed: CatalogFeed) {
        val stored = subscribed.value[feed.url] ?: return
        viewModelScope.launch { news.removeFeed(stored.url) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun create(): DiscoverViewModel = DiscoverViewModel(TTSingApp.instance.news)

        /** The device's language when the catalogue has sources in it, else English. */
        private fun defaultLanguage(): String {
            val device = Locale.getDefault().language
            return FeedCatalog.languages.firstOrNull { Locale.forLanguageTag(it).language == device }
                ?: FeedCatalog.languages.first()
        }
    }
}
