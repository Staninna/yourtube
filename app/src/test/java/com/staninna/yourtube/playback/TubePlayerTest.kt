package com.staninna.yourtube.playback

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.page.NowPlaying.Status
import com.staninna.yourtube.web.PlayerCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** The media3 adapter: the hub's media as player state, and player commands as page commands. */
@RunWith(RobolectricTestRunner::class)
class TubePlayerTest {

    private val sent = mutableListOf<PlayerCommand>()

    // media3 runs positions on with the same clock.
    private val player = TubePlayer(Looper.getMainLooper(), { sent += it }, now = SystemClock::elapsedRealtime)

    private fun media(status: Status = Status.PLAYING, position: Double = 30.0, duration: Double = 212.0, rate: Double = 1.0) =
        SessionMedia.of(NowPlaying("dQw4w9WgXcQ", "Never Gonna", "Rick Astley", position, duration, status, rate), SystemClock.elapsedRealtime())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun nothingInWatchIsAnIdleEmptyPlayer() {
        player.show(null)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertTrue(player.currentTimeline.isEmpty)
        assertFalse(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
    }

    @Test fun aPlayingVideoMapsToAReadyPlayingItem() {
        player.show(media(rate = 1.5))
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
        assertTrue(player.isPlaying)
        assertEquals("dQw4w9WgXcQ", player.currentMediaItem?.mediaId)
        assertEquals("Never Gonna", player.mediaMetadata.title.toString())
        assertEquals("Rick Astley", player.mediaMetadata.artist.toString())
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", player.mediaMetadata.artworkUri.toString())
        assertEquals(212_000L, player.duration)
        assertEquals(30_000L, player.currentPosition)
        assertEquals(1.5f, player.playbackParameters.speed)
    }

    @Test fun pausedAndEndedVideos() {
        player.show(media(Status.PAUSED))
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.playWhenReady)
        player.show(media(Status.ENDED, position = 212.0))
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertFalse(player.isPlaying)
    }

    @Test fun anUnknownDurationIsUnsetAndNotSeekable() {
        player.show(media(duration = 0.0))
        assertEquals(C.TIME_UNSET, player.duration)
        assertFalse(player.isCurrentMediaItemSeekable)
    }

    @Test fun offersPlayPauseAndSeekingButNoQueue() {
        player.show(media())
        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(player.isCommandAvailable(Player.COMMAND_STOP))
    }

    @Test fun commandsGoToThePage() {
        player.show(media(Status.PAUSED))
        player.play()
        player.show(media(Status.PLAYING))
        player.pause()
        player.show(media(Status.PAUSED))
        player.seekTo(95_500)
        idle()
        assertEquals(listOf(PlayerCommand.Play, PlayerCommand.Pause, PlayerCommand.Seek("dQw4w9WgXcQ", 95.5)), sent)
    }

    @Test fun aCommandShowsItsOutcomeUntilThePageAnswers() {
        player.show(media(Status.PAUSED))
        player.play()
        assertTrue("shown at once", player.playWhenReady)
        // The page has not answered in time: the player shows the page's state again.
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertFalse(player.playWhenReady)
    }

    @Test fun aSeekThePageMadeIsReportedAsOne() {
        player.show(media(position = 30.0))
        val discontinuities = mutableListOf<Int>()
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                discontinuities += reason
            }
        })
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        player.show(media(position = 31.0))
        idle()
        assertTrue("time passing is no seek", discontinuities.isEmpty())
        player.show(media(position = 120.0))
        idle()
        assertEquals(1, discontinuities.size)
        assertEquals(120_000L, player.currentPosition)
    }
}
