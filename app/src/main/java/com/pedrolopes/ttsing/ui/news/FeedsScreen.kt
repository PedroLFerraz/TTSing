package com.pedrolopes.ttsing.ui.news

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.ui.common.Hairline
import com.pedrolopes.ttsing.ui.common.HeaderIcon
import com.pedrolopes.ttsing.ui.common.MonoText
import com.pedrolopes.ttsing.ui.common.PillButton
import com.pedrolopes.ttsing.ui.common.ScreenHeader
import com.pedrolopes.ttsing.ui.common.ScreenPadding
import com.pedrolopes.ttsing.ui.common.StatusStrip
import com.pedrolopes.ttsing.ui.common.simpleFactory
import com.pedrolopes.ttsing.ui.theme.AppFonts
import com.pedrolopes.ttsing.ui.theme.Ink

@Composable
fun FeedsScreen(
    onBack: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onDiscover: () -> Unit,
    viewModel: FeedsViewModel = viewModel(factory = simpleFactory { FeedsViewModel.create() }),
) {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }

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
                title = "FEEDS",
                leading = { HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink.Text, onClick = onBack) },
                actions = {
                    if (busy) {
                        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = Ink.Live, modifier = Modifier.size(18.dp))
                        }
                    } else if (feeds.isNotEmpty()) {
                        HeaderIcon(Icons.Filled.Refresh, "Refresh all feeds") { viewModel.refreshAll() }
                    }
                    HeaderIcon(Icons.Filled.Explore, "Discover feeds by topic", onClick = onDiscover)
                },
            )

            if (feeds.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.RssFeed, contentDescription = null, tint = Ink.Live, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(18.dp))
                    MonoText("No feeds yet", size = 12f, tracking = 0.2f, color = Ink.Live)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Pick the biggest sources for a topic, or add any RSS or Atom feed, and " +
                            "their stories will be read aloud like a book — full text, not just the summary.",
                        fontFamily = AppFonts.Grotesk,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = Ink.Muted,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { showAdd = true }, modifier = Modifier.padding(top = 10.dp)) {
                        MonoText("Add a feed by address", size = 11f, tracking = 0.16f, color = Ink.Live)
                    }
                }
            } else {
                StatusStrip(
                    parts = listOf("Subscriptions", "${feeds.size} ${if (feeds.size == 1) "feed" else "feeds"}"),
                    highlightIndex = 1,
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(feeds, key = { it.url }) { feed ->
                        Hairline()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenFeed(feed.url) }
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
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                MonoText(
                                    feed.siteLink ?: feed.url,
                                    size = 10f,
                                    tracking = 0.1f,
                                    uppercase = false,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable { pendingRemoval = feed.url },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Remove ${feed.title}",
                                    tint = Ink.Dim,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    item { Hairline() }
                }
            }

            // With nothing subscribed yet, the topic catalogue is the easier start.
            PillButton(
                text = if (feeds.isEmpty()) "Browse topics" else "Add a feed",
                onClick = { if (feeds.isEmpty()) onDiscover() else showAdd = true },
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp),
            )
        }
    }

    if (showAdd) {
        AddFeedDialog(
            onDismiss = { showAdd = false },
            onAdd = { url ->
                showAdd = false
                viewModel.addFeed(url)
            },
        )
    }

    pendingRemoval?.let { url ->
        val title = feeds.firstOrNull { it.url == url }?.title ?: url
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            containerColor = Ink.Raised,
            title = { MonoText("Remove feed?", size = 12f, tracking = 0.18f, color = Ink.Text, weight = FontWeight.Bold) },
            text = {
                Text(
                    "\"$title\" and its downloaded stories will be removed.",
                    fontFamily = AppFonts.Grotesk,
                    fontSize = 14.sp,
                    color = Ink.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFeed(url)
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

@Composable
private fun AddFeedDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = { MonoText("Add a feed", size = 12f, tracking = 0.18f, color = Ink.Text, weight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { MonoText("Feed or site address", size = 10f, tracking = 0.16f) },
                    placeholder = {
                        Text("theverge.com", fontFamily = AppFonts.Grotesk, fontSize = 14.sp, color = Ink.Dim)
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = AppFonts.Grotesk,
                        fontSize = 15.sp,
                        color = Ink.Text,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink.Live,
                        unfocusedBorderColor = Ink.Edge,
                        cursorColor = Ink.Live,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Paste a feed or just the site's address — its feed is found for you. It's checked " +
                        "now, so you'll know straight away if there isn't one.",
                    fontFamily = AppFonts.Grotesk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Ink.Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url) }, enabled = url.isNotBlank()) {
                MonoText(
                    "Add",
                    size = 11f,
                    tracking = 0.16f,
                    color = if (url.isNotBlank()) Ink.Live else Ink.Dim,
                    weight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { MonoText("Cancel", size = 11f, tracking = 0.16f, color = Ink.Muted) }
        },
    )
}
