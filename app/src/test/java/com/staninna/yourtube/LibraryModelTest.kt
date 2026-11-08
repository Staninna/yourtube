package com.staninna.yourtube

import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.LibrarySort
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.TagMatch
import com.staninna.yourtube.data.TagState
import com.staninna.yourtube.page.ChannelResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Starring, the tag sheet, Library's tag filter, sort and selection, and avatar backfill, without a view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val videoId = "dQw4w9WgXcQ"
    private val channelId = "UCuAXFkgsw1L7xaCfnd5JJOw"
    private val video = StarredItem(
        videoId, ItemType.VIDEO, "Never Gonna", author = "Rick Astley", url = "https://m.youtube.com/watch?v=$videoId", channelId = channelId,
    )

    private lateinit var store: Store
    private val target = MutableStateFlow<StarredItem?>(null)
    private val events = mutableListOf<UiEvent>()

    private fun TestScope.model(fetch: suspend (String) -> String? = { null }): LibraryModel {
        store = Store(tmp.root, backgroundScope)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return LibraryModel(store, ChannelResolver(store, backgroundScope, fetch), scope, target, emit = { events += it })
    }

    @Test fun starringOpensTheTagSheetAndDismissingKeepsTheStar() = runTest {
        val model = model()
        target.value = video
        model.onStarClick()
        assertTrue(model.starred.value)
        val sheet = model.tagSheet.value!!
        assertEquals(videoId, sheet.item.id)
        assertEquals("the channel can be subscribed from the sheet", channelId, sheet.channel?.id)

        model.createTag(videoId, "Music")
        assertEquals(listOf("Music"), store.library.value.tags.map { it.name })
        assertEquals(1, model.tagSheet.value!!.item.tagIds.size)

        model.closeTagSheet()
        assertNull(model.tagSheet.value)
        assertEquals(listOf(videoId), store.library.value.items.map { it.id })
    }

    @Test fun aFilledStarOpensTheSheetAgainAndRemoveFromLibraryCanBeUndone() = runTest {
        val model = model()
        target.value = video
        model.onStarClick()
        model.createTag(videoId, "Music")
        model.closeTagSheet()

        model.onStarClick()
        assertTrue("the filled star edits, it does not unstar", model.starred.value)
        assertEquals(videoId, model.tagSheet.value?.item?.id)
        assertTrue(events.isEmpty())

        model.removeFromLibrary(videoId)
        assertTrue(store.library.value.items.isEmpty())
        assertNull(model.tagSheet.value)
        model.undoUnstar(events.single() as UiEvent.Unstarred)
        assertEquals("tags come back too", 1, store.library.value.items.single().tagIds.size)
    }

    @Test fun theStarWaitsForAnIdentifiedPage() = runTest {
        val model = model()
        model.onStarClick()
        assertTrue(store.library.value.items.isEmpty())
        assertFalse(model.starred.value)
    }

    @Test fun theSheetClosesWhenItsItemIsUnstarred() = runTest {
        val model = model()
        target.value = video
        model.onStarClick()
        model.unstar(videoId)
        assertNull(model.tagSheet.value)
    }

    @Test fun switchingTheSubscriptionOffAndOnKeepsTheChannelsTagsAndDate() = runTest {
        val model = model()
        target.value = video
        model.onStarClick()
        val channel = model.tagSheet.value!!.channel!!
        model.setSubscribed(channel, true)
        assertTrue(model.tagSheet.value!!.channelStarred)
        val talks = store.createTag("Talks", attachTo = setOf(channelId))!!
        val before = store.library.value.items.first { it.id == channelId }

        model.setSubscribed(channel, false)
        assertFalse(model.tagSheet.value!!.channelStarred)
        model.setSubscribed(model.tagSheet.value!!.channel!!, true)
        val after = store.library.value.items.first { it.id == channelId }
        assertEquals(setOf(talks.id), after.tagIds)
        assertEquals(before.addedAt, after.addedAt)
        assertTrue("no Undo snackbar: the switch flips back", events.isEmpty())
    }

    @Test fun closingTheSheetForgetsTheUnsubscribedChannel() = runTest {
        val model = model()
        target.value = video
        model.onStarClick()
        val channel = model.tagSheet.value!!.channel!!
        model.setSubscribed(channel, true)
        store.createTag("Talks", attachTo = setOf(channelId))
        model.setSubscribed(channel, false)
        model.closeTagSheet()
        model.openTagSheet(videoId)
        model.setSubscribed(model.tagSheet.value!!.channel!!, true)
        assertTrue("a later sheet subscribes afresh", store.library.value.items.first { it.id == channelId }.tagIds.isEmpty())
    }

    @Test fun eachSegmentKeepsItsOwnTagFilter() = runTest {
        val model = model()
        store.star(StarredItem(channelId, ItemType.CHANNEL, "C", url = "u", channelId = channelId, thumbnail = "https://a"))
        store.star(video)
        val music = store.createTag("Music", attachTo = setOf(channelId))!!
        store.setTag(setOf(videoId), music.id, attached = true)

        model.selectSegment(ItemType.CHANNEL)
        model.toggleTagFilter(music.id)
        assertEquals(setOf(music.id), model.view.value.tags.filter.tags)
        model.selectSegment(ItemType.VIDEO)
        assertTrue(model.view.value.tags.filter.tags.isEmpty())
        model.selectSegment(ItemType.CHANNEL)
        assertEquals(setOf(music.id), model.view.value.tags.filter.tags)
    }

    private fun saved(id: String, title: String) = StarredItem(id, ItemType.VIDEO, title, url = "u")

    private fun TestScope.threeVideos(): LibraryModel {
        val model = model()
        store.star(saved("a", "Alpha"))
        store.star(saved("b", "Beta"))
        store.star(saved("c", "Gamma"))
        model.selectSegment(ItemType.VIDEO)
        return model
    }

    @Test fun selectionTogglesSelectsAllAndEndsWithAnotherSegment() = runTest {
        val model = threeVideos()
        val selection = model.selection
        selection.toggle("a")
        selection.toggle("c")
        assertEquals("in Library's order, newest first", listOf("c", "a"), selection.selected.value.toList())
        selection.toggle("a")
        assertEquals(setOf("c"), selection.selected.value)
        selection.selectAll()
        assertEquals(setOf("a", "b", "c"), selection.selected.value)
        model.selectSegment(ItemType.CHANNEL)
        assertTrue(selection.selected.value.isEmpty())
    }

    @Test fun aRowTheFilterHidesLeavesTheSelection() = runTest {
        val model = threeVideos()
        val music = store.createTag("Music", attachTo = setOf("a"))!!
        model.selection.selectAll()
        model.toggleTagFilter(music.id)
        assertEquals(setOf("a"), model.selection.selected.value)
    }

    @Test fun aRowASearchHidSelectionStaysUnselectedOnceTheSearchClears() = runTest {
        val model = model()
        (1..31).forEach { store.star(saved("v$it", "Video $it")) }
        model.selectSegment(ItemType.VIDEO)
        model.selection.toggle("v1")
        model.selection.toggle("v2")
        model.setQuery("Video 2")
        assertEquals(setOf("v2"), model.selection.selected.value)
        model.setQuery("")
        assertEquals("v1 does not come back selected", setOf("v2"), model.selection.selected.value)
        model.setQuery("nothing like it")
        model.setQuery("")
        assertTrue("selection mode ended and stays ended", model.selection.selected.value.isEmpty())
    }

    @Test fun aRowRemovedAndBroughtBackIsNotSelected() = runTest {
        val model = threeVideos()
        model.selection.toggle("b")
        model.selection.tag()
        model.removeFromLibrary("b")
        assertTrue(model.selection.selected.value.isEmpty())
        model.undoUnstar(events.single() as UiEvent.Unstarred)
        assertEquals(3, store.library.value.items.size)
        assertTrue("Undo does not turn selection mode back on", model.selection.selected.value.isEmpty())
    }

    @Test fun theBulkSheetKeepsItsRowsWhenATapMovesThemOutOfTheFilter() = runTest {
        val model = threeVideos()
        val music = store.createTag("Music", attachTo = setOf("c"))!!
        model.toggleUntaggedFilter()
        assertEquals(listOf("b", "a"), model.view.value.items.map { it.id })
        model.selection.toggle("a")
        model.selection.toggle("b")
        model.selection.tag()
        model.selection.setTag(music.id, attached = true)
        val sheet = model.selection.bulkTags.value
        assertEquals("the sheet stays open on its rows", setOf("a", "b"), sheet?.itemIds)
        assertEquals(TagState.ALL, sheet!!.tags.single { it.first.id == music.id }.second)
        model.selection.createTag("Talks")
        val talks = store.library.value.tags.single { it.name == "Talks" }
        assertEquals(setOf("a", "b"), store.library.value.items.filter { talks.id in it.tagIds }.map { it.id }.toSet())
        assertEquals("c carries Music only", setOf(music.id), store.library.value.items.single { it.id == "c" }.tagIds)
    }

    @Test fun aChoiceThatDroppedOutStaysOutWhenAnItemBringsItBack() = runTest {
        val model = threeVideos()
        val music = store.createTag("Music", attachTo = setOf("c"))!!
        model.toggleUntaggedFilter()
        store.setTag(setOf("a", "b"), music.id, attached = true)
        assertFalse(model.view.value.tags.filter.untagged)
        store.star(saved("d", "Delta"))
        assertFalse("Untagged does not come back", model.view.value.tags.filter.untagged)
        assertEquals(4, model.view.value.items.size)
    }

    @Test fun removingTheSelectionHasOneUndoForAll() = runTest {
        val model = threeVideos()
        model.selection.toggle("a")
        model.selection.toggle("b")
        model.selection.remove()
        assertEquals(listOf("c"), store.library.value.items.map { it.id })
        assertTrue("selection mode ends", model.selection.selected.value.isEmpty())
        val event = events.single() as UiEvent.Unstarred
        assertEquals(setOf("a", "b"), event.items.map { it.id }.toSet())
        model.undoUnstar(event)
        assertEquals(listOf("c", "b", "a"), store.library.value.items.map { it.id })
    }

    @Test fun tagOpensTheItemsOwnSheetForOneAndTheBulkSheetForMore() = runTest {
        val model = threeVideos()
        model.selection.toggle("b")
        model.selection.tag()
        assertEquals("b", model.tagSheet.value?.item?.id)
        assertNull(model.selection.bulkTags.value)
        model.closeTagSheet()

        val music = store.createTag("Music", attachTo = setOf("a"))!!
        model.selection.toggle("a")
        model.selection.tag()
        assertNull(model.tagSheet.value)
        assertEquals(listOf(music to TagState.SOME), model.selection.bulkTags.value!!.tags)

        model.selection.setTag(music.id, attached = true)
        assertEquals(listOf(music to TagState.ALL), model.selection.bulkTags.value!!.tags)
        model.selection.createTag("Talks")
        val talks = store.library.value.tags.single { it.name == "Talks" }
        assertEquals(setOf("a", "b"), store.library.value.items.filter { talks.id in it.tagIds }.map { it.id }.toSet())

        model.selection.clear()
        assertNull("the bulk sheet closes with selection mode", model.selection.bulkTags.value)
    }

    @Test fun theSortIsStoredAndOrdersTheSegment() = runTest {
        val model = threeVideos()
        assertEquals(listOf("c", "b", "a"), model.view.value.items.map { it.id })
        model.setSort(LibrarySort.NAME)
        assertEquals(LibrarySort.NAME, store.settings.value.librarySort)
        assertEquals(listOf("a", "b", "c"), model.view.value.items.map { it.id })
    }

    @Test fun untaggedAndMatchAllApplyToTheCurrentSegment() = runTest {
        val model = threeVideos()
        val music = store.createTag("Music", attachTo = setOf("a", "b"))!!
        val talks = store.createTag("Talks", attachTo = setOf("b"))!!
        model.toggleUntaggedFilter()
        assertEquals(listOf("c"), model.view.value.items.map { it.id })
        model.toggleTagFilter(music.id)
        model.toggleTagFilter(talks.id)
        assertFalse(model.view.value.tags.filter.untagged)
        assertEquals(listOf("b", "a"), model.view.value.items.map { it.id })
        model.setTagMatch(TagMatch.ALL)
        assertEquals(listOf("b"), model.view.value.items.map { it.id })
        model.clearTagFilter()
        assertEquals(3, model.view.value.items.size)
        assertEquals(TagMatch.ALL, model.view.value.tags.filter.match)
    }

    @Test fun missingAvatarsAreBackfilledOnce() = runTest {
        var fetches = 0
        val model = model(fetch = {
            fetches++
            """<meta property="og:title" content="C"><meta itemprop="identifier" content="$channelId"><meta property="og:image" content="https://avatar">"""
        })
        store.star(StarredItem(channelId, ItemType.CHANNEL, "C", url = "u", channelId = channelId))
        runCurrent()
        assertEquals("https://avatar", model.state.value.items.single().thumbnail)
        store.createTag("Anything")
        runCurrent()
        assertEquals(1, fetches)
    }

    @Test fun aFailedAvatarLookupIsNotRetriedOnEveryChange() = runTest {
        var fetches = 0
        model(fetch = {
            fetches++
            null
        })
        store.star(StarredItem(channelId, ItemType.CHANNEL, "C", url = "u", channelId = channelId))
        runCurrent()
        store.createTag("Anything")
        runCurrent()
        assertEquals(1, fetches)
        assertNull(store.library.value.items.single().thumbnail)
    }
}
