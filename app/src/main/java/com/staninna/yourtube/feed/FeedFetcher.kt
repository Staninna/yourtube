package com.staninna.yourtube.feed

import com.staninna.yourtube.Diagnostics
import com.staninna.yourtube.HttpResult
import com.staninna.yourtube.YouTubeHttp
import com.staninna.yourtube.data.FeedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a channel's uploads came from. */
enum class FeedSource { RSS, PAGE }

/** Why a channel's uploads could not be read. */
enum class FetchFailure {
    /** The device has no network, or no name resolution. */
    OFFLINE,

    /** YouTube answered with an error, refused the connection, or did not answer in time. */
    UNAVAILABLE,

    /** YouTube answered with a page we could not read: markup drift. */
    UNREADABLE,
}

/** One channel's newest uploads, newest first, or why there are none. */
sealed interface ChannelFetch {
    data class Ok(val videos: List<FeedVideo>, val source: FeedSource) : ChannelFetch

    data class Failed(val reason: FetchFailure) : ChannelFetch
}

/**
 * Fetches one channel's newest uploads; [known] is the channel's last list, whose lengths carry over.
 * Feeds runs several at once; tests inject a fake.
 */
fun interface FeedFetcher {
    suspend fun fetch(channelId: String, known: List<FeedVideo>): ChannelFetch
}

/**
 * The real fetcher, anonymous and keyless. Per channel: the RSS feed under [RetryPolicy.RSS], exact
 * times; when that fails, the channel's videos page under [RetryPolicy.PAGE], rounded times. Without
 * a connection the page is not tried. RSS has no lengths, so [FeedLengths] says when to read them from
 * the videos and live pages. See `docs/research/feeds-source.md` for why both sources.
 */
class YouTubeFeedFetcher(
    private val get: suspend (url: String) -> HttpResult = YouTubeHttp::get,
    private val now: () -> Long = System::currentTimeMillis,
) : FeedFetcher {

    override suspend fun fetch(channelId: String, known: List<FeedVideo>): ChannelFetch {
        val rss = RetryPolicy.RSS.run { get(RssFeed.url(channelId)) }
        if (rss is HttpResult.Ok) {
            offMain { RssFeed.parse(rss.body, channelId) }?.let { return ChannelFetch.Ok(withLengths(channelId, it, known), FeedSource.RSS) }
        }
        if (rss is HttpResult.Failed && rss.offline) return ChannelFetch.Failed(FetchFailure.OFFLINE)

        return when (val page = RetryPolicy.PAGE.run { get(ChannelPage.url(channelId)) }) {
            is HttpResult.Ok -> offMain { ChannelPage.parse(page.body, channelId, now()) }
                ?.let { ChannelFetch.Ok(it, FeedSource.PAGE) }
                ?: ChannelFetch.Failed(FetchFailure.UNREADABLE)
            is HttpResult.Status -> ChannelFetch.Failed(FetchFailure.UNAVAILABLE)
            is HttpResult.Failed -> ChannelFetch.Failed(if (page.offline) FetchFailure.OFFLINE else FetchFailure.UNAVAILABLE)
        }
    }

    /**
     * [videos] from RSS with their lengths: carried from [known], then read from the videos page and,
     * for what it lacks, the live page, one try each (see [FeedLengths.worthReading]). No connection or
     * a 5xx leaves the rest without a length, so the next fetch asks again. A 4xx, or a page that does
     * not read (consent, markup drift), counts the rest as unlisted, so the pages wait six hours.
     */
    private suspend fun withLengths(channelId: String, videos: List<FeedVideo>, known: List<FeedVideo>): List<FeedVideo> {
        var out = FeedLengths.carry(videos, known)
        val at = now()
        for (tab in ChannelPage.Tab.entries) {
            if (!FeedLengths.worthReading(tab, out, at)) continue
            val found = when (val page = get(ChannelPage.url(channelId, tab))) {
                is HttpResult.Ok -> offMain { ChannelPage.lengths(page.body, channelId, at) }
                    ?: return FeedLengths.unlisted(out, at).also { Diagnostics.count("feed.lengths.unreadable", "$channelId/${tab.path}") }
                is HttpResult.Status -> return if (page.code in 400..499) FeedLengths.unlisted(out, at) else out
                is HttpResult.Failed -> return out
            }
            out = FeedLengths.fill(out, found, at)
        }
        return FeedLengths.unlisted(out, at)
    }

    /**
     * Runs [parse] off the caller's (main) thread: a 550 KB page is real work. A parser that throws on
     * input nobody foresaw counts as an unreadable page (null), not as a crash of the caller's scope.
     */
    private suspend fun <T> offMain(parse: () -> T?): T? = withContext(Dispatchers.Default) {
        try {
            parse()
        } catch (e: RuntimeException) {
            null
        }
    }
}
