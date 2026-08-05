package com.pedrolopes.ttsing.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.data.news.ArticleImageStore
import com.pedrolopes.ttsing.data.news.NewsRepository
import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import com.pedrolopes.ttsing.data.news.db.FeedEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 * maintaining a near-duplicate for each.
 */
class ArticlesViewModel(
    private val feedUrl: String?,
    private val news: NewsRepository,
    private val images: ArticleImageStore,
) : ViewModel() {

    val articles: StateFlow<List<ArticleEntity>> =
        (if (feedUrl != null) news.observeArticles(feedUrl) else news.observeLatest())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val feeds: StateFlow<List<FeedEntity>> =
        news.observeFeeds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            if (feedUrl != null) news.refresh(feedUrl) else news.refreshAll()
            _refreshing.value = false
        }
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
