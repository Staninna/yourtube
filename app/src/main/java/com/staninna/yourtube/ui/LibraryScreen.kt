package com.staninna.yourtube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.Tab
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.LibraryFilter
import com.staninna.yourtube.data.LibrarySort
import com.staninna.yourtube.data.LibraryView
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.ui.components.EmptyState
import com.staninna.yourtube.ui.components.MediaLeading
import com.staninna.yourtube.ui.components.MediaRow
import com.staninna.yourtube.ui.components.SegmentedButtons
import com.staninna.yourtube.ui.components.StateAction
import com.staninna.yourtube.ui.components.TabScaffold

/**
 * Starred channels, videos and playlists: a Subs | Videos | Playlists segment, a row with the tag
 * filter and the sort under it, a search field once a segment is long, and the segment's items.
 * Long-press a row to select it; selection mode tags or removes several rows at once.
 */
@Composable
fun LibraryScreen(vm: MainViewModel, onSearch: () -> Unit, onSettings: () -> Unit) {
    val view by vm.library.view.collectAsStateWithLifecycle()
    val selection = vm.library.selection
    val selected by selection.selected.collectAsStateWithLifecycle()
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting, onBack = selection::clear)
    TabScaffold(
        title = "Library",
        onSearch = onSearch,
        onSettings = onSettings,
        contextualBar = if (selecting) ({ SelectionTopBar(selected.size, selection) }) else null,
    ) { padding ->
        if (view.emptyReason == LibraryView.EmptyReason.LIBRARY) {
            EmptyState(
                icon = Icons.Outlined.VideoLibrary,
                title = "Your library is empty",
                body = "Tap the star on a video, channel or playlist in Watch to keep it here.",
                action = StateAction("Browse YouTube") { vm.selectTab(Tab.WATCH) },
                modifier = Modifier.padding(padding),
            )
            return@TabScaffold
        }
        val segment = segments.first { it.type == view.segment }
        // The field edits its own text, so the cursor never waits on the filter; it starts from the
        // view model's query when Library comes back.
        var query by remember { mutableStateOf(vm.library.queryText.value) }
        val setQuery = { value: String ->
            query = value
            vm.library.setQuery(value)
        }
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            SegmentedButtons(
                segments.map { it.label },
                selectedIndex = segments.indexOf(segment),
                onSelect = { vm.library.selectSegment(segments[it].type) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            if (view.segmentSize > 0) {
                TagFilterBar(view.tags, vm.library, segment.noun, Modifier.padding(top = 4.dp)) {
                    SortButton(view.sort, LibrarySort.offeredFor(view.segment), vm.library::setSort)
                }
            }
            if (view.searchable) LibrarySearch(query, setQuery, segment.label.lowercase())
            Box(Modifier.weight(1f)) {
                when (view.emptyReason) {
                    LibraryView.EmptyReason.SEGMENT -> EmptyState(
                        icon = segment.emptyIcon,
                        title = segment.emptyTitle,
                        body = segment.emptyBody,
                        action = StateAction("Browse YouTube") { vm.selectTab(Tab.WATCH) },
                    )
                    LibraryView.EmptyReason.NO_MATCH -> {
                        val copy = TagFilterText.libraryNoMatch(view, segment.noun.many)
                        EmptyState(
                            icon = Icons.Outlined.SearchOff,
                            title = copy.title,
                            body = copy.body,
                            action = StateAction(copy.action.label) {
                                when (copy.action) {
                                    EmptyAction.CLEAR_TAGS, EmptyAction.SHOW_ALL -> vm.library.clearTagFilter()
                                    EmptyAction.CLEAR_SEARCH -> setQuery("")
                                }
                            },
                        )
                    }
                    else -> LibraryList(view, selected, vm)
                }
            }
        }
    }
}

/**
 * The segment's rows. Out of selection mode a tap opens the item and a long press selects it; in it,
 * a tap or a long press flips the row, and a check box takes the place of the star.
 */
@Composable
private fun LibraryList(view: LibraryView, selected: Set<String>, vm: MainViewModel) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val lengths by vm.lengths.collectAsStateWithLifecycle()
    val latestUploads by vm.feeds.latestUploads.collectAsStateWithLifecycle()
    // Ages in Library only need to be right when the list is drawn; Feeds is where they tick.
    val now = remember(latestUploads) { System.currentTimeMillis() }
    val selection = vm.library.selection
    val selecting = selected.isNotEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(view.items, key = { it.id }) { item ->
            val isSelected = item.id in selected
            MediaRow(
                title = item.title,
                subtitle = librarySubtitle(item, LibraryFilter.tagNames(item, view.tags.tags), latestUploads[item.id], now),
                leading = leading(item, progress, lengths),
                onClick = { if (selecting) selection.toggle(item.id) else vm.open(item.url) },
                onLongClick = { selection.toggle(item.id) },
                onLongClickLabel = if (isSelected) "Deselect" else "Select",
                trailing = {
                    if (selecting) {
                        Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    } else {
                        Unstar(item.title, onClick = { vm.library.unstar(item.id) })
                    }
                },
                modifier = Modifier
                    .animateItem()
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    // The check box only draws the state; TalkBack hears it from the row. Outside
                    // selection mode the rows say nothing about it.
                    .semantics { if (selecting) this.selected = isSelected },
            )
        }
    }
}

/** The search field of a long segment ([what] is its name, such as "videos"). */
@Composable
private fun LibrarySearch(text: String, onChange: (String) -> Unit, what: String) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        placeholder = { Text("Search $what") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
        },
        singleLine = true,
        shape = CircleShape,
    )
}

private fun leading(item: StarredItem, progress: Map<String, WatchProgress>, lengths: Map<String, Double>): MediaLeading = when (item.type) {
    ItemType.CHANNEL -> MediaLeading.Avatar(item.title, key = item.channelId ?: item.id, url = item.thumbnail)
    // A starred video was nearly always played, so history knows its length.
    ItemType.VIDEO -> MediaLeading.Thumbnail(item.thumbnail, progress[item.id], lengths[item.id]?.let(::formatDuration))
    ItemType.PLAYLIST -> MediaLeading.Thumbnail(item.thumbnail, fallbackIcon = Icons.AutoMirrored.Filled.PlaylistPlay)
}

/** Every library row is starred; the filled star is an action that takes it out, with Undo. */
@Composable
private fun Unstar(title: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Star, contentDescription = "Remove $title from library", tint = MaterialTheme.colorScheme.primary)
    }
}

/** A segment's label, how its filter sheet counts, and what it says when it has nothing. */
private class Segment(
    val type: ItemType,
    val label: String,
    val noun: Noun,
    val emptyIcon: ImageVector,
    val emptyTitle: String,
    val emptyBody: String,
)

private val segments = listOf(
    Segment(
        ItemType.CHANNEL, "Subs", Noun("channel", "channels"), Icons.Outlined.Subscriptions,
        "No subscriptions yet", "Star a channel in Watch to follow its new videos in Feeds.",
    ),
    Segment(
        ItemType.VIDEO, "Videos", Noun("video", "videos"), Icons.Outlined.VideoLibrary,
        "No saved videos", "Star a video in Watch to keep it here.",
    ),
    Segment(
        ItemType.PLAYLIST, "Playlists", Noun("playlist", "playlists"), Icons.AutoMirrored.Outlined.PlaylistPlay,
        "No saved playlists", "Open a playlist in Watch and star it to keep it here.",
    ),
)
