package com.staninna.yourtube.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.FeedsModel
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.Tab
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.feed.FeedView
import com.staninna.yourtube.ui.components.ChannelAvatar
import com.staninna.yourtube.ui.components.ChipOption
import com.staninna.yourtube.ui.components.EmptyState
import com.staninna.yourtube.ui.components.ErrorState
import com.staninna.yourtube.ui.components.FilterChipsRow
import com.staninna.yourtube.ui.components.LoadingState
import com.staninna.yourtube.ui.components.MediaLeading
import com.staninna.yourtube.ui.components.MediaRow
import com.staninna.yourtube.ui.components.StateAction
import com.staninna.yourtube.ui.components.TabScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Newest uploads of every starred channel, merged newest first: tag chips over the list, pull to
 * refresh, a page at a time with Load more, a quiet notice with Retry for channels that failed, and a
 * "new videos" pill when a refresh brings some while the list is scrolled down.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedsScreen(vm: MainViewModel, onSearch: () -> Unit, onSettings: () -> Unit) {
    val view by vm.feeds.view.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val feeds = vm.feeds
    // A new chip selection is a new list, so it starts at its top.
    val list = remember(view.selected) { LazyListState() }
    // The bar tints from where the list is; with no list on screen there is nothing under it.
    val notScrolled = rememberScrollState()

    TabScaffold(
        title = "Feeds",
        onSearch = onSearch,
        onSettings = onSettings,
        scrolled = if (view.status == FeedView.Status.LIST) list else notScrolled,
        actions = {
            // Pull to refresh has no TalkBack gesture; the button is its accessible twin.
            if (view.subscriptions > 0) IconButton(onClick = feeds::refresh, enabled = !view.loading) { Icon(Icons.Filled.Refresh, "Refresh feeds") }
        },
    ) { padding ->
        if (view.status == FeedView.Status.NO_SUBSCRIPTIONS) {
            EmptyState(
                icon = Icons.Outlined.Subscriptions,
                title = "No subscriptions yet",
                body = "Star a channel in Watch and its newest videos show up here.",
                action = StateAction("Browse YouTube") { vm.selectTab(Tab.WATCH) },
                modifier = Modifier.padding(padding),
            )
            return@TabScaffold
        }
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            AnimatedVisibility(view.chips.isNotEmpty()) {
                FilterChipsRow(
                    options = view.chips.map { ChipOption(it.id, it.name, Color(it.color)) },
                    selected = view.selected,
                    onToggle = feeds::toggleTag,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            val pull = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = view.refreshing,
                onRefresh = feeds::refresh,
                state = pull,
                modifier = Modifier.weight(1f),
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(state = pull, isRefreshing = view.refreshing, modifier = Modifier.align(Alignment.TopCenter))
                },
            ) {
                FeedsContent(view, progress, feeds, list, onOpen = vm::open)
            }
        }
    }
}

@Composable
private fun FeedsContent(
    view: FeedView,
    progress: Map<String, WatchProgress>,
    feeds: FeedsModel,
    list: LazyListState,
    onOpen: (String) -> Unit,
) {
    when (view.status) {
        FeedView.Status.LIST -> FeedList(view, progress, feeds, list, onOpen)
        FeedView.Status.LOADING -> LoadingState("Fetching new videos")
        FeedView.Status.OFFLINE -> ErrorState(
            icon = Icons.Outlined.CloudOff,
            title = "You're offline",
            body = "Connect to the internet to see your channels' new videos.",
            action = StateAction("Retry", feeds::retryFailed),
        )
        FeedView.Status.FAILED -> ErrorState(
            icon = Icons.Outlined.ErrorOutline,
            title = "YouTube didn't answer",
            // With chips on, only the channels past them are in question.
            body = (if (view.selected.isEmpty()) "None of your channels" else "None of these channels") +
                " could be read just now. Try again in a moment.",
            action = StateAction("Retry", feeds::retryFailed),
        )
        FeedView.Status.NO_MATCH -> EmptyState(
            icon = Icons.Outlined.FilterAltOff,
            title = "Nothing under these tags",
            body = "The channels with these tags have no videos to show.",
            action = StateAction("Show all", feeds::clearTags),
        )
        FeedView.Status.NO_VIDEOS -> EmptyState(
            icon = Icons.Outlined.VideocamOff,
            title = "No videos yet",
            body = "Your channels have not uploaded anything Feeds can show.",
            action = StateAction("Refresh", feeds::refresh),
        )
        FeedView.Status.NO_SUBSCRIPTIONS -> Unit
    }
}

