package com.staninna.yourtube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.Tab
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.web.WebViewHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TubeRoot(vm: MainViewModel, host: WebViewHost) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val nav by host.nav.collectAsStateWithLifecycle()
    val starTarget by vm.starTarget.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()

    val target = starTarget
    val starred = target != null && library.items.any { it.id == target.id }

    BackHandler(enabled = tab != Tab.WATCH || nav.canGoBack) {
        when {
            tab != Tab.WATCH -> vm.selectTab(Tab.WATCH)
            nav.canGoBack -> host.goBack()
        }
    }

    Scaffold(
        topBar = {
            when (tab) {
                Tab.WATCH -> TopAppBar(
                    title = {},
                    navigationIcon = {
                        if (nav.canGoBack) IconButton(onClick = { host.goBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (target != null) IconButton(onClick = { vm.toggleStarCurrent() }) {
                            if (starred) Icon(Icons.Filled.Star, "Unstar", tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Outlined.StarBorder, "Star")
                        }
                        IconButton(onClick = { vm.open(YouTube.HOME) }) { Icon(Icons.Filled.Home, "Home") }
                    },
                )
                Tab.LIBRARY -> TopAppBar(title = { Text("Library") })
                Tab.HISTORY -> TopAppBar(
                    title = { Text("History") },
                    actions = { IconButton(onClick = { vm.clearHistory() }) { Icon(Icons.Filled.DeleteSweep, "Clear") } },
                )
                Tab.FEEDS -> TopAppBar(
                    title = { Text("Feeds") },
                    actions = { IconButton(onClick = { vm.refreshFeeds() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavItem(tab, Tab.WATCH, Icons.Filled.PlayArrow, "Watch", vm)
                NavItem(tab, Tab.LIBRARY, Icons.Outlined.VideoLibrary, "Library", vm)
                NavItem(tab, Tab.HISTORY, Icons.Filled.History, "History", vm)
                NavItem(tab, Tab.FEEDS, Icons.Filled.RssFeed, "Feeds", vm)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // The WebView stays attached and playing while native tabs cover it.
            WebViewSlot(host, Modifier.fillMaxSize())
            if (tab != Tab.WATCH) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (tab) {
                        Tab.LIBRARY -> LibraryScreen(vm)
                        Tab.HISTORY -> HistoryScreen(vm)
                        Tab.FEEDS -> FeedsScreen(vm)
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    current: Tab, tab: Tab, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, vm: MainViewModel,
) {
    NavigationBarItem(
        selected = current == tab,
        onClick = { vm.selectTab(tab) },
        icon = { Icon(icon, label) },
        label = { Text(label) },
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryScreen(vm: MainViewModel) {
    val library by vm.library.collectAsStateWithLifecycle()
    if (library.items.isEmpty()) { Empty("Star channels and videos to see them here."); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(library.items, key = { it.id }) { item ->
            MediaRow(
                title = item.title,
                subtitle = item.author ?: item.type.name.lowercase(),
                thumbnail = item.thumbnail,
                onClick = { vm.open(item.url) },
                onTrailing = { vm.toggleStar(item) },
                trailing = Icons.Filled.Star,
            )
        }
    }
}

@Composable
private fun HistoryScreen(vm: MainViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    if (history.isEmpty()) { Empty("Videos you watch show up here."); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(history, key = { it.videoId }) { h ->
            MediaRow(
                title = h.meta?.title ?: h.url,
                subtitle = h.meta?.author,
                thumbnail = h.thumbnail,
                onClick = { vm.open(h.url) },
            )
        }
    }
}

@Composable
private fun FeedsScreen(vm: MainViewModel) {
    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val loading by vm.feedsLoading.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (feeds.isEmpty()) vm.refreshFeeds() }
    when {
        loading && feeds.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        feeds.isEmpty() -> Empty("Star some channels, then pull Refresh to see their newest uploads.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(feeds, key = { it.videoId }) { f ->
                MediaRow(title = f.title, subtitle = f.author, thumbnail = f.thumbnail, onClick = { vm.open(f.url) })
            }
        }
    }
}

@Composable
private fun MediaRow(
    title: String,
    subtitle: String?,
    thumbnail: String?,
    onClick: () -> Unit,
    onTrailing: (() -> Unit)? = null,
    trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (!subtitle.isNullOrBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            coil.compose.AsyncImage(
                model = thumbnail,
                contentDescription = null,
                modifier = Modifier.size(width = 96.dp, height = 56.dp),
            )
        },
        trailingContent = {
            if (trailing != null && onTrailing != null) IconButton(onClick = onTrailing) {
                Icon(trailing, "action", tint = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
