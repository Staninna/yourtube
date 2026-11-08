package com.staninna.yourtube

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.sponsor.Segment
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.theme.TubeTheme
import com.staninna.yourtube.web.BridgeReply
import com.staninna.yourtube.web.PageSegment
import com.staninna.yourtube.web.SegmentSkipped
import com.staninna.yourtube.web.SegmentsReply
import com.staninna.yourtube.web.UndoSkip
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings > Content on the real shell, and the skip snackbar with Undo. SponsorBlock answers from a
 * fake source and the page is driven through the view model's page listener, so nothing goes online.
 */
class ContentFlowsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val store: Store by lazy {
        val dir = File(compose.activity.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        Store(dir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
    }
    private lateinit var vm: MainViewModel
    private val pushes = CopyOnWriteArrayList<BridgeReply>()
    private val sponsor = Segment(SegmentCategory.SPONSOR, 10.0, 20.0)

    private fun setContent() {
        vm = MainViewModel(
            store,
            ChannelResolver(store, CoroutineScope(SupervisorJob() + Dispatchers.Default)),
            FeedFetcher { ChannelFetch.Ok(emptyList(), FeedSource.RSS) },
            SegmentSource { _, videoId -> if (videoId == VIDEO) listOf(sponsor) else null },
            testHub(),
        )
        lateinit var host: WebViewHost
        compose.runOnUiThread {
            host = WebViewHost(compose.activity, store.settings, vm, onRestartNeeded = {})
            compose.activity.lifecycleScope.launch { vm.pagePushes.collect { pushes += it } }
        }
        compose.setContent { TubeTheme(dynamicColor = false) { TubeRoot(vm = vm, host = host) } }
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
    }

    private fun switch(title: String) = compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))

    private fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())

    @Test fun everyAdLayerStartsOnAndSwitchesOffAlone() {
        setContent()
        openSettings()
        compose.onNodeWithText("Ad blocking").assertIsDisplayed()
        listOf("Video ads", "Ad servers", "Ads in pages", "Skip leaked ads").forEach { switch(it).performScrollTo().assertIsOn() }
        switch("Ad servers").performClick()
        compose.waitUntil(5_000) { store.settings.value.adBlocking.off == setOf(AdLayer.NETWORK) }
        switch("Ad servers").assertIsOff()
        switch("Video ads").assertIsOn()
    }

    @Test fun sponsorBlockOffLocksItsSegmentsAndServer() {
        setContent()
        openSettings()
        row("Segments").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Skips sponsor and self-promotion. Marks 6 categories.").assertIsDisplayed()
        compose.onNodeWithText("sponsor.ajay.app (default)").performScrollTo().assertIsDisplayed()
        switch("Use SponsorBlock").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { !store.settings.value.sponsorBlock.enabled }
        compose.onNode(hasText("Segments")).assertIsNotEnabled()
        compose.onNode(hasText("Server")).assertIsNotEnabled()
        switch("Use SponsorBlock").performClick()
        compose.waitUntil(5_000) { store.settings.value.sponsorBlock.enabled }
        row("Segments").assertIsEnabled()
    }

    @Test fun theSegmentsSheetSetsEachCategorysAction() {
        setContent()
        openSettings()
        row("Segments").performScrollTo().performClick()
        compose.onNodeWithText("Paid promotion and sponsor reads").assertIsDisplayed()
        // Choices run Skip, Mute, Show, Off per category, in category order; Intro is the fourth.
        val introMute = compose.onAllNodesWithText("Mute")[SegmentCategory.INTRO.ordinal]
        introMute.performScrollTo().performClick()
        compose.waitUntil(5_000) { store.settings.value.sponsorBlock.action(SegmentCategory.INTRO) == SegmentAction.MUTE }
        compose.onAllNodesWithText("Off")[SegmentCategory.SPONSOR.ordinal].performScrollTo().performClick()
        compose.waitUntil(5_000) { store.settings.value.sponsorBlock.action(SegmentCategory.SPONSOR) == SegmentAction.OFF }
        // Back reaches the sheet's own window, which closes it and leaves Settings open.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Paid promotion and sponsor reads").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Skips self-promotion. Mutes intro. Marks 5 categories.").performScrollTo().assertIsDisplayed()
    }

    @Test fun theServerIsCheckedBeforeItIsSaved() {
        setContent()
        openSettings()
        row("Server").performScrollTo().performClick()
        val field = compose.onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput("http://sb.example.org")
        compose.onNodeWithText("The address must start with https://").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        field.performTextClearance()
        field.performTextInput("https://sb.example.org/")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { store.settings.value.sponsorBlock.server == "https://sb.example.org" }
        compose.onNodeWithText("sb.example.org").performScrollTo().assertIsDisplayed()
    }

    @Test fun aSkipShowsASnackbarWhoseUndoSeeksBack() {
        setContent()
        var reply: SegmentsReply? = null
        compose.runOnUiThread { vm.requestSegments(VIDEO) { reply = it } }
        compose.waitUntil(5_000) { reply != null }
        assertEquals(SegmentsReply(VIDEO, listOf(PageSegment(10.0, 20.0, SegmentCategory.SPONSOR, SegmentAction.SKIP))), reply)

        compose.runOnUiThread { vm.onSegmentSkipped(SegmentSkipped(VIDEO, SegmentCategory.SPONSOR, 10.0, 20.0)) }
        compose.onNodeWithText("Skipped sponsor").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { pushes.isNotEmpty() }
        assertEquals(listOf<BridgeReply>(UndoSkip(VIDEO, 10.0)), pushes.toList())
    }

    @Test fun aFailedLookupGivesThePageNoSegments() {
        setContent()
        var reply: SegmentsReply? = null
        compose.runOnUiThread { vm.requestSegments("unknownvid1") { reply = it } }
        compose.waitUntil(5_000) { reply != null }
        assertEquals(SegmentsReply("unknownvid1", emptyList()), reply)
    }

    private companion object {
        const val VIDEO = "eyZEqSPeMwg"
    }
}
