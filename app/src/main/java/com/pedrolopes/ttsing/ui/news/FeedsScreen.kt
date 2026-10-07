package com.pedrolopes.ttsing.ui.news

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.data.news.db.FeedEntity
import com.pedrolopes.ttsing.ui.common.ChoiceChip
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
    var regrouping by remember { mutableStateOf<String?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var renamingGroup by remember { mutableStateOf<String?>(null) }
    var deletingGroup by remember { mutableStateOf<String?>(null) }
    val addState by viewModel.add.collectAsStateWithLifecycle()
    val groups = remember(feeds) { groupsOf(feeds) }
    // Each group under its own heading, alphabetically, with ungrouped feeds last.
    val sections = remember(feeds, groups) {
        (groups + null).mapNotNull { group ->
            feeds.filter { it.folder.equals(group, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { group to it }
        }
    }

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
                    if (feeds.isNotEmpty()) {
                        HeaderIcon(Icons.Filled.CreateNewFolder, "New group") { creatingGroup = true }
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
                    for ((group, sectionFeeds) in sections) {
                        // With no groups made yet there is nothing to head.
                        if (groups.isNotEmpty()) {
                            item(key = "group:$group") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = ScreenPadding, end = ScreenPadding - 12.dp, top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MonoText(
                                        group ?: "No group",
                                        size = 11f,
                                        tracking = 0.18f,
                                        color = if (group != null) Ink.Live else Ink.Dim,
                                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                                    )
                                    if (group != null) {
                                        RowAction(Icons.Filled.Edit, "Rename group $group") { renamingGroup = group }
                                        RowAction(Icons.Filled.Delete, "Delete group $group") { deletingGroup = group }
                                    }
                                }
                            }
                        }
                        items(sectionFeeds, key = { it.url }) { feed -> FeedRow(feed, onOpenFeed, { regrouping = feed.url }, { pendingRemoval = feed.url }) }
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
            state = addState,
            onDismiss = {
                viewModel.cancelAdd()
                showAdd = false
            },
            onAdd = { url -> viewModel.addFeed(url) { showAdd = false } },
            onEdit = viewModel::clearAddError,
        )
    }

    renamingGroup?.let { group ->
        RenameGroupDialog(
            current = group,
            onDismiss = { renamingGroup = null },
            onRename = { name ->
                renamingGroup = null
                viewModel.renameGroup(group, name)
            },
        )
    }
    deletingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            containerColor = Ink.Raised,
            title = { MonoText("Delete group?", size = 12f, tracking = 0.18f, color = Ink.Text, weight = FontWeight.Bold) },
            text = {
                Text(
                    "The feeds in \"$group\" stay subscribed, with no group.",
                    fontFamily = AppFonts.Grotesk,
                    fontSize = 14.sp,
                    color = Ink.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGroup(group)
                    deletingGroup = null
                }) { MonoText("Delete", size = 11f, tracking = 0.16f, color = Ink.Live, weight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deletingGroup = null }) {
                    MonoText("Cancel", size = 11f, tracking = 0.16f, color = Ink.Muted)
                }
            },
        )
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

    regrouping?.let { url ->
        val feed = feeds.firstOrNull { it.url == url } ?: return@let
        GroupDialog(
            feedTitle = feed.title,
            current = feed.folder,
            groups = groups,
            onDismiss = { regrouping = null },
            onSave = { group ->
                regrouping = null
                viewModel.setGroup(url, group)
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

/**
 * Stays open while the address is checked (a spinner in place of "Add"), shows what went wrong
 * under the field and keeps what was typed so it can be fixed; [FeedsViewModel] closes it on success.
 */
@Composable
private fun AddFeedDialog(
    state: FeedsViewModel.AddState,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onEdit: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val canAdd = url.isNotBlank() && !state.checking
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = { MonoText("Add a feed", size = 12f, tracking = 0.18f, color = Ink.Text, weight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        onEdit()
                    },
                    label = "Feed or site address",
                    placeholder = "theverge.com",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd(url) }),
                    enabled = !state.checking,
                    isError = state.error != null,
                )
                if (state.error != null) {
                    Text(
                        state.error,
                        fontFamily = AppFonts.Grotesk,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
            if (state.checking) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = Ink.Live, modifier = Modifier.size(18.dp))
                }
            } else {
                TextButton(onClick = { onAdd(url) }, enabled = canAdd) {
                    MonoText(
                        "Add",
                        size = 11f,
                        tracking = 0.16f,
                        color = if (canAdd) Ink.Live else Ink.Dim,
                        weight = FontWeight.Bold,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { MonoText("Cancel", size = 11f, tracking = 0.16f, color = Ink.Muted) }
        },
    )
}

/** Gives a group a new name; one that matches another group merges into it. */
@Composable
private fun RenameGroupDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val ready = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = { MonoText("Rename group", size = 12f, tracking = 0.18f, color = Ink.Text, weight = FontWeight.Bold) },
        text = {
            DialogTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                placeholder = "Science",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ready) onRename(name) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = ready) {
                MonoText("Rename", size = 11f, tracking = 0.16f, color = if (ready) Ink.Live else Ink.Dim, weight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { MonoText("Cancel", size = 11f, tracking = 0.16f, color = Ink.Muted) }
        },
    )
}

