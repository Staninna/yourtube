package com.staninna.yourtube.playback

import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.page.NowPlaying.Status
import com.staninna.yourtube.playback.BackgroundPlayback.Companion.GRACE_MS
import com.staninna.yourtube.web.PlayerCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHubTest {

    private fun nowPlaying(status: Status, position: Double = 10.0) = NowPlaying("VID", "Title", "Channel", position, 100.0, status, 1.0)

    private class Fixture(scope: TestScope) {
        var starts = 0
        val sent = mutableListOf<PlayerCommand>()
        val page: (PlayerCommand) -> Unit = { sent += it }
        val hub = PlaybackHub(scope.backgroundScope, now = { scope.testScheduler.currentTime }, startService = { starts++ })
    }

    @Test fun playingInTheAppStartsTheServiceOnce() = runTest {
        val f = Fixture(this)
        f.hub.attach(f.page)
        f.hub.publish(nowPlaying(Status.PLAYING))
        assertEquals("not while the Activity is hidden", 0, f.starts)
        f.hub.setVisible(true)
        assertEquals(1, f.starts)
        assertTrue(f.hub.state.value.session)
        f.hub.publish(nowPlaying(Status.PLAYING, position = 11.0))
        f.hub.setVisible(false)
        assertEquals(1, f.starts)
    }

    @Test fun pausedAndAwayTheSessionEndsAfterTheGracePeriodAndThePageIsPaused() = runTest {
        val f = Fixture(this)
        f.hub.attach(f.page)
        f.hub.setVisible(true)
        f.hub.publish(nowPlaying(Status.PLAYING))
        f.hub.publish(nowPlaying(Status.PAUSED))
        f.hub.setVisible(false)
        advanceTimeBy(GRACE_MS - 1)
        runCurrent()
        assertTrue(f.hub.state.value.session)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(f.hub.state.value.session)
        assertEquals(listOf<PlayerCommand>(PlayerCommand.Pause), f.sent)
    }

    @Test fun playingAgainCancelsTheCountdown() = runTest {
        val f = Fixture(this)
        f.hub.attach(f.page)
        f.hub.setVisible(true)
        f.hub.publish(nowPlaying(Status.PLAYING))
        f.hub.setVisible(false)
        f.hub.publish(nowPlaying(Status.PAUSED))
        advanceTimeBy(GRACE_MS / 2)
        f.hub.publish(nowPlaying(Status.PLAYING))
        advanceTimeBy(2 * GRACE_MS)
        runCurrent()
        assertTrue(f.hub.state.value.session)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun theActivityGoingEndsTheSession() = runTest {
        val f = Fixture(this)
        f.hub.attach(f.page)
        f.hub.setVisible(true)
        f.hub.publish(nowPlaying(Status.PLAYING))
        f.hub.detach { }
        assertTrue("another page's detach changes nothing", f.hub.state.value.session)
        f.hub.detach(f.page)
        assertFalse(f.hub.state.value.session)
        assertNull(f.hub.media.value)
        f.hub.send(PlayerCommand.Play)
        assertTrue("a detached page gets nothing", f.sent.isEmpty())
    }

    @Test fun mediaChangesOnStateChangesAndSeeksNotOnProgress() = runTest {
        val f = Fixture(this)
        f.hub.publish(nowPlaying(Status.PLAYING, position = 10.0))
        val first = f.hub.media.value
        advanceTimeBy(1_000)
        f.hub.publish(nowPlaying(Status.PLAYING, position = 11.0))
        assertEquals(first, f.hub.media.value)
        f.hub.publish(nowPlaying(Status.PLAYING, position = 80.0))
        assertEquals(80_000L, f.hub.media.value?.positionMs)
        f.hub.publish(nowPlaying(Status.PAUSED, position = 80.0))
        assertEquals(Status.PAUSED, f.hub.media.value?.status)
    }

    @Test fun swipingAwayPausesThePage() = runTest {
        val f = Fixture(this)
        f.hub.attach(f.page)
        f.hub.setVisible(true)
        f.hub.publish(nowPlaying(Status.PLAYING))
        f.hub.setVisible(false)
        f.hub.taskRemoved()
        assertFalse(f.hub.state.value.session)
        assertEquals(listOf<PlayerCommand>(PlayerCommand.Pause), f.sent)
    }
}
