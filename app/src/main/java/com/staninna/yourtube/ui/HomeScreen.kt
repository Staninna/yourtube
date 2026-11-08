package com.staninna.yourtube.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.R
import com.staninna.yourtube.Tab
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.feed.RelativeAge
import com.staninna.yourtube.home.Dashboard
import com.staninna.yourtube.ui.components.EmptyState
import com.staninna.yourtube.ui.components.MediaCard
import com.staninna.yourtube.ui.components.MediaLeading
import com.staninna.yourtube.ui.components.TabScaffold

/**
 * Watch's home: the app's logo and name, a search field that opens the app's search, and rows of
 * Continue watching, New from your subs and Saved videos, each with a › to its full list. Empty rows
 * hide; with all three empty a welcome takes their place. A link at the end opens YouTube's own home.
 */
@Composable
fun HomeScreen(vm: MainViewModel, onSearch: () -> Unit, onSettings: () -> Unit) {
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    TabScaffold(
        title = "YourTube",
        onSearch = null,
        onSettings = onSettings,
        titleIcon = { Image(painterResource(R.drawable.ic_logo), contentDescription = null, Modifier.size(width = 34.dp, height = 24.dp)) },
    ) { padding ->
        val frame = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
        val openYouTubeHome = { vm.open(YouTube.HOME) }
        if (dashboard.empty) {
            Column(frame) {
                SearchField(onSearch)
                EmptyState(
                    icon = Icons.Outlined.WavingHand,
                    title = "Welcome to YourTube",
                    body = "Search for something to watch. Star the videos and channels you like and they show up here.",
                    modifier = Modifier.weight(1f),
                )
                YouTubeHomeLink(openYouTubeHome)
            }
        } else {
            Column(frame.verticalScroll(rememberScrollState())) {
                SearchField(onSearch)
                DashboardRows(dashboard, vm)
                YouTubeHomeLink(openYouTubeHome)
            }
        }
    }
}

/** The three rows, each only when it has something. */
@Composable
private fun DashboardRows(dashboard: Dashboard, vm: MainViewModel) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val lengths by vm.lengths.collectAsStateWithLifecycle()
    // Ages only need to be right when the row is drawn; Feeds is where they tick.
    val now = remember(dashboard.newFromSubs) { System.currentTimeMillis() }
    if (dashboard.continueWatching.isNotEmpty()) {
        Shelf("Continue watching", allLabel = "Open History", onAll = { vm.selectTab(Tab.HISTORY) }) {
            items(dashboard.continueWatching.size, key = { dashboard.continueWatching[it].videoId }) {
                val entry = dashboard.continueWatching[it]
                MediaCard(
                    title = entry.meta?.title ?: "Untitled video",
                    subtitle = entry.meta?.author,
                    subtitleEnd = timeLeft(entry),
                    thumbnail = MediaLeading.Thumbnail(entry.thumbnail, WatchProgress.of(entry), entry.length?.let(::formatDuration)),
                    onClick = { vm.open(entry.url) },
                )
            }
        }
    }
    if (dashboard.newFromSubs.isNotEmpty()) {
        Shelf("New from your subs", allLabel = "Open Feeds", onAll = { vm.selectTab(Tab.FEEDS) }) {
            items(dashboard.newFromSubs.size, key = { dashboard.newFromSubs[it].videoId }) {
                val video = dashboard.newFromSubs[it]
                MediaCard(
                    title = video.title,
                    subtitle = video.author,
                    subtitleEnd = RelativeAge.format(video.publishedAt, now),
                    thumbnail = MediaLeading.Thumbnail(video.thumbnail, progress[video.videoId], feedBadge(video)),
                    onClick = { vm.open(video.url) },
                )
            }
        }
    }
    if (dashboard.saved.isNotEmpty()) {
        Shelf("Saved videos", allLabel = "Open saved videos in Library", onAll = vm::openSavedVideos) {
            items(dashboard.saved.size, key = { dashboard.saved[it].id }) {
                val item = dashboard.saved[it]
                MediaCard(
                    title = item.title,
                    subtitle = item.author,
                    thumbnail = MediaLeading.Thumbnail(item.thumbnail, progress[item.id], lengths[item.id]?.let(::formatDuration)),
                    onClick = { vm.open(item.url) },
                )
            }
        }
    }
}

/** The pill that opens search; it looks like a field but is a button, so TalkBack calls it one. */
@Composable
private fun SearchField(onSearch: () -> Unit) {
    Surface(
        onClick = onSearch,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .heightIn(min = 64.dp)
            .semantics { role = Role.Button },
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Search YouTube", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A titled row of cards. The whole header is the › button, a heading TalkBack announces with
 * [allLabel] as what a tap does.
 */
@Composable
private fun Shelf(title: String, allLabel: String, onAll: () -> Unit, cards: LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { heading() }
                .clickable(onClickLabel = allLabel, role = Role.Button, onClick = onAll)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = cards,
        )
    }
}

/** YouTube's own home page, trending and recommendations, which loads in Watch like anything else. */
@Composable
private fun ColumnScope.YouTubeHomeLink(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp)) {
        Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        Text("YouTube home")
    }
}
