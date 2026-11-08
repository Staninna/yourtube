package com.staninna.yourtube.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.Tab
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.LibraryFilter
import com.staninna.yourtube.data.LibraryView
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.ui.components.ChipOption
import com.staninna.yourtube.ui.components.EmptyState
import com.staninna.yourtube.ui.components.FilterChipsRow
import com.staninna.yourtube.ui.components.MediaLeading
import com.staninna.yourtube.ui.components.MediaRow
import com.staninna.yourtube.ui.components.SegmentedButtons
import com.staninna.yourtube.ui.components.StateAction
import com.staninna.yourtube.ui.components.TabScaffold

/**
 * Starred channels, videos and playlists: a Subs | Videos | Playlists segment, the segment's tags as
 * filter chips, a search field once a segment is long, and newest first. Long-press a row for its tags.
 */
@Composable
fun LibraryScreen(vm: MainViewModel, onSearch: () -> Unit, onSettings: () -> Unit) {
    val view by vm.library.view.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    TabScaffold(title = "Library", onSearch = onSearch, onSettings = onSettings) { padding ->
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
                modifier = Modifier.padding(top = 8.dp),
            )
            AnimatedVisibility(view.chips.isNotEmpty()) {
                FilterChipsRow(
                    options = view.chips.map { ChipOption(it.id, it.name, Color(it.color)) },
                    selected = view.selected,
                    onToggle = vm.library::toggleTagFilter,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
                    LibraryView.EmptyReason.NO_MATCH -> EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = "Nothing matches",
                        body = "No ${segment.label.lowercase()} have that in their name or channel.",
                        action = StateAction("Clear search") { setQuery("") },
                    )
                    else -> LibraryList(view, progress, vm)
                }
            }
        }
    }
}

@Composable
private fun LibraryList(view: LibraryView, progress: Map<String, WatchProgress>, vm: MainViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(view.items, key = { it.id }) { item ->
            MediaRow(
                title = item.title,
                subtitle = librarySubtitle(item, LibraryFilter.tagNames(item, view.chips)),
                leading = leading(item, progress),
                onClick = { vm.open(item.url) },
                onLongClick = { vm.library.openTagSheet(item.id) },
                onLongClickLabel = "Edit tags",
                trailing = { Unstar(item.title, onClick = { vm.library.unstar(item.id) }) },
                modifier = Modifier.animateItem(),
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

private fun leading(item: StarredItem, progress: Map<String, WatchProgress>): MediaLeading = when (item.type) {
    ItemType.CHANNEL -> MediaLeading.Avatar(item.title, key = item.channelId ?: item.id, url = item.thumbnail)
    ItemType.VIDEO -> MediaLeading.Thumbnail(item.thumbnail, progress[item.id])
    ItemType.PLAYLIST -> MediaLeading.Thumbnail(item.thumbnail, fallbackIcon = Icons.AutoMirrored.Filled.PlaylistPlay)
}

/** Every library row is starred; the filled star is an action that takes it out, with Undo. */
@Composable
private fun Unstar(title: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Star, contentDescription = "Remove $title from library", tint = MaterialTheme.colorScheme.primary)
    }
}

/** A segment's label and what it says when it has nothing. */
private class Segment(
    val type: ItemType,
    val label: String,
    val emptyIcon: ImageVector,
    val emptyTitle: String,
    val emptyBody: String,
)

private val segments = listOf(
    Segment(
        ItemType.CHANNEL, "Subs", Icons.Outlined.Subscriptions,
        "No subscriptions yet", "Star a channel in Watch to follow its new videos in Feeds.",
    ),
    Segment(
        ItemType.VIDEO, "Videos", Icons.Outlined.VideoLibrary,
        "No saved videos", "Star a video in Watch to keep it here.",
    ),
    Segment(
        ItemType.PLAYLIST, "Playlists", Icons.AutoMirrored.Outlined.PlaylistPlay,
        "No saved playlists", "Open a playlist in Watch and star it to keep it here.",
    ),
)
