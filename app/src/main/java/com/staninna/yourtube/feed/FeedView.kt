package com.staninna.yourtube.feed

import com.staninna.yourtube.data.FeedPageSize
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.LibraryFilter
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.TagChoices
import com.staninna.yourtube.data.TagFilter

/**
 * One channel's state while the app runs. [videos] are its last uploads that came in (or were cached),
 * null until the first; [failure] is why its latest fetch failed.
 */
data class ChannelFeed(val videos: List<FeedVideo>?, val failure: FetchFailure?)

/**
 * What Feeds holds: each channel by id, the channels being fetched now, and [fresh], the videos that
 * refreshes brought in since the user last saw the top of the list.
 */
data class FeedResults(
    val channels: Map<String, ChannelFeed> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val fresh: Set<String> = emptySet(),
) {
    fun start(channelIds: Set<String>): FeedResults = copy(loading = loading + channelIds)

    /**
     * [channelId] is done with [fetch]. A failure keeps the channel's last good uploads on screen. A
     * success marks as fresh the videos its previous list did not have and that are newer than all of
     * it; a channel's first list (just subscribed, or back from failing from the start) marks nothing.
     */
    fun finish(channelId: String, fetch: ChannelFetch): FeedResults {
        val previous = channels[channelId]?.videos
        return when (fetch) {
            is ChannelFetch.Ok -> copy(
                channels = channels + (channelId to ChannelFeed(fetch.videos, failure = null)),
                loading = loading - channelId,
                fresh = fresh + freshIn(previous, fetch.videos),
            )
            is ChannelFetch.Failed -> copy(
                channels = channels + (channelId to ChannelFeed(previous, fetch.reason)),
                loading = loading - channelId,
            )
        }
    }

    /** [channelId]'s fetch ended after it was unsubscribed; nothing of it is kept. */
    fun abandon(channelId: String): FeedResults = copy(loading = loading - channelId)

    /** Only [subscriptions] stay; an unsubscribed channel's videos leave with it. */
    fun keep(subscriptions: Set<String>): FeedResults = copy(channels = channels.filterKeys { it in subscriptions })

    /**
     * The feed cache as someone else wrote it (the background new-video check): a subscribed channel
     * whose cached list differs from ours, and that is not being fetched, takes it as if fetched.
     * Our own writes match what we hold, so they change nothing.
     */
    fun adopt(cache: Map<String, List<FeedVideo>>, subscriptions: Set<String>, cap: Int): FeedResults =
        cache.entries.fold(this) { acc, (channelId, videos) ->
            val ours = acc.channels[channelId]?.videos?.take(cap)
            if (channelId !in subscriptions || channelId in acc.loading || ours == videos) acc
            else acc.finish(channelId, ChannelFetch.Ok(videos, FeedSource.RSS))
        }

    /** Every channel's last uploads, for the store's cache. */
    fun cache(): Map<String, List<FeedVideo>> = channels.mapNotNull { (id, feed) -> feed.videos?.let { id to it } }.toMap()

    private fun freshIn(previous: List<FeedVideo>?, videos: List<FeedVideo>): List<String> {
        if (previous.isNullOrEmpty()) return emptyList()
        val known = previous.mapTo(HashSet()) { it.videoId }
        val newest = previous.maxOf { it.publishedAt }
        return videos.filter { it.videoId !in known && it.publishedAt > newest }.map { it.videoId }
    }

    companion object {
        /** A cold start: the cached lists of [subscriptions], shown until each channel refreshes. */
        fun cached(cache: Map<String, List<FeedVideo>>, subscriptions: Set<String>): FeedResults =
            FeedResults(channels = cache.filterKeys { it in subscriptions }.mapValues { (_, videos) -> ChannelFeed(videos, failure = null) })
    }
}

/** Feeds' in-memory choices: the tag filter and the pages revealed by Load more. */
data class FeedControls(val filter: TagFilter = TagFilter(), val pages: Int = 1)

