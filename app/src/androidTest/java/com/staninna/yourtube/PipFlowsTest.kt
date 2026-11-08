package com.staninna.yourtube

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.lifecycleScope
import com.staninna.yourtube.data.PipMode
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.playback.PipSupport
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.theme.TubeTheme
import com.staninna.yourtube.web.PlaybackEvent
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Settings > Playback > Picture-in-picture, Watch's Manual entry, and the shell in picture-in-picture.
 * Platform picture-in-picture belongs to the Activity, so support is set by hand and an entry is seen
 * as a request on the model; the page is driven through the view model's listener and loads nothing.
 */
class PipFlowsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val store: Store by lazy {
        val dir = File(compose.activity.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        Store(dir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
    }
    private val hub by lazy { testHub() }
    private lateinit var vm: MainViewModel
    private val entries = AtomicInteger()

    private fun setContent(support: PipSupport = PipSupport.AVAILABLE) {
        vm = MainViewModel(
            store,
            ChannelResolver(store, CoroutineScope(SupervisorJob() + Dispatchers.Default)),
            FeedFetcher { ChannelFetch.Ok(emptyList(), FeedSource.RSS) },
            SegmentSource { _, _ -> emptyList() },
            hub,
        )
        vm.pip.setSupport(support)
        lateinit var host: WebViewHost
        compose.runOnUiThread {
            host = WebViewHost(compose.activity, store.settings, vm, onRestartNeeded = {})
            compose.activity.lifecycleScope.launch { vm.pip.entries.collect { entries.incrementAndGet() } }
        }
        compose.setContent { TubeTheme(dynamicColor = false) { TubeRoot(vm = vm, host = host) } }
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
    }

    /** Watch shows a video; nothing loads, as no one collects the view model's open requests here. */
    private fun watch(playing: Boolean) = compose.runOnUiThread {
        vm.open(URL)
        vm.onNavigated(URL)
        vm.onPlayback(PlaybackEvent(if (playing) PlaybackKind.PLAY else PlaybackKind.PAUSE, VIDEO, 10.0, 212.0, paused = !playing))
    }

    private fun choice(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))

    @Test fun theSettingStartsAutomaticAndStoresAChoice() {
        setContent()
        openSettings()
        compose.onNodeWithText("Picture-in-picture").assertIsDisplayed()
        compose.onNodeWithText("A playing video moves to a small window when you leave the app").assertIsDisplayed()
        choice("Automatic").assertIsOn()
        choice("Manual").performClick()
        compose.waitUntil(5_000) { store.settings.value.pip == PipMode.MANUAL }
        choice("Manual").assertIsOn()
        choice("Automatic").assertIsOff()
        choice("Fullscreen").performClick()
        compose.waitUntil(5_000) { store.settings.value.pip == PipMode.FULLSCREEN }
        compose.onNodeWithText("Only from fullscreen", substring = true).assertIsDisplayed()
    }

    @Test fun withoutSupportTheSettingIsLockedWithItsReason() {
        setContent(PipSupport.TURNED_OFF)
        openSettings()
        compose.onNodeWithText("Turned off for YourTube in Android's settings").assertIsDisplayed()
        listOf("Automatic", "Fullscreen", "Manual").forEach { choice(it).performScrollTo().assertIsNotEnabled() }
    }

    @Test fun manualPutsPipInWatchsMenuWhileTheVideoPlays() {
        store.updateSettings { it.copy(pip = PipMode.MANUAL) }
        setContent()
        watch(playing = true)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Picture-in-picture").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { entries.get() == 1 }

        // Paused: no entry, since the window would have no play button.
        watch(playing = false)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onAllNodesWithText("Picture-in-picture").assertCountEquals(0)
    }

    @Test fun automaticHasNoMenuEntry() {
        setContent()
        watch(playing = true)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onAllNodesWithText("Picture-in-picture").assertCountEquals(0)
    }

    @Test fun inPipOnlyTheVideoShowsAndTheShellComesBackAfter() {
        setContent()
        watch(playing = true)
        compose.onNodeWithContentDescription("YouTube home").assertIsDisplayed()
        compose.runOnUiThread { hub.setPip(true) }
        compose.onAllNodesWithContentDescription("YouTube home").assertCountEquals(0)
        compose.onAllNodesWithText("Library").assertCountEquals(0)
        compose.runOnUiThread { hub.setPip(false) }
        compose.onNodeWithContentDescription("YouTube home").assertIsDisplayed()
        compose.onNodeWithText("Library").assertIsDisplayed()
    }

    private companion object {
        const val VIDEO = "dQw4w9WgXcQ"
        const val URL = "https://m.youtube.com/watch?v=$VIDEO"
    }
}
