package com.staninna.yourtube.playback

import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.page.NowPlaying.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMediaTest {

    private fun nowPlaying(position: Double = 10.0, status: Status = Status.PLAYING, rate: Double = 1.0, duration: Double = 100.0) =
        NowPlaying("VID", "Title", "Channel", position, duration, status, rate)

    private fun media(position: Double = 10.0, at: Long = 0, status: Status = Status.PLAYING, rate: Double = 1.0) =
        SessionMedia.of(nowPlaying(position, status, rate), at)

    @Test fun takesTheVideoInMilliseconds() {
        val media = SessionMedia.of(nowPlaying(position = 12.3456, duration = 212.5), now = 7)
        assertEquals(12_346L, media.positionMs)
        assertEquals(212_500L, media.durationMs)
        assertEquals("https://i.ytimg.com/vi/VID/hqdefault.jpg", media.thumbnail)
        assertEquals(7L, media.at)
        assertNull("no duration yet", SessionMedia.of(nowPlaying(duration = 0.0), 0).durationMs)
    }

    @Test fun thePositionRunsOnWhilePlayingAtItsRate() {
        assertEquals(13_000L, media(at = 1_000).positionAt(4_000))
        assertEquals(16_000L, media(at = 1_000, rate = 2.0).positionAt(4_000))
        assertEquals(10_000L, media(status = Status.PAUSED).positionAt(60_000))
        assertEquals("never past the end", 100_000L, media().positionAt(500_000))
    }

    @Test fun progressAloneIsNoChange() {
        // A report a second later, a second further on, is what the session already shows.
        assertFalse(SessionMedia.changed(media(10.0, at = 0), media(11.0, at = 1_000)))
        assertFalse(SessionMedia.changed(media(10.0, at = 0), media(12.4, at = 1_000)))
    }

    @Test fun seeksStateTitlesAndSpeedAreChanges() {
        val current = media(10.0, at = 0)
        assertTrue("a seek", SessionMedia.changed(current, media(60.0, at = 1_000)))
        assertTrue("a seek back", SessionMedia.changed(current, media(5.0, at = 1_000)))
        assertTrue(SessionMedia.changed(current, media(11.0, at = 1_000, status = Status.PAUSED)))
        assertTrue(SessionMedia.changed(current, media(11.0, at = 1_000, rate = 1.5)))
        assertTrue(SessionMedia.changed(current, current.copy(title = "New")))
        assertTrue(SessionMedia.changed(current, current.copy(videoId = "OTHER")))
        assertTrue(SessionMedia.changed(null, current))
        assertTrue(SessionMedia.changed(current, null))
        assertFalse(SessionMedia.changed(null, null))
    }

    @Test fun aJumpIsOnlyWithinTheSameVideo() {
        assertTrue(media(60.0, at = 1_000).jumpedFrom(media(10.0)))
        assertFalse(media(60.0, at = 1_000).copy(videoId = "OTHER").jumpedFrom(media(10.0)))
        // Paused, the position holds still: any move is a seek.
        assertTrue(media(12.0, at = 5_000, status = Status.PAUSED).jumpedFrom(media(10.0, status = Status.PAUSED)))
    }
}