/** What Feeds shows. */
data class FeedView(
    /** The revealed pages of the merged list, newest first. */
    val videos: List<FeedVideo>,
    /** Videos past the tag filter, revealed or not. */
    val matching: Int,
    /** The tag filter sheet's choices over the subscribed channels, with the filter in effect. */
    val tags: TagChoices,
    /** Subscribed channels past the tag filter. */
    val channels: Int,
    val subscriptions: Int,
    /** A subscribed channel is being fetched, or has not been yet. */
    val loading: Boolean,
    /** Channels past the tag filter whose latest fetch failed, newest subscription first. */
    val failed: List<StarredItem>,
    /** Every one of [failed] failed for want of a connection. */
    val offline: Boolean,
    /** One of [failed] is being fetched again. */
    val retrying: Boolean,
    /** Whether any subscribed channel has videos, the tag filter aside. */
    val anyVideos: Boolean,
    /** Channel avatars by channel id, for the rows. */
    val avatars: Map<String, String>,
    /** Fresh videos past the tag filter, for the "new videos" pill. */
    val newVideos: Int,
    val pageSize: FeedPageSize,
) {
    val hasMore: Boolean get() = matching > videos.size

    /** Fetching behind a list already on screen: the pull-to-refresh indicator, not the loading state. */
    val refreshing: Boolean get() = loading && videos.isNotEmpty()

    val status: Status
        get() = when {
            subscriptions == 0 -> Status.NO_SUBSCRIPTIONS
            videos.isNotEmpty() -> Status.LIST
            loading -> Status.LOADING
            failed.isNotEmpty() -> if (offline) Status.OFFLINE else Status.FAILED
            anyVideos -> Status.NO_MATCH
            else -> Status.NO_VIDEOS
        }

    /** What fills the screen. Partial failures show inside [LIST] as a notice over the rows. */
    enum class Status { NO_SUBSCRIPTIONS, LOADING, OFFLINE, FAILED, NO_MATCH, NO_VIDEOS, LIST }
}

/** Feeds' rules: every starred channel's uploads merged, filtered by tags, paged client-side. */
object FeedRules {
    /** Channel fetches that run at once. */
    const val CONCURRENCY = 6

    /** Subscribed channel ids. A starred channel's id is its UC id. */
    fun subscriptions(library: Library): Set<String> =
        library.items.filter { it.type == ItemType.CHANNEL }.mapTo(LinkedHashSet()) { it.id }

    /**
     * The uploads of subscribed channels past the tag filter, newest first, each video once, revealed
     * [FeedControls.pages] at a time. The filter works as in Library's Subs segment (any or all of the
     * selected tags, or untagged channels only), and a choice no channel offers any more drops out.
     * With [removeShorts] Shorts are left out, here and in every count.
     */
    fun view(library: Library, results: FeedResults, controls: FeedControls, pageSize: FeedPageSize, removeShorts: Boolean): FeedView {
        val subs = subscriptions(library)
        val chipView = LibraryFilter.view(library, ItemType.CHANNEL, controls.filter, query = "")
        val shown = chipView.items.flatMap { channel -> results.channels[channel.id]?.videos.orEmpty().map { named(it, channel) } }
            .filterNot { removeShorts && it.isShort }
            .distinctBy { it.videoId }
            .sortedByDescending { it.publishedAt }
        val failed = chipView.items.filter { results.channels[it.id]?.failure != null }
        val failures = failed.mapNotNull { results.channels[it.id]?.failure }
        return FeedView(
            videos = shown.take(controls.pages * pageSize.count),
            matching = shown.size,
            tags = chipView.tags,
            channels = chipView.items.size,
            subscriptions = subs.size,
            loading = subs.any { it in results.loading || it !in results.channels },
            failed = failed,
            offline = failures.isNotEmpty() && failures.all { it == FetchFailure.OFFLINE },
            retrying = failed.any { it.id in results.loading },
            anyVideos = subs.any { id -> results.channels[id]?.videos.orEmpty().any { !(removeShorts && it.isShort) } },
            avatars = library.items.filter { it.type == ItemType.CHANNEL }
                .mapNotNull { channel -> channel.thumbnail?.let { channel.id to it } }.toMap(),
            newVideos = shown.count { it.videoId in results.fresh },
            pageSize = pageSize,
        )
    }

    /**
     * Each channel's newest upload time in [results], for Library's Subs rows; with [removeShorts] a
     * Short does not count. A channel without a video Feeds would show has none.
     */
    fun latestUploads(results: FeedResults, removeShorts: Boolean): Map<String, Long> =
        results.channels.mapNotNull { (id, feed) ->
            feed.videos.orEmpty().filterNot { removeShorts && it.isShort }.maxOfOrNull { it.publishedAt }?.let { id to it }
        }.toMap()

    /** The filter in effect given the [stored] one, by Library's Subs rule; edits start from it. */
    fun inEffect(library: Library, stored: TagFilter): TagFilter = LibraryFilter.inEffect(library, ItemType.CHANNEL, stored)

    /** An RSS entry without a channel name or title still gets a readable row: the subscription's title. */
    private fun named(video: FeedVideo, channel: StarredItem): FeedVideo = when {
        video.author.isNotBlank() && video.title.isNotBlank() -> video
        else -> video.copy(
            author = video.author.ifBlank { channel.title },
            title = video.title.ifBlank { channel.title },
        )
    }
}
