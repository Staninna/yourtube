package com.staninna.yourtube

import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
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

/** Starring, the tag sheet, Library's chips and avatar backfill, without a view model. */
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
        val talks = store.createTag("Talks", attachTo = channelId)!!
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
        store.createTag("Talks", attachTo = channelId)
        model.setSubscribed(channel, false)
        model.closeTagSheet()
        model.openTagSheet(videoId)
        model.setSubscribed(model.tagSheet.value!!.channel!!, true)
        assertTrue("a later sheet subscribes afresh", store.library.value.items.first { it.id == channelId }.tagIds.isEmpty())
    }

    @Test fun eachSegmentKeepsItsOwnChips() = runTest {
        val model = model()
        store.star(StarredItem(channelId, ItemType.CHANNEL, "C", url = "u", channelId = channelId, thumbnail = "https://a"))
        store.star(video)
        val music = store.createTag("Music", attachTo = channelId)!!
        store.setItemTag(videoId, music.id, attached = true)

        model.selectSegment(ItemType.CHANNEL)
        model.toggleTagFilter(music.id)
        assertEquals(setOf(music.id), model.view.value.selected)
        model.selectSegment(ItemType.VIDEO)
        assertTrue(model.view.value.selected.isEmpty())
        model.selectSegment(ItemType.CHANNEL)
        assertEquals(setOf(music.id), model.view.value.selected)
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
