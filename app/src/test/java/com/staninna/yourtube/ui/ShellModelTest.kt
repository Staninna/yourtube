package com.staninna.yourtube.ui

import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.Settings
import com.staninna.yourtube.data.VideoMeta
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.page.PageIdentity
import com.staninna.yourtube.ui.components.Placeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the shell's components. */
class ShellModelTest {

    private val meta = VideoMeta("VID", "A title", "A channel", null)

    // --- now playing ---

    @Test fun nowPlayingNeedsAVideoInWatch() {
        assertNull(NowPlaying.of(PageIdentity.Other, emptyList(), playing = true))
        assertNull(NowPlaying.of(PageIdentity.Channel("/@x", null, "X"), emptyList(), playing = false))
    }

    @Test fun nowPlayingPrefersThePageAndFallsBackToHistory() {
        val fromPage = NowPlaying.of(PageIdentity.Video("VID", meta), emptyList(), playing = true)!!
        assertEquals("A title", fromPage.title)
        assertNull(fromPage.progress)
        assertTrue(fromPage.playing)
        assertEquals("https://i.ytimg.com/vi/VID/hqdefault.jpg", fromPage.thumbnail)

        val history = listOf(HistoryEntry("VID", meta, position = 30.0, duration = 120.0, watchedAt = 1))
        val fromHistory = NowPlaying.of(PageIdentity.Video("VID", null), history, playing = false)!!
        assertEquals("A title", fromHistory.title)
        assertEquals("A channel", fromHistory.author)
        assertEquals(0.25f, fromHistory.progress!!, 0f)

        val unknown = NowPlaying.of(PageIdentity.Video("NEW", null), history, playing = false)!!
        assertNull(unknown.title)
        assertNull(unknown.progress)
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

    @Test fun diagnosticsListMostFrequentFirst() {
        val rows = SettingsModel.diagnostics(mapOf("b.event" to 2, "a.event" to 2, "c.event" to 5))
        assertEquals(listOf("c.event" to 5, "a.event" to 2, "b.event" to 2), rows)
        assertTrue(SettingsModel.diagnostics(emptyMap()).isEmpty())
    }
}
