package com.staninna.yourtube.feed

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.NotifyState
import com.staninna.yourtube.data.SeenChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as new: seeding, the seed time and its margin, Shorts, unsubscribed channels, the summary. */
class NewVideosTest {

    private val seeded = 1_000_000_000L
    private val now = seeded + 5_000_000
    private val subs = setOf("A", "B")
    private val margin = NewVideos.CLOCK_MARGIN_MS

    private fun video(id: String, channel: String = "A", at: Long = seeded + 1, short: Boolean = false) =
        FeedVideo(id, "Title $id", "Channel $channel", channel, at, isShort = short)

    private fun state(vararg seen: Pair<String, List<String>>) = NotifyState(1, seen.associate { (id, ids) -> id to SeenChannel(seeded, ids) })

    private fun check(state: NotifyState, fetched: Map<String, List<FeedVideo>>, removeShorts: Boolean = false) =
        NewVideos.check(state, fetched, subs, removeShorts, now)

    @Test fun aChannelSeenForTheFirstTimeIsSeededWithoutNotifying() {
        val round = check(NotifyState(1), mapOf("A" to listOf(video("a1"), video("a2"))))
        assertTrue(round.fresh.isEmpty())
        assertEquals(SeenChannel(now, listOf("a1", "a2")), round.state.seen["A"])
    }

    @Test fun anEmptyFirstAnswerSeedsNothingSoTheBacklogStillCannotNotify() {
        val empty = check(NotifyState(1), mapOf("A" to emptyList()))
        assertNull(empty.state.seen["A"])
        val backlog = check(empty.state, mapOf("A" to List(15) { video("old$it", at = seeded - 2 * margin) }))
        assertTrue("the first list that shows is the seed", backlog.fresh.isEmpty())
    }

    @Test fun anUnseenIdOfASeededChannelIsNewOnce() {
        val round = check(state("A" to listOf("a1")), mapOf("A" to listOf(video("a2", at = seeded + 10), video("a1"))))
        assertEquals(listOf("a2"), round.fresh.map { it.videoId })
        assertEquals(listOf("a2", "a1"), round.state.seen.getValue("A").ids)
        assertEquals("the seed time stays", seeded, round.state.seen.getValue("A").seededAt)
        val again = check(round.state, mapOf("A" to listOf(video("a2", at = seeded + 10), video("a1"))))
        assertTrue("nothing notifies twice", again.fresh.isEmpty())
    }

    @Test fun uploadsFromBeforeTheSeedNeverNotifyWithAnHourForClockDrift() {
        val fetched = mapOf("A" to listOf(video("old", at = seeded - margin - 1), video("drift", at = seeded - margin + 1), video("a1")))
        val round = check(state("A" to listOf("a1")), fetched)
        assertEquals("a device clock up to an hour ahead still notifies", listOf("drift"), round.fresh.map { it.videoId })
        assertEquals("but the old one counts as seen", listOf("old", "drift", "a1"), round.state.seen.getValue("A").ids)
    }

    @Test fun shortsNotifyOnlyWhileShortsAreKept() {
        val fetched = mapOf("A" to listOf(video("s1", short = true), video("a1")))
        assertTrue(check(state("A" to listOf("a1")), fetched, removeShorts = true).fresh.isEmpty())
        assertEquals(listOf("s1"), check(state("A" to listOf("a1")), fetched).fresh.map { it.videoId })
        assertEquals("a removed Short is still seen", listOf("s1", "a1"), check(state("A" to listOf("a1")), fetched, true).state.seen.getValue("A").ids)
    }

    @Test fun aChannelThatDidNotAnswerKeepsItsStateAndGoneOnesAreForgotten() {
        val round = check(state("A" to listOf("a1"), "B" to listOf("b1"), "Gone" to listOf("g1")), mapOf("A" to listOf(video("a1"))))
        assertEquals(setOf("A", "B"), round.state.seen.keys)
        assertEquals(listOf("b1"), round.state.seen.getValue("B").ids)
    }

    @Test fun newVideosFromSeveralChannelsComeNewestFirst() {
        val round = check(state("A" to emptyList(), "B" to emptyList()), mapOf("A" to listOf(video("a1", at = seeded + 2)), "B" to listOf(video("b1", "B", at = seeded + 5))))
        assertEquals(listOf("b1", "a1"), round.fresh.map { it.videoId })
    }

    @Test fun theSeenListIsCapped() {
        val round = check(state("A" to List(NewVideos.SEEN_PER_CHANNEL) { "old$it" }), mapOf("A" to listOf(video("new"))))
        assertEquals(NewVideos.SEEN_PER_CHANNEL, round.state.seen.getValue("A").ids.size)
        assertEquals("new", round.state.seen.getValue("A").ids.first())
    }

    @Test fun theSummaryCountsVideosStillInTheShadeAndWhatTheCapLeftOut() {
        val earlier = mapOf("e1" to "Channel A: Earlier", "a1" to "Channel A: Title a1 (old line)")
        val summary = NewVideos.summary(earlier, listOf(video("a2"), video("a1")), left = 3)!!
        assertEquals("three lines and three left out", "6 new videos", summary.title)
        assertEquals(listOf("Channel A: Title a2", "Channel A: Title a1", "Channel A: Earlier"), summary.lines)
        assertEquals("+3 more", summary.more)
        assertEquals("1 new video", NewVideos.summary(emptyMap(), listOf(video("a1")), 0)!!.title)
        assertNull(NewVideos.summary(emptyMap(), emptyList(), 0))
    }
}
