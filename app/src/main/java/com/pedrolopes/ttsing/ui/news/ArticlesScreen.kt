package com.pedrolopes.ttsing.ui.news

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import com.pedrolopes.ttsing.ui.common.simpleFactory
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlesScreen(
    feedUrl: String,
    onBack: () -> Unit,
    onOpenArticle: (String) -> Unit,
    viewModel: ArticlesViewModel = viewModel(
        key = feedUrl,
        factory = simpleFactory { ArticlesViewModel.create(feedUrl) },
    ),
) {
    val articles by viewModel.articles.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val feedTitle by viewModel.feedTitle.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(feedTitle.ifEmpty { "Stories" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp).padding(end = 4.dp),
                        )
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (articles.isEmpty() && !refreshing) {
                Text(
                    "No stories in this feed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(articles, key = { it.id }) { article ->
                        ArticleRow(article) { onOpenArticle(article.id) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleRow(article: ArticleEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                article.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (article.isRead) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 3,
            )
            article.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = buildString {
                    append(relativeTime(article.publishedAt))
                    // Once fetched we know the real length, which is a useful "is this a
                    // quick read or a long piece" signal before pressing play.
                    if (article.textLength > 0) append("  ·  ${article.textLength / 1000 + 1} min read")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (article.isRead) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Already read",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp).padding(start = 8.dp),
            )
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
