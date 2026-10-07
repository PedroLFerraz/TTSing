package com.pedrolopes.ttsing.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.data.news.YouTube
import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import com.pedrolopes.ttsing.ui.common.ChoiceChip
import com.pedrolopes.ttsing.ui.common.Hairline
import com.pedrolopes.ttsing.ui.common.HeaderIcon
import com.pedrolopes.ttsing.ui.common.LocalImage
import com.pedrolopes.ttsing.ui.common.MonoText
import com.pedrolopes.ttsing.ui.common.PillButton
import com.pedrolopes.ttsing.ui.common.ScreenHeader
import com.pedrolopes.ttsing.ui.common.ScreenPadding
import com.pedrolopes.ttsing.ui.common.StatusStrip
import com.pedrolopes.ttsing.ui.common.simpleFactory
import com.pedrolopes.ttsing.ui.theme.AppFonts
import com.pedrolopes.ttsing.ui.theme.Ink
import java.util.concurrent.TimeUnit

/**
 * The article list: either every story across every subscribed feed ([feedUrl] null — what
 * the library's News button opens directly onto), or one feed's own stories (reached from
 * "Manage feeds"). Sharing one screen keeps the row design and thumbnail handling in one place.
 * The all-feeds view can be narrowed to one topic, and names each story's source.
 */
@Composable
fun ArticlesScreen(
    feedUrl: String?,
    onBack: () -> Unit,
    onOpenArticle: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onManageFeeds: () -> Unit,
    onDiscover: () -> Unit,
    viewModel: ArticlesViewModel = viewModel(
        key = feedUrl,
        factory = simpleFactory { ArticlesViewModel.create(feedUrl) },
    ),
) {
    val articles by viewModel.articles.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val feedTitle by viewModel.feedTitle.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val feedNames = remember(feeds) { feeds.associate { it.url to it.title } }
    var creatingGroup by remember { mutableStateOf(false) }

    // Emptying a group takes its chip away; don't leave the list filtered by something that
    // can no longer be seen or cleared.
    LaunchedEffect(group, groups) {
        if (group != null && group !in groups) viewModel.selectGroup(null)
    }

    Scaffold(containerColor = Ink.Surface) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = if (feedUrl != null) feedTitle.ifEmpty { "Stories" }.uppercase() else "NEWS",
                leading = {
                    HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink.Text, onClick = onBack)
                },
                actions = {
                    if (refreshing) {
                        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = Ink.Live,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        HeaderIcon(Icons.Filled.Refresh, "Refresh") { viewModel.refresh() }
                    }
                    // Only the all-feeds view needs a way to subscriptions; a single feed's
                    // list is itself reached from there.
                    if (feedUrl == null) {
                        HeaderIcon(Icons.Filled.Explore, "Discover feeds by topic", onClick = onDiscover)
                        HeaderIcon(Icons.Filled.RssFeed, "Manage feeds", onClick = onManageFeeds)
                    }
                },
            )

            if (feedUrl == null && feeds.isNotEmpty()) {
                ChipRow(modifier = Modifier.padding(top = 14.dp)) {
                    ChoiceChip("All", selected = group == null) { viewModel.selectGroup(null) }
                    groups.forEach { entry ->
                        ChoiceChip(entry, selected = entry == group) { viewModel.selectGroup(entry) }
                    }
                    ChoiceChip("+ New group", selected = false) { creatingGroup = true }
                }
            }

            when {
                feedUrl == null && feeds.isEmpty() -> NoFeedsYet(onDiscover, onManageFeeds, Modifier.weight(1f))
                articles.isEmpty() && !refreshing -> EmptyMessage(
                    if (group != null) "No stories in $group yet." else "Nothing here yet — this feed has no stories.",
                    Modifier.weight(1f),
                )
                else -> {
                    val unread = articles.count { !it.isRead }
                    val parts = listOfNotNull(
                        if (feedUrl == null) {
                            "${feeds.size} ${if (feeds.size == 1) "feed" else "feeds"}"
                        } else {
                            "${articles.size} stories"
                        },
                        "$unread unread".takeIf { unread > 0 },
                    )
                    StatusStrip(parts = parts, highlightIndex = parts.lastIndex.coerceAtLeast(1))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(articles, key = { it.id }) { article ->
                            Hairline()
                            ArticleRow(
                                article = article,
                                // One feed's own list needn't repeat its name on every row.
                                source = if (feedUrl == null) feedNames[article.feedUrl] else null,
                                loadImage = viewModel::imageBytes,
                                onClick = {
                                    viewModel.onOpen()
                                    if (YouTube.videoId(article.link) != null) onOpenVideo(article.id) else onOpenArticle(article.id)
                                },
                            )
                        }
                        item { Hairline() }
                    }
                }
            }
        }
    }

    if (creatingGroup) {
        NewGroupDialog(
            feeds = feeds,
            onDismiss = { creatingGroup = false },
            onCreate = { name, urls ->
                creatingGroup = false
                viewModel.createGroup(name, urls)
            },
        )
    }
}

