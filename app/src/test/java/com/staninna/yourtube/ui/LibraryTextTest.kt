package com.staninna.yourtube.ui

import com.staninna.yourtube.UiEvent
import com.staninna.yourtube.data.BackupProblem
import com.staninna.yourtube.data.HistoryDay
import com.staninna.yourtube.data.ImportReport
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** The words Library, History and the snackbar put on screen. */
class LibraryTextTest {

    private fun item(type: ItemType, author: String?) = StarredItem("x", type, "T", author = author, url = "u")

    @Test fun subtitlesNameTheOwnerThenTheTags() {
        assertEquals("Someone · Music, Talks", librarySubtitle(item(ItemType.VIDEO, "Someone"), listOf("Music", "Talks")))
        assertEquals("Someone", librarySubtitle(item(ItemType.PLAYLIST, "Someone"), emptyList()))
        assertEquals("Music", librarySubtitle(item(ItemType.CHANNEL, "Its own name"), listOf("Music")))
        assertNull(librarySubtitle(item(ItemType.CHANNEL, "Its own name"), emptyList()))
        assertNull(librarySubtitle(item(ItemType.VIDEO, " "), emptyList()))
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
            UiEvent.Unstarred(item(ItemType.VIDEO, null)),
        )
        undoable.forEach { assertEquals("Undo", Notices.of(it).action) }
        assertEquals("Export", Notices.unreadableStore.action)
        assertNull(Notices.of(UiEvent.ImportFailed(BackupProblem.NEWER_VERSION)).action)
    }
}
