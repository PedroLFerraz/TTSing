package com.pedrolopes.ttsing.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.data.news.ArticleImageStore
import com.pedrolopes.ttsing.data.news.CatalogFeed
import com.pedrolopes.ttsing.data.news.FeedCatalog
import com.pedrolopes.ttsing.data.news.NewsRepository
import com.pedrolopes.ttsing.data.news.RefreshResult
import com.pedrolopes.ttsing.data.news.Topic
import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import com.pedrolopes.ttsing.data.news.db.FeedEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

    /** A "Refresh all" is running. Adding a feed is independent of it, see [add]. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The add-feed dialog's state: [checking] the address, or [error], why it didn't work. */
    data class AddState(val checking: Boolean = false, val error: String? = null)

    private val _add = MutableStateFlow(AddState())
    val add: StateFlow<AddState> = _add.asStateFlow()
    private var addJob: Job? = null

    /**
     * Checks and subscribes to [url] while the dialog stays open. [onDone] runs when the dialog
     * can close: the feed was added, or was already there. A failure is left in [add] for the
     * dialog to show next to the address, which stays as typed. Works during a refresh.
     */
    fun addFeed(url: String, onDone: () -> Unit) {
        if (_add.value.checking) return
        _add.value = AddState(checking = true)
        addJob = viewModelScope.launch {
            try {
                when (val result = news.addFeed(url)) {
                    is NewsRepository.AddResult.Added -> {
                        _message.value = "Added ${result.feed.title} · ${result.articleCount} stories"
                        _add.value = AddState()
                        onDone()
                    }
                    is NewsRepository.AddResult.AlreadySubscribed -> {
                        _message.value = "Already subscribed to ${result.feed.title}"
                        _add.value = AddState()
                        onDone()
                    }
                    is NewsRepository.AddResult.Failed -> _add.value = AddState(error = result.message)
                }
            } finally {
                if (_add.value.checking) _add.value = AddState()
            }
        }
    }

    /** The dialog was closed: stop checking, forget the error. */
    fun cancelAdd() {
        addJob?.cancel()
        _add.value = AddState()
    }

    fun clearAddError() {
        _add.update { it.copy(error = null) }
    }

    fun refreshAll() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                _message.value = news.refreshAll().describe()
            } finally {
                _busy.value = false
            }
        }
    }

    fun removeFeed(url: String) {
        viewModelScope.launch { news.removeFeed(url) }
    }

    fun setGroup(url: String, group: String?) {
        viewModelScope.launch { news.setGroup(url, group) }
    }

    fun createGroup(name: String, feedUrls: Set<String>) {
        viewModelScope.launch { news.createGroup(name, feedUrls) }
    }

    fun renameGroup(from: String, to: String) {
        viewModelScope.launch { news.renameGroup(from, to) }
    }

    fun deleteGroup(group: String) {
        viewModelScope.launch { news.deleteGroup(group) }
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
 * maintaining a near-duplicate for each. Only the all-feeds view filters by group.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticlesViewModel(
    private val feedUrl: String?,
    private val news: NewsRepository,
    private val images: ArticleImageStore,
) : ViewModel() {

    private val _group = MutableStateFlow<String?>(null)
    /** The group the all-feeds list is narrowed to, or null for every story. */
    val group: StateFlow<String?> = _group.asStateFlow()

    val articles: StateFlow<List<ArticleEntity>> =
        (
            if (feedUrl != null) {
                news.observeArticles(feedUrl)
            } else {
                _group.flatMapLatest { group -> if (group == null) news.observeLatest() else news.observeLatestInGroup(group) }
            }
            )
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val feeds: StateFlow<List<FeedEntity>> =
        news.observeFeeds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The groups the user has filed feeds under, one filter chip each. */
    val groups: StateFlow<List<String>> =
        news.observeFeeds()
            .map { feeds -> groupsOf(feeds) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastRefresh = MutableStateFlow<RefreshResult?>(null)
    /** How the latest refresh went, so an empty list can say "couldn't connect" rather than "no stories". */
    val lastRefresh: StateFlow<RefreshResult?> = _lastRefresh.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** A refresh that couldn't reach some feeds, to tell the user once. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _feedTitle = MutableStateFlow("")
    val feedTitle: StateFlow<String> = _feedTitle.asStateFlow()

    init {
        if (feedUrl != null) {
            viewModelScope.launch { _feedTitle.value = news.feed(feedUrl)?.title.orEmpty() }
        }
        // A group that disappears (its last feed moved or the group deleted) takes the filter
        // with it; a group that was never there yet (just created) is left alone.
        viewModelScope.launch {
            var previous = emptyList<String>()
            news.observeFeeds().map { groupsOf(it) }.collect { current ->
                _group.update { groupAfterChange(it, previous, current) }
                previous = current
            }
        }
        refresh()
    }

    fun selectGroup(group: String?) {
        _group.value = group
    }

    /** Makes the group and shows it straight away. */
    fun createGroup(name: String, feedUrls: Set<String>) {
        viewModelScope.launch {
            _group.value = news.createGroup(name, feedUrls) ?: return@launch
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                val result = if (feedUrl != null) news.refresh(feedUrl) else news.refreshAll()
                _lastRefresh.value = result
                if (result.failedFeeds > 0) _message.value = result.describe()
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Marks every story in the list as it is now filtered (a group, or one feed) read. */
    fun markAllRead() {
        val ids = articles.value.map { it.id }
        viewModelScope.launch { news.setRead(ids, true) }
    }

    fun setRead(articleId: String, read: Boolean) {
        viewModelScope.launch { news.setRead(listOf(articleId), read) }
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

/** The distinct groups among [feeds], alphabetically; "science" and "Science" are one group. */
fun groupsOf(feeds: List<FeedEntity>): List<String> =
    feeds.mapNotNull { it.folder }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)

/**
 * The group the list should be filtered by after the groups went from [previous] to [current]:
 * [selected], unless it was among [previous] and is gone from [current]. A group not in
 * [previous] yet (one just created, whose feeds haven't been filed in the list yet) is not
 * dropped for being absent.
 */
internal fun groupAfterChange(selected: String?, previous: List<String>, current: List<String>): String? =
    if (selected != null &&
        previous.any { it.equals(selected, ignoreCase = true) } &&
        current.none { it.equals(selected, ignoreCase = true) }
    ) {
        null
    } else {
        selected
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
                is NewsRepository.AddResult.AlreadySubscribed -> "Already subscribed to ${result.feed.title}"
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
                    is NewsRepository.AddResult.AlreadySubscribed -> Unit
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
