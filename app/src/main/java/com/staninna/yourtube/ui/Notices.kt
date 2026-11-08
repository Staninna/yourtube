package com.staninna.yourtube.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.staninna.yourtube.UiEvent
import com.staninna.yourtube.data.BackupProblem
import com.staninna.yourtube.data.ImportReport

/** A snackbar: its text and its action button, if any. */
data class Notice(val message: String, val action: String? = null)

/** What the snackbar says for each [UiEvent], and for the damaged-store notice. */
object Notices {
    /** Store files were unreadable at startup and set aside; offers an export of what is left. */
    val unreadableStore = Notice("Some saved data was damaged and set aside. Export a backup of the rest?", "Export")

    fun of(event: UiEvent): Notice = when (event) {
        is UiEvent.HistoryCleared -> Notice("History cleared", "Undo")
        is UiEvent.HistoryRemoved -> Notice("Removed from history", "Undo")
        is UiEvent.Unstarred -> Notice("Removed from your library", "Undo")
        is UiEvent.SegmentSkipped -> Notice(ContentText.skipped(event.category), "Undo")
        is UiEvent.Imported -> Notice(importSummary(event.report))
        is UiEvent.ImportFailed -> Notice(problem(event.problem))
        UiEvent.Exported -> Notice("Library exported")
        UiEvent.ExportFailed -> Notice("Couldn't save the export. Try another folder.")
    }

    /** "Imported 3 items, 1 tag and 40 history entries", with tag gains and skipped entries after. */
    fun importSummary(report: ImportReport): String {
        val added = listOfNotNull(
            countOf(report.itemsAdded, "item", "items"),
            countOf(report.tagsAdded, "tag", "tags"),
            countOf(report.historyAdded, "history entry", "history entries"),
        )
        val parts = buildList {
            if (added.isNotEmpty()) add("Imported " + naturalList(added))
            countOf(report.itemsUpdated, "item", "items")?.let { add("$it got new tags") }
        }
        val summary = if (parts.isEmpty()) "Nothing new to import" else parts.joinToString("; ")
        val skipped = countOf(report.skipped, "damaged entry", "damaged entries")
        return if (skipped == null) summary else "$summary. Skipped $skipped."
    }

    private fun problem(problem: BackupProblem): String = when (problem) {
        BackupProblem.UNREADABLE -> "Couldn't read that file"
        BackupProblem.NOT_A_BACKUP -> "That file isn't a YourTube export"
        BackupProblem.NEWER_VERSION -> "That export is from a newer YourTube. Update the app to import it."
        BackupProblem.DAMAGED -> "That export is damaged. Nothing was imported."
    }

    /** A count worth mentioning; zero is left out of the summary. */
    private fun countOf(n: Int, one: String, many: String): String? = if (n == 0) null else plural(n, one, many)

    private fun naturalList(parts: List<String>): String =
        if (parts.size == 1) parts.single() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}

/**
 * The shell's two snackbar queues: [events] for one-off notices (a newer one replaces the one showing)
 * and [notice] for the damaged-store notice, which waits for an answer and is never replaced.
 */
class Snackbars(val events: SnackbarHostState, val notice: SnackbarHostState)

/** Both queues, the waiting notice above the passing ones, for a Scaffold's snackbar slot. */
@Composable
fun SnackbarStack(snackbars: Snackbars) {
    Column {
        SnackbarHost(snackbars.notice)
        SnackbarHost(snackbars.events)
    }
}
