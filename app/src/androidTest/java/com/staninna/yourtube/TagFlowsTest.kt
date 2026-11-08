package com.staninna.yourtube

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
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
import androidx.compose.ui.test.performTouchInput
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.LibrarySort
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.TagPalette
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Library's tag filter sheet and sort, selection mode with the bulk tag sheet and one Undo for a bulk
 * remove, and Manage tags' Move actions and 32-color picker, on the real shell and offline.
 */
class TagFlowsTest {

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

    private fun video(id: String, title: String) =
        StarredItem(id, ItemType.VIDEO, title, author = "Someone", url = "https://m.youtube.com/watch?v=$id")

    /** Banana, then apple, then Cherry starred, so newest first is Cherry, apple, Banana. */
    private fun starThree() {
        store.star(video("bbbbbbbbbbb", "Banana"))
        Thread.sleep(5)
        store.star(video("aaaaaaaaaaa", "apple"))
        Thread.sleep(5)
        store.star(video("ccccccccccc", "Cherry"))
    }

    private fun openVideos() {
        setContent()
        tab("Library").performClick()
        compose.onNodeWithText("Videos").performClick()
    }

    private fun shownTitles() = vm.library.view.value.items.map { it.title }

    private fun sheetClosed() = compose.waitUntil(5_000) { compose.onAllNodesWithText("Filter by tags").fetchSemanticsNodes().isEmpty() }

    @Test fun theSortMenuOrdersByNameAndIsStored() {
        starThree()
        openVideos()
        assertEquals(listOf("Cherry", "apple", "Banana"), shownTitles())
        compose.onNodeWithContentDescription("Sort: Recently added").performClick()
        compose.onNodeWithText("Name A to Z").performClick()
        compose.waitUntil(5_000) { store.settings.value.librarySort == LibrarySort.NAME }
        compose.onNodeWithContentDescription("Sort: Name A to Z").assertIsDisplayed()
        assertEquals(listOf("apple", "Banana", "Cherry"), shownTitles())
    }

    @Test fun theFilterSheetCountsTagsAndAppliesAnyAllAndUntaggedLive() {
        starThree()
        val music = store.createTag("Music", attachTo = setOf("aaaaaaaaaaa", "bbbbbbbbbbb"))!!
        store.createTag("Talks", attachTo = setOf("aaaaaaaaaaa"))
        openVideos()
        compose.onNodeWithContentDescription("Tags: all").performClick()
        compose.onNode(hasText("Music") and hasText("2")).assertIsDisplayed()
        compose.onNodeWithText("Untagged").assertIsDisplayed()

        compose.onNodeWithText("Music").performClick()
        compose.waitUntil(5_000) { shownTitles() == listOf("apple", "Banana") }
        compose.onNodeWithText("Talks").performClick()
        compose.onNodeWithText("Match all").performClick()
        compose.waitUntil(5_000) { shownTitles() == listOf("apple") }

        compose.onNodeWithText("Untagged").performClick()
        compose.waitUntil(5_000) { shownTitles() == listOf("Cherry") }
        compose.onNodeWithText("Done").performClick()
        sheetClosed()
        compose.onNodeWithContentDescription("Tags: untagged only").assertIsDisplayed()

        compose.onNodeWithContentDescription("Clear tag filter").performClick()
        compose.waitUntil(5_000) { shownTitles().size == 3 }
        compose.onNodeWithContentDescription("Tags: all").assertIsDisplayed()
        assertEquals(2, store.library.value.items.count { music.id in it.tagIds })
    }

    @Test fun selectionModeTagsInBulkAndRemovesWithOneUndo() {
        starThree()
        val music = store.createTag("Music", attachTo = setOf("aaaaaaaaaaa"))!!
        openVideos()
        compose.onNodeWithText("apple").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithText("Banana").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        // TalkBack hears each row's state from the row itself.
        compose.onNodeWithText("apple").assertIsSelected()
        compose.onNodeWithText("Banana").assertIsSelected()
        compose.onNodeWithText("Cherry").assertIsNotSelected()
        assertEquals("a tap selects, it does not open", Tab.LIBRARY, vm.tab.value)

        compose.onNodeWithContentDescription("Tag selected").performClick()
        compose.onNodeWithText("Tag 2 items").assertIsDisplayed()
        val chip = compose.onNode(hasText("Music") and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        chip.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On some"))
        chip.performClick()
        compose.waitUntil(5_000) { store.library.value.items.count { music.id in it.tagIds } == 2 }
        chip.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On all"))
        compose.onNode(hasSetTextAction()).performTextInput("Gaming")
        compose.onNodeWithContentDescription("Add tag").performClick()
        compose.waitUntil(5_000) {
            val gaming = store.library.value.tags.firstOrNull { it.name == "Gaming" }
            gaming != null && store.library.value.items.filter { gaming.id in it.tagIds }.map { it.title }.toSet() == setOf("apple", "Banana")
        }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { vm.library.selection.bulkTags.value == null }

        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithText("3 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove selected from library").performClick()
        compose.waitUntil(5_000) { store.library.value.items.isEmpty() }
        compose.onNodeWithText("Removed 3 items from your library").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { store.library.value.items.size == 3 }
        assertEquals("tags come back too", 2, store.library.value.items.count { music.id in it.tagIds })
        compose.onAllNodesWithText("3 selected").assertCountEquals(0)
    }

    @Test fun manageTagsMovesWithTalkBackActionsAndPicksFromThirtyTwoColors() {
        store.star(video("aaaaaaaaaaa", "A saved video"))
        store.createTag("Music")
        store.createTag("Talks")
        setContent()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Manage tags").performScrollTo().performClick()

        val musicRow = compose.onNode(hasText("Music") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val actions = musicRow.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals("the first row only moves down", listOf("Move down"), actions.map { it.label })
        assertTrue(
            "the actions sit on the row TalkBack focuses as one",
            musicRow.fetchSemanticsNode().config.isMergingSemanticsOfDescendants,
        )
        compose.runOnUiThread { actions.single().action() }
        compose.waitUntil(5_000) { store.library.value.tags.map { it.name } == listOf("Talks", "Music") }

        val silver = TagPalette.colors.last()
        compose.onNodeWithContentDescription("Color of Music: Red. Change").performClick()
        compose.onNodeWithContentDescription(silver.name).performScrollTo().performClick()
        compose.waitUntil(5_000) { store.library.value.tags.first { it.name == "Music" }.color == silver.argb }
        compose.onNodeWithContentDescription("Color of Music: Silver. Change").assertIsDisplayed()
    }
}
