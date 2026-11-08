package com.staninna.yourtube

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.data.TagPalette
import com.staninna.yourtube.data.VideoMeta
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.theme.TubeTheme
import com.staninna.yourtube.web.PageReport
import com.staninna.yourtube.web.PlayerFacts
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The stage 4 flows end to end on the real shell: star and tag sheet, Library segments and chips,
 * long-press tagging, History swipe with Undo, and Manage tags. Watch is driven through the view
 * model's page listener with nothing loaded, so every test stays offline and deterministic.
 */
class LibraryFlowsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val store: Store by lazy {
        val dir = File(compose.activity.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        Store(dir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
    }
    private lateinit var vm: MainViewModel

    private fun setContent() {
        vm = MainViewModel(
            store,
            ChannelResolver(store, CoroutineScope(SupervisorJob() + Dispatchers.Default)),
            FeedFetcher { _, _ -> ChannelFetch.Ok(emptyList(), FeedSource.RSS) },
            SegmentSource { _, _ -> emptyList() },
            testHub(),
        )
        lateinit var host: WebViewHost
        compose.runOnUiThread { host = WebViewHost(compose.activity, store.settings, vm, onRestartNeeded = {}) }
        compose.setContent { TubeTheme(dynamicColor = false) { TubeRoot(vm = vm, host = host) } }
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))

    private val videoId = "dQw4w9WgXcQ"
    private val channelId = "UCuAXFkgsw1L7xaCfnd5JJOw"

    private fun video(id: String, title: String) = StarredItem(
        id, ItemType.VIDEO, title, author = "Someone", thumbnail = null, url = "https://m.youtube.com/watch?v=$id",
    )

    /** Watch shows the video as if the page had loaded and the player confirmed it. Nothing loads. */
    private fun showVideoInWatch() = compose.runOnUiThread {
        val url = "https://m.youtube.com/watch?v=$videoId"
        vm.open(url) // switches to Watch; no one collects open requests here, so the WebView stays empty
        vm.onNavigated(url)
        vm.onPageReport(PageReport(url, PageReport.Reason.METADATA, player = PlayerFacts(videoId, channelId, "Never Gonna", "Rick Astley")))
    }

    @Test fun starOpensTheTagSheetAndTheNewTagFiltersLibrary() {
        store.star(video("aaaaaaaaaaa", "An untagged video"))
        setContent()
        showVideoInWatch()

        compose.onNodeWithContentDescription("Star").performClick()
        compose.onNodeWithText("In your library").assertIsDisplayed()
        compose.onNodeWithText("Subscribe to Rick Astley").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Music")
        compose.onNodeWithContentDescription("Add tag").performClick()
        compose.waitUntil(5_000) { store.library.value.items.first().tagIds.isNotEmpty() }
        compose.onNodeWithText("Music").assertIsSelected()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { vm.library.tagSheet.value == null }

        tab("Library").performClick()
        compose.onNodeWithText("Videos").performClick()
        compose.onNodeWithText("An untagged video").assertIsDisplayed()
        compose.onNodeWithText("Music").performClick()
        // The row's second line; the title alone also shows in the now-playing bar.
        compose.onNodeWithText("Rick Astley · Music").assertIsDisplayed()
        compose.onAllNodesWithText("An untagged video").assertCountEquals(0)
    }

