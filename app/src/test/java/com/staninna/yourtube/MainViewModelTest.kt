package com.staninna.yourtube

import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.web.PlaybackEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun item(type: ItemType) = StarredItem(id = "x", type = type, title = "x", url = "u")

    private lateinit var store: Store

    private fun TestScope.viewModel(): Pair<MainViewModel, Channel<String>> {
        store = Store(tmp.root, backgroundScope)
        val vm = MainViewModel(store, ChannelResolver(store, backgroundScope))
        val opened = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.openRequests.collect { opened.send(it) } }
        return vm to opened
    }

    @Test fun coldLaunchOpensFeedsWhenThereAreSubs() =
        assertEquals(Tab.FEEDS, MainViewModel.startTab(Library(items = listOf(item(ItemType.VIDEO), item(ItemType.CHANNEL)))))

    @Test fun coldLaunchOpensLibraryWithoutSubs() {
        assertEquals(Tab.LIBRARY, MainViewModel.startTab(Library()))
        assertEquals(Tab.LIBRARY, MainViewModel.startTab(Library(items = listOf(item(ItemType.VIDEO)))))
    }

    @Test fun watchTabWithNothingOpenedLoadsHome() = runTest {
        val (vm, opened) = viewModel()
        vm.selectTab(Tab.WATCH)
        assertEquals(Tab.WATCH, vm.tab.value)
        assertEquals(YouTube.HOME, opened.tryReceive().getOrNull())

        vm.selectTab(Tab.HISTORY)
        vm.selectTab(Tab.WATCH)
        assertTrue("home loads once", opened.tryReceive().isFailure)
    }

    @Test fun watchTabAfterOpeningSomethingKeepsIt() = runTest {
        val (vm, opened) = viewModel()
        vm.open("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        vm.selectTab(Tab.LIBRARY)
        vm.selectTab(Tab.WATCH)
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", opened.tryReceive().getOrNull())
        assertTrue(opened.tryReceive().isFailure)
    }

    @Test fun searchOpensResultsInWatch() = runTest {
        val (vm, opened) = viewModel()
        vm.selectTab(Tab.LIBRARY)
        assertTrue(vm.search("  lofi girl "))
        assertEquals(Tab.WATCH, vm.tab.value)
        assertEquals("https://m.youtube.com/results?search_query=lofi+girl", opened.tryReceive().getOrNull())
        assertFalse(vm.search("   "))
        assertTrue("a blank query loads nothing", opened.tryReceive().isFailure)
    }

    @Test fun clearingHistoryOffersAnUndoThatRestoresIt() = runTest {
        val (vm, _) = viewModel()
        store.recordPlayback(PlaybackKind.TIME, "VID", 60.0, 300.0, null)
        val events = Channel<UiEvent>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.send(it) } }

        vm.clearHistory()
        assertTrue(store.history.value.isEmpty())
        val cleared = events.tryReceive().getOrNull() as UiEvent.HistoryCleared
        vm.undoClearHistory(cleared)
        assertEquals(listOf("VID"), store.history.value.map { it.videoId })

        store.clearHistory()
        vm.clearHistory()
        assertTrue("nothing to clear, nothing to undo", events.tryReceive().isFailure)
    }

    @Test fun leavingAVideoPageStopsCountingAsPlaying() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        vm.onPlayback(PlaybackEvent(PlaybackKind.PLAY, "dQw4w9WgXcQ", 0.0, 212.0))
        assertTrue(vm.playing.value)
        assertEquals("dQw4w9WgXcQ", vm.nowPlaying.value?.videoId)

        vm.onNavigated("https://m.youtube.com/@RickAstleyYT")
        assertFalse(vm.playing.value)
        assertNull(vm.nowPlaying.value)
    }

    private val video = "dQw4w9WgXcQ"

    private fun MainViewModel.report(kind: PlaybackKind, paused: Boolean) =
        onPlayback(PlaybackEvent(kind, video, 30.0, 212.0, paused = paused))

    @Test fun aSeekWhilePausedDoesNotCountAsPlaying() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.PAUSE, paused = true)
        vm.report(PlaybackKind.TIME, paused = true)
        assertFalse(vm.playing.value)
    }

    @Test fun progressAfterAnAdCountsAsPlaying() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        // The ad's own events never reach the app; the video continues without a play event.
        vm.report(PlaybackKind.TIME, paused = false)
        assertTrue(vm.playing.value)
        vm.report(PlaybackKind.ENDED, paused = false)
        assertFalse("an ended video is not playing, whatever paused says", vm.playing.value)
    }
}
