package com.staninna.yourtube.playback

import com.staninna.yourtube.playback.BackgroundPlayback.Companion.GRACE_MS
import com.staninna.yourtube.playback.BackgroundPlayback.Effect
import com.staninna.yourtube.playback.BackgroundPlayback.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D4's rows without picture-in-picture, one test per row. */
class BackgroundPlaybackTest {

    /** Feeds events in order at the given times and keeps every effect. */
    private class Run(var state: BackgroundPlayback = BackgroundPlayback()) {
        val effects = mutableListOf<Effect>()

        fun on(event: Event, at: Long = 0): Run {
            val step = state.on(event, at)
            state = step.state
            step.effect?.let(effects::add)
            return this
        }

        fun shown(at: Long = 0) = on(Event.Visibility(true), at)
        fun hidden(at: Long = 0) = on(Event.Visibility(false), at)
        fun playing(at: Long = 0) = on(Event.Playback(video = true, playing = true), at)
        fun paused(at: Long = 0) = on(Event.Playback(video = true, playing = false), at)
    }

    /** In the app with the video playing: the session runs. */
    private fun playingInApp() = Run().shown().playing().also { assertEquals(listOf(Effect.START), it.effects) }

    @Test fun playingAndLeavingTheAppKeepsPlayingWithTheNotification() {
        val run = playingInApp().hidden(at = 1_000)
        assertTrue(run.state.session)
        assertEquals(listOf(Effect.START), run.effects)
        assertNull("nothing counts down while it plays", run.state.releaseAt)
        run.on(Event.Tick, at = 10 * GRACE_MS)
        assertTrue(run.state.session)
    }

    @Test fun screenOffWhileInTheAppIsLeavingIt() {
        // The Activity stops when the screen goes off; the same event as Home, the same outcome.
        val run = playingInApp().hidden(at = 1_000).on(Event.Tick, at = 2 * GRACE_MS)
        assertTrue(run.state.session)
    }

    @Test fun pausedWhenLeavingTheSessionLastsTheGracePeriod() {
        val run = playingInApp().paused(at = 5_000).hidden(at = 6_000)
        assertEquals(5_000 + GRACE_MS, run.state.releaseAt)
        run.on(Event.Tick, at = 5_000 + GRACE_MS - 1)
        assertTrue(run.state.session)
        run.on(Event.Tick, at = 5_000 + GRACE_MS)
        assertFalse(run.state.session)
        assertEquals(listOf(Effect.START, Effect.RELEASE), run.effects)
        assertNull(run.state.releaseAt)
    }

    @Test fun aPausedVideoInTheAppKeepsItsSessionUntilTheAppIsLeft() {
        val run = playingInApp().paused(at = 0).on(Event.Tick, at = 3 * GRACE_MS)
        assertTrue("in the app, the session stays", run.state.session)
        assertNull(run.state.releaseAt)
        // Past the grace period already: leaving ends it at once.
        run.hidden(at = 3 * GRACE_MS)
        assertFalse(run.state.session)
        assertEquals(Effect.RELEASE, run.effects.last())
    }

    @Test fun pausesAndAutoplayWithinTheGracePeriodKeepTheSession() {
        val run = playingInApp().hidden()
        run.paused(at = 1_000).playing(at = 60_000)
        // Autoplay: the video ends, the next one loads paused, then plays.
        run.paused(at = 100_000).paused(at = 103_000).playing(at = 105_000)
        run.on(Event.Tick, at = 100_000 + GRACE_MS)
        assertTrue(run.state.session)
        assertEquals("never released, never started again", listOf(Effect.START), run.effects)
    }

    @Test fun graceCountsFromTheLastStop() {
        val run = playingInApp().hidden().paused(at = 0).playing(at = 1_000).paused(at = 2_000)
        assertEquals(2_000 + GRACE_MS, run.state.releaseAt)
    }

    @Test fun switchingToANativeTabIsNotAnEvent() {
        // Native tabs lie over Watch in the same Activity: nothing reaches the machine, so nothing changes.
        val run = playingInApp()
        assertTrue(run.state.session && run.state.playing && run.state.visible)
    }

    @Test fun backAtTheRootMovesTheTaskBackOnlyWhilePlaying() {
        val run = playingInApp()
        assertTrue(run.state.backMovesTaskToBack)
        run.paused()
        assertFalse(run.state.backMovesTaskToBack)
        assertFalse(BackgroundPlayback().backMovesTaskToBack)
    }

    @Test fun swipingTheTaskAwayEndsTheSession() {
        val run = playingInApp().hidden().on(Event.TaskRemoved, at = 1_000)
        assertFalse(run.state.session)
        assertEquals(listOf(Effect.START, Effect.RELEASE), run.effects)
        // A task with no session has nothing to end.
        assertNull(BackgroundPlayback().on(Event.TaskRemoved, 0).effect)
    }

    @Test fun returningToTheAppKeepsTheSameSession() {
        val run = playingInApp().paused(at = 0).hidden(at = 0).shown(at = GRACE_MS / 2)
        assertTrue(run.state.session)
        assertNull("in the app nothing counts down", run.state.releaseAt)
        assertEquals(listOf(Effect.START), run.effects)
    }

    @Test fun theSessionOnlyStartsWhileTheActivityIsVisible() {
        val hidden = Run().hidden().playing()
        assertFalse(hidden.state.session)
        assertTrue(hidden.effects.isEmpty())
        // Coming back while it plays starts it.
        hidden.shown()
        assertEquals(listOf(Effect.START), hidden.effects)
        // A paused video in the app has no session yet.
        assertTrue(Run().shown().paused().effects.isEmpty())
    }

    @Test fun noVideoInWatchEndsTheSessionAtOnce() {
        val run = playingInApp().on(Event.Playback(video = false, playing = false), at = 1_000)
        assertFalse(run.state.session)
        assertEquals(Effect.RELEASE, run.effects.last())
    }

    @Test fun afterAReleasePlayingInTheAppStartsANewSession() {
        val run = playingInApp().hidden().on(Event.TaskRemoved).shown().playing()
        assertEquals(listOf(Effect.START, Effect.RELEASE, Effect.START), run.effects)
    }
}
