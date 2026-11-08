package com.staninna.yourtube

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.lifecycleScope
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.theme.TubeTheme
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Settings > Privacy's switch, the mask in Watch's bar, and the bot-check banner, in the real shell. */
class PrivacyFlowsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val store: Store by lazy {
        val dir = File(compose.activity.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        Store(dir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
    }

    private lateinit var vm: MainViewModel
    private lateinit var host: WebViewHost

    private fun setContent() {
        vm = MainViewModel(
            store,
            ChannelResolver(store, CoroutineScope(SupervisorJob() + Dispatchers.Default)),
            FeedFetcher { ChannelFetch.Ok(emptyList(), FeedSource.RSS) },
            SegmentSource { _, _ -> emptyList() },
            testHub(),
        )
        compose.runOnUiThread {
            host = WebViewHost(compose.activity, store.settings, vm, onRestartNeeded = {})
            // As MainActivity wires it.
            compose.activity.lifecycleScope.launch { vm.identityRequests.collect { host.reroll() } }
        }
        compose.setContent { TubeTheme(dynamicColor = false) { TubeRoot(vm = vm, host = host) } }
    }

    @Test fun thePrivacySwitchHidesTheMask() {
        setContent()
        compose.runOnUiThread { vm.selectTab(Tab.WATCH) }
        compose.onNodeWithContentDescription("New identity").assertIsDisplayed()

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasText("Show new-identity button") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .performScrollTo()
            .assertIsOn()
            .performClick()
        compose.waitUntil(5_000) { !store.settings.value.showNewIdentity }
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithContentDescription("YouTube home").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("New identity").assertCountEquals(0)
    }

    @Test fun theBotCheckBannerOffersANewIdentityEvenWithTheMaskHidden() {
        store.updateSettings { it.copy(showNewIdentity = false) }
        setContent()
        compose.runOnUiThread { vm.selectTab(Tab.WATCH) }
        compose.onAllNodesWithContentDescription("New identity").assertCountEquals(0)

        compose.runOnUiThread { vm.onNavigated("https://www.google.com/sorry/index?continue=https://m.youtube.com/") }
        compose.onNodeWithText("YouTube wants a check. Try a fresh identity").assertIsDisplayed()

        val generation = host.generation.value
        compose.onNodeWithText("New identity").performClick()
        compose.onNodeWithText("New identity. Your library and history are untouched.").assertIsDisplayed()
        compose.waitUntil(15_000) { host.generation.value.let { it != null && it != generation } }
    }
}
