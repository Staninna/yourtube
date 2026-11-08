package com.staninna.yourtube

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.VideoMeta
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.theme.TubeTheme
import com.staninna.yourtube.ui.theme.dynamicColorAvailable
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * End-to-end UI test for the app shell: the real [TubeRoot] with a live [MainViewModel] and
 * [WebViewHost] on a fresh store. Only the search test starts a youtube.com load, and it asserts on
 * the shell alone, so every test stays deterministic offline.
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
        compose.setContent { TubeTheme(dynamicColor = store.settings.value.dynamicColor) { TubeRoot(vm = vm, host = host) } }
    }

    /** The navigation bar item for [label]; the screen title with the same text has no click action. */
    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))

    @Test fun coldLaunchWithoutSubsOpensLibrary() {
        setContent()
        compose.onNodeWithText("Your library is empty").assertIsDisplayed()
        tab("Library").assertIsSelected()
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
        tab("Feeds").assertIsSelected()
        tab("Library").performClick()
        compose.onNodeWithText("Example Channel").assertIsDisplayed()
        compose.onNodeWithText("Channel").assertIsDisplayed()
    }

    @Test fun tabsSwitchAndShowTheirScreens() {
        setContent()
        tab("History").performClick()
        tab("History").assertIsSelected()
        compose.onNodeWithText("No history yet").assertIsDisplayed()
        tab("Feeds").performClick()
        compose.onNodeWithText("No subscriptions yet").assertIsDisplayed()
        tab("Library").performClick()
        compose.onNodeWithText("Your library is empty").assertIsDisplayed()
        // Watch shows the WebView: the native screens are gone and its top bar is up.
        tab("Watch").performClick()
        tab("Watch").assertIsSelected()
        compose.onNodeWithContentDescription("YouTube home").assertIsDisplayed()
        compose.onAllNodesWithText("Your library is empty").assertCountEquals(0)
    }

    @Test fun historyShowsWatchedVideos() {
        store.recordPlayback(PlaybackKind.PLAY, "VID1", 0.0, 0.0, VideoMeta("VID1", "A watched video", "Someone", null))
        setContent()
        tab("History").performClick()
        compose.onNodeWithText("A watched video").assertIsDisplayed()
    }

    @Test fun clearingHistoryCanBeUndone() {
        store.recordPlayback(PlaybackKind.PLAY, "VID1", 0.0, 0.0, VideoMeta("VID1", "A watched video", "Someone", null))
        setContent()
        tab("History").performClick()
        compose.onNodeWithContentDescription("Clear history").performClick()
        compose.onNodeWithText("No history yet").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { store.history.value.isNotEmpty() }
        compose.onNodeWithText("A watched video").assertIsDisplayed()
    }

    @Test fun settingsOpensFromTheOverflowAndBackCloses() {
        setContent()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Dynamic color").assertIsDisplayed()
        compose.onNodeWithText("About").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithText("Dynamic color").assertCountEquals(0)
        compose.onNodeWithText("Your library is empty").assertIsDisplayed()

        // And the top bar's back arrow closes it too.
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodesWithText("Dynamic color").assertCountEquals(0)
    }

    @Test fun dynamicColorSwitchIsStoredAndReflected() {
        assumeTrue("needs Android 12 for wallpaper colors", dynamicColorAvailable)
        setContent()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        val row = compose.onNode(hasText("Dynamic color") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        row.assertIsOn()
        row.performClick()
        compose.waitUntil(5_000) { !store.settings.value.dynamicColor }
        row.assertIsOff()
        assertFalse(store.settings.value.dynamicColor)
        row.performClick()
        compose.waitUntil(5_000) { store.settings.value.dynamicColor }
        row.assertIsOn()
    }

    @Test fun searchFromANativeTabOpensResultsInWatch() {
        setContent()
        tab("Library").assertIsSelected()
        compose.onNodeWithContentDescription("Search YouTube").performClick()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("lofi girl")
        field.performImeAction()
        compose.waitForIdle()
        tab("Watch").assertIsSelected()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("YouTube home").assertIsDisplayed()
    }

    @Test fun hideShortsSwitchIsStored() {
        setContent()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasText("Hide Shorts") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .assertIsOff()
            .performClick()
        compose.waitUntil(5_000) { store.settings.value.hideShorts }
        assertEquals(true, store.settings.value.hideShorts)
    }

    @Test fun watchTopBarOffersSearchHomeAndOverflow() {
        setContent()
        tab("Watch").performClick()
        compose.onNode(hasContentDescription("Search YouTube")).assertIsDisplayed()
        compose.onNode(hasContentDescription("YouTube home")).assertIsDisplayed()
        compose.onNode(hasContentDescription("More options")).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))
    }
}
