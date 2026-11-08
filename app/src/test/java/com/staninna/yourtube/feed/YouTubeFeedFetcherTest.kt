package com.staninna.yourtube.feed

import com.staninna.yourtube.Diagnostics
import com.staninna.yourtube.HttpResult
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.VideoLength
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RSS first, the videos page when RSS fails, per channel; lengths for RSS videos from the videos and
 * live pages. Robolectric for the RSS parser.
 */
@RunWith(RobolectricTestRunner::class)
class YouTubeFeedFetcherTest {

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("feed/$name")).readText()

    private val ltt = "UCXuqSBlHAE6Xw-yeJA0Tunw"
    private val lofi = "UCSJ4gkVC6NrvII8umztf0Ow"
    private val now = 1_800_000_000_000L
    private val requests = mutableListOf<String>()
    private val unreadableBefore = Diagnostics.counts.value["feed.lengths.unreadable"] ?: 0

    /** LTT's two videos in `rss-ltt.xml` with their lengths already known, so no page is read for them. */
    private val lttKnown = listOf("7hlTu95nOCw", "Ao9IQyaH0fk").map {
        FeedVideo(it, "", "", ltt, 0, length = VideoLength.Known(600))
    }

    /** A fetcher whose RSS, videos page and live page requests answer from the given queues, in order. */
    private fun fetcher(
        rss: List<HttpResult>,
        page: List<HttpResult> = emptyList(),
        live: List<HttpResult> = emptyList(),
        at: Long = now,
    ): YouTubeFeedFetcher {
        val answers = mapOf("rss" to ArrayDeque(rss), "videos" to ArrayDeque(page), "streams" to ArrayDeque(live))
        return YouTubeFeedFetcher(
            get = { url ->
                requests += url
                val kind = if ("feeds/videos.xml" in url) "rss" else url.substringAfterLast('/')
                answers.getValue(kind).removeFirst()
            },
            now = { at },
        )
    }

    private fun rssCount() = requests.count { "feeds/videos.xml" in it }
    private fun pageCount() = requests.count { it.endsWith("/videos") }
    private fun liveCount() = requests.count { it.endsWith("/streams") }

    @Test fun aWorkingFeedWithKnownLengthsNeverTouchesThePages() = runTest {
        val result = fetcher(listOf(HttpResult.Ok(fixture("rss-ltt.xml")))).fetch(ltt, lttKnown)
        assertEquals(FeedSource.RSS, (result as ChannelFetch.Ok).source)
        assertEquals(3, result.videos.size)
        assertEquals(listOf(RssFeed.url(ltt)), requests)
        assertEquals(listOf(VideoLength.Known(600), null, VideoLength.Known(600)), result.videos.map { it.length })
    }

    @Test fun aFlappingFeedIsTriedAgain() = runTest {
        val result = fetcher(listOf(HttpResult.Status(404), HttpResult.Ok(fixture("rss-ltt.xml")))).fetch(ltt, lttKnown)
        assertEquals(FeedSource.RSS, (result as ChannelFetch.Ok).source)
        assertEquals(2, rssCount())
        assertEquals(0, pageCount())
    }

    @Test fun lengthsComeFromTheVideosPageThenTheLivePage() = runTest {
        val result = fetcher(
            rss = listOf(HttpResult.Ok(fixture("rss-lofi.xml"))),
            page = listOf(HttpResult.Ok(fixture("videos-page.html"))),
            live = listOf(HttpResult.Ok(fixture("streams-page.html"))),
        ).fetch(lofi, emptyList())
        val videos = (result as ChannelFetch.Ok).videos
        assertEquals(listOf("RmoMitddWek", "d3QQHqRmvH4", "SzXRVyrazYs", "0sfDth4lMLc", "PVSOJyG5WKU"), videos.map { it.videoId })
        assertEquals(
            listOf(null, VideoLength.Known(73), VideoLength.Unlisted(now), VideoLength.Live(now), VideoLength.Known(39)),
            videos.map { it.length },
        )
        assertEquals(listOf(RssFeed.url(lofi), ChannelPage.url(lofi), ChannelPage.url(lofi, ChannelPage.Tab.LIVE)), requests)
    }

    @Test fun theLivePageIsSkippedWhenTheVideosPageHadEveryLength() = runTest {
        val known = listOf("SzXRVyrazYs", "0sfDth4lMLc").map { FeedVideo(it, "", "", lofi, 0, length = VideoLength.Known(60)) }
        fetcher(
            rss = listOf(HttpResult.Ok(fixture("rss-lofi.xml"))),
            page = listOf(HttpResult.Ok(fixture("videos-page.html"))),
        ).fetch(lofi, known)
        assertEquals(1, pageCount())
        assertEquals(0, liveCount())
    }

    @Test fun liveAndUnlistedAnswersHoldForSixHours() = runTest {
        val first = fetcher(
            rss = listOf(HttpResult.Ok(fixture("rss-lofi.xml"))),
            page = listOf(HttpResult.Ok(fixture("videos-page.html"))),
            live = listOf(HttpResult.Ok(fixture("streams-page.html"))),
        ).fetch(lofi, emptyList()) as ChannelFetch.Ok
        requests.clear()

        fetcher(listOf(HttpResult.Ok(fixture("rss-lofi.xml"))), at = now + FeedLengths.RECHECK_MS - 1).fetch(lofi, first.videos)
        assertEquals("within six hours only RSS is read", 1, requests.size)

        requests.clear()
        val later = fetcher(
            rss = listOf(HttpResult.Ok(fixture("rss-lofi.xml"))),
            page = listOf(HttpResult.Ok(fixture("videos-page.html"))),
            live = listOf(HttpResult.Ok(fixture("streams-page.html"))),
            at = now + FeedLengths.RECHECK_MS,
        ).fetch(lofi, first.videos) as ChannelFetch.Ok
        assertEquals(3, requests.size)
        assertEquals(VideoLength.Known(73), later.videos[1].length)
    }

    @Test fun aServerErrorOrNoConnectionLeavesLengthsUnknownForTheNextFetch() = runTest {
        listOf(HttpResult.Status(503), HttpResult.Failed(offline = false)).forEach { answer ->
            requests.clear()
            val result = fetcher(
                rss = listOf(HttpResult.Ok(fixture("rss-lofi.xml"))),
                page = listOf(answer),
            ).fetch(lofi, emptyList()) as ChannelFetch.Ok
            assertEquals("one try, no live page after a failure", listOf(1, 0), listOf(pageCount(), liveCount()))
            assertEquals(listOf(null, null, null, null, null), result.videos.map { it.length })
        }
    }

    @Test fun aRefusalOrAnUnreadablePageWaitsSixHoursAndIsCounted() = runTest {
        val unlisted = VideoLength.Unlisted(now)
        listOf(HttpResult.Status(429), HttpResult.Status(404), HttpResult.Ok("<html>consent</html>")).forEach { answer ->
            requests.clear()
            val result = fetcher(
                rss = listOf(HttpResult.Ok(fixture("rss-lofi.xml"))),
                page = listOf(answer),
            ).fetch(lofi, emptyList()) as ChannelFetch.Ok
            assertEquals(listOf(1, 0), listOf(pageCount(), liveCount()))
            assertEquals(listOf(null, unlisted, unlisted, unlisted, unlisted), result.videos.map { it.length })
        }
        assertEquals(1, Diagnostics.counts.value["feed.lengths.unreadable"]?.minus(unreadableBefore))
    }

    @Test fun streamsDueForAnotherLookGoStraightToTheLivePage() = runTest {
        val known = listOf(
            FeedVideo("SzXRVyrazYs", "", "", lofi, 0, length = VideoLength.Known(60)),
            FeedVideo("0sfDth4lMLc", "", "", lofi, 0, length = VideoLength.Live(now - FeedLengths.RECHECK_MS)),
            FeedVideo("d3QQHqRmvH4", "", "", lofi, 0, length = VideoLength.Known(73)),
            FeedVideo("PVSOJyG5WKU", "", "", lofi, 0, length = VideoLength.Known(39)),
        )
        val result = fetcher(
            rss = listOf(HttpResult.Ok(fixture("rss-lofi.xml"))),
            live = listOf(HttpResult.Ok(fixture("streams-page.html"))),
        ).fetch(lofi, known) as ChannelFetch.Ok
        assertEquals(listOf(0, 1), listOf(pageCount(), liveCount()))
        assertEquals(VideoLength.Live(now), result.videos[3].length)
    }

    @Test fun aFeedThatKeepsFailingFallsBackToTheVideosPage() = runTest {
        val result = fetcher(
            rss = listOf(HttpResult.Status(404), HttpResult.Status(500)),
            page = listOf(HttpResult.Ok(fixture("videos-page.html"))),
        ).fetch(lofi, emptyList())
        assertEquals(FeedSource.PAGE, (result as ChannelFetch.Ok).source)
        assertEquals(listOf("d3QQHqRmvH4", "PVSOJyG5WKU", "vadHXNBy4jQ"), result.videos.map { it.videoId })
        assertEquals("the page has lengths of its own", listOf(73L, 39L, 28_800L), result.videos.map { (it.length as VideoLength.Known).seconds })
        assertEquals(listOf(RssFeed.url(lofi), RssFeed.url(lofi), ChannelPage.url(lofi)), requests)
    }

    @Test fun anErrorPageServedWithOkFallsBackToo() = runTest {
        val result = fetcher(
            rss = listOf(HttpResult.Ok(fixture("rss-404.html"))),
            page = listOf(HttpResult.Ok(fixture("videos-page.html"))),
        ).fetch(lofi, emptyList())
        assertEquals(FeedSource.PAGE, (result as ChannelFetch.Ok).source)
        assertEquals("a 200 is not retried", 1, rssCount())
    }

    @Test fun offlineStopsWithoutTryingThePage() = runTest {
        val result = fetcher(listOf(HttpResult.Failed(offline = true))).fetch(ltt, emptyList())
        assertEquals(ChannelFetch.Failed(FetchFailure.OFFLINE), result)
        assertEquals(0, pageCount())
    }

    @Test fun bothSourcesFailingSaysWhy() = runTest {
        val down = listOf(HttpResult.Status(404), HttpResult.Status(404))
        assertEquals(ChannelFetch.Failed(FetchFailure.UNAVAILABLE), fetcher(down, listOf(HttpResult.Status(404))).fetch(lofi, emptyList()))
        assertEquals(
            ChannelFetch.Failed(FetchFailure.UNAVAILABLE),
            fetcher(down, List(3) { HttpResult.Failed(offline = false) }).fetch(lofi, emptyList()),
        )
        assertEquals(
            ChannelFetch.Failed(FetchFailure.OFFLINE),
            fetcher(down, listOf(HttpResult.Failed(offline = true))).fetch(lofi, emptyList()),
        )
        assertEquals(
            "a page we cannot read is markup drift",
            ChannelFetch.Failed(FetchFailure.UNREADABLE),
            fetcher(down, listOf(HttpResult.Ok("<html>consent</html>"))).fetch(lofi, emptyList()),
        )
    }
}