    @Test fun theFilledStarOpensTheSheetWhoseRemoveCanBeUndone() {
        setContent()
        showVideoInWatch()
        compose.onNodeWithContentDescription("Star").performClick()
        compose.onNodeWithText("In your library").assertIsDisplayed()
        // Back goes to the sheet's own window, as the system back gesture would.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { vm.library.tagSheet.value == null }
        assertEquals(listOf(videoId), store.library.value.items.map { it.id })

        val star = compose.onNodeWithContentDescription("Star")
        star.assert(SemanticsMatcher("offers Edit tags") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Edit tags" })
        star.performClick()
        compose.onNodeWithText("In your library").assertIsDisplayed()
        assertEquals("the filled star edits, it does not unstar", listOf(videoId), store.library.value.items.map { it.id })

        compose.onNodeWithText("Remove from library").performClick()
        compose.waitUntil(5_000) { store.library.value.items.isEmpty() }
        compose.waitUntil(5_000) { vm.library.tagSheet.value == null }
        compose.onAllNodesWithText("In your library").assertCountEquals(0)
        compose.onNodeWithText("Removed from your library").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { store.library.value.items.isNotEmpty() }
    }

    @Test fun longPressOnALibraryRowTagsIt() {
        store.star(video(videoId, "A saved video"))
        val talks = store.createTag("Talks")!!
        setContent()
        tab("Library").performClick()
        compose.onNodeWithText("Videos").performClick()
        compose.onNodeWithText("A saved video").performTouchInput { longClick() }
        compose.onNodeWithText("In your library").assertIsDisplayed()
        compose.onNodeWithText("Talks").performClick()
        compose.waitUntil(5_000) { talks.id in store.library.value.items.single().tagIds }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { vm.library.tagSheet.value == null }
        compose.onNodeWithText("Someone · Talks").assertIsDisplayed()
    }

    @Test fun segmentsSwitchBetweenTypesWithTheirOwnEmptyStates() {
        store.star(
            StarredItem(channelId, ItemType.CHANNEL, "A channel", thumbnail = "https://example.invalid/a.png", url = "u", channelId = channelId),
        )
        store.star(video(videoId, "A saved video"))
        setContent()
        tab("Library").performClick()
        compose.onNodeWithText("A channel").assertIsDisplayed()
        compose.onAllNodesWithText("A saved video").assertCountEquals(0)

        compose.onNodeWithText("Videos").performClick()
        compose.onNodeWithText("A saved video").assertIsDisplayed()
        compose.onAllNodesWithText("A channel").assertCountEquals(0)

        compose.onNodeWithText("Playlists").performClick()
        compose.onNodeWithText("No saved playlists").assertIsDisplayed()

        compose.onNodeWithText("Subs").performClick()
        compose.onNodeWithText("A channel").assertIsDisplayed()
    }

    @Test fun unstarringInLibraryCanBeUndone() {
        store.star(video(videoId, "A saved video"))
        setContent()
        tab("Library").performClick()
        compose.onNodeWithText("Videos").performClick()
        compose.onNodeWithContentDescription("Remove A saved video from library").performClick()
        compose.waitUntil(5_000) { store.library.value.items.isEmpty() }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { store.library.value.items.isNotEmpty() }
        compose.onNodeWithText("A saved video").assertIsDisplayed()
    }

    @Test fun swipingAHistoryRowRemovesItAndUndoBringsItBackWithItsPosition() {
        // A second row keeps the list composed, so the removed row's saved state would survive and could
        // come back with it.
        store.recordPlayback(PlaybackKind.PAUSE, "aaaaaaaaaaa", 30.0, 300.0, VideoMeta("aaaaaaaaaaa", "Another video", "Someone", null))
        store.recordPlayback(PlaybackKind.PAUSE, videoId, 60.0, 300.0, VideoMeta(videoId, "A watched video", "Someone", null))
        setContent()
        tab("History").performClick()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("A watched video").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { store.history.value.size == 1 }
        compose.onAllNodesWithText("A watched video").assertCountEquals(0)

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { store.history.value.size == 2 }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("A watched video").assertIsDisplayed()
        assertEquals("the restored row stays", 2, store.history.value.size)
        assertEquals(55.0, store.resumePosition(videoId)!!, 0.0)
    }

    @Test fun manageTagsRenamesRecolorsAndDeletes() {
        store.star(video(videoId, "A saved video"))
        val music = store.createTag("Music", attachTo = videoId)!!
        setContent()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Manage tags").performScrollTo().performClick()
        compose.onNodeWithText("1 item").assertIsDisplayed()
        // Settings lies under Manage tags; TalkBack must not reach its rows.
        compose.onAllNodesWithText("Export library").assertCountEquals(0)

        compose.onNodeWithContentDescription("Rename Music").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Jazz")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { store.library.value.tags.single().name == "Jazz" }

        val teal = TagPalette.colors.first { it.name == "Teal" }
        compose.onNodeWithContentDescription("Color of Jazz: Red. Change").performClick()
        compose.onNodeWithContentDescription("Teal").performClick()
        compose.waitUntil(5_000) { store.library.value.tags.single().color == teal.argb }

        compose.onNodeWithContentDescription("Delete Jazz").performClick()
        compose.onNodeWithText("It comes off 1 item. The item stays in your library.").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { store.library.value.tags.isEmpty() }
        compose.onNodeWithText("Your tags show up here", substring = true).assertIsDisplayed()
        val item = store.library.value.items.single()
        assertEquals("the item stays starred, untagged", emptySet<String>(), item.tagIds)
        assertEquals(false, music.id in item.tagIds)
    }
}
