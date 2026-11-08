package com.staninna.yourtube.feed

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

/** Fetches one channel's newest uploads. Feeds runs several at once; tests inject a fake. */
fun interface FeedFetcher {
    suspend fun fetch(channelId: String): ChannelFetch
}

/**
 * The real fetcher, anonymous and keyless. Per channel: the RSS feed under [RetryPolicy.RSS], exact
 * times; when that fails, the channel's videos page under [RetryPolicy.PAGE], rounded times. Without
 * a connection the page is not tried. See `docs/research/feeds-source.md` for why both.
 */
class YouTubeFeedFetcher(
    private val get: suspend (url: String) -> HttpResult = YouTubeHttp::get,
    private val now: () -> Long = System::currentTimeMillis,
) : FeedFetcher {

    override suspend fun fetch(channelId: String): ChannelFetch {
        val rss = RetryPolicy.RSS.run { get(RssFeed.url(channelId)) }
        if (rss is HttpResult.Ok) offMain { RssFeed.parse(rss.body, channelId) }?.let { return ChannelFetch.Ok(it, FeedSource.RSS) }
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