@Composable
private fun EmptyMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            fontFamily = AppFonts.Grotesk,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = Ink.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoFeedsYet(onDiscover: () -> Unit, onManageFeeds: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.RssFeed,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Ink.Live,
            )
            Spacer(Modifier.height(18.dp))
            MonoText("No feeds yet", size = 12f, tracking = 0.2f, color = Ink.Live)
            Spacer(Modifier.height(14.dp))
            Text(
                "Pick the biggest sources for a topic, or add any RSS or Atom feed, and their " +
                    "stories will be read aloud like a book — full text, not just the summary.",
                fontFamily = AppFonts.Grotesk,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = Ink.Muted,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onManageFeeds, modifier = Modifier.padding(top = 10.dp)) {
                MonoText("Add a feed by address", size = 11f, tracking = 0.16f, color = Ink.Live)
            }
        }
        PillButton(
            text = "Browse topics",
            onClick = onDiscover,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp),
        )
    }
}

@Composable
private fun ArticleRow(
    article: ArticleEntity,
    /** The feed's name, when the list mixes several feeds. */
    source: String?,
    loadImage: suspend (String) -> ByteArray?,
    onClick: () -> Unit,
) {
    // A story you've already heard steps back a whole tone: lighter weight, greyer text,
    // and its timestamp loses the live colour.
    val titleColor = if (article.isRead) Ink.Muted else Ink.Text
    val summaryColor = if (article.isRead) Ink.Faint else Ink.Muted
    val metaColor = if (article.isRead) Ink.Faint else Ink.Live

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenPadding, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                article.title,
                fontFamily = AppFonts.Grotesk,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = if (article.isRead) FontWeight.Normal else FontWeight.SemiBold,
                color = titleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            article.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    summary,
                    fontFamily = AppFonts.Grotesk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = summaryColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 7.dp)) {
                MonoText(
                    text = listOfNotNull(
                        source,
                        relativeTime(article.publishedAt).ifEmpty { null },
                        // Once fetched we know the real length, which is a useful "is this a
                        // quick read or a long piece" signal before pressing play.
                        "${article.textLength / 1000 + 1} min read".takeIf { article.textLength > 0 }
                            ?: "Video".takeIf { YouTube.videoId(article.link) != null },
                    ).joinToString(" · "),
                    size = 10f,
                    tracking = 0.14f,
                    color = metaColor,
                )
                if (article.isRead) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Already read",
                        tint = Ink.Live,
                        modifier = Modifier.padding(start = 8.dp).size(13.dp),
                    )
                }
            }
        }
        article.imageUrl?.let { url ->
            Box(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .size(width = 96.dp, height = 72.dp)
                    .background(Ink.Raised)
                    .border(1.dp, Ink.Edge),
            ) {
                LocalImage(
                    key = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    loadBytes = { loadImage(url) },
                )
            }
        }
    }
}

/** "3h ago" style stamp; feeds without a usable date simply show nothing. */
private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val elapsed = System.currentTimeMillis() - epochMillis
    if (elapsed < 0) return "just now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}
