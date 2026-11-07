package com.staninna.yourtube

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.VideoMeta
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.TubeTheme
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * End-to-end UI test for the app shell: the real [TubeRoot] with a live [MainViewModel] and
 * [WebViewHost] on a fresh store. It never loads youtube.com, so it stays offline and deterministic.
 */
class TubeRootTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val store: Store by lazy {
        val dir = File(compose.activity.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        Store(dir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
    }

    private fun setContent() {
        val vm = MainViewModel(store, ChannelResolver(store, CoroutineScope(SupervisorJob() + Dispatchers.Default)))
        lateinit var host: WebViewHost
        compose.runOnUiThread { host = WebViewHost(compose.activity, store.settings, vm, onRestartNeeded = {}) }
        compose.setContent { TubeTheme(dynamicColor = false) { TubeRoot(vm = vm, host = host) } }
    }

    @Test fun coldLaunchWithoutSubsOpensLibrary() {
        setContent()
        compose.onNodeWithText("Star channels and videos to see them here.").assertIsDisplayed()
        compose.onAllNodesWithText("Feeds").assertCountEquals(1)
    }

    @Test fun coldLaunchWithSubsOpensFeeds() {
        store.toggleStar(
            StarredItem(
                id = "UC1", type = ItemType.CHANNEL, title = "Example Channel",
                url = "https://m.youtube.com/channel/UC1", channelId = "UC1",
            ),
        )
        setContent()
        // The Feeds tab is open: its title shows next to its navigation label.
        compose.onAllNodesWithText("Feeds").assertCountEquals(2)
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Example Channel").assertIsDisplayed()
    }

    @Test fun historyShowsWatchedVideos() {
        store.recordPlayback(PlaybackKind.PLAY, "VID1", 0.0, 0.0, VideoMeta("VID1", "A watched video", "Someone", null))
        setContent()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("A watched video").assertIsDisplayed()
    }

    @Test fun emptyHistoryShowsItsGuidance() {
        setContent()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Videos you watch show up here.").assertIsDisplayed()
    }
}
