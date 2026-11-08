package com.staninna.yourtube

import com.staninna.yourtube.data.BackupProblem
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.PlayerPrefs
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedSource
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.playback.PlaybackHub
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.web.PageReport
import com.staninna.yourtube.web.PlaybackEvent
import com.staninna.yourtube.web.PlayerCommand
import com.staninna.yourtube.web.PlayerFacts
import com.staninna.yourtube.web.Carried
import com.staninna.yourtube.web.Playability
import com.staninna.yourtube.web.ResumeReply
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun item(type: ItemType) = StarredItem(id = "x", type = type, title = "x", url = "u")

    private lateinit var store: Store
    private lateinit var hub: PlaybackHub

    private val noFeeds = FeedFetcher { _, _ -> ChannelFetch.Ok(emptyList(), FeedSource.RSS) }
    private val noSegments = SegmentSource { _, _ -> emptyList() }

    /** Every request also lands in [requests], with whether it starts a fresh history. */
    private val requests = Channel<OpenRequest>(Channel.UNLIMITED)

    private fun TestScope.viewModel(): Pair<MainViewModel, Channel<String>> {
        store = Store(tmp.root, backgroundScope)
        hub = PlaybackHub(backgroundScope, now = { testScheduler.currentTime }, startService = {})
        val vm = MainViewModel(store, ChannelResolver(store, backgroundScope), noFeeds, noSegments, hub)
        val opened = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.openRequests.collect {
                opened.send(it.url)
                requests.send(it)
            }
        }
        return vm to opened
    }

    @Test fun coldLaunchOpensFeedsWhenThereAreSubs() =
        assertEquals(Tab.FEEDS, MainViewModel.startTab(Library(items = listOf(item(ItemType.VIDEO), item(ItemType.CHANNEL)))))

    @Test fun coldLaunchOpensLibraryWithoutSubs() {
        assertEquals(Tab.LIBRARY, MainViewModel.startTab(Library()))
        assertEquals(Tab.LIBRARY, MainViewModel.startTab(Library(items = listOf(item(ItemType.VIDEO)))))
    }

    @Test fun watchTabWithNothingOpenedShowsTheHomeAndLoadsNothing() = runTest {
        val (vm, opened) = viewModel()
        assertEquals(Screen.LIBRARY, vm.screen.value)
        vm.selectTab(Tab.WATCH)
        assertEquals(Tab.WATCH, vm.tab.value)
        assertEquals(Screen.HOME, vm.screen.value)
        assertTrue(opened.tryReceive().isFailure)
    }

    @Test fun openingFromTheHomeStartsAFreshHistoryAndOpeningOverAPageDoesNot() = runTest {
        val (vm, _) = viewModel()
        vm.selectTab(Tab.WATCH)
        vm.open("https://m.youtube.com/watch?v=$video")
        assertEquals(Screen.PAGE, vm.screen.value)
        assertEquals(OpenRequest("https://m.youtube.com/watch?v=$video", freshHistory = true), requests.tryReceive().getOrNull())

        vm.open("https://m.youtube.com/@RickAstleyYT")
        assertEquals(false, requests.tryReceive().getOrNull()?.freshHistory)

        // A deep link or a native tab's row while Watch is on its home opens over the home too.
        vm.showHome()
        vm.selectTab(Tab.HISTORY)
        vm.open("https://m.youtube.com/watch?v=rL0qEmTRI-I")
        assertEquals(true, requests.tryReceive().getOrNull()?.freshHistory)
    }

    @Test fun homeStaysOverThePageUntilThePageIsAskedFor() = runTest {
        val (vm, opened) = viewModel()
        vm.open("https://m.youtube.com/watch?v=$video")
        opened.tryReceive()
        vm.showHome()
        assertEquals(Screen.HOME, vm.screen.value)
        vm.selectTab(Tab.FEEDS)
        vm.selectTab(Tab.WATCH)
        assertEquals("Watch comes back on its home", Screen.HOME, vm.screen.value)
        vm.showPage()
        assertEquals(Screen.PAGE, vm.screen.value)
        vm.selectTab(Tab.HISTORY)
        vm.selectTab(Tab.WATCH)
        assertEquals(Screen.PAGE, vm.screen.value)
        assertTrue("nothing reloads", opened.tryReceive().isFailure)
    }

    @Test fun savedVideosOpenLibraryOnItsVideos() = runTest {
        val (vm, _) = viewModel()
        vm.selectTab(Tab.WATCH)
        vm.openSavedVideos()
        assertEquals(Screen.LIBRARY, vm.screen.value)
        assertEquals(ItemType.VIDEO, vm.library.view.value.segment)
    }

    @Test fun theDashboardFollowsHistoryAndTheLibrary() = runTest {
        val (vm, _) = viewModel()
        assertTrue(vm.dashboard.value.empty)
        store.recordPlayback(PlaybackKind.TIME, video, 90.0, 212.0, null)
        store.star(StarredItem("v", ItemType.VIDEO, "V", url = "u"))
        assertEquals(listOf(video), vm.dashboard.value.continueWatching.map { it.videoId })
        assertEquals(listOf("v"), vm.dashboard.value.saved.map { it.id })
    }

    @Test fun aLinkFromAnotherAppStartsFreshAndKeepsItsFlagUntilTheUserMovesOn() = runTest {
        val (vm, _) = viewModel()
        vm.open("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue("over the home it starts fresh", requests.tryReceive().getOrThrow().freshHistory)
        vm.open("https://m.youtube.com/watch?v=aircAruvnKk")
        assertFalse("a page over a page keeps the history", requests.tryReceive().getOrThrow().freshHistory)
        assertFalse(vm.fromLink.value)

        vm.open("https://m.youtube.com/watch?v=jNQXAC9IVRw", fromLink = true)
        assertTrue("a link always starts fresh", requests.tryReceive().getOrThrow().freshHistory)
        assertTrue(vm.fromLink.value)
        assertEquals(Screen.PAGE, vm.screen.value)

        vm.selectTab(Tab.WATCH)
        assertTrue("staying on Watch keeps it", vm.fromLink.value)
        vm.selectTab(Tab.FEEDS)
        vm.selectTab(Tab.WATCH)
        assertFalse("another tab makes the page the app's own", vm.fromLink.value)

        vm.open("https://m.youtube.com/watch?v=jNQXAC9IVRw", fromLink = true)
        vm.showHome()
        assertFalse(vm.fromLink.value)
        vm.open("https://m.youtube.com/watch?v=jNQXAC9IVRw", fromLink = true)
        vm.open("https://m.youtube.com/results?search_query=x")
        assertFalse("opening something else in the app ends it", vm.fromLink.value)
    }

    @Test fun backIsDecidedFromTheShellAsItIsNow() = runTest {
        val (vm, _) = viewModel()
        vm.open("https://m.youtube.com/watch?v=jNQXAC9IVRw", fromLink = true)
        assertNull("a link's first page leaves the app", vm.backStep(canGoBack = false, pageLoaded = true))
        assertEquals(BackStep.PAGE_HISTORY, vm.backStep(canGoBack = true, pageLoaded = true))
        vm.open("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("with no history yet the root is the home", BackStep.HOME, vm.backStep(canGoBack = false, pageLoaded = true))
        vm.showHome()
        assertNull(vm.backStep(canGoBack = true, pageLoaded = true))
    }

    @Test fun leavingLibraryEndsSelectionMode() = runTest {
        val (vm, _) = viewModel()
        store.star(StarredItem("v", ItemType.VIDEO, "V", url = "u"))
        vm.selectTab(Tab.LIBRARY)
        vm.library.selectSegment(ItemType.VIDEO)
        vm.library.selection.toggle("v")
        assertEquals(setOf("v"), vm.library.selection.selected.value)
        vm.selectTab(Tab.FEEDS)
        assertTrue(vm.library.selection.selected.value.isEmpty())
        vm.selectTab(Tab.LIBRARY)
        vm.library.selection.toggle("v")
        vm.open("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue("a link that switches to Watch ends it too", vm.library.selection.selected.value.isEmpty())
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
        vm.undo(cleared)
        assertEquals(listOf("VID"), store.history.value.map { it.videoId })

        store.clearHistory()
        vm.clearHistory()
        assertTrue("nothing to clear, nothing to undo", events.tryReceive().isFailure)
    }

    @Test fun openingAPageClosesSearchAndSettings() = runTest {
        val (vm, _) = viewModel()
        vm.openSearch()
        assertEquals(Overlay.SEARCH, vm.overlay.value)
        vm.open("https://m.youtube.com/watch?v=$video", fromLink = true)
        assertEquals("a link over search shows its page", Overlay.NONE, vm.overlay.value)
        assertEquals(Screen.PAGE, vm.screen.value)

        vm.selectTab(Tab.LIBRARY)
        vm.openSettings()
        vm.open("https://m.youtube.com/watch?v=jNQXAC9IVRw", fromLink = true)
        assertEquals(Overlay.NONE, vm.overlay.value)
        assertEquals(Tab.WATCH, vm.tab.value)

        vm.openSettings()
        vm.closeOverlay()
        assertEquals(Overlay.NONE, vm.overlay.value)
        vm.openSearch()
        assertFalse("a blank search keeps the field open", vm.search(" "))
        assertEquals(Overlay.SEARCH, vm.overlay.value)
        vm.search("lofi")
        assertEquals(Overlay.NONE, vm.overlay.value)
    }

    @Test fun clearedHistoryStaysClearWhileItsVideoPlaysOn() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.TIME, paused = false)
        store.recordPlayback(PlaybackKind.TIME, "OLD", 60.0, 300.0, null)
        vm.clearHistory()
        vm.report(PlaybackKind.TIME, paused = false)
        vm.report(PlaybackKind.PAUSE, paused = true)
        assertTrue("the video on screen does not come straight back", store.history.value.isEmpty())
        assertEquals("the bar still follows it", NowPlaying.Status.PAUSED, vm.nowPlaying.value?.status)

        // The next video is recorded as usual, and so is this one once it shows again.
        vm.onNavigated("https://m.youtube.com/watch?v=jNQXAC9IVRw")
        vm.onPlayback(PlaybackEvent(PlaybackKind.TIME, "jNQXAC9IVRw", 5.0, 19.0))
        assertEquals(listOf("jNQXAC9IVRw"), store.history.value.map { it.videoId })
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.TIME, paused = false)
        assertEquals(listOf(video, "jNQXAC9IVRw"), store.history.value.map { it.videoId })
    }

    @Test fun openingTheClearedVideoAgainRecordsIt() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.TIME, paused = false)
        vm.clearHistory()
        vm.open("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.TIME, paused = false)
        assertEquals(listOf(video), store.history.value.map { it.videoId })
    }

    @Test fun undoingAClearRecordsTheVideoAgain() = runTest {
        val (vm, _) = viewModel()
        val events = events(vm)
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.TIME, paused = false)
        vm.clearHistory()
        vm.undo(events.tryReceive().getOrThrow() as UiEvent.Undoable)
        vm.onPlayback(PlaybackEvent(PlaybackKind.TIME, video, 50.0, 212.0))
        assertEquals(50.0, store.history.value.single().position, 0.0)
    }

    @Test fun undoingAnotherRemovalKeepsThePlayingVideoOut() = runTest {
        val (vm, _) = viewModel()
        val events = events(vm)
        store.recordPlayback(PlaybackKind.TIME, "OTHER", 60.0, 300.0, null)
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.TIME, paused = false)
        vm.removeHistory(video)
        events.tryReceive()
        vm.removeHistory("OTHER")
        vm.undo(events.tryReceive().getOrThrow() as UiEvent.Undoable)
        vm.report(PlaybackKind.TIME, paused = false)
        assertEquals(listOf("OTHER"), store.history.value.map { it.videoId })
    }

    @Test fun aPausedVideoIsNotHeldAndComesBackOnceItPlays() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.PAUSE, paused = true)
        vm.clearHistory()
        assertTrue(store.history.value.isEmpty())
        vm.report(PlaybackKind.TIME, paused = false)
        assertEquals("pressing play is watching it again", listOf(video), store.history.value.map { it.videoId })
    }

    @Test fun swipingAwayTheVideoOnScreenKeepsItOutButNotOthers() = runTest {
        val (vm, _) = viewModel()
        store.recordPlayback(PlaybackKind.TIME, "OTHER", 60.0, 300.0, null)
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.TIME, paused = false)
        vm.removeHistory("OTHER")
        vm.report(PlaybackKind.TIME, paused = false)
        assertEquals(listOf(video), store.history.value.map { it.videoId })
        vm.removeHistory(video)
        vm.report(PlaybackKind.TIME, paused = false)
        assertTrue(store.history.value.isEmpty())
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

    // --- star, tag sheet and undo ---

    private val channelId = "UCuAXFkgsw1L7xaCfnd5JJOw"

    /** Watch shows [video], confirmed by the player. */
    private fun MainViewModel.showVideo() {
        val url = "https://m.youtube.com/watch?v=$video"
        onNavigated(url)
        onPageReport(PageReport(url, PageReport.Reason.METADATA, player = PlayerFacts(video, channelId, "Never Gonna", "Rick Astley")))
    }

    private fun TestScope.events(vm: MainViewModel): Channel<UiEvent> {
        val events = Channel<UiEvent>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.send(it) } }
        return events
    }

    @Test fun aLinkClosesTheTagSheetOfThePageBefore() = runTest {
        val (vm, _) = viewModel()
        vm.showVideo()
        vm.library.onStarClick()
        assertEquals(video, vm.library.tagSheet.value?.item?.id)
        vm.open("https://m.youtube.com/watch?v=jNQXAC9IVRw", fromLink = true)
        assertNull(vm.library.tagSheet.value)
        assertTrue("the star stays", vm.library.starred.value)
    }

    @Test fun theWatchStarGoesThroughTheLibraryAndItsUndoThroughTheViewModel() = runTest {
        val (vm, _) = viewModel()
        val events = events(vm)
        vm.showVideo()
        assertFalse(vm.library.starred.value)
        vm.library.onStarClick()
        assertTrue(vm.library.starred.value)
        assertEquals(video, vm.library.tagSheet.value?.item?.id)
        vm.library.closeTagSheet()

        vm.library.onStarClick()
        assertTrue("a filled star opens the sheet again", vm.library.starred.value)
        assertEquals(video, vm.library.tagSheet.value?.item?.id)
        vm.library.removeFromLibrary(video)
        assertFalse(vm.library.starred.value)
        vm.undo(events.tryReceive().getOrNull() as UiEvent.Unstarred)
        assertTrue(vm.library.starred.value)
    }

    @Test fun removingAHistoryEntryCanBeUndone() = runTest {
        val (vm, _) = viewModel()
        val events = events(vm)
        store.recordPlayback(PlaybackKind.TIME, "VID", 60.0, 300.0, null)
        vm.removeHistory("VID")
        assertNull(store.resumePosition("VID"))
        vm.undo(events.tryReceive().getOrNull() as UiEvent.HistoryRemoved)
        assertEquals(55.0, store.resumePosition("VID")!!, 0.0)
    }

    @Test fun anUnreadableStoreStaysNoticedUntilAnswered() = runTest {
        java.io.File(tmp.root, "library.json").writeText("{broken")
        val (vm, _) = viewModel()
        assertEquals(setOf("library.json"), vm.corruptNotice.value)
        // Other notices come and go; this one is state, not an event, so they cannot push it out.
        vm.showVideo()
        vm.library.onStarClick()
        vm.library.unstar(video)
        assertEquals(setOf("library.json"), vm.corruptNotice.value)
        vm.dismissCorruptNotice()
        assertNull(vm.corruptNotice.value)
        assertNull("a new screen in the same process does not bring it back", MainViewModel(store, ChannelResolver(store, backgroundScope), noFeeds, noSegments, hub).corruptNotice.value)
    }

    @Test fun backupsReportThroughTheEventStream() = runTest {
        val (vm, _) = viewModel()
        val events = events(vm)
        vm.exportBackup { throw IOException("full") }
        assertEquals(UiEvent.ExportFailed, events.receive())
        vm.importBackup { throw IOException("gone") }
        assertEquals(UiEvent.ImportFailed(BackupProblem.UNREADABLE), events.receive())
    }

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

    // --- media session ---

    @Test fun playPauseGoesToThePageThroughTheHub() = runTest {
        val (vm, _) = viewModel()
        val sent = mutableListOf<PlayerCommand>()
        hub.attach { sent += it }
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.PLAY, paused = false)
        vm.playPause()
        vm.report(PlaybackKind.PAUSE, paused = true)
        vm.playPause()
        assertEquals(listOf(PlayerCommand.Pause, PlayerCommand.Play), sent)
    }

    @Test fun theSessionShowsWhatWatchPlays() = runTest {
        val (vm, _) = viewModel()
        vm.showVideo()
        vm.report(PlaybackKind.PLAY, paused = false)
        val media = hub.media.value!!
        assertEquals(video, media.videoId)
        assertEquals("Never Gonna", media.title)
        assertEquals(30_000L, media.positionMs)
        vm.onNavigated("https://m.youtube.com/@RickAstleyYT")
        assertNull(hub.media.value)
    }

    @Test fun aNativeTabKeepsThePlayingVideoInTheBar() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.PLAY, paused = false)
        vm.selectTab(Tab.FEEDS)
        assertTrue(vm.playing.value)
        assertEquals(video, vm.nowPlaying.value?.videoId)
    }

    @Test fun comingBackToAVideoForgetsItsOldState() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.report(PlaybackKind.PLAY, paused = false)
        vm.onNavigated("https://m.youtube.com/@RickAstleyYT")
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        assertFalse("not playing until the page says so", vm.playing.value)
    }

    // --- new identity, consent and bot checks ---

    @Test fun aNewIdentityAsksTheHostAndTellsTheUser() = runTest {
        val (vm, _) = viewModel()
        val events = Channel<UiEvent>(Channel.UNLIMITED)
        val requests = Channel<Unit>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.send(it) } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.identityRequests.collect { requests.send(it) } }
        vm.newIdentity()
        assertEquals(Unit, requests.tryReceive().getOrNull())
        assertEquals(UiEvent.NewIdentity, events.tryReceive().getOrNull())
    }

    @Test fun theReloadedVideoPlaysOnAsItDidOnce() = runTest {
        val (vm, _) = viewModel()
        store.recordPlayback(PlaybackKind.TIME, video, 90.0, 212.0, null)
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.onPlayback(PlaybackEvent(PlaybackKind.PAUSE, video, 90.0, 212.0, paused = true, rate = 1.5))
        vm.newIdentity()

        assertEquals(ResumeReply(video, 85.0, muted = false, Carried(rate = 1.5, paused = true)), vm.resume(video))
        assertEquals("once only", ResumeReply(video, 85.0, muted = false), vm.resume(video))
    }

    @Test fun anotherVideoAfterANewIdentityStartsAsUsual() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.onPlayback(PlaybackEvent(PlaybackKind.PLAY, video, 30.0, 212.0, rate = 2.0))
        vm.newIdentity()
        assertEquals(ResumeReply("rL0qEmTRI-I", null, muted = false), vm.resume("rL0qEmTRI-I"))
    }

    @Test fun theCarryIsDroppedOnceWatchLeavesTheVideo() = runTest {
        val (vm, _) = viewModel()
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        vm.onPlayback(PlaybackEvent(PlaybackKind.PLAY, video, 30.0, 212.0, rate = 2.0))
        vm.newIdentity()
        vm.onNavigated("https://m.youtube.com/@RickAstleyYT")
        vm.onNavigated("https://m.youtube.com/watch?v=$video")
        assertNull(vm.resume(video).carried)
    }

    @Test fun everyVideoStartsWithSoundUntilTheViewerMutes() = runTest {
        val (vm, _) = viewModel()
        assertFalse(vm.resume(video).muted)
        vm.onViewerMute(true)
        assertTrue("the next video stays muted", vm.resume("rL0qEmTRI-I").muted)
        vm.onViewerMute(false)
        assertFalse(vm.resume(video).muted)
    }

    @Test fun aNewIdentityKeepsWatchOnItsPage() = runTest {
        val (vm, _) = viewModel()
        vm.open("https://m.youtube.com/watch?v=$video")
        vm.newIdentity()
        assertEquals(Screen.PAGE, vm.screen.value)
    }

    @Test fun botChecksShowByAddressOrByTheRefusedPlayerAndAreCounted() = runTest {
        val (vm, _) = viewModel()
        val before = Diagnostics.counts.value["botcheck"] ?: 0
        vm.onNavigated("https://www.google.com/sorry/index?continue=x")
        assertTrue(vm.botCheck.value)

        val url = "https://m.youtube.com/watch?v=$video"
        vm.onNavigated(url)
        assertFalse(vm.botCheck.value)
        vm.onPageReport(PageReport(url, PageReport.Reason.LOAD, playability = Playability("LOGIN_REQUIRED", "Sign in to confirm you're not a bot")))
        assertTrue(vm.botCheck.value)
        assertEquals(before + 2, Diagnostics.counts.value["botcheck"])
    }

    @Test fun consentPagesAndDialogsAreCounted() = runTest {
        val (vm, _) = viewModel()
        val page = Diagnostics.counts.value["consent.page"] ?: 0
        val dialog = Diagnostics.counts.value["consent.dialog"] ?: 0
        vm.onNavigated("https://consent.youtube.com/m?continue=x")
        vm.onConsentRejected()
        assertEquals(page + 1, Diagnostics.counts.value["consent.page"])
        assertEquals(dialog + 1, Diagnostics.counts.value["consent.dialog"])
    }

    @Test fun playerChoicesGoToTheStoreAndBack() = runTest {
        val (vm, _) = viewModel()
        val prefs = PlayerPrefs(quality = 720, captions = false)
        vm.onPlayerPrefs(prefs)
        assertEquals(prefs, store.playerPrefs.value)
        assertEquals(prefs, vm.playerPrefs())
    }

    @Test fun thePrivacySwitchHidesTheButton() = runTest {
        val (vm, _) = viewModel()
        assertTrue(vm.settings.value.showNewIdentity)
        vm.setShowNewIdentity(false)
        assertFalse(vm.settings.value.showNewIdentity)
    }
}
