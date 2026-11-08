package com.staninna.yourtube

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.lifecycleScope
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.VideoMeta
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.theme.TubeTheme
import com.staninna.yourtube.web.PlaybackEvent
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Watch's home on the real shell: the welcome for a new user, the three rows, a card opening its video
 * and Back coming home, each row's ›, the search field, the Home button and the now-playing bar. Opens
 * reach the WebView only in [openingOverTheHomeStartsAFreshPageHistory], which loads local pages, so
 * every test stays offline.
 */
class HomeFlowsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val store: Store by lazy {
        val dir = File(compose.activity.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        Store(dir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
    }
    private lateinit var vm: MainViewModel
    private lateinit var host: WebViewHost

    private val fetcher = FeedFetcher { id, _ ->
        ChannelFetch.Ok(listOf(FeedVideo(UPLOAD, "A new upload", "Example Channel", id, System.currentTimeMillis() - 3_600_000)), FeedSource.RSS)
    }

    private fun setContent(loadPages: Boolean = false) {
        vm = MainViewModel(
            store, ChannelResolver(store, CoroutineScope(SupervisorJob() + Dispatchers.Default)), fetcher, SegmentSource { _, _ -> emptyList() }, testHub(),
        )
        compose.runOnUiThread {
            host = WebViewHost(compose.activity, store.settings, vm, onRestartNeeded = {})
            // As MainActivity wires it.
            if (loadPages) compose.activity.lifecycleScope.launch { vm.openRequests.collect { host.load(it.url, it.freshHistory) } }
        }
        compose.setContent { TubeTheme(dynamicColor = false) { TubeRoot(vm = vm, host = host) } }
    }

    /** A video stopped partway, a subscription with an upload, and a starred video. */
    private fun fill() {
        store.recordPlayback(PlaybackKind.TIME, WATCHED, 120.0, 900.0, VideoMeta(WATCHED, "A half-watched video", "Someone", null))
        store.star(StarredItem("UC1", ItemType.CHANNEL, "Example Channel", url = "https://m.youtube.com/channel/UC1", channelId = "UC1"))
        store.star(StarredItem(SAVED, ItemType.VIDEO, "A saved video", author = "Somebody", url = "https://m.youtube.com/watch?v=$SAVED"))
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun aNewUserGetsTheWelcomeUnderTheSearchField() {
        setContent()
        tab("Watch").performClick()
        compose.onNodeWithText("YourTube").assertIsDisplayed()
        compose.onNodeWithText("Search YouTube").assertIsDisplayed()
        compose.onNodeWithText("Welcome to YourTube").assertIsDisplayed()
        compose.onNodeWithText("YouTube home").assertIsDisplayed()
        listOf("Continue watching", "New from your subs", "Saved videos").forEach { compose.onAllNodesWithText(it).assertCountEquals(0) }
        // The home covers the page and its bar.
        compose.onAllNodesWithContentDescription("Home").assertCountEquals(0)
    }

    @Test fun aCardOpensItsVideoAndBackComesHome() {
        fill()
        setContent()
        tab("Watch").performClick()
        compose.onNodeWithText("Continue watching").assertIsDisplayed()
        compose.onNodeWithText("13 min left", substring = true).assertIsDisplayed()
        waitForText("A new upload")
        compose.onNodeWithText("Saved videos").assertIsDisplayed()
        compose.onAllNodesWithText("Welcome to YourTube").assertCountEquals(0)

        compose.onNodeWithText("A half-watched video").performClick()
        compose.runOnUiThread { assertEquals(Screen.PAGE, vm.screen.value) }
        compose.onNodeWithContentDescription("Home").assertIsDisplayed()

        // Nothing loaded, so the page has no history: Back goes to the home.
        back()
        compose.onNodeWithText("Continue watching").assertIsDisplayed()
        compose.runOnUiThread { assertEquals(Screen.HOME, vm.screen.value) }
    }

    @Test fun eachRowsArrowOpensItsList() {
        fill()
        setContent()
        tab("Watch").performClick()
        compose.onNodeWithText("Continue watching").performClick()
        tab("History").assertIsSelected()

        tab("Watch").performClick()
        waitForText("New from your subs")
        compose.onNodeWithText("New from your subs").performClick()
        tab("Feeds").assertIsSelected()

        tab("Watch").performClick()
        compose.onNodeWithText("Saved videos").performClick()
        tab("Library").assertIsSelected()
        compose.onNodeWithText("Videos").assertIsOn()
        compose.onNodeWithText("A saved video").assertIsDisplayed()
    }

    @Test fun theSearchFieldOpensSearchAndResultsOpenInWatch() {
        setContent()
        tab("Watch").performClick()
        compose.onNodeWithText("Search YouTube").performClick()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("lofi girl")
        field.performImeAction()
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("Home").assertIsDisplayed()
        compose.runOnUiThread { assertEquals(Screen.PAGE, vm.screen.value) }
        // The search overlay's own Back went with it: Back from the results comes home.
        back()
        compose.onNodeWithText("Welcome to YourTube").assertIsDisplayed()
    }

    @Test fun homeLiesOverAPlayingVideoWithTheNowPlayingBar() {
        setContent()
        compose.runOnUiThread {
            vm.open("https://m.youtube.com/watch?v=$WATCHED")
            vm.onNavigated("https://m.youtube.com/watch?v=$WATCHED")
            vm.onPlayback(PlaybackEvent(PlaybackKind.PLAY, WATCHED, 10.0, 212.0, paused = false))
        }
        compose.onNodeWithContentDescription("Home").performClick()
        compose.onNodeWithText("Welcome to YourTube").assertIsDisplayed()
        // The video plays on under the home; the bar takes it back to the page.
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.runOnUiThread { assertFalse("no picture-in-picture over the home", vm.pip.watchShown.value) }
        compose.onNode(hasContentDescription("Now playing", substring = true)).performClick()
        compose.onNodeWithContentDescription("Home").assertIsDisplayed()
        compose.runOnUiThread { assertEquals(Screen.PAGE, vm.screen.value) }
    }

    @Test fun openingOverTheHomeStartsAFreshPageHistory() {
        setContent(loadPages = true)
        fun open(page: String) {
            compose.runOnUiThread { vm.open(page) }
            compose.waitUntil(10_000) { host.nav.value.url == page && host.nav.value.progress == 100 }
        }
        open(PAGE_ONE)
        open(PAGE_TWO)
        assertTrue("a page opened over a page goes on its history", host.nav.value.canGoBack)
        back()
        compose.waitUntil(10_000) { host.nav.value.url == PAGE_ONE }
        open(PAGE_TWO)

        compose.onNodeWithContentDescription("Home").performClick()
        compose.onNodeWithText("Welcome to YourTube").assertIsDisplayed()
        compose.runOnUiThread { vm.open(PAGE_THREE) }
        compose.runOnUiThread {
            assertFalse("no history while the fresh page loads, so Back cannot walk the old one", host.nav.value.canGoBack)
            assertTrue("the page that lay under the home stays covered until the new one draws", host.nav.value.stale)
        }
        compose.waitUntil(10_000) { host.nav.value.url == PAGE_THREE && host.nav.value.progress == 100 }
        compose.waitUntil(10_000) { !host.nav.value.stale }
        assertFalse("a page opened over the home starts a new history", host.nav.value.canGoBack)
        back()
        compose.onNodeWithText("Welcome to YourTube").assertIsDisplayed()
    }

    /** A fresh page that fails to load keeps its fresh start: Back from the error, or after Retry, comes home. */
    @Test fun aFailedPageOverTheHomeStillStartsAFreshHistory() {
        setContent(loadPages = true)
        listOf(PAGE_ONE, PAGE_TWO).forEach { page ->
            compose.runOnUiThread { vm.open(page) }
            compose.waitUntil(10_000) { host.nav.value.url == page && host.nav.value.progress == 100 }
        }
        compose.onNodeWithContentDescription("Home").performClick()
        compose.runOnUiThread { vm.open(REFUSED) }
        compose.waitUntil(10_000) { host.nav.value.error != null }
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.waitUntil(10_000) { host.nav.value.progress == 100 }
        assertFalse("the pages before the failed one are gone", host.nav.value.canGoBack)

        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(10_000) { host.nav.value.error != null && host.nav.value.progress == 100 }
        assertFalse("Retry adds no entry to go back to", host.nav.value.canGoBack)
        back()
        compose.onNodeWithText("Welcome to YourTube").assertIsDisplayed()
    }

    private companion object {
        /** Nothing listens on port 1, so the load fails at once, offline or not. */
        const val REFUSED = "http://127.0.0.1:1/"
        const val WATCHED = "dQw4w9WgXcQ"
        const val SAVED = "rL0qEmTRI-I"
        const val UPLOAD = "jNQXAC9IVRw"
        const val PAGE_ONE = "data:text/html,one"
        const val PAGE_TWO = "data:text/html,two"
        const val PAGE_THREE = "data:text/html,three"
    }
}