@Composable
private fun FeedList(
    view: FeedView,
    progress: Map<String, WatchProgress>,
    feeds: FeedsModel,
    list: LazyListState,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Keyed on the list, which is new for each chip selection.
    val atTop by remember(list) { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 } }
    val firstKey: Any? = if (view.failed.isNotEmpty()) NOTICE_KEY else view.videos.firstOrNull()?.videoId
    KeepTopInView(list, firstKey)
    // At the top the new videos are in sight; the pill is only for a list scrolled down.
    LaunchedEffect(atTop, view.newVideos) { if (atTop && view.newVideos > 0) feeds.seenNewVideos() }
    val now by produceState(System.currentTimeMillis()) {
        // Ages move on while the list is open; a minute is their finest step.
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag(FEED_LIST_TAG), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
            if (view.failed.isNotEmpty()) {
                item(key = NOTICE_KEY) { FailureNotice(view, feeds::retryFailed, Modifier.animateItem()) }
            }
            items(view.videos, key = { it.videoId }) { video ->
                FeedRow(video, view.avatars[video.channelId], progress[video.videoId], now, onOpen, Modifier.animateItem())
            }
            if (view.hasMore) {
                item(key = MORE_KEY) { LoadMore(view.videos.size, view.matching, feeds::loadMore) }
            }
        }
        NewVideosPill(
            count = view.newVideos,
            visible = view.newVideos > 0 && !atTop,
            onClick = { scope.launch { list.animateScrollToItem(0) } },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
    }
}

/**
 * Keeps a list that sits at its top there when items arrive above its first one: a lazy list holds on
 * to the item it shows, so the first load (channels land one by one) and a refresh would otherwise hide
 * what came in above it. A list scrolled down stays on its item, and the pill says what came in.
 */
@Composable
private fun KeepTopInView(list: LazyListState, firstKey: Any?) {
    val last = remember { FirstKey(firstKey) }
    // Runs before the next measure, so the request beats the list's own anchoring.
    SideEffect {
        if (firstKey == last.key) return@SideEffect
        if (list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) list.requestScrollToItem(0)
        last.key = firstKey
    }
}

/** The list's first key as of the last composition; not state, since nothing redraws from it. */
private class FirstKey(var key: Any?)

@Composable
private fun FeedRow(
    video: FeedVideo,
    avatar: String?,
    progress: WatchProgress?,
    now: Long,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    MediaRow(
        title = video.title,
        subtitle = video.author,
        detail = feedDetail(video, now),
        leading = MediaLeading.Thumbnail(video.thumbnail, progress, feedBadge(video)),
        onClick = { onOpen(video.url) },
        subtitleLeading = { ChannelAvatar(video.author, video.channelId, avatar, size = 20.dp) },
        modifier = modifier,
    )
}

/** Channels that failed, quietly over the rows: what came in stays readable. */
@Composable
private fun FailureNotice(view: FeedView, onRetry: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (view.offline) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                feedFailureNotice(view.failed.map { it.title }, view.offline),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRetry,
                enabled = !view.retrying,
                modifier = Modifier.semantics { if (view.retrying) stateDescription = "Retrying" },
            ) { Text(if (view.retrying) "Retrying" else "Retry") }
        }
    }
}

@Composable
private fun LoadMore(shown: Int, of: Int, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = onClick) { Text("Load more") }
        Text(
            "Showing $shown of $of",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "3 new videos" over the list; it scrolls to the top, where they are. */
@Composable
private fun NewVideosPill(count: Int, visible: Boolean, onClick: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Button(onClick = onClick, shape = MaterialTheme.shapes.extraLarge) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(plural(count, "new video", "new videos"), Modifier.padding(start = 8.dp))
        }
    }
}

private const val NOTICE_KEY = "failed-channels"
private const val MORE_KEY = "load-more"

/** The feed list's test tag, for instrumented tests that scroll it. */
const val FEED_LIST_TAG = "feed-list"
