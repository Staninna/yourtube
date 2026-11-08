package com.staninna.yourtube.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.Tab
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.ui.components.EmptyState
import com.staninna.yourtube.ui.components.LoadingState
import com.staninna.yourtube.ui.components.MediaLeading
import com.staninna.yourtube.ui.components.MediaRow
import com.staninna.yourtube.ui.components.StateAction
import com.staninna.yourtube.ui.components.TabScaffold

/** Newest uploads of every starred channel. Paging, tags and pull-to-refresh arrive in stage 5. */
@Composable
fun FeedsScreen(vm: MainViewModel, onSearch: () -> Unit, onSettings: () -> Unit) {
    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val loading by vm.feedsLoading.collectAsStateWithLifecycle()
    val subscribed by vm.hasSubscriptions.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    LaunchedEffect(subscribed) { if (subscribed && feeds.isEmpty()) vm.refreshFeeds() }

    TabScaffold(
        title = "Feeds",
        onSearch = onSearch,
        onSettings = onSettings,
        actions = {
            if (subscribed) IconButton(onClick = vm::refreshFeeds, enabled = !loading) { Icon(Icons.Filled.Refresh, "Refresh feeds") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !subscribed -> EmptyState(
                    icon = Icons.Outlined.Subscriptions,
                    title = "No subscriptions yet",
                    body = "Star a channel in Watch and its newest videos show up here.",
                    action = StateAction("Browse YouTube") { vm.selectTab(Tab.WATCH) },
                )
                loading && feeds.isEmpty() -> LoadingState("Fetching new videos")
                feeds.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Subscriptions,
                    title = "Nothing came in",
                    body = "Your channels' feeds could not be read. Check your connection and refresh.",
                    action = StateAction("Refresh", vm::refreshFeeds),
                )
                else -> FeedList(feeds, progress, onOpen = vm::open)
            }
            AnimatedVisibility(loading && feeds.isNotEmpty(), Modifier.align(Alignment.TopCenter)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FeedList(
    feeds: List<FeedVideo>,
    progress: Map<String, WatchProgress>,
    onOpen: (String) -> Unit,
) {
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(feeds, key = { it.videoId }) { f ->
            val age = DateUtils.getRelativeTimeSpanString(f.publishedAt, now, DateUtils.MINUTE_IN_MILLIS)
            MediaRow(
                title = f.title,
                subtitle = "${f.author} · $age",
                leading = MediaLeading.Thumbnail(f.thumbnail, progress[f.videoId]),
                onClick = { onOpen(f.url) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}
