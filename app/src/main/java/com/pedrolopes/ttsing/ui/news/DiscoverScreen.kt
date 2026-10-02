package com.pedrolopes.ttsing.ui.news

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.data.news.CatalogFeed
import com.pedrolopes.ttsing.data.news.FeedCatalog
import com.pedrolopes.ttsing.data.news.SourceKind
import com.pedrolopes.ttsing.data.news.Topic
import com.pedrolopes.ttsing.ui.common.ChoiceChip
import com.pedrolopes.ttsing.ui.common.Hairline
import com.pedrolopes.ttsing.ui.common.HeaderIcon
import com.pedrolopes.ttsing.ui.common.MonoText
import com.pedrolopes.ttsing.ui.common.ScreenHeader
import com.pedrolopes.ttsing.ui.common.ScreenPadding
import com.pedrolopes.ttsing.ui.common.simpleFactory
import com.pedrolopes.ttsing.ui.theme.AppFonts
import com.pedrolopes.ttsing.ui.theme.Ink

/**
 * The biggest sources per topic, in two groups — newsrooms and newsletters — each a tap away
 * from being subscribed. Saves knowing, or hunting for, a single feed address.
 */
@Composable
fun DiscoverScreen(
    onBack: () -> Unit,
    viewModel: DiscoverViewModel = viewModel(factory = simpleFactory { DiscoverViewModel.create() }),
) {
    val topic by viewModel.topic.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val subscribed by viewModel.subscribed.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingRemoval by remember { mutableStateOf<CatalogFeed?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = Ink.Surface,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = "DISCOVER",
                leading = { HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink.Text, onClick = onBack) },
            )

            ChipRow(modifier = Modifier.padding(top = 14.dp)) {
                Topic.entries.forEach { entry ->
                    ChoiceChip(entry.label, selected = entry == topic) { viewModel.selectTopic(entry) }
                }
            }
            ChipRow(modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) {
                FeedCatalog.languages.forEach { entry ->
                    ChoiceChip(FeedCatalog.languageLabel(entry), selected = entry == language) {
                        viewModel.selectLanguage(entry)
                    }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                for (kind in SourceKind.entries) {
                    val group = FeedCatalog.group(topic, kind, language)
                    if (group.isEmpty()) continue
                    item(key = "header:${kind.name}") {
                        GroupHeader(
                            label = kind.label,
                            count = group.size,
                            canAddAll = group.any { it.url !in subscribed && it.url !in pending },
                            onAddAll = { viewModel.addAll(group) },
                        )
                    }
                    items(group, key = { it.url }) { feed ->
                        Hairline()
                        CatalogRow(
                            feed = feed,
                            isSubscribed = feed.url in subscribed,
                            isPending = feed.url in pending,
                            onClick = {
                                when {
                                    feed.url in pending -> Unit
                                    feed.url in subscribed -> pendingRemoval = feed
                                    else -> viewModel.add(feed)
                                }
                            },
                        )
                    }
                    item(key = "end:${kind.name}") { Hairline() }
                }
            }
        }
    }

    pendingRemoval?.let { feed ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            containerColor = Ink.Raised,
            title = { MonoText("Remove feed?", size = 12f, tracking = 0.18f, color = Ink.Text, weight = FontWeight.Bold) },
            text = {
                Text(
                    "\"${feed.title}\" and its downloaded stories will be removed.",
                    fontFamily = AppFonts.Grotesk,
                    fontSize = 14.sp,
                    color = Ink.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(feed)
                    pendingRemoval = null
                }) { MonoText("Remove", size = 11f, tracking = 0.16f, color = Ink.Live, weight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    MonoText("Cancel", size = 11f, tracking = 0.16f, color = Ink.Muted)
                }
            },
        )
    }
}

/** A row of choice chips that scrolls sideways once there are more than fit. */
@Composable
internal fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun GroupHeader(label: String, count: Int, canAddAll: Boolean, onAddAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding - 12.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText("$label · $count", size = 11.5f, tracking = 0.18f, color = Ink.Text, modifier = Modifier.weight(1f))
        if (canAddAll) {
            Box(
                modifier = Modifier.clickable(onClick = onAddAll).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                MonoText("Add all", size = 11f, tracking = 0.16f, color = Ink.Live, weight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CatalogRow(
    feed: CatalogFeed,
    isSubscribed: Boolean,
    isPending: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = ScreenPadding, end = ScreenPadding - 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                feed.title,
                fontFamily = AppFonts.Grotesk,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                feed.blurb,
                fontFamily = AppFonts.Grotesk,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Ink.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (feed.teaserOnly) {
                // Said up front, so a two-sentence story isn't mistaken for a broken app.
                MonoText("Teasers only", size = 10f, tracking = 0.14f, color = Ink.Dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            when {
                isPending -> CircularProgressIndicator(strokeWidth = 2.dp, color = Ink.Live, modifier = Modifier.size(18.dp))
                isSubscribed -> Icon(Icons.Filled.Check, contentDescription = "Subscribed — tap to remove", tint = Ink.Live, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Filled.Add, contentDescription = "Subscribe to ${feed.title}", tint = Ink.Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}
