package com.staninna.yourtube

import com.staninna.yourtube.data.FeedPageSize
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedRules
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.feed.FeedView
import com.staninna.yourtube.feed.FetchFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fetching, failures and Retry, paging, chips, fresh videos and the cache, with a fake fetcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedsModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: Store
    private val calls = mutableListOf<String>()

    /** What each channel answers next; a channel without an answer gets no videos. */
    private val answers = mutableMapOf<String, ArrayDeque<ChannelFetch>>()

    /** While set, every fetch waits for it. */
    private var gate: CompletableDeferred<Unit>? = null

    private fun answer(channelId: String, vararg results: ChannelFetch) {
        answers.getOrPut(channelId) { ArrayDeque() }.addAll(results)
    }

    private fun channel(id: String) = StarredItem(id, ItemType.CHANNEL, "Channel $id", url = "u", channelId = id)

    private fun video(channelId: String, n: Int, at: Long) = FeedVideo("$channelId%010d".format(n), "Video $n", channelId, channelId, at)

    private fun videos(channelId: String, count: Int, newest: Long = 1_000, first: Int = 0) =
        ChannelFetch.Ok(List(count) { video(channelId, first + it, newest - it) }, FeedSource.RSS)

    private val fake = FeedFetcher { id ->
        calls += id
        gate?.await()
        answers[id]?.removeFirstOrNull() ?: ChannelFetch.Ok(emptyList(), FeedSource.RSS)
    }

    /** Opens the store files, as a new process would. */
    private fun TestScope.openStore(): Store = Store(tmp.root, backgroundScope).also { store = it }

    private fun TestScope.model(vararg channels: String, fetcher: FeedFetcher = fake): FeedsModel {
        if (!::store.isInitialized) openStore()
        channels.forEach { store.star(channel(it)) }
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return FeedsModel(store, fetcher, scope)
    }

    @Test fun everySubscriptionIsFetchedAtStartAndMerged() = runTest {
        answer("A", videos("A", 2, newest = 100))
        answer("B", videos("B", 2, newest = 200))
        val model = model("A", "B")
        runCurrent()
        assertEquals(setOf("A", "B"), calls.toSet())
        assertEquals(listOf("B", "B", "A", "A"), model.view.value.videos.map { it.channelId })
        assertFalse(model.view.value.loading)
    }

    @Test fun aNewSubscriptionFetchesJustThatChannel() = runTest {
        val model = model("A")
        runCurrent()
        calls.clear()
        store.star(channel("B"))
        runCurrent()
        assertEquals(listOf("B"), calls)
        assertEquals(2, model.view.value.subscriptions)
    }

    @Test fun unsubscribingRemovesTheVideosAndResubscribingFetchesAgain() = runTest {
        answer("B", videos("B", 2), videos("B", 3))
        val model = model("A", "B")
        runCurrent()
        assertEquals(2, model.view.value.videos.size)
        store.unstar("B")
        runCurrent()
        assertTrue(model.view.value.videos.isEmpty())
        assertFalse("the cache drops it too", "B" in store.feeds.value)
        calls.clear()
        store.star(channel("B"))
        runCurrent()
        assertEquals(listOf("B"), calls)
        assertEquals(3, model.view.value.videos.size)
    }

    @Test fun aFailureKeepsWhatCameInAndRetryFetchesOnlyTheFailed() = runTest {
        answer("A", videos("A", 3), videos("A", 3))
        answer("B", videos("B", 2), ChannelFetch.Failed(FetchFailure.UNAVAILABLE), videos("B", 2))
        val model = model("A", "B")
        runCurrent()
        model.refresh()
        runCurrent()
        val view = model.view.value
        assertEquals("B's last videos stay", 5, view.videos.size)
        assertEquals(listOf("B"), view.failed.map { it.id })

        calls.clear()
        model.retryFailed()
        runCurrent()
        assertEquals(listOf("B"), calls)
        assertTrue(model.view.value.failed.isEmpty())
    }

    @Test fun aFetcherThatThrowsFailsItsChannelWithoutCrashing() = runTest {
        answer("A", videos("A", 2))
        val model = model("A", "B", fetcher = FeedFetcher { id ->
            if (id == "B") error("a bug in the fetcher")
            answers.getValue(id).removeFirst()
        })
        runCurrent()
        val view = model.view.value
        assertEquals(2, view.videos.size)
        assertEquals(listOf("B"), view.failed.map { it.id })
        assertFalse(view.loading)
    }

    @Test fun loadMoreRevealsAnotherPageAndChipsStartOver() = runTest {
        answer("A", videos("A", 120))
        val model = model("A")
        val music = store.createTag("Music", attachTo = "A")!!
        runCurrent()
        assertEquals(50, model.view.value.videos.size)
        model.loadMore()
        assertEquals(100, model.view.value.videos.size)
        model.toggleTag(music.id)
        assertEquals(setOf(music.id), model.view.value.selected)
        assertEquals(50, model.view.value.videos.size)
        model.clearTags()
        assertEquals(emptySet<String>(), model.view.value.selected)
    }

    @Test fun revealedPagesSurviveARefresh() = runTest {
        answer("A", videos("A", 120), videos("A", 120))
        val model = model("A")
        runCurrent()
        model.loadMore()
        model.refresh()
        runCurrent()
        assertEquals(100, model.view.value.videos.size)
    }

    @Test fun thePageSizeIsStoredAndStartsTheListOver() = runTest {
        answer("A", videos("A", 120))
        val model = model("A")
        runCurrent()
        model.loadMore()
        model.setPageSize(FeedPageSize.P25)
        runCurrent()
        assertEquals(FeedPageSize.P25, store.settings.value.feedPageSize)
        assertEquals(25, model.view.value.videos.size)
        model.loadMore()
        assertEquals(50, model.view.value.videos.size)
    }

    @Test fun aRefreshCountsTheNewVideosUntilSeen() = runTest {
        answer("A", videos("A", 2, newest = 100), videos("A", 4, newest = 300, first = 10))
        val model = model("A")
        runCurrent()
        assertEquals("the first load is not news", 0, model.view.value.newVideos)
        model.refresh()
        runCurrent()
        assertEquals(4, model.view.value.newVideos)
        model.seenNewVideos()
        assertEquals(0, model.view.value.newVideos)
    }

    @Test fun subscribingDuringARefreshCountsOnlyTheRefreshedNews() = runTest {
        answer("A", videos("A", 2, newest = 100), videos("A", 3, newest = 300, first = 10))
        answer("B", videos("B", 5, newest = 500))
        val model = model("A")
        runCurrent()
        gate = CompletableDeferred()
        model.refresh()
        store.star(channel("B"))
        runCurrent()
        assertEquals(listOf("A", "A", "B"), calls)
        gate!!.complete(Unit)
        runCurrent()
        assertEquals("B's first list is not news", 3, model.view.value.newVideos)
        assertEquals("A's refreshed three and B's five", 8, model.view.value.videos.size)
    }

    @Test fun toggleAChipDuringARefreshAndThePillFollowsTheChips() = runTest {
        answer("A", videos("A", 1, newest = 100), videos("A", 2, newest = 300, first = 10))
        answer("B", videos("B", 1, newest = 100), videos("B", 3, newest = 300, first = 10))
        val model = model("A", "B")
        val music = store.createTag("Music", attachTo = "A")!!
        runCurrent()
        gate = CompletableDeferred()
        model.refresh()
        model.toggleTag(music.id)
        gate!!.complete(Unit)
        runCurrent()
        assertEquals("A's two new ones; B's are behind the chip", 2, model.view.value.newVideos)
        model.clearTags()
        assertEquals(5, model.view.value.newVideos)
    }

    @Test fun aRefreshWhileOneRunsDoesNotFetchTwice() = runTest {
        gate = CompletableDeferred()
        val model = model("A")
        runCurrent()
        assertTrue(model.view.value.loading)
        model.refresh()
        runCurrent()
        assertEquals(listOf("A"), calls)
        gate!!.complete(Unit)
        runCurrent()
        assertFalse(model.view.value.loading)
        assertEquals(FeedView.Status.NO_VIDEOS, model.view.value.status)
    }

    @Test fun atMostSixChannelsAreFetchedAtOnce() = runTest {
        val release = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0
        val ids = List(10) { "C$it" }.toTypedArray()
        val model = model(*ids, fetcher = FeedFetcher {
            running++
            peak = maxOf(peak, running)
            release.await()
            running--
            ChannelFetch.Ok(emptyList(), FeedSource.RSS)
        })
        runCurrent()
        assertEquals(FeedRules.CONCURRENCY, peak)
        release.complete(Unit)
        runCurrent()
        assertFalse(model.view.value.loading)
    }

    @Test fun aColdStartShowsTheCachedListBeforeTheFetchAnswers() = runTest {
        answer("A", videos("A", 40))
        model("A")
        runCurrent()
        assertEquals("the cache keeps 30 per channel", 30, store.feeds.value.getValue("A").size)

        // A new process: the same files, a fetch that has not answered yet.
        openStore()
        gate = CompletableDeferred()
        val cold = model()
        runCurrent()
        val view = cold.view.value
        assertEquals(FeedView.Status.LIST, view.status)
        assertEquals(30, view.videos.size)
        assertTrue("and it refreshes behind the list", view.refreshing)
        gate!!.complete(Unit)
        runCurrent()
        assertFalse(cold.view.value.refreshing)
    }
}
