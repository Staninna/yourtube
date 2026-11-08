package com.staninna.yourtube.notify

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.FeedLengths
import com.staninna.yourtube.feed.FeedRules
import com.staninna.yourtube.feed.NewVideos
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What the check needs from Android's notifications; tests fake it. */
interface NewVideoAlerts {
    /** Whether a notification would show: the permission, the app's switch, and the "New videos" channel. */
    fun allowed(): Boolean

    /**
     * Loads what the notifications for [videos] (newest first, all of them) show, such as stills; may
     * suspend on the network and be cancelled. Returns the posting, which must not suspend.
     */
    suspend fun prepare(videos: List<FeedVideo>): () -> Unit
}

/**
 * One background check: every subscribed channel's RSS feed through [rss], [FeedRules.CONCURRENCY] at
 * a time, then [NewVideos] decides what is new. Notifications are prepared first; saving the seen state
 * and posting then happen together, beyond cancellation, so a video is never marked seen unposted.
 * The fetched lists also go into the feed cache, so Feeds shows them.
 */
class NewVideoCheck(
    private val store: Store,
    private val rss: suspend (channelId: String) -> List<FeedVideo>?,
    private val alerts: NewVideoAlerts,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Returns the videos it notified about, newest first. */
    suspend fun run(): List<FeedVideo> {
        if (!store.settings.value.newVideoNotifications) return emptyList()
        if (!alerts.allowed()) {
            // Nothing would show. Starting over means the run after they are allowed again seeds, rather
            // than bringing everything uploaded while they were blocked.
            if (store.notify.value.seen.isNotEmpty()) store.restartNotify()
            return emptyList()
        }
        val start = store.notify.value
        val library = store.library.value
        val subscriptions = FeedRules.subscriptions(library)
        val gate = Semaphore(FeedRules.CONCURRENCY)
        val fetched = coroutineScope {
            subscriptions.map { id -> async { gate.withPermit { id to rss(id) } } }.awaitAll()
        }.mapNotNull { (id, videos) -> videos?.let { id to it } }.toMap()

        // RSS can leave the channel's name out; the subscription's title stands in, as in Feeds.
        val titles = library.items.filter { it.type == ItemType.CHANNEL }.associate { it.id to it.title }
        val named = fetched.mapValues { (id, videos) -> videos.map { if (it.author.isBlank()) it.copy(author = titles[id].orEmpty()) else it } }

        val round = NewVideos.check(start, named, subscriptions, store.settings.value.removeShorts, now())
        val post = if (round.fresh.isEmpty()) null else alerts.prepare(round.fresh)
        return withContext(NonCancellable) {
            mergeIntoCache(named)
            if (store.replaceNotify(start, round.state)) {
                post?.invoke()
                round.fresh
            } else {
                // Turned on again or restarted mid-check: that new start owns the state.
                emptyList()
            }
        }
    }

    /** The fetched lists into the feed cache, for channels still subscribed, keeping the lengths it knows. */
    private fun mergeIntoCache(named: Map<String, List<FeedVideo>>) = store.updateFeeds { cache ->
        val subscribed = FeedRules.subscriptions(store.library.value)
        cache + named.filterKeys { it in subscribed }.mapValues { (id, videos) -> FeedLengths.carry(videos, cache[id].orEmpty()) }
    }
}
