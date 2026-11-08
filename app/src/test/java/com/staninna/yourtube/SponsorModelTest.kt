package com.staninna.yourtube

import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.sponsor.Segment
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.web.BridgeReply
import com.staninna.yourtube.web.PageSegment
import com.staninna.yourtube.web.SegmentSkipped
import com.staninna.yourtube.web.SegmentsReply
import com.staninna.yourtube.web.UndoSkip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SponsorBlock between page and source: replies, pushes on settings changes, skips and Undo, failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class SponsorModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sponsor = Segment(SegmentCategory.SPONSOR, 10.0, 20.0)
    private val intro = Segment(SegmentCategory.INTRO, 0.0, 5.0)

    /** Segments by video id; a missing video fails the lookup. */
    private var answers: Map<String, List<Segment>> = mapOf("v" to listOf(intro, sponsor))
    private val asked = mutableListOf<Pair<String, String>>()
    private val source = SegmentSource { server, videoId ->
        asked += server to videoId
        answers[videoId]
    }

    private class Setup(val store: Store, val model: SponsorModel, val events: List<UiEvent>, val pushes: Channel<BridgeReply>)

    private fun TestScope.setup(source: SegmentSource = this@SponsorModelTest.source): Setup {
        val store = Store(tmp.root, backgroundScope)
        val events = mutableListOf<UiEvent>()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler)).backgroundScope
        val model = SponsorModel(store, source, scope, emit = { events += it })
        val pushes = Channel<BridgeReply>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.pagePushes.collect { pushes.send(it) } }
        return Setup(store, model, events, pushes)
    }

    private fun Setup.ask(videoId: String): SegmentsReply? {
        var reply: SegmentsReply? = null
        model.request(videoId) { reply = it }
        return reply
    }

    @Test fun thePageGetsSegmentsWithTheUsersActions() = runTest {
        val s = setup()
        assertEquals(
            SegmentsReply(
                "v",
                listOf(PageSegment(0.0, 5.0, SegmentCategory.INTRO, SegmentAction.SHOW), PageSegment(10.0, 20.0, SegmentCategory.SPONSOR, SegmentAction.SKIP)),
            ),
            s.ask("v"),
        )
        assertEquals(listOf("https://sponsor.ajay.app" to "v"), asked)
    }

    @Test fun aFailedLookupOrABrokenSourcePlaysTheVideoAsItIs() = runTest {
        assertEquals(SegmentsReply("gone", emptyList()), setup().ask("gone"))
        val broken = setup(SegmentSource { _, _ -> error("bug") })
        assertEquals(SegmentsReply("v", emptyList()), broken.ask("v"))
    }

    @Test fun nothingIsLookedUpWhileSponsorBlockIsOff() = runTest {
        val s = setup()
        s.store.updateSettings { it.copy(sponsorBlock = it.sponsorBlock.copy(enabled = false)) }
        assertEquals(SegmentsReply("v", emptyList()), s.ask("v"))
        assertEquals(emptyList<Pair<String, String>>(), asked)
    }

    @Test fun offCategoriesNeverReachThePage() = runTest {
        val s = setup()
        s.store.updateSettings {
            it.copy(sponsorBlock = it.sponsorBlock.with(SegmentCategory.INTRO, SegmentAction.OFF).with(SegmentCategory.SPONSOR, SegmentAction.MUTE))
        }
        assertEquals(SegmentsReply("v", listOf(PageSegment(10.0, 20.0, SegmentCategory.SPONSOR, SegmentAction.MUTE))), s.ask("v"))
    }

    @Test fun nothingIsLookedUpWhenEveryCategoryIsOff() = runTest {
        val s = setup()
        s.store.updateSettings { settings ->
            settings.copy(sponsorBlock = SegmentCategory.entries.fold(settings.sponsorBlock) { sb, c -> sb.with(c, SegmentAction.OFF) })
        }
        assertEquals(SegmentsReply("v", emptyList()), s.ask("v"))
        assertEquals(emptyList<Pair<String, String>>(), asked)
    }

    @Test fun aSettingsChangeIsPushedForTheVideoOnScreen() = runTest {
        val s = setup()
        s.ask("v")
        s.store.updateSettings { it.copy(sponsorBlock = it.sponsorBlock.with(SegmentCategory.SPONSOR, SegmentAction.MUTE)) }
        runCurrent()
        assertEquals(
            SegmentsReply(
                "v",
                listOf(PageSegment(0.0, 5.0, SegmentCategory.INTRO, SegmentAction.SHOW), PageSegment(10.0, 20.0, SegmentCategory.SPONSOR, SegmentAction.MUTE)),
            ),
            s.pushes.tryReceive().getOrNull(),
        )
        // Other settings push nothing.
        s.store.updateSettings { it.copy(dynamicColor = false) }
        runCurrent()
        assertEquals(null, s.pushes.tryReceive().getOrNull())
    }

    @Test fun aSkipOffersUndoThatSeeksBack() = runTest {
        val s = setup()
        s.model.onSkipped(SegmentSkipped("v", SegmentCategory.SPONSOR, 10.0, 20.0))
        val event = s.events.single() as UiEvent.SegmentSkipped
        assertEquals(UiEvent.SegmentSkipped("v", SegmentCategory.SPONSOR, 10.0), event)
        s.model.undo(event)
        assertEquals(UndoSkip("v", 10.0), s.pushes.tryReceive().getOrNull())
    }
}
