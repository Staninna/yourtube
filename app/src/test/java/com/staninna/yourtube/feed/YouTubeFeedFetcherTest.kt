package com.staninna.yourtube.feed

import com.staninna.yourtube.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** RSS first, the videos page when RSS fails, per channel. Robolectric for the RSS parser. */
@RunWith(RobolectricTestRunner::class)
class YouTubeFeedFetcherTest {

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("feed/$name")).readText()

    private val ltt = "UCXuqSBlHAE6Xw-yeJA0Tunw"
    private val lofi = "UCSJ4gkVC6NrvII8umztf0Ow"
    private val requests = mutableListOf<String>()

    /** A fetcher whose RSS and page requests answer from the given queues, in order. */
    private fun fetcher(rss: List<HttpResult>, page: List<HttpResult> = emptyList()): YouTubeFeedFetcher {
        val rssAnswers = ArrayDeque(rss)
        val pageAnswers = ArrayDeque(page)
        return YouTubeFeedFetcher(
            get = { url ->
                requests += url
                if ("feeds/videos.xml" in url) rssAnswers.removeFirst() else pageAnswers.removeFirst()
            },
            now = { 1_800_000_000_000L },
        )
    }

    private fun rssCount() = requests.count { "feeds/videos.xml" in it }
    private fun pageCount() = requests.count { it.endsWith("/videos") }

    @Test fun aWorkingFeedNeverTouchesThePage() = runTest {
        val result = fetcher(listOf(HttpResult.Ok(fixture("rss-ltt.xml")))).fetch(ltt)
        assertEquals(FeedSource.RSS, (result as ChannelFetch.Ok).source)
        assertEquals(3, result.videos.size)
        assertEquals(listOf(RssFeed.url(ltt)), requests)
    }

    @Test fun aFlappingFeedIsTriedAgain() = runTest {
        val result = fetcher(listOf(HttpResult.Status(404), HttpResult.Ok(fixture("rss-ltt.xml")))).fetch(ltt)
        assertEquals(FeedSource.RSS, (result as ChannelFetch.Ok).source)
        assertEquals(2, rssCount())
        assertEquals(0, pageCount())
    }

    @Test fun aFeedThatKeepsFailingFallsBackToTheVideosPage() = runTest {
        val result = fetcher(
            rss = listOf(HttpResult.Status(404), HttpResult.Status(500)),
            page = listOf(HttpResult.Ok(fixture("videos-page.html"))),
        ).fetch(lofi)
        assertEquals(FeedSource.PAGE, (result as ChannelFetch.Ok).source)
        assertEquals(listOf("d3QQHqRmvH4", "PVSOJyG5WKU", "vadHXNBy4jQ"), result.videos.map { it.videoId })
        assertEquals(listOf(RssFeed.url(lofi), RssFeed.url(lofi), ChannelPage.url(lofi)), requests)
    }

    @Test fun anErrorPageServedWithOkFallsBackToo() = runTest {
        val result = fetcher(
            rss = listOf(HttpResult.Ok(fixture("rss-404.html"))),
            page = listOf(HttpResult.Ok(fixture("videos-page.html"))),
        ).fetch(lofi)
        assertEquals(FeedSource.PAGE, (result as ChannelFetch.Ok).source)
        assertEquals("a 200 is not retried", 1, rssCount())
    }

    @Test fun offlineStopsWithoutTryingThePage() = runTest {
        val result = fetcher(listOf(HttpResult.Failed(offline = true))).fetch(ltt)
        assertEquals(ChannelFetch.Failed(FetchFailure.OFFLINE), result)
        assertEquals(0, pageCount())
    }

    @Test fun bothSourcesFailingSaysWhy() = runTest {
        val down = listOf(HttpResult.Status(404), HttpResult.Status(404))
        assertEquals(ChannelFetch.Failed(FetchFailure.UNAVAILABLE), fetcher(down, listOf(HttpResult.Status(404))).fetch(lofi))
        assertEquals(
            ChannelFetch.Failed(FetchFailure.UNAVAILABLE),
            fetcher(down, List(3) { HttpResult.Failed(offline = false) }).fetch(lofi),
        )
        assertEquals(ChannelFetch.Failed(FetchFailure.OFFLINE), fetcher(down, listOf(HttpResult.Failed(offline = true))).fetch(lofi))
        assertEquals(
            "a page we cannot read is markup drift",
            ChannelFetch.Failed(FetchFailure.UNREADABLE),
            fetcher(down, listOf(HttpResult.Ok("<html>consent</html>"))).fetch(lofi),
        )
    }
}
