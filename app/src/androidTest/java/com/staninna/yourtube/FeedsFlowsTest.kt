package com.staninna.yourtube

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import com.staninna.yourtube.data.FeedPageSize
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.feed.FetchFailure
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.ui.FEED_LIST_TAG
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.theme.TubeTheme
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The stage 5 flows on the real shell: a cold start from the cache, Load more, tag chips, a partial
 * failure with Retry, the page-size setting, and the new-videos pill. Feeds fetches through a fake, so every test runs offline. Three
 * channels upload 30 videos each, interleaved: Alpha 00, Beta 00, Gamma 00, Alpha 01, and so on.
 */
class FeedsFlowsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val store: Store by lazy {
        val dir = File(compose.activity.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        Store(dir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
    }
    private lateinit var vm: MainViewModel

    private val names = mapOf(ALPHA to "Alpha", BETA to "Beta", GAMMA to "Gamma")

    /** Answers queued per channel; once a queue is empty the channel answers with its 30 videos. */
    private val queued = ConcurrentHashMap<String, ConcurrentLinkedQueue<ChannelFetch>>()

    private fun queue(channelId: String, vararg results: ChannelFetch) {
        queued.getOrPut(channelId) { ConcurrentLinkedQueue() }.addAll(results)
    }

    /** While set, every fetch waits for it. */
    @Volatile private var hold: CompletableDeferred<Unit>? = null

    private val fetcher = FeedFetcher { id, _ ->
        hold?.await()
        queued[id]?.poll() ?: uploads(id)
    }

    private fun uploads(channelId: String, extra: List<FeedVideo> = emptyList()): ChannelFetch {
        val offset = listOf(ALPHA, BETA, GAMMA).indexOf(channelId)
        val name = names.getValue(channelId)
        val videos = List(30) { n ->
            FeedVideo("%-11s".format("${name.take(1)}$n").replace(' ', '_'), "$name %02d".format(n), name, channelId, NOW - (3 * n + offset) * MINUTE)
        }
        return ChannelFetch.Ok(extra + videos, FeedSource.RSS)
    }

    private fun subscribe(channelId: String, vararg tags: String) {
        store.star(StarredItem(channelId, ItemType.CHANNEL, names.getValue(channelId), url = "u", channelId = channelId))
        tags.forEach { store.createTag(it, attachTo = channelId) }
    }

    private fun setContent() {
        vm = MainViewModel(
            store, ChannelResolver(store, CoroutineScope(SupervisorJob() + Dispatchers.Default)), fetcher, SegmentSource { _, _ -> emptyList() }, testHub(),
        )
        lateinit var host: WebViewHost
        compose.runOnUiThread { host = WebViewHost(compose.activity, store.settings, vm, onRestartNeeded = {}) }
        compose.setContent { TubeTheme(dynamicColor = false) { TubeRoot(vm = vm, host = host) } }
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun list() = compose.onNodeWithTag(FEED_LIST_TAG)

    @Test fun aColdStartShowsTheCachedListWhileItRefreshes() {
        subscribe(ALPHA)
        store.cacheFeeds(mapOf(ALPHA to listOf(FeedVideo("CACHED_vid1", "A cached video", "Alpha", ALPHA, NOW - MINUTE))))
        hold = CompletableDeferred()
        setContent()
        compose.onNodeWithText("A cached video").assertIsDisplayed()
        assertTrue("the fetch has not answered yet", vm.feeds.view.value.refreshing)
        hold?.complete(Unit)
        waitForText("Alpha 00")
        compose.onAllNodesWithText("A cached video").assertCountEquals(0)
    }

    @Test fun loadMoreRevealsTheNextPage() {
        subscribe(ALPHA)
        subscribe(BETA)
        subscribe(GAMMA)
        setContent()
        waitForText("Alpha 00")
        list().performScrollToNode(hasText("Load more"))
        compose.onNodeWithText("Showing 50 of 90").assertIsDisplayed()
        compose.onNodeWithText("Load more").performClick()
        // The 90th video, the oldest, is in the list now, and there is nothing more to load.
        list().performScrollToNode(hasText("Gamma 29"))
        compose.onNodeWithText("Gamma 29").assertIsDisplayed()
        compose.onAllNodesWithText("Load more").assertCountEquals(0)
    }

    @Test fun aTagChipShowsOnlyItsChannels() {
        subscribe(ALPHA, "Music")
        subscribe(BETA, "News")
        subscribe(GAMMA)
        setContent()
        waitForText("Beta 00")
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText("Alpha 00").assertIsDisplayed()
        compose.onNodeWithText("Alpha 01").assertIsDisplayed()
        compose.onAllNodesWithText("Beta 00").assertCountEquals(0)
        compose.onAllNodesWithText("Gamma 00").assertCountEquals(0)

        // OR: a second chip adds its channels.
        compose.onNodeWithText("News").performClick()
        compose.onNodeWithText("Beta 00").assertIsDisplayed()
        compose.onAllNodesWithText("Gamma 00").assertCountEquals(0)
    }

    @Test fun aChannelThatFailsShowsANoticeAndRetryBringsItIn() {
        queue(BETA, ChannelFetch.Failed(FetchFailure.UNAVAILABLE))
        subscribe(ALPHA)
        subscribe(BETA)
        setContent()
        waitForText("Beta didn't load.")
        // What came in stays: Alpha's videos show under the notice.
        compose.onNodeWithText("Alpha 00").assertIsDisplayed()
        compose.onAllNodesWithText("Beta 00").assertCountEquals(0)

        compose.onNodeWithText("Retry").performClick()
        waitForText("Beta 00")
        compose.onAllNodesWithText("Beta didn't load.").assertCountEquals(0)
    }

    @Test fun thePageSizeSettingChangesHowManyShow() {
        subscribe(ALPHA)
        subscribe(BETA)
        subscribe(GAMMA)
        setContent()
        waitForText("Alpha 00")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Videos per page").performScrollTo().assertIsDisplayed()
        val option25 = compose.onNode(hasText("25") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))
        option25.performScrollTo().performClick()
        compose.waitUntil(5_000) { store.settings.value.feedPageSize == FeedPageSize.P25 }
        compose.onNodeWithContentDescription("Back").performClick()

        list().performScrollToNode(hasText("Load more"))
        compose.onNodeWithText("Showing 25 of 90").assertIsDisplayed()
        assertEquals(25, vm.feeds.view.value.videos.size)
    }

    @Test fun aRefreshWhileScrolledDownOffersThePillThatGoesToTheTop() {
        subscribe(ALPHA)
        subscribe(BETA)
        setContent()
        waitForText("Alpha 00")
        val fresh = FeedVideo("NEW_video_1", "A brand new video", "Alpha", ALPHA, NOW + MINUTE)
        queue(ALPHA, uploads(ALPHA, extra = listOf(fresh)))
        list().performScrollToIndex(30)
        compose.runOnUiThread { vm.feeds.refresh() }
        waitForText("1 new video")
        compose.onNodeWithText("1 new video").performClick()
        waitForText("A brand new video")
        compose.onNodeWithText("A brand new video").assertIsDisplayed()
        compose.waitUntil(5_000) { vm.feeds.view.value.newVideos == 0 }
    }

    private companion object {
        const val ALPHA = "UCaaaaaaaaaaaaaaaaaaaaaa"
        const val BETA = "UCbbbbbbbbbbbbbbbbbbbbbb"
        const val GAMMA = "UCcccccccccccccccccccccc"
        const val MINUTE = 60_000L
        val NOW = System.currentTimeMillis()
    }
}
