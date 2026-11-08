package com.staninna.yourtube.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.Tab
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.ui.components.EmptyState
import com.staninna.yourtube.ui.components.MediaLeading
import com.staninna.yourtube.ui.components.MediaRow
import com.staninna.yourtube.ui.components.StateAction
import com.staninna.yourtube.ui.components.TabScaffold

/** Starred channels, videos and playlists, newest first. Segments and tag chips arrive in stage 4. */
@Composable
fun LibraryScreen(vm: MainViewModel, onSearch: () -> Unit, onSettings: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    TabScaffold(title = "Library", onSearch = onSearch, onSettings = onSettings) { padding ->
        if (library.items.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.VideoLibrary,
                title = "Your library is empty",
                body = "Tap the star on a video, channel or playlist in Watch to keep it here.",
                action = StateAction("Browse YouTube") { vm.selectTab(Tab.WATCH) },
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 8.dp)) {
                items(library.items, key = { it.id }) { item ->
                    MediaRow(
                        title = item.title,
                        subtitle = subtitle(item),
                        leading = leading(item, progress),
                        onClick = { vm.open(item.url) },
                        trailing = { Unstar(onClick = { vm.toggleStar(item) }) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

private fun subtitle(item: StarredItem): String = when (item.type) {
    ItemType.CHANNEL -> "Channel"
    ItemType.VIDEO -> item.author ?: "Video"
    ItemType.PLAYLIST -> listOfNotNull("Playlist", item.author).joinToString(" · ")
}

private fun leading(item: StarredItem, progress: Map<String, WatchProgress>): MediaLeading = when (item.type) {
    ItemType.CHANNEL -> MediaLeading.Avatar(item.title, key = item.channelId ?: item.id, url = item.thumbnail)
    ItemType.VIDEO -> MediaLeading.Thumbnail(item.thumbnail, progress[item.id])
    ItemType.PLAYLIST -> MediaLeading.Thumbnail(item.thumbnail, fallbackIcon = Icons.AutoMirrored.Filled.PlaylistPlay)
}

/** Every library row is starred; the filled star takes it out. */
@Composable
private fun Unstar(onClick: () -> Unit) {
    IconToggleButton(
        checked = true,
        onCheckedChange = { onClick() },
        modifier = Modifier.semantics { stateDescription = "Starred" },
    ) {
        Icon(Icons.Filled.Star, contentDescription = "Star", tint = MaterialTheme.colorScheme.primary)
    }
}
