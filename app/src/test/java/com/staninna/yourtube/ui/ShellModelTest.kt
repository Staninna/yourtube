package com.staninna.yourtube.ui

import com.staninna.yourtube.playback.PipSupport
import com.staninna.yourtube.data.PipMode
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.Settings
import com.staninna.yourtube.data.VideoMeta
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.page.PageIdentity
import com.staninna.yourtube.ui.components.Placeholder
import com.staninna.yourtube.web.PlaybackEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the shell's components. */
class ShellModelTest {

    private val meta = VideoMeta("VID", "A title", "A channel", null)

    // --- now playing ---

    private fun event(kind: PlaybackKind, paused: Boolean, videoId: String = "VID") =
        PlaybackEvent(kind, videoId, position = 60.0, duration = 120.0, paused = paused, rate = 1.25)

    @Test fun nowPlayingNeedsAVideoInWatch() {
        assertNull(NowPlaying.of(PageIdentity.Other, emptyList(), event(PlaybackKind.PLAY, paused = false)))
        assertNull(NowPlaying.of(PageIdentity.Channel("/@x", null, "X"), emptyList(), null))
    }

    @Test fun nowPlayingPrefersThePageAndFallsBackToHistory() {
        val fromPage = NowPlaying.of(PageIdentity.Video("VID", meta), emptyList(), event(PlaybackKind.PLAY, paused = false))!!
        assertEquals("A title", fromPage.title)
        assertEquals(0.5f, fromPage.progress!!, 0f)
        assertTrue(fromPage.playing)
        assertEquals(1.25, fromPage.rate, 0.0)
        assertEquals("https://i.ytimg.com/vi/VID/hqdefault.jpg", fromPage.thumbnail)

        val history = listOf(HistoryEntry("VID", meta, position = 30.0, duration = 120.0, watchedAt = 1))
        val fromHistory = NowPlaying.of(PageIdentity.Video("VID", null), history, null)!!
        assertEquals("A title", fromHistory.title)
        assertEquals("A channel", fromHistory.author)
        assertEquals(0.25f, fromHistory.progress!!, 0f)
        assertEquals("before the page reports, it counts as paused", NowPlaying.Status.PAUSED, fromHistory.status)

        val unknown = NowPlaying.of(PageIdentity.Video("NEW", null), history, null)!!
        assertNull(unknown.title)
        assertNull(unknown.progress)
    }

    @Test fun nowPlayingTakesOnlyItsOwnVideosEvent() {
        val history = listOf(HistoryEntry("VID", meta, position = 30.0, duration = 120.0, watchedAt = 1))
        val other = NowPlaying.of(PageIdentity.Video("VID", null), history, event(PlaybackKind.PLAY, paused = false, videoId = "OLD"))!!
        assertEquals(NowPlaying.Status.PAUSED, other.status)
        assertEquals(30.0, other.position, 0.0)
        assertEquals(1.0, other.rate, 0.0)
    }

    @Test fun nowPlayingStatusFollowsTheEvent() {
        val status = { kind: PlaybackKind, paused: Boolean -> NowPlaying.of(PageIdentity.Video("VID", meta), emptyList(), event(kind, paused))!!.status }
        assertEquals(NowPlaying.Status.PLAYING, status(PlaybackKind.TIME, false))
        assertEquals(NowPlaying.Status.PAUSED, status(PlaybackKind.TIME, true))
        assertEquals(NowPlaying.Status.PAUSED, status(PlaybackKind.METADATA, true))
        assertEquals("ended, whatever paused says", NowPlaying.Status.ENDED, status(PlaybackKind.ENDED, false))
    }

    // --- watch progress ---

    private fun entry(position: Double, duration: Double) = HistoryEntry("v", position = position, duration = duration, watchedAt = 0)