@Composable
private fun FeedRow(feed: FeedEntity, onOpen: (String) -> Unit, onRegroup: () -> Unit, onRemove: () -> Unit) {
    Hairline()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(feed.url) }
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
        RowAction(Icons.AutoMirrored.Filled.DriveFileMove, "Move ${feed.title} to a group", onRegroup)
        RowAction(Icons.Filled.Delete, "Remove ${feed.title}", onRemove)
    }
}

@Composable
private fun RowAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(modifier = Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = contentDescription, tint = Ink.Dim, modifier = Modifier.size(18.dp))
    }
}

/** Picks one of the existing groups, types a new one, or takes the feed out of any. */
@Composable
private fun GroupDialog(
    feedTitle: String,
    current: String?,
    groups: List<String>,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var name by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = { MonoText("Group", size = 12f, tracking = 0.18f, color = Ink.Text, weight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Where \"$feedTitle\" sits in the News list's filter.",
                    fontFamily = AppFonts.Grotesk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Ink.Muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("None", selected = name.isBlank()) { name = "" }
                    groups.forEach { group -> ChoiceChip(group, selected = name.trim().equals(group, ignoreCase = true)) { name = group } }
                }
                DialogTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Or a new group",
                    placeholder = "Science",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                MonoText("Save", size = 11f, tracking = 0.16f, color = Ink.Live, weight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { MonoText("Cancel", size = 11f, tracking = 0.16f, color = Ink.Muted) }
        },
    )
}

@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { MonoText(label, size = 10f, tracking = 0.16f) },
        placeholder = { Text(placeholder, fontFamily = AppFonts.Grotesk, fontSize = 14.sp, color = Ink.Dim) },
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
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        enabled = enabled,
        isError = isError,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Names a new group and picks the feeds that go in it. A group is the feeds filed under it,
 * so it needs at least one; a feed already in another group moves.
 */
@Composable
internal fun NewGroupDialog(
    feeds: List<FeedEntity>,
    onDismiss: () -> Unit,
    onCreate: (String, Set<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val ready = name.isNotBlank() && picked.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = { MonoText("New group", size = 12f, tracking = 0.18f, color = Ink.Text, weight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    placeholder = "Science",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                )
                MonoText("Feeds in it", size = 10f, tracking = 0.16f)
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    feeds.forEach { feed ->
                        val checked = feed.url in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = if (checked) picked - feed.url else picked + feed.url },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Ink.Live, uncheckedColor = Ink.Dim, checkmarkColor = Ink.Surface),
                                modifier = Modifier.padding(10.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(feed.title, fontFamily = AppFonts.Grotesk, fontSize = 14.sp, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                feed.folder?.let { MonoText("Now in $it", size = 9f, tracking = 0.1f, color = Ink.Dim) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, picked) }, enabled = ready) {
                MonoText("Create", size = 11f, tracking = 0.16f, color = if (ready) Ink.Live else Ink.Dim, weight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { MonoText("Cancel", size = 11f, tracking = 0.16f, color = Ink.Muted) }
        },
    )
}
