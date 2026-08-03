package com.pedrolopes.ttsing.ui.news

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.ui.common.simpleFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(
    onBack: () -> Unit,
    onOpenFeed: (String) -> Unit,
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
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("News") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp).padding(end = 4.dp),
                        )
                    } else {
                        IconButton(onClick = { viewModel.refreshAll() }, enabled = feeds.isNotEmpty()) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh all feeds")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a feed")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (feeds.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No feeds yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add an RSS or Atom feed and its stories will be read aloud like a book — " +
                            "full text, not just the summary.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(feeds, key = { it.url }) { feed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenFeed(feed.url) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(feed.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                Text(
                                    feed.siteLink ?: feed.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { pendingRemoval = feed.url }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Remove ${feed.title}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
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
            title = { Text("Remove feed?") },
            text = { Text("\"$title\" and its downloaded stories will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFeed(url)
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AddFeedDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Feed address") },
                    placeholder = { Text("example.com/rss") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "RSS or Atom. The address is checked now, so you'll know straight away if it isn't a feed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url) }, enabled = url.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