    @Test fun progressShowsOnceAVideoIsUnderway() {
        assertNull(WatchProgress.of(entry(30.0, 0.0)))
        assertNull(WatchProgress.of(entry(2.0, 600.0)))
        assertEquals(WatchProgress(0.5f, finished = false), WatchProgress.of(entry(300.0, 600.0)))
    }

    @Test fun nearTheEndCountsAsWatched() {
        assertEquals(WatchProgress(1f, finished = true), WatchProgress.of(entry(600.0, 600.0)))
        assertTrue(WatchProgress.of(entry(590.0, 600.0))!!.finished)
        assertFalse(WatchProgress.of(entry(580.0, 600.0))!!.finished)
    }

    // --- formatting ---

    @Test fun durationsReadLikeYouTubes() {
        assertNull(formatDuration(0.0))
        assertNull(formatDuration(Double.NaN))
        assertEquals("0:07", formatDuration(7.4))
        assertEquals("4:05", formatDuration(245.0))
        assertEquals("1:02:09", formatDuration(3729.0))
    }

    // --- channel placeholder ---

    @Test fun initialSkipsHandlesAndSymbols() {
        assertEquals("V", Placeholder.initial("@veritasium"))
        assertEquals("R", Placeholder.initial("  rick astley"))
        assertEquals("3", Placeholder.initial("3Blue1Brown"))
        assertEquals("É", Placeholder.initial("élodie"))
        assertEquals("?", Placeholder.initial("   "))
        assertEquals("😀", Placeholder.initial("😀😀")) // a whole emoji, not half
    }

    @Test fun colorSlotIsStableAndInRange() {
        val slots = (0 until 500).map { Placeholder.colorSlot("UC$it", 8) }
        assertTrue(slots.all { it in 0 until 8 })
        assertEquals(8, slots.toSet().size)
        assertEquals(Placeholder.colorSlot("UCabc", 8), Placeholder.colorSlot("UCabc", 8))
        // "polygenelubricants".hashCode() is Int.MIN_VALUE: still a valid slot.
        assertTrue(Placeholder.colorSlot("polygenelubricants", 8) in 0 until 8)
    }

    // --- settings ---

    @Test fun dynamicColorIsLockedOffWithoutSupport() {
        assertEquals(SwitchState(checked = true, enabled = true), SettingsModel.dynamicColor(Settings(dynamicColor = true), true))
        assertEquals(SwitchState(checked = false, enabled = true), SettingsModel.dynamicColor(Settings(dynamicColor = false), true))
        assertEquals(SwitchState(checked = false, enabled = false), SettingsModel.dynamicColor(Settings(dynamicColor = true), false))
    }

    @Test fun diagnosticsListMostFrequentFirstUnderOneLabel() {
        val text = SettingsModel.diagnostics(mapOf("b.event" to 2, "a.event" to 2, "c.event" to 5, "d.event" to 1))
        assertEquals("c.event: 5 times\na.event: 2 times\nb.event: 2 times\nd.event: once", text)
        assertEquals("Nothing unusual since the app started", SettingsModel.diagnostics(emptyMap()))
    }

    @Test fun pipSummarySaysWhatTheModeDoesOrWhyItIsLocked() {
        assertEquals("A playing video moves to a small window when you leave the app", SettingsModel.pipSummary(PipMode.AUTO, PipSupport.AVAILABLE))
        assertTrue(SettingsModel.pipSummary(PipMode.FULLSCREEN, PipSupport.AVAILABLE).startsWith("Only from fullscreen"))
        assertTrue(SettingsModel.pipSummary(PipMode.MANUAL, PipSupport.AVAILABLE).startsWith("Watch's menu"))
        for (mode in PipMode.entries) {
            assertEquals("This device has no picture-in-picture", SettingsModel.pipSummary(mode, PipSupport.NO_FEATURE))
            assertEquals("Turned off for YourTube in Android's settings", SettingsModel.pipSummary(mode, PipSupport.TURNED_OFF))
        }
        assertEquals(PipMode.entries.size, SettingsModel.pipOptions.size)
    }
}
