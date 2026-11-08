package com.staninna.yourtube

import com.staninna.yourtube.data.FeedPageSize
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedControls
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedResults
import com.staninna.yourtube.feed.FeedRules
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.feed.FeedView
import com.staninna.yourtube.feed.FetchFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Feeds as the screen uses it: every subscribed channel's uploads, fetched [FeedRules.CONCURRENCY] at a
 * time through [fetcher], merged, filtered by tag chips and paged. It starts from the store's feed
 * cache, so a cold start shows the last list at once; each channel's answer replaces its part and is
 * cached again. A channel is fetched when it is first seen (at start, or when subscribed) and on
 * refresh; a failed fetch keeps its last uploads. Everything runs on [scope], one coroutine at a time
 * (the main thread).
 */
class FeedsModel(
    private val store: Store,
    private val fetcher: FeedFetcher,
    private val scope: CoroutineScope,
) {
    private val results = MutableStateFlow(FeedResults.cached(store.feeds.value, FeedRules.subscriptions(store.library.value)))
    private val controls = MutableStateFlow(FeedControls())
    private val gate = Semaphore(FeedRules.CONCURRENCY)

    val view: StateFlow<FeedView> = combine(store.library, results, controls, store.settings) { lib, res, ctl, settings ->
        FeedRules.view(lib, res, ctl, settings.feedPageSize)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        FeedRules.view(store.library.value, results.value, controls.value, store.settings.value.feedPageSize),
    )

    init {
        // New subscriptions, the first batch included, are fetched as they appear; gone ones leave.
        scope.launch {
            var known = emptySet<String>()
            store.library.map(FeedRules::subscriptions).distinctUntilChanged().collect { subs ->
                if (!subs.containsAll(known)) {
                    results.update { it.keep(subs) }
                    store.cacheFeeds(results.value.cache())
                }
                fetch(subs - known)
                known = subs
            }
        }
    }

    /** Fetches every subscribed channel again; what is on screen stays until each one answers. */
    fun refresh() = fetch(FeedRules.subscriptions(store.library.value))

    /** Fetches again the failed channels the chips let through, the ones the notice names. */
    fun retryFailed() = fetch(view.value.failed.mapTo(HashSet()) { it.id })

    fun loadMore() = controls.update { it.copy(pages = it.pages + 1) }

    /** Flips a tag chip. A new filter starts again at the first page. */
    fun toggleTag(tagId: String) = controls.update {
        it.copy(selection = FeedRules.toggleChip(store.library.value, it.selection, tagId), pages = 1)
    }

    fun clearTags() = controls.update { it.copy(selection = emptySet(), pages = 1) }

    /** The list is at its top, so the "new videos" pill has done its job. */
    fun seenNewVideos() = results.update { it.copy(fresh = emptySet()) }

    /** Stores the page size; the list starts again at one page of the new size. */
    fun setPageSize(size: FeedPageSize) {
        store.updateSettings { it.copy(feedPageSize = size) }
        controls.update { it.copy(pages = 1) }
    }

    private fun fetch(channelIds: Set<String>) {
        val todo = channelIds - results.value.loading
        if (todo.isEmpty()) return
        results.update { it.start(todo) }
        todo.forEach { id -> scope.launch { fetchOne(id) } }
    }

    private suspend fun fetchOne(channelId: String) {
        // A cancelled fetch counts as failed, so the channel always leaves the loading set.
        var outcome: ChannelFetch = ChannelFetch.Failed(FetchFailure.UNAVAILABLE)
        try {
            outcome = gate.withPermit { fetcher.fetch(channelId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A fetcher bug must not take the app down from the view model's scope; it fails one channel.
            Diagnostics.count("feed.error", "$channelId: $e")
        } finally {
            val subscribed = channelId in FeedRules.subscriptions(store.library.value)
            results.update { if (subscribed) it.finish(channelId, outcome) else it.abandon(channelId) }
            store.cacheFeeds(results.value.cache())
        }
        when (val o = outcome) {
            is ChannelFetch.Ok -> if (o.source == FeedSource.PAGE) Diagnostics.count("feed.rss.fallback", channelId)
            is ChannelFetch.Failed -> if (o.reason == FetchFailure.UNREADABLE) Diagnostics.count("feed.unreadable", channelId)
        }
    }
}
