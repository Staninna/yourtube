package com.staninna.yourtube.ui

import com.staninna.yourtube.UiEvent
import com.staninna.yourtube.data.BackupProblem
import com.staninna.yourtube.data.HistoryDay
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.ImportReport
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.VideoMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** The words Library, History and the snackbar put on screen. */
class LibraryTextTest {

    private fun item(type: ItemType, author: String?) = StarredItem("x", type, "T", author = author, url = "u")

    @Test fun subtitlesNameTheOwnerThenTheTags() {
        assertEquals("Someone · Music, Talks", librarySubtitle(item(ItemType.VIDEO, "Someone"), listOf("Music", "Talks"), null, 0))
        assertEquals("Someone", librarySubtitle(item(ItemType.PLAYLIST, "Someone"), emptyList(), null, 0))
        assertEquals("Music", librarySubtitle(item(ItemType.CHANNEL, "Its own name"), listOf("Music"), null, 0))
        assertNull(librarySubtitle(item(ItemType.CHANNEL, "Its own name"), emptyList(), null, 0))
        assertNull(librarySubtitle(item(ItemType.VIDEO, " "), emptyList(), null, 0))
    }

    @Test fun aChannelLeadsWithItsLatestUpload() {
        val day = 24 * 60 * 60 * 1000L
        val channel = item(ItemType.CHANNEL, "Its own name")
        assertEquals("Uploaded 3 days ago · Music", librarySubtitle(channel, listOf("Music"), latestUpload = 0, now = 3 * day))
        assertEquals("Uploaded just now", librarySubtitle(channel, emptyList(), latestUpload = 0, now = 1))
        assertEquals("only channels", "Someone", librarySubtitle(item(ItemType.VIDEO, "Someone"), emptyList(), latestUpload = 0, now = day))
    }

    private fun watched(position: Double, duration: Double) = HistoryEntry("aaaaaaaaaaa", position = position, duration = duration, watchedAt = 0)

    @Test fun timeLeftIsForVideosStoppedPartway() {
        assertEquals("12 min left", timeLeft(watched(position = 600.0, duration = 1300.0)))
        assertEquals("rounded up", "1 min left", timeLeft(watched(position = 600.0, duration = 620.0)))
        assertEquals("1 hr 5 min left", timeLeft(watched(position = 60.0, duration = 3960.0)))
        assertEquals("2 hr left", timeLeft(watched(position = 60.0, duration = 7260.0)))
        assertNull("barely started", timeLeft(watched(position = 2.0, duration = 600.0)))
        assertNull("watched", timeLeft(watched(position = 590.0, duration = 600.0)))
        assertNull("no length yet", timeLeft(watched(position = 30.0, duration = 0.0)))
        val onAir = watched(position = 600.0, duration = 1300.0).copy(meta = VideoMeta("aaaaaaaaaaa", "T", null, null, live = true))
        assertNull("a stream on air has a replay window, not a length", timeLeft(onAir))
        assertNull(onAir.length)
    }

    @Test fun pluralsPickTheNoun() {
        assertEquals("1 item", plural(1, "item", "items"))
        assertEquals("0 items", plural(0, "item", "items"))
        assertEquals("12 items", plural(12, "item", "items"))
    }

    @Test fun dayLabels() {
        val today = LocalDate.of(2026, 11, 8)
        assertEquals("Today", historyDayLabel(HistoryDay.Today, today, Locale.UK))
        assertEquals("Yesterday", historyDayLabel(HistoryDay.Yesterday, today, Locale.UK))
        assertEquals("Thursday 5 November", historyDayLabel(HistoryDay.On(LocalDate.of(2026, 11, 5)), today, Locale.UK))
        assertEquals("Wednesday 31 December 2025", historyDayLabel(HistoryDay.On(LocalDate.of(2025, 12, 31)), today, Locale.UK))
    }

    @Test fun importSummaries() {
        assertEquals(
            "Imported 3 items, 1 tag and 40 history entries",
            Notices.importSummary(ImportReport(itemsAdded = 3, itemsUpdated = 0, tagsAdded = 1, historyAdded = 40)),
        )
        assertEquals(
            "Imported 1 history entry; 2 items got new tags. Skipped 1 damaged entry.",
            Notices.importSummary(ImportReport(itemsAdded = 0, itemsUpdated = 2, tagsAdded = 0, historyAdded = 1, skipped = 1)),
        )
        assertEquals("Nothing new to import", Notices.importSummary(ImportReport(0, 0, 0, 0)))
    }

    @Test fun undoableNoticesOfferUndoAndTheCorruptNoticeOffersExport() {
        val undoable = listOf(
            UiEvent.HistoryCleared(emptyList()),
            UiEvent.Unstarred(listOf(item(ItemType.VIDEO, null))),
        )
        undoable.forEach { assertEquals("Undo", Notices.of(it).action) }
        assertEquals("Export", Notices.unreadableStore.action)
        assertNull(Notices.of(UiEvent.ImportFailed(BackupProblem.NEWER_VERSION)).action)
    }
}
